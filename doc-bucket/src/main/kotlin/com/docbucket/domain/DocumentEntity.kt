package com.docbucket.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "document_metadata")
class DocumentEntity {

    constructor()

    @Id
    lateinit var id: UUID

    @Column(name = "tenant_id", nullable = false, length = 64)
    lateinit var tenantId: String

    @Column(name = "app_id", nullable = false, length = 64)
    lateinit var appId: String

    @Column(name = "owner_user_id", length = 256)
    var ownerUserId: String? = null

    @Column(name = "bucket", nullable = false, length = 256)
    lateinit var bucket: String

    @Column(name = "object_key", nullable = false, length = 1024)
    lateinit var objectKey: String

    /** Original upload filename for Content-Disposition (object key uses a sanitised segment). */
    @Column(name = "original_filename", length = 512)
    var originalFilename: String? = null

    @Column(name = "content_type", length = 256)
    var contentType: String? = null

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    @Column(name = "etag", length = 256)
    var etag: String? = null

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}
