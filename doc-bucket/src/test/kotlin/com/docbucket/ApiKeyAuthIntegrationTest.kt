package com.docbucket

import com.docbucket.domain.ApiClientRepository
import com.docbucket.security.JwksCache
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(IsolationTestConfigResource::class)
class ApiKeyAuthIntegrationTest {

    @Inject
    lateinit var apiClientRepository: ApiClientRepository

    @Inject
    lateinit var jwksCache: JwksCache

    private val adminKey = "test-admin-plaintext"

    private lateinit var testKid: String
    private lateinit var testPrivateKey: ECPrivateKey

    @BeforeEach
    fun setupKeys() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        testKid = UUID.randomUUID().toString()
        testPrivateKey = kp.private as ECPrivateKey
        jwksCache.loadKey(testKid, kp.public as ECPublicKey)
    }

    @AfterEach
    @Transactional
    fun cleanup() {
        apiClientRepository.deleteAll()
    }

    @Test
    fun `expired JWT returns 401`() {
        val token = buildJwt(userId = "user-1", appId = "app-1", ttlSeconds = -60)

        given()
            .header("Authorization", "Bearer $token")
        .`when`().get("/api/documents")
        .then()
            .statusCode(401)
            .body("error", equalTo("unauthorized"))
            .body("status", equalTo(401))
            .body("message", containsString("expired"))
    }

    @Test
    fun `unknown kid returns 401`() {
        val token = buildJwt(userId = "user-1", appId = "app-1", kid = "unknown-kid-${UUID.randomUUID()}")

        given()
            .header("Authorization", "Bearer $token")
        .`when`().get("/api/documents")
        .then()
            .statusCode(401)
            .body("error", equalTo("unauthorized"))
    }

    @Test
    fun `valid JWT authenticates and reaches document list`() {
        val token = buildJwt(userId = "user-1", appId = "app-1")

        // 200 or empty list — just confirm auth passes (S3 may not be up in tests)
        given()
            .header("Authorization", "Bearer $token")
        .`when`().get("/api/documents")
        .then()
            .statusCode(200)
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

    // ---- helpers ----

    private fun buildJwt(
        userId: String,
        appId: String?,
        ttlSeconds: Long = 300,
        kid: String = testKid,
    ): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(userId)
            .claim("userId", userId)
            .apply { if (appId != null) claim("appId", appId) }
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .build()

        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(testPrivateKey))
        return jwt.serialize()
    }
}
