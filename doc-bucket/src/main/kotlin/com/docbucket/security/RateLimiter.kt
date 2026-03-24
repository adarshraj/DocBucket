package com.docbucket.security

import com.docbucket.config.RateLimitConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
class RateLimiter @Inject constructor(
    private val rateLimitConfig: RateLimitConfig,
) {
    private data class Window(val startMs: Long, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()
    private val windowMs = 60_000L

    /**
     * Returns `true` if the caller is within quota, `false` if they should be throttled.
     */
    fun tryAcquire(key: String): Boolean {
        if (!rateLimitConfig.enabled()) return true
        val now = System.currentTimeMillis()
        val window = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startMs > windowMs) {
                Window(now, AtomicInteger(0))
            } else {
                existing
            }
        }!!
        return window.count.incrementAndGet() <= rateLimitConfig.requestsPerMinute()
    }

    /**
     * Removes stale window entries for keys that have been inactive for more than two window
     * periods. Prevents unbounded growth of the map when API key hashes accumulate over time.
     */
    @Scheduled(every = "2m")
    fun evictStaleWindows() {
        val cutoff = System.currentTimeMillis() - windowMs * 2
        windows.entries.removeIf { it.value.startMs < cutoff }
    }
}
