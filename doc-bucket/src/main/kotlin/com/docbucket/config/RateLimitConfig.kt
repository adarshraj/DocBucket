package com.docbucket.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName

@ConfigMapping(prefix = "doc.bucket.rate-limit")
interface RateLimitConfig {

    @WithDefault("true")
    fun enabled(): Boolean

    @WithName("requests-per-minute")
    @WithDefault("200")
    fun requestsPerMinute(): Int
}
