-- Per-app credentials: raw API key is hashed (HMAC-SHA256) at rest; never store plaintext.
CREATE TABLE api_client (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    label VARCHAR(256),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_client_key_hash UNIQUE (key_hash),
    CONSTRAINT uq_api_client_tenant_app UNIQUE (tenant_id, app_id)
);

CREATE INDEX idx_api_client_tenant ON api_client (tenant_id);
