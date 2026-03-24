package com.docbucket.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DocumentRepository : PanacheRepositoryBase<DocumentEntity, UUID> {

    fun listActive(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        contentType: String?,
        createdAfter: Instant?,
        page: Int,
        size: Int,
    ): List<DocumentEntity> {
        val (ql, params) = activeFilterQuery(tenantId, appId, ownerUserId, contentType, createdAfter)
        return find("$ql ORDER BY createdAt DESC", params)
            .page(page, size)
            .list()
    }

    fun countActive(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        contentType: String?,
        createdAfter: Instant?,
    ): Long {
        val (ql, params) = activeFilterQuery(tenantId, appId, ownerUserId, contentType, createdAfter)
        return count(ql, params)
    }

    fun findDeletedBefore(cutoff: Instant, batchSize: Int = 500): List<DocumentEntity> =
        find("deletedAt IS NOT NULL AND deletedAt < ?1", cutoff)
            .page(0, batchSize)
            .list()

    private fun activeFilterQuery(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        contentType: String?,
        createdAfter: Instant?,
    ): Pair<String, Map<String, Any>> {
        val params = mutableMapOf<String, Any>("tenantId" to tenantId, "appId" to appId)
        var ql = "tenantId = :tenantId AND appId = :appId AND deletedAt IS NULL"
        if (ownerUserId != null) {
            ql += " AND ownerUserId = :ownerUserId"
            params["ownerUserId"] = ownerUserId
        }
        if (contentType != null) {
            ql += " AND contentType = :contentType"
            params["contentType"] = contentType
        }
        if (createdAfter != null) {
            ql += " AND createdAt >= :createdAfter"
            params["createdAfter"] = createdAfter
        }
        return ql to params
    }
}
