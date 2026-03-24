package com.docbucket.security

import jakarta.enterprise.context.ApplicationScoped

/**
 * In-memory flag indicating whether any API clients are registered.
 * Populated at startup by [StartupGuard] and kept in sync by [com.docbucket.api.ClientResource]
 * after mutations, so [ApiKeyAuthFilter] avoids a COUNT(*) DB round-trip on every request.
 */
@ApplicationScoped
class ClientRegistryCache {

    @Volatile
    private var clientsRegistered: Boolean = false

    fun hasClients(): Boolean = clientsRegistered

    fun markHasClients() {
        clientsRegistered = true
    }

    fun refresh(clientCount: Long) {
        clientsRegistered = clientCount > 0
    }
}
