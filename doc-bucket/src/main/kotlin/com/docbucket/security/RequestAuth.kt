package com.docbucket.security

/**
 * Request-scoped auth set by [ApiKeyAuthFilter] when a registered per-app key matches.
 */
object RequestAuth {
    const val PROP_TENANT = "bucket.auth.tenant"
    const val PROP_APP = "bucket.auth.app"
    /** "legacy" | "client" | "none" */
    const val PROP_MODE = "bucket.auth.mode"
}
