package com.docbucket.security

/**
 * Request-scoped auth set by [JwtBearerAuthFilter] after validating an ES256 JWT from auth-service.
 */
object RequestAuth {
    const val PROP_TENANT = "bucket.auth.tenant"
    const val PROP_APP = "bucket.auth.app"
    /** "legacy" | "client" | "none" */
    const val PROP_MODE = "bucket.auth.mode"
}
