-- Per-app credentials: raw API key is hashed (HMAC-SHA256) at rest; never store plaintext.
-- DEFAULT gen_random_uuid() is omitted — the application always sets the id before persisting,
-- so the DB default would never be used and gen_random_uuid() is PostgreSQL-specific.
CREATE TABLE api_client (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    label VARCHAR(256),
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_api_client_key_hash UNIQUE (key_hash),
    CONSTRAINT uq_api_client_tenant_app UNIQUE (tenant_id, app_id)
);

CREATE INDEX idx_api_client_tenant ON api_client (tenant_id);
