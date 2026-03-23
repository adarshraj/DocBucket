package com.docbucket.api.dto

import java.time.Instant
import java.util.UUID

data class DocumentResponse(
    val id: UUID,
    val tenantId: String,
    val appId: String,
    val ownerUserId: String?,
    val bucket: String,
    val objectKey: String,
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
