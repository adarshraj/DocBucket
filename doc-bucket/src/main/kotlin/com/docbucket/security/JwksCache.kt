package com.docbucket.security

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and caches EC public keys from the auth-service JWKS endpoint.
 *
 * Keys are loaded at startup and on-demand when an unknown `kid` is encountered
 * (handles key rotation without a restart).
 */
@ApplicationScoped
class JwksCache @Inject constructor(
    @ConfigProperty(name = "doc.bucket.auth.jwks-url") private val jwksUrl: String,
) {
    companion object {
        private val log: Logger = Logger.getLogger(JwksCache::class.java)
    }

    private val keys = ConcurrentHashMap<String, ECPublicKey>()
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    fun onStart(@Observes ev: StartupEvent) {
        refresh()
    }

    /**
     * Returns the [ECPublicKey] for [kid], fetching JWKS once if the key is not cached.
     * Returns null if the key is still unknown after a refresh.
     */
    fun getKey(kid: String): ECPublicKey? {
        return keys[kid] ?: run {
            refresh()
            keys[kid]
        }
    }

    /** Fetches the JWKS endpoint and updates the in-memory key cache. */
    fun refresh() {
        try {
            val request = HttpRequest.newBuilder(URI.create(jwksUrl)).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val jwkSet = JWKSet.parse(response.body())
            var loaded = 0
            jwkSet.keys.forEach { jwk ->
                if (jwk is ECKey && jwk.keyID != null) {
                    keys[jwk.keyID] = jwk.toECPublicKey()
                    loaded++
                }
            }
            log.infof("Loaded %d EC JWK(s) from %s", loaded, jwksUrl)
        } catch (e: Exception) {
            log.errorf(e, "Failed to fetch JWKS from %s — authentication will fail until keys are loaded", jwksUrl)
        }
    }

    /** Directly registers a key — intended for tests only. */
    internal fun loadKey(kid: String, key: ECPublicKey) {
        keys[kid] = key
    }
}
