package com.docbucket.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "doc.bucket.presign")
interface PresignConfig {

    @WithDefault("60")
    fun minTtlSeconds(): Long

    @WithDefault("604800")
    fun maxTtlSeconds(): Long
}
