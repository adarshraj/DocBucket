package com.docbucket.api

import com.docbucket.api.dto.ClientResponse
import com.docbucket.api.dto.RegisterClientRequest
import com.docbucket.domain.ApiClient
import com.docbucket.domain.ApiClientRepository
import com.docbucket.security.ApiKeyHasher
import com.docbucket.security.TenantAppPathValidator
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.NotSupportedException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import java.security.SecureRandom
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Path("/api/clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "clients", description = "Manage API client registrations (requires X-Admin-Key)")
class ClientResource @Inject constructor(
    private val clientRepository: ApiClientRepository,
    private val hasher: ApiKeyHasher,
    @ConfigProperty(name = "doc.bucket.admin-key") private val adminKey: Optional<String>,
) {
    companion object {
        private val log: Logger = Logger.getLogger(ClientResource::class.java)
        private val secureRandom = SecureRandom()
    }

    @POST
    @Transactional
    @Operation(summary = "Register a new API client; returns the raw API key once — store it securely")
    fun register(
        @HeaderParam("X-Admin-Key") providedKey: String?,
        body: RegisterClientRequest,
    ): Response {
        checkAdmin(providedKey)
        if (body.tenantId.isBlank() || body.appId.isBlank()) {
            throw BadRequestException("tenantId and appId are required")
        }
        TenantAppPathValidator.validatePair(body.tenantId, body.appId)

        val rawKey = generateKey()
        val client = ApiClient().apply {
            id = UUID.randomUUID()
            tenantId = body.tenantId
            appId = body.appId
            keyHash = hasher.hash(rawKey)
            label = body.label
            expiresAt = body.expiresAt
            createdAt = Instant.now()
        }
        clientRepository.persist(client)
        log.infof("Registered API client tenant=%s app=%s id=%s expires=%s", body.tenantId, body.appId, client.id, client.expiresAt)

        return Response.status(Response.Status.CREATED).entity(toResponse(client, rawKey)).build()
    }

    @GET
    @Operation(summary = "List all registered API clients")
    fun list(@HeaderParam("X-Admin-Key") providedKey: String?): List<ClientResponse> {
        checkAdmin(providedKey)
        return clientRepository.listAll().map { toResponse(it, null) }
    }

    @POST
    @Path("/{id}/rotate")
    @Transactional
    @Operation(summary = "Issue a new API key for an existing client; the old key is immediately invalidated")
    fun rotate(
        @HeaderParam("X-Admin-Key") providedKey: String?,
        @PathParam("id") id: UUID,
    ): Response {
        checkAdmin(providedKey)
        val client = clientRepository.findById(id) ?: throw NotFoundException("client not found")

        val rawKey = generateKey()
        client.keyHash = hasher.hash(rawKey)
        log.infof("Rotated API key for tenant=%s app=%s id=%s", client.tenantId, client.appId, id)

        return Response.ok(toResponse(client, rawKey)).build()
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Revoke an API client by ID")
    fun revoke(
        @HeaderParam("X-Admin-Key") providedKey: String?,
        @PathParam("id") id: UUID,
    ): Response {
        checkAdmin(providedKey)
        val client = clientRepository.findById(id) ?: throw NotFoundException("client not found")
        clientRepository.delete(client)
        log.infof("Revoked API client tenant=%s app=%s id=%s", client.tenantId, client.appId, id)
        return Response.noContent().build()
    }

    /**
     * Constant-time HMAC comparison: hashes both the provided and configured keys with the same
     * server secret, then compares the resulting hashes. This prevents timing-based side-channel
     * attacks while also ensuring the admin key is never compared as a plaintext string.
     */
    private fun checkAdmin(providedKey: String?) {
        val configured = adminKey.orElse(null)?.takeIf { it.isNotBlank() }
            ?: throw NotSupportedException("Admin API is disabled — set DOC_BUCKET_ADMIN_KEY to enable it")
        if (providedKey.isNullOrBlank()) {
            throw NotAuthorizedException("Missing X-Admin-Key", "Admin")
        }
        if (!hasher.verify(providedKey, hasher.hash(configured))) {
            throw NotAuthorizedException("Invalid X-Admin-Key", "Admin")
        }
    }

    /** Generates a 256-bit (64 hex chars) cryptographically random API key. */
    private fun generateKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun toResponse(client: ApiClient, rawKey: String?) = ClientResponse(
        id = client.id,
        tenantId = client.tenantId,
        appId = client.appId,
        label = client.label,
        createdAt = client.createdAt,
        expiresAt = client.expiresAt,
        apiKey = rawKey,
    )
}
