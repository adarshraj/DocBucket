-- Partial index for the purge job query: deleted_at IS NOT NULL AND deleted_at < cutoff.
-- Only indexes rows that are actually soft-deleted, keeping the index small.
CREATE INDEX idx_document_deleted_at ON document_metadata (deleted_at)
    WHERE deleted_at IS NOT NULL;
