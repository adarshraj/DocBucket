package com.docbucket.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class DocumentShareId(
    @Column(name = "document_id") var documentId: UUID = UUID(0L, 0L),
    @Column(name = "grantee_tenant_id") var granteeTenantId: String = "",
    @Column(name = "grantee_app_id") var granteeAppId: String = "",
) : Serializable

@Entity
@Table(name = "document_share")
class DocumentShare : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: DocumentShareId

    @Column(name = "granted_at", nullable = false)
    var grantedAt: Instant = Instant.now()
}
