package com.docbucket.service

import com.docbucket.api.dto.DocumentResponse
import com.docbucket.api.dto.PagedResponse
import com.docbucket.api.dto.PresignResponse
import com.docbucket.api.dto.ShareResponse
import com.docbucket.config.PresignConfig
import com.docbucket.config.UploadConfig
import com.docbucket.domain.DocumentEntity
import com.docbucket.domain.DocumentRepository
import com.docbucket.domain.DocumentShare
import com.docbucket.domain.DocumentShareRepository
import com.docbucket.security.CallerContext
import com.docbucket.security.TenantAppPathValidator
import com.docbucket.storage.ObjectStorage
import com.docbucket.storage.StorageConfig
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.jboss.logging.Logger
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DocumentService @Inject constructor(
    private val objectStorage: ObjectStorage,
    private val storageConfig: StorageConfig,
    private val uploadConfig: UploadConfig,
    private val presignConfig: PresignConfig,
    private val documentRepository: DocumentRepository,
    private val shareRepository: DocumentShareRepository,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log: Logger = Logger.getLogger(DocumentService::class.java)
        private const val MAX_ORIGINAL_FILENAME_LEN = 512
    }

    @Transactional
    fun upload(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        filename: String?,
        contentType: String?,
        contentLengthHint: Long?,
        input: InputStream,
    ): DocumentResponse {
        TenantAppPathValidator.validatePair(tenantId, appId)
        // Normalise to base MIME type (strip charset and other parameters) so that the stored
        // value is consistent and the listDocuments contentType filter works with exact equality.
        val ct = (contentType ?: "application/octet-stream").substringBefore(";").trim()
        validateContentType(ct)

        val maxBytes = uploadConfig.maxBytes()
        if (contentLengthHint != null && contentLengthHint > maxBytes) {
            log.warnf("Upload rejected for tenant=%s app=%s: Content-Length %d exceeds limit %d", tenantId, appId, contentLengthHint, maxBytes)
            meterRegistry.counter("docbucket.upload.rejected", "reason", "size_exceeded").increment()
            throw BadRequestException("Upload size $contentLengthHint bytes exceeds maximum allowed $maxBytes bytes")
        }

        val id = UUID.randomUUID()
        val bucket = storageConfig.defaultBucket()
        val safeName = (filename ?: "blob").replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "blob" }
        val key = "$tenantId/$appId/$id/$safeName"
        val originalStored = filename?.trim()?.take(MAX_ORIGINAL_FILENAME_LEN)?.takeIf { it.isNotEmpty() }

        val tmp = Files.createTempFile("doc-bucket-", ".upload")
        try {
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            val size = Files.size(tmp)

            if (size > maxBytes) {
                log.warnf("Upload rejected for tenant=%s app=%s: size %d exceeds limit %d", tenantId, appId, size, maxBytes)
                meterRegistry.counter("docbucket.upload.rejected", "reason", "size_exceeded").increment()
                throw BadRequestException("Upload size $size bytes exceeds maximum allowed $maxBytes bytes")
            }

            log.infof("Uploading document id=%s tenant=%s app=%s size=%d contentType=%s", id, tenantId, appId, size, ct)
            val put = objectStorage.putObject(bucket, key, tmp, ct)

            val entity = DocumentEntity().apply {
                this.id = id
                this.tenantId = tenantId
                this.appId = appId
                this.ownerUserId = ownerUserId
                this.bucket = bucket
                this.objectKey = key
                this.originalFilename = originalStored
                this.contentType = ct
                this.sizeBytes = size
                this.etag = put.etag?.trim('"')
                this.createdAt = Instant.now()
            }
            documentRepository.persist(entity)
            meterRegistry.counter("docbucket.documents.uploaded").increment()
            log.infof("Document stored id=%s etag=%s", id, entity.etag)

            return toResponse(entity)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Transactional
    fun getMetadata(id: UUID, caller: CallerContext?): DocumentResponse {
        val entity = requireActive(id, caller)
        log.debugf("Metadata fetched id=%s tenant=%s app=%s", id, entity.tenantId, entity.appId)
        return toResponse(entity)
    }

    @Transactional
    fun openContentStream(id: UUID, caller: CallerContext?): DocumentContentStream {
        val entity = requireActive(id, caller)
        log.debugf("Content stream opened id=%s bucket=%s key=%s", id, entity.bucket, entity.objectKey)
        meterRegistry.counter("docbucket.documents.downloaded").increment()
        val stream = objectStorage.getObject(entity.bucket, entity.objectKey)
        return DocumentContentStream(
            stream = stream,
            contentType = entity.contentType,
            sizeBytes = entity.sizeBytes,
            etag = entity.etag,
            originalFilename = entity.originalFilename,
            objectKey = entity.objectKey,
        )
    }

    @Transactional
    fun presignDownload(id: UUID, ttlSeconds: Long, caller: CallerContext?): PresignResponse {
        val entity = requireActive(id, caller)
        val minT = presignConfig.minTtlSeconds()
        val maxT = presignConfig.maxTtlSeconds()
        if (ttlSeconds < minT || ttlSeconds > maxT) {
            throw BadRequestException("ttl must be between $minT and $maxT seconds")
        }
        val duration = Duration.ofSeconds(ttlSeconds)
        val uri = objectStorage.presignGetObject(entity.bucket, entity.objectKey, duration)
        val expiresAt = Instant.now().plus(duration)
        log.debugf("Presigned GET id=%s ttl=%ds", id, ttlSeconds)
        meterRegistry.counter("docbucket.documents.presigned").increment()
        return PresignResponse(url = uri.toString(), expiresAt = expiresAt)
    }

    @Transactional
    fun softDelete(id: UUID, caller: CallerContext?) {
        val entity = documentRepository.findById(id) ?: throw NotFoundException("document not found")
        assertCaller(entity, caller)
        if (entity.deletedAt != null) {
            return
        }
        entity.deletedAt = Instant.now()
        log.infof("Document soft-deleted id=%s tenant=%s app=%s", id, entity.tenantId, entity.appId)
        try {
            objectStorage.deleteObject(entity.bucket, entity.objectKey)
        } catch (e: Exception) {
            log.warnf(e, "S3 delete failed for id=%s bucket=%s key=%s — metadata already soft-deleted", id, entity.bucket, entity.objectKey)
        }
        meterRegistry.counter("docbucket.documents.deleted").increment()
    }

    @Transactional
    fun hardDelete(id: UUID, caller: CallerContext?) {
        val entity = documentRepository.findById(id) ?: throw NotFoundException("document not found")
        assertCaller(entity, caller)
        try {
            objectStorage.deleteObject(entity.bucket, entity.objectKey)
        } catch (e: Exception) {
            // Object may already be gone if previously soft-deleted; log and continue with DB removal
            log.debugf("S3 delete on hard delete id=%s (object may already be gone): %s", id, e.message)
        }
        shareRepository.revokeAll(id)
        documentRepository.delete(entity)
        log.infof("Document hard-deleted id=%s tenant=%s app=%s", id, entity.tenantId, entity.appId)
        meterRegistry.counter("docbucket.documents.hard_deleted").increment()
    }

    @Transactional
    fun listDocuments(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        contentType: String?,
        createdAfter: Instant?,
        page: Int,
        size: Int,
    ): PagedResponse<DocumentResponse> {
        TenantAppPathValidator.validatePair(tenantId, appId)
        val items = documentRepository.listActive(tenantId, appId, ownerUserId, contentType, createdAfter, page, size)
        val total = documentRepository.countActive(tenantId, appId, ownerUserId, contentType, createdAfter)
        log.debugf("List documents tenant=%s app=%s page=%d size=%d total=%d", tenantId, appId, page, size, total)
        return PagedResponse(
            items = items.map { toResponse(it) },
            total = total,
            page = page,
            size = size,
            hasNext = (page + 1) * size < total,
        )
    }

    private fun validateContentType(contentType: String) {
        val raw = uploadConfig.mimeAllowlist().orElse(null)?.trim()
        if (raw.isNullOrEmpty() || raw == "*") return
        val allowed = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (allowed.any { it == "*" }) return
        val base = contentType.substringBefore(";").trim().lowercase()
        if (allowed.none { it == base }) {
            log.warnf("Upload rejected: content-type '%s' not in allowlist", contentType)
            meterRegistry.counter("docbucket.upload.rejected", "reason", "content_type_blocked").increment()
            throw BadRequestException("Content-type '$contentType' is not allowed")
        }
    }

    private fun requireActive(id: UUID, caller: CallerContext?): DocumentEntity {
        val entity = if (caller != null) {
            documentRepository.findByIdForCaller(id, caller.tenantId, caller.appId)
        } else {
            documentRepository.findById(id)?.takeIf { it.deletedAt == null }
        } ?: throw NotFoundException("document not found")
        return entity
    }

    private fun assertCaller(entity: DocumentEntity, caller: CallerContext?) {
        if (caller == null) return
        if (entity.tenantId != caller.tenantId || entity.appId != caller.appId) {
            throw NotFoundException("document not found")
        }
    }

    @Transactional
    fun shareDocument(id: UUID, caller: CallerContext?, granteeTenantId: String, granteeAppId: String): ShareResponse {
        val entity = documentRepository.findById(id) ?: throw NotFoundException("document not found")
        assertCaller(entity, caller)
        if (entity.deletedAt != null) throw NotFoundException("document not found")
        if (granteeTenantId == entity.tenantId && granteeAppId == entity.appId) {
            throw BadRequestException("cannot share a document with its own owner")
        }
        val share = shareRepository.grant(id, granteeTenantId, granteeAppId)
        log.infof("Document shared id=%s with tenant=%s app=%s", id, granteeTenantId, granteeAppId)
        return toShareResponse(id, share)
    }

    @Transactional
    fun revokeShare(id: UUID, caller: CallerContext?, granteeTenantId: String, granteeAppId: String) {
        val entity = documentRepository.findById(id) ?: throw NotFoundException("document not found")
        assertCaller(entity, caller)
        val removed = shareRepository.revoke(id, granteeTenantId, granteeAppId)
        if (!removed) throw NotFoundException("share not found")
        log.infof("Share revoked id=%s grantee tenant=%s app=%s", id, granteeTenantId, granteeAppId)
    }

    @Transactional
    fun listShares(id: UUID, caller: CallerContext?): List<ShareResponse> {
        val entity = documentRepository.findById(id) ?: throw NotFoundException("document not found")
        assertCaller(entity, caller)
        return shareRepository.listForDocument(id).map { toShareResponse(id, it) }
    }

    private fun toShareResponse(documentId: UUID, share: DocumentShare) = ShareResponse(
        documentId = documentId,
        granteeTenantId = share.id.granteeTenantId,
        granteeAppId = share.id.granteeAppId,
        grantedAt = share.grantedAt,
    )

    private fun toResponse(entity: DocumentEntity): DocumentResponse {
        return DocumentResponse(
            id = entity.id,
            tenantId = entity.tenantId,
            appId = entity.appId,
            ownerUserId = entity.ownerUserId,
            originalFilename = entity.originalFilename,
            contentType = entity.contentType,
            sizeBytes = entity.sizeBytes,
            etag = entity.etag,
            createdAt = entity.createdAt,
            contentPath = "/api/documents/${entity.id}/content",
        )
    }
}
