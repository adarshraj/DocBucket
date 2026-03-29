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
    @ConfigProperty(name = "doc.bucket.auth.jwks-url") private val jwksUrl: String,
    @ConfigProperty(name = "doc.bucket.admin-key") private val adminKey: Optional<String>,
    private val clientRepository: ApiClientRepository,
    private val clientRegistryCache: ClientRegistryCache,
) {
    companion object {
        private val log: Logger = Logger.getLogger(StartupGuard::class.java)
    }

    @Transactional
    fun onStart(@Observes ev: StartupEvent) {
        checkJwksUrl()
        checkAdminKey()
        val count = clientRepository.countAll()
        clientRegistryCache.refresh(count)
    }

    private fun checkJwksUrl() {
        if (jwksUrl.isBlank()) {
            val msg = "doc.bucket.auth.jwks-url is not set. Set DOC_BUCKET_AUTH_JWKS_URL to the auth-service JWKS endpoint."
            if (isProd()) throw IllegalStateException(msg) else log.warn(msg)
        } else {
            log.infof("JWT auth: JWKS endpoint = %s", jwksUrl)
        }
    }

    private fun checkAdminKey() {
        val hasKey = adminKey.orElse(null)?.isNotBlank() == true
        if (!hasKey) {
            log.warn("DOC_BUCKET_ADMIN_KEY is not set — POST/GET/DELETE /api/clients is disabled (HTTP 501). Set it to enable client management.")
        }
    }

    private fun isProd() = profile == "prod"
}
