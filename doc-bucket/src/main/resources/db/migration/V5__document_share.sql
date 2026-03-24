-- Explicit grants allowing one (tenantId, appId) to access another's documents.
-- No FK constraint: SQLite requires PRAGMA foreign_keys=ON for cascade; we handle
-- cleanup explicitly in the service layer instead.
CREATE TABLE document_share (
    document_id       UUID        NOT NULL,
    grantee_tenant_id VARCHAR(64) NOT NULL,
    grantee_app_id    VARCHAR(64) NOT NULL,
    granted_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, grantee_tenant_id, grantee_app_id)
);

CREATE INDEX idx_document_share_grantee ON document_share (grantee_tenant_id, grantee_app_id);
