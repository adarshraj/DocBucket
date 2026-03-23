package com.docbucket.security

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simple fixed-window rate limiter keyed by an arbitrary string (API key hash, IP, etc.).
 * A new window opens as soon as the previous one expires; counts do not carry over.
 *
 * TODO: In-memory counters are per JVM — with multiple pods the effective limit is
 * `requestsPerMinute × instanceCount`. Use Redis, a gateway (Traefik/Caddy), or an API mesh for shared quotas.
 */
@ApplicationScoped
class RateLimiter(
    @ConfigProperty(name = "doc.bucket.rate-limit.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "doc.bucket.rate-limit.requests-per-minute", defaultValue = "200")
    private val requestsPerMinute: Int,
) {
    private data class Window(val startMs: Long, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()
    private val windowMs = 60_000L

    /**
     * Returns `true` if the caller is within quota, `false` if they should be throttled.
     */
    fun tryAcquire(key: String): Boolean {
        if (!enabled) return true
        val now = System.currentTimeMillis()
        val window = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startMs > windowMs) {
                Window(now, AtomicInteger(0))
            } else {
                existing
            }
        }!!
        return window.count.incrementAndGet() <= requestsPerMinute
    }
}
