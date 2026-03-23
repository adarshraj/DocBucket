package com.docbucket.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "doc.bucket.purge")
interface PurgeConfig {

    @WithDefault("true")
    fun enabled(): Boolean

    /** Soft-deleted rows older than this are removed from the DB (S3 delete is retried best-effort). */
    @WithDefault("30")
    fun retentionDays(): Long
}
