package com.docbucket.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class DocumentResponse(
    val id: UUID,
    val tenantId: String,
    val appId: String,
    val ownerUserId: String?,
    /** Original filename from the upload request, when provided. */
    val originalFilename: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val etag: String?,
    val createdAt: Instant,
    /** Relative path to stream bytes from this service (proxies S3 GetObject). */
    val contentPath: String,
)

data class PresignResponse(
    val url: String,
    val expiresAt: Instant,
)

data class PagedResponse<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
)

data class ShareRequest(
    @field:NotBlank @field:Size(max = 64) val granteeTenantId: String,
    @field:NotBlank @field:Size(max = 64) val granteeAppId: String,
)

data class ShareResponse(
    val documentId: UUID,
    val granteeTenantId: String,
    val granteeAppId: String,
    val grantedAt: Instant,
)
