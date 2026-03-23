package com.docbucket.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import java.util.Optional

@ConfigMapping(prefix = "doc.bucket.upload")
interface UploadConfig {

    /** Maximum allowed upload size in bytes. Default 100 MB. */
    @WithDefault("104857600")
    fun maxBytes(): Long

    /**
     * Comma-separated allowed MIME base types (e.g. `application/pdf,image/png`).
     * Empty / absent means all content-types are accepted. Use `*` to allow all.
     * Env: `DOC_BUCKET_UPLOAD_MIME_ALLOWLIST` (avoids ambiguous legacy env aliases).
     */
    @WithName("mime-allowlist")
    fun mimeAllowlist(): Optional<String>
}
