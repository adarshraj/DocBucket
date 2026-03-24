package com.docbucket.security

import com.docbucket.domain.ApiClientRepository
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Per-app key auth (preferred): rows in `api_client`; each app has its own secret;
 * `X-API-Key` is HMAC-SHA256 hashed and looked up; tenant/app come from the DB row.
 *
 * Dev-open mode: no client rows → no `X-API-Key` required; tenant/app from headers only.
 * This mode logs a warning and should never reach production.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
class ApiKeyAuthFilter @Inject constructor(
    private val clientRepository: ApiClientRepository,
    private val clientRegistryCache: ClientRegistryCache,
    private val rateLimiter: RateLimiter,
    private val hasher: ApiKeyHasher,
) : ContainerRequestFilter {

    companion object {
        private val log: Logger = Logger.getLogger(ApiKeyAuthFilter::class.java)
    }

    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.path
        // Management and infrastructure endpoints handle their own auth
        if (path.startsWith("q/") || path.startsWith("/q/")) return
        if (path.startsWith("api/clients") || path.startsWith("/api/clients")) return

        val isApi = path.startsWith("api/") || path.startsWith("/api/")
        if (!isApi) return

        val header = requestContext.getHeaderString("X-API-Key")

        // Enter dev-open mode only when the cache confirms no clients are registered AND the
        // caller has not supplied an API key. If a key IS present, always validate it against
        // the DB so that directly-inserted or just-registered clients are not silently ignored.
        if (!clientRegistryCache.hasClients() && header.isNullOrBlank()) {
            log.debugf("Dev-open mode: path=%s", path)
            requestContext.setProperty(RequestAuth.PROP_MODE, "none")
            return
        }

        if (header.isNullOrBlank()) {
            log.warnf("Missing X-API-Key for path=%s", path)
            abort(requestContext)
            return
        }

        val hash = hasher.hash(header)
        val client = clientRepository.findActiveByKeyHash(hash)

        if (client == null) {
            // Check if it exists but is expired for a clearer log message
            val expired = clientRepository.findExpiredByKeyHash(hash)
            if (expired != null) {
                log.warnf("Expired API key used: tenant=%s app=%s path=%s", expired.tenantId, expired.appId, path)
                abortUnauthorized(requestContext, "API key has expired")
            } else {
                log.warnf("Unknown API key (hash prefix=%s) for path=%s", hash.take(8), path)
                abort(requestContext)
            }
            return
        }

        if (!rateLimiter.tryAcquire(hash)) {
            log.warnf("Rate limit exceeded tenant=%s app=%s path=%s", client.tenantId, client.appId, path)
            abortTooManyRequests(requestContext)
            return
        }

        requestContext.setProperty(RequestAuth.PROP_MODE, "client")
        requestContext.setProperty(RequestAuth.PROP_TENANT, client.tenantId)
        requestContext.setProperty(RequestAuth.PROP_APP, client.appId)
        log.debugf("Authenticated tenant=%s app=%s path=%s", client.tenantId, client.appId, path)
    }

    private fun abort(requestContext: ContainerRequestContext) =
        abortUnauthorized(requestContext, "Invalid or missing X-API-Key")

    private fun abortUnauthorized(requestContext: ContainerRequestContext, detail: String) {
        requestContext.abortWith(
            Response.status(Response.Status.UNAUTHORIZED)
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .entity(
                    mapOf(
                        "error" to "unauthorized",
                        "message" to detail,
                        "status" to 401,
                    ),
                )
                .build(),
        )
    }

    private fun abortTooManyRequests(requestContext: ContainerRequestContext) {
        requestContext.abortWith(
            Response.status(429)
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .header("Retry-After", "60")
                .entity(
                    mapOf(
                        "error" to "too_many_requests",
                        "message" to "Rate limit exceeded. Try again later.",
                        "status" to 429,
                    ),
                )
                .build(),
        )
    }
}
