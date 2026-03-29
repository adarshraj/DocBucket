package com.docbucket.api

import com.docbucket.api.dto.DocumentResponse
import com.docbucket.api.dto.PagedResponse
import com.docbucket.api.dto.PresignResponse
import com.docbucket.api.dto.ShareRequest
import com.docbucket.api.dto.ShareResponse
import com.docbucket.security.RequestAuth
import com.docbucket.security.CallerContext
import com.docbucket.service.DocumentService
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.container.ContainerRequestContext
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.io.InputStream
import java.time.Instant
import java.util.UUID

@Path("/api/documents")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "documents", description = "Document storage — metadata in DB, bytes via S3-compatible storage")
class DocumentResource @Inject constructor(
    private val documentService: DocumentService,
) {

    @POST
    @Path("/upload")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Upload raw bytes; stores bytes via S3 API and persists metadata to the database")
    fun upload(
        @HeaderParam("X-Tenant-Id") @Size(max = 64) tenantIdHeader: String?,
        @HeaderParam("X-App-Id") @Size(max = 64) appIdHeader: String?,
        @HeaderParam("X-Owner-User-Id") @Size(max = 256) ownerUserId: String?,
        @HeaderParam(HttpHeaders.CONTENT_TYPE) contentType: String?,
        @HeaderParam(HttpHeaders.CONTENT_LENGTH) contentLength: Long?,
        @QueryParam("filename") filename: String?,
        @Context requestContext: ContainerRequestContext,
        input: InputStream,
    ): DocumentResponse {
        val caller = callerContext(requestContext)
        val (tenantId, appId) = if (caller != null) {
            caller.tenantId to caller.appId
        } else {
            val t = tenantIdHeader?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("X-Tenant-Id required")
            val a = appIdHeader?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("X-App-Id required")
            t to a
        }
        return documentService.upload(tenantId, appId, ownerUserId, filename, contentType, contentLength, input)
    }

    @GET
    @Operation(summary = "List documents for the caller's tenant/app with pagination and optional filters")
    fun list(
        @HeaderParam("X-Tenant-Id") @Size(max = 64) tenantIdHeader: String?,
        @HeaderParam("X-App-Id") @Size(max = 64) appIdHeader: String?,
        @QueryParam("page") @DefaultValue("0") @Min(0) page: Int,
        @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) size: Int,
        @QueryParam("ownerUserId") ownerUserId: String?,
        @QueryParam("contentType") contentType: String?,
        @QueryParam("createdAfter") createdAfterRaw: String?,
        @Context requestContext: ContainerRequestContext,
    ): PagedResponse<DocumentResponse> {
        val caller = callerContext(requestContext)
        val (tenantId, appId) = if (caller != null) {
            caller.tenantId to caller.appId
        } else {
            val t = tenantIdHeader?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("X-Tenant-Id required")
            val a = appIdHeader?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("X-App-Id required")
            t to a
        }
        val createdAfter = parseCreatedAfter(createdAfterRaw)
        return documentService.listDocuments(tenantId, appId, ownerUserId, contentType, createdAfter, page, size)
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get metadata (includes path to stream content)")
    fun getMetadata(
        @PathParam("id") id: UUID,
        @Context requestContext: ContainerRequestContext,
    ): DocumentResponse {
        return documentService.getMetadata(id, callerContext(requestContext))
    }

    @GET
    @Path("/{id}/content")
    @Operation(summary = "Stream object bytes with Content-Type, Length, Disposition, ETag")
    fun getContent(
        @PathParam("id") id: UUID,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        val meta = documentService.openContentStream(id, callerContext(requestContext))
        val filename = meta.originalFilename
            ?: meta.objectKey.substringAfterLast('/').ifBlank { "download" }
        val rb = Response.ok(meta.stream)
            .type(meta.contentType ?: MediaType.APPLICATION_OCTET_STREAM)
        if (meta.sizeBytes != null) {
            rb.header(HttpHeaders.CONTENT_LENGTH, meta.sizeBytes)
        }
        val etagVal = meta.etag?.trim('"')
        if (!etagVal.isNullOrBlank()) {
            rb.header(HttpHeaders.ETAG, "\"$etagVal\"")
        }
        rb.header(HttpHeaders.CONTENT_DISPOSITION, contentDispositionAttachment(filename))
        return rb.build()
    }

    @GET
    @Path("/{id}/presign")
    @Operation(summary = "Issue a time-limited presigned S3 GET URL (direct download; ttl in seconds)")
    fun presign(
        @PathParam("id") id: UUID,
        @QueryParam("ttl") @DefaultValue("21600") ttlSeconds: Long,
        @Context requestContext: ContainerRequestContext,
    ): PresignResponse {
        return documentService.presignDownload(id, ttlSeconds, callerContext(requestContext))
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Soft-delete (default) removes object from storage; hard=true removes the DB row")
    fun delete(
        @PathParam("id") id: UUID,
        @QueryParam("hard") @DefaultValue("false") hard: Boolean,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        if (hard) {
            documentService.hardDelete(id, callerContext(requestContext))
        } else {
            documentService.softDelete(id, callerContext(requestContext))
        }
        return Response.noContent().build()
    }

    @POST
    @Path("/{id}/shares")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Grant another app access to this document (owner only)")
    fun share(
        @PathParam("id") id: UUID,
        @Valid body: ShareRequest,
        @Context requestContext: ContainerRequestContext,
    ): ShareResponse {
        return documentService.shareDocument(id, callerContext(requestContext), body.granteeTenantId, body.granteeAppId)
    }

    @DELETE
    @Path("/{id}/shares/{granteeTenantId}/{granteeAppId}")
    @Operation(summary = "Revoke a previously granted share (owner only)")
    fun revokeShare(
        @PathParam("id") id: UUID,
        @PathParam("granteeTenantId") granteeTenantId: String,
        @PathParam("granteeAppId") granteeAppId: String,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        documentService.revokeShare(id, callerContext(requestContext), granteeTenantId, granteeAppId)
        return Response.noContent().build()
    }

    @GET
    @Path("/{id}/shares")
    @Operation(summary = "List all apps that have been granted access to this document (owner only)")
    fun listShares(
        @PathParam("id") id: UUID,
        @Context requestContext: ContainerRequestContext,
    ): List<ShareResponse> {
        return documentService.listShares(id, callerContext(requestContext))
    }

    private fun contentDispositionAttachment(filename: String): String {
        // Strip characters that could break the header value: quotes, semicolons (field separator),
        // backslashes, and all ASCII control characters.
        val safe = filename
            .replace(Regex("""["\;\\\r\n]"""), "")
            .replace(Regex("""[\x00-\x1f\x7f]"""), "")
            .trim()
            .ifBlank { "download" }
        return "attachment; filename=\"$safe\""
    }

    private fun parseCreatedAfter(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            Instant.parse(raw)
        } catch (_: Exception) {
            throw BadRequestException("createdAfter must be an ISO-8601 instant (e.g. 2025-01-01T00:00:00Z)")
        }
    }

    private fun callerContext(requestContext: ContainerRequestContext): CallerContext? {
        val mode = requestContext.getProperty(RequestAuth.PROP_MODE) as String?
        if (mode != "client") {
            return null
        }
        val t = requestContext.getProperty(RequestAuth.PROP_TENANT) as String?
        val a = requestContext.getProperty(RequestAuth.PROP_APP) as String?
        if (t.isNullOrBlank() || a.isNullOrBlank()) {
            return null
        }
        return CallerContext(t, a)
    }
}
