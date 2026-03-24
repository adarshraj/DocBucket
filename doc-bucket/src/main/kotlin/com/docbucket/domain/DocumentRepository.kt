package com.docbucket.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Parameters
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
        return find("$ql ORDER BY d.createdAt DESC", params)
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
        return find(ql, params).count()
    }

    fun findDeletedBefore(cutoff: Instant, batchSize: Int = 500): List<DocumentEntity> =
        find("deletedAt IS NOT NULL AND deletedAt < ?1", cutoff)
            .page(0, batchSize)
            .list()

    fun findByIdForCaller(id: UUID, tenantId: String, appId: String): DocumentEntity? =
        find(
            """FROM DocumentEntity d WHERE d.id = :id AND d.deletedAt IS NULL
               AND ((d.tenantId = :tenantId AND d.appId = :appId)
                    OR EXISTS (SELECT s FROM DocumentShare s
                               WHERE s.id.documentId = :id
                                 AND s.id.granteeTenantId = :tenantId
                                 AND s.id.granteeAppId = :appId))""",
            Parameters.with("id", id).and("tenantId", tenantId).and("appId", appId)
        ).firstResult()

    private fun activeFilterQuery(
        tenantId: String,
        appId: String,
        ownerUserId: String?,
        contentType: String?,
        createdAfter: Instant?,
    ): Pair<String, Parameters> {
        var params = Parameters.with("tenantId", tenantId).and("appId", appId)
        var ql = """FROM DocumentEntity d WHERE d.deletedAt IS NULL
            AND ((d.tenantId = :tenantId AND d.appId = :appId)
                 OR EXISTS (SELECT s FROM DocumentShare s
                            WHERE s.id.documentId = d.id
                              AND s.id.granteeTenantId = :tenantId
                              AND s.id.granteeAppId = :appId))"""
        if (ownerUserId != null) {
            ql += " AND d.ownerUserId = :ownerUserId"
            params = params.and("ownerUserId", ownerUserId)
        }
        if (contentType != null) {
            ql += " AND d.contentType = :contentType"
            params = params.and("contentType", contentType)
        }
        if (createdAfter != null) {
            ql += " AND d.createdAt >= :createdAfter"
            params = params.and("createdAfter", createdAfter)
        }
        return ql to params
    }
}
