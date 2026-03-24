-- TIMESTAMP is used instead of TIMESTAMPTZ so this migration runs on both SQLite and PostgreSQL
-- without modification. Hibernate maps Instant to UTC in both cases regardless of column type.
CREATE TABLE document_metadata (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(256),
    bucket VARCHAR(256) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(256),
    size_bytes BIGINT,
    etag VARCHAR(256),
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_document_tenant_app ON document_metadata (tenant_id, app_id);
CREATE INDEX idx_document_created ON document_metadata (created_at DESC);
