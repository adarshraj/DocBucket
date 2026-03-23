package com.docbucket.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "api_client")
class ApiClient {

    constructor()

    @Id
    lateinit var id: UUID

    @Column(name = "tenant_id", nullable = false, length = 64)
    lateinit var tenantId: String

    @Column(name = "app_id", nullable = false, length = 64)
    lateinit var appId: String

    @Column(name = "key_hash", nullable = false, length = 64)
    lateinit var keyHash: String

    @Column(name = "label", length = 256)
    var label: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at")
    var expiresAt: Instant? = null
}
