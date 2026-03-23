package com.docbucket

import com.docbucket.domain.ApiClient
import com.docbucket.domain.ApiClientRepository
import com.docbucket.security.ApiKeyHasher
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(IsolationTestConfigResource::class)
class ApiKeyAuthIntegrationTest {

    @Inject
    lateinit var apiClientRepository: ApiClientRepository

    @Inject
    lateinit var hasher: ApiKeyHasher

    private val adminKey = "test-admin-plaintext"

    @AfterEach
    @Transactional
    fun cleanup() {
        apiClientRepository.deleteAll()
    }

    @Test
    fun `expired API key returns 401 with normalized body`() {
        val rawKey = "a".repeat(64)
        QuarkusTransaction.requiringNew().run {
            val client = ApiClient().apply {
                id = UUID.randomUUID()
                tenantId = "t1"
                appId = "a1"
                keyHash = hasher.hash(rawKey)
                label = "expired"
                expiresAt = Instant.now().minus(1, ChronoUnit.DAYS)
                createdAt = Instant.now().minus(2, ChronoUnit.DAYS)
            }
            apiClientRepository.persist(client)
        }

        given()
            .header("X-API-Key", rawKey)
        .`when`().get("/api/documents")
        .then()
            .statusCode(401)
            .body("error", equalTo("unauthorized"))
            .body("status", equalTo(401))
            .body("message", containsString("expired"))
    }

    @Test
    fun `client register list rotate delete`() {
        val reg = given()
            .header("X-Admin-Key", adminKey)
            .contentType(ContentType.JSON)
            .body("""{"tenantId":"regt","appId":"rega","label":"L"}""")
        .`when`().post("/api/clients")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("apiKey", notNullValue())
            .extract().body().jsonPath()

        val id = UUID.fromString(reg.getString("id"))
        val rawKey = reg.getString("apiKey")

        given()
            .header("X-Admin-Key", adminKey)
        .`when`().get("/api/clients")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))

        given()
            .header("X-Admin-Key", adminKey)
            .contentType(ContentType.JSON)
        .`when`().post("/api/clients/$id/rotate")
        .then()
            .statusCode(200)
            .body("apiKey", notNullValue())

        given()
            .header("X-Admin-Key", adminKey)
        .`when`().delete("/api/clients/$id")
        .then()
            .statusCode(204)

        given()
            .header("X-Admin-Key", adminKey)
        .`when`().get("/api/clients")
        .then()
            .statusCode(200)
            .body("size()", equalTo(0))
    }
}
