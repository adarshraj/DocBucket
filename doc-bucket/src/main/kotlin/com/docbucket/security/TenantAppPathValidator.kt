package com.docbucket.security

import jakarta.ws.rs.BadRequestException

private val SEGMENT_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

/**
 * Tenant and app identifiers are used as S3 key path segments; allow only safe characters.
 */
object TenantAppPathValidator {

    fun validateTenantId(tenantId: String) {
        if (!tenantId.matches(SEGMENT_PATTERN)) {
            throw BadRequestException(
                "tenantId must match [a-zA-Z0-9_-]+ (got invalid characters or empty)",
            )
        }
    }

    fun validateAppId(appId: String) {
        if (!appId.matches(SEGMENT_PATTERN)) {
            throw BadRequestException(
                "appId must match [a-zA-Z0-9_-]+ (got invalid characters or empty)",
            )
        }
    }

    fun validatePair(tenantId: String, appId: String) {
        validateTenantId(tenantId)
        validateAppId(appId)
    }
}
