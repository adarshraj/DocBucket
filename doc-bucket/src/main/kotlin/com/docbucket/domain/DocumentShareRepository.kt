package com.docbucket.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Parameters
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DocumentShareRepository : PanacheRepositoryBase<DocumentShare, DocumentShareId> {

    fun grant(documentId: UUID, granteeTenantId: String, granteeAppId: String): DocumentShare {
        val existing = find(
            "id.documentId = :docId AND id.granteeTenantId = :t AND id.granteeAppId = :a",
            Parameters.with("docId", documentId).and("t", granteeTenantId).and("a", granteeAppId)
        ).firstResult()
        if (existing != null) return existing

        val share = DocumentShare().apply {
            id = DocumentShareId(
                documentId = documentId,
                granteeTenantId = granteeTenantId,
                granteeAppId = granteeAppId,
            )
        }
        persist(share)
        return share
    }

    fun revoke(documentId: UUID, granteeTenantId: String, granteeAppId: String): Boolean {
        val deleted = delete(
            "id.documentId = :docId AND id.granteeTenantId = :t AND id.granteeAppId = :a",
            Parameters.with("docId", documentId).and("t", granteeTenantId).and("a", granteeAppId)
        )
        return deleted > 0
    }

    fun revokeAll(documentId: UUID) {
        delete("id.documentId = :docId", Parameters.with("docId", documentId))
    }

    fun listForDocument(documentId: UUID): List<DocumentShare> =
        find("id.documentId = :docId", Parameters.with("docId", documentId)).list()
}
