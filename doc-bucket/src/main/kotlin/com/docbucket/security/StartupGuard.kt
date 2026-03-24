package com.docbucket.security

import com.docbucket.domain.ApiClientRepository
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

@ApplicationScoped
class StartupGuard @Inject constructor(
    @ConfigProperty(name = "quarkus.profile", defaultValue = "dev") private val profile: String,
    @ConfigProperty(name = "doc.bucket.key-hmac-secret") private val hmacSecret: String,
    @ConfigProperty(name = "doc.bucket.admin-key") private val adminKey: Optional<String>,
    private val clientRepository: ApiClientRepository,
    private val clientRegistryCache: ClientRegistryCache,
) {
    companion object {
        private val log: Logger = Logger.getLogger(StartupGuard::class.java)
        private const val DEFAULT_SECRET = "dev-only-insecure-hmac-secret-change-in-prod"
        private const val MIN_SECRET_LENGTH = 32
    }

    @Transactional
    fun onStart(@Observes ev: StartupEvent) {
        validateHmacSecret()
        checkAdminKey()
        val count = clientRepository.countAll()
        clientRegistryCache.refresh(count)
        checkOpenMode(count)
    }

    private fun validateHmacSecret() {
        if (hmacSecret == DEFAULT_SECRET) {
            val msg = "doc.bucket.key-hmac-secret is using the default insecure value. Set DOC_BUCKET_KEY_HMAC_SECRET."
            if (isProd()) throw IllegalStateException(msg) else log.warn(msg)
        }
        if (hmacSecret.length < MIN_SECRET_LENGTH) {
            val msg = "doc.bucket.key-hmac-secret is only ${hmacSecret.length} chars; minimum $MIN_SECRET_LENGTH required."
            if (isProd()) throw IllegalStateException(msg) else log.warn(msg)
        }
    }

    private fun checkAdminKey() {
        val hasKey = adminKey.orElse(null)?.isNotBlank() == true
        if (!hasKey) {
            log.warn("DOC_BUCKET_ADMIN_KEY is not set — POST/GET/DELETE /api/clients is disabled (HTTP 501). Set it to enable client management.")
        }
    }

    private fun checkOpenMode(count: Long) {
        if (count == 0L) {
            val msg = "SECURITY: No API clients registered — all /api/documents/* endpoints are unprotected (dev-open mode). Register a client via POST /api/clients."
            if (isProd()) log.error(msg) else log.warn(msg)
        }
    }

    private fun isProd() = profile == "prod"
}
