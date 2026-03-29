package com.docbucket.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.text.ParseException
import java.util.Date

/**
 * Validates ES256 Bearer JWTs issued by the auth-service.
 *
 * On each /api/ request (except /api/clients):
 * 1. Extracts `Authorization: Bearer <token>` header.
 * 2. Parses the JWT header to get `kid`, fetches the matching EC public key from [JwksCache].
 * 3. Verifies the ES256 signature and `exp` claim.
 * 4. Maps `userId` to [RequestAuth.PROP_TENANT] and `appId` to [RequestAuth.PROP_APP].
 *
 * Dev-open mode: if no `Authorization` header is present, sets mode to "none" and continues
 * unauthenticated. This should never happen in production.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
class JwtBearerAuthFilter @Inject constructor(
    private val jwksCache: JwksCache,
    private val rateLimiter: RateLimiter,
) : ContainerRequestFilter {

    companion object {
        private val log: Logger = Logger.getLogger(JwtBearerAuthFilter::class.java)
    }

    override fun filter(ctx: ContainerRequestContext) {
        val path = ctx.uriInfo.path
        if (path.startsWith("q/") || path.startsWith("/q/")) return
        if (path.startsWith("api/clients") || path.startsWith("/api/clients")) return
        if (!path.startsWith("api/") && !path.startsWith("/api/")) return

        val authHeader = ctx.getHeaderString("Authorization")

        if (authHeader.isNullOrBlank()) {
            log.debugf("Dev-open mode: no Authorization header for path=%s", path)
            ctx.setProperty(RequestAuth.PROP_MODE, "none")
            return
        }

        if (!authHeader.startsWith("Bearer ")) {
            abortUnauthorized(ctx, "Authorization header must use Bearer scheme")
            return
        }

        val token = authHeader.removePrefix("Bearer ").trim()

        try {
            val jwt = SignedJWT.parse(token)

            val kid = jwt.header.keyID
            if (kid.isNullOrBlank()) {
                abortUnauthorized(ctx, "JWT missing kid header")
                return
            }

            if (jwt.header.algorithm != JWSAlgorithm.ES256) {
                log.warnf("Rejected JWT with unsupported algorithm=%s path=%s", jwt.header.algorithm, path)
                abortUnauthorized(ctx, "Unsupported JWT algorithm — ES256 required")
                return
            }

            val publicKey = jwksCache.getKey(kid)
            if (publicKey == null) {
                log.warnf("Unknown JWT kid=%s path=%s", kid, path)
                abortUnauthorized(ctx, "Unknown signing key")
                return
            }

            if (!jwt.verify(ECDSAVerifier(publicKey))) {
                log.warnf("JWT signature verification failed kid=%s path=%s", kid, path)
                abortUnauthorized(ctx, "Invalid JWT signature")
                return
            }

            val claims = jwt.jwtClaimsSet
            val exp = claims.expirationTime
            if (exp == null || exp.before(Date())) {
                abortUnauthorized(ctx, "JWT has expired")
                return
            }

            val userId = claims.getStringClaim("userId") ?: claims.subject
            val appId = claims.getStringClaim("appId")

            if (userId.isNullOrBlank()) {
                abortUnauthorized(ctx, "JWT missing userId claim")
                return
            }

            if (!rateLimiter.tryAcquire(userId)) {
                log.warnf("Rate limit exceeded userId=%s path=%s", userId, path)
                abortTooManyRequests(ctx)
                return
            }

            ctx.setProperty(RequestAuth.PROP_MODE, "client")
            ctx.setProperty(RequestAuth.PROP_TENANT, userId)
            ctx.setProperty(RequestAuth.PROP_APP, appId ?: "")
            log.debugf("Authenticated userId=%s appId=%s path=%s", userId, appId, path)

        } catch (e: ParseException) {
            log.debugf("Malformed JWT for path=%s: %s", path, e.message)
            abortUnauthorized(ctx, "Malformed JWT")
        }
    }

    private fun abortUnauthorized(ctx: ContainerRequestContext, detail: String) {
        ctx.abortWith(
            Response.status(Response.Status.UNAUTHORIZED)
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .entity(mapOf("error" to "unauthorized", "message" to detail, "status" to 401))
                .build(),
        )
    }

    private fun abortTooManyRequests(ctx: ContainerRequestContext) {
        ctx.abortWith(
            Response.status(429)
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .header("Retry-After", "60")
                .entity(mapOf("error" to "too_many_requests", "message" to "Rate limit exceeded. Try again later.", "status" to 429))
                .build(),
        )
    }
}
