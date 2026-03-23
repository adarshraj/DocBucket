package com.docbucket.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class ApiClientRepository : PanacheRepositoryBase<ApiClient, UUID> {

    /** Returns the client if the key hash matches and the key has not expired. */
    fun findActiveByKeyHash(keyHash: String): ApiClient? =
        find(
            "keyHash = ?1 AND (expiresAt IS NULL OR expiresAt > ?2)",
            keyHash,
            Instant.now(),
        ).firstResult()

    /** Returns the client if the key hash matches but the key IS expired (for diagnostic logging). */
    fun findExpiredByKeyHash(keyHash: String): ApiClient? =
        find(
            "keyHash = ?1 AND expiresAt IS NOT NULL AND expiresAt <= ?2",
            keyHash,
            Instant.now(),
        ).firstResult()

    fun countAll(): Long = count()
}
