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
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_tenant_app ON document_metadata (tenant_id, app_id);
CREATE INDEX idx_document_created ON document_metadata (created_at DESC);
