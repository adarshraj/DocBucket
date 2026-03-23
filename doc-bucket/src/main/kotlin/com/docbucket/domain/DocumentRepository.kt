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
        return find("$ql ORDER BY createdAt DESC", *params.toTypedArray())
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
        return count(ql, *params.toTypedArray())
    }

    fun findDeletedBefore(cutoff: Instant): List<DocumentEntity> =
        find("deletedAt IS NOT NULL AND deletedAt < ?1", cutoff).list()

    private fun activeFilterQuery(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        contentType: String?,
        createdAfter: Instant?,
    ): Pair<String, List<Any>> {
        val params = mutableListOf<Any>(tenantId, appId)
        var ql = "tenantId = ?1 AND appId = ?2 AND deletedAt IS NULL"
        var i = 3
        if (ownerUserId != null) {
            ql += " AND ownerUserId = ?$i"
            params.add(ownerUserId)
            i++
        }
        if (contentType != null) {
            ql += " AND contentType = ?$i"
            params.add(contentType)
            i++
        }
        if (createdAfter != null) {
            ql += " AND createdAt >= ?$i"
            params.add(createdAfter)
            i++
        }
        return ql to params
    }
}
