package com.docbucket.security

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 hashing for API keys.
 *
 * API keys have 256 bits of random entropy, so brute-force attacks are infeasible regardless of
 * hash speed. HMAC-SHA256 with a server-side secret adds an extra layer: even if the database is
 * fully compromised, the attacker cannot verify guesses without also knowing the HMAC secret.
 *
 * Argon2id / bcrypt are designed for *low-entropy* user-chosen passwords. Using a slow KDF here
 * would only add latency on every authenticated request with no meaningful security benefit.
 */
@ApplicationScoped
class ApiKeyHasher(
    @ConfigProperty(name = "doc.bucket.key-hmac-secret") private val hmacSecret: String,
) {
    private val secretKey: SecretKeySpec by lazy {
        SecretKeySpec(hmacSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    }

    /** Returns the HMAC-SHA256 of [rawKey] as a 64-char lowercase hex string. */
    fun hash(rawKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(rawKey.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time equality check: hashes [rawKey] and compares it to [storedHash].
     * Uses [MessageDigest.isEqual] to prevent timing-based side-channel attacks.
     */
    fun verify(rawKey: String, storedHash: String): Boolean {
        val computed = hash(rawKey).toByteArray(StandardCharsets.UTF_8)
        val stored = storedHash.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(computed, stored)
    }
}
