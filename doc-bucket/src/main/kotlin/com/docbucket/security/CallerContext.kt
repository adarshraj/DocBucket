package com.docbucket.security

/** Resolved from a registered per-app API key (table `api_client`). */
data class CallerContext(
    val tenantId: String,
    val appId: String,
)
