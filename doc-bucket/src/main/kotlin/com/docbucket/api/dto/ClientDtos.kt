package com.docbucket.api.dto

import java.time.Instant
import java.util.UUID

data class RegisterClientRequest(
    val tenantId: String,
    val appId: String,
    val label: String? = null,
    /** Optional absolute expiry. Null means the key never expires. */
    val expiresAt: Instant? = null,
)

data class ClientResponse(
    val id: UUID,
    val tenantId: String,
    val appId: String,
    val label: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    /** Only present immediately after creation or rotation — never returned again. Store it securely. */
    val apiKey: String? = null,
)
