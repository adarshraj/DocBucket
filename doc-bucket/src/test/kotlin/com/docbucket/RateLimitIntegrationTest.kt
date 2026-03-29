package com.docbucket

import com.docbucket.domain.ApiClientRepository
import com.docbucket.security.JwksCache
import com.docbucket.storage.ObjectStorage
import com.docbucket.storage.PutObjectResult
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.mockk.every
import io.mockk.justRun
import io.quarkiverse.test.junit.mockk.InjectMock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import java.util.UUID

class RateLimitTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): MutableMap<String, String> = hashMapOf(
        "doc.bucket.rate-limit.enabled" to "true",
        "doc.bucket.rate-limit.requests-per-minute" to "2",
    )

    override fun stop() {
        System.clearProperty("doc.bucket.rate-limit.enabled")
        System.clearProperty("doc.bucket.rate-limit.requests-per-minute")
    }
}

@QuarkusTest
@QuarkusTestResource(IsolationTestConfigResource::class)
@QuarkusTestResource(RateLimitTestResource::class)
class RateLimitIntegrationTest {

    @InjectMock
    lateinit var objectStorage: ObjectStorage

    @Inject
    lateinit var apiClientRepository: ApiClientRepository

    @Inject
    lateinit var jwksCache: JwksCache

    private lateinit var testKid: String
    private lateinit var testPrivateKey: ECPrivateKey

    @BeforeEach
    @Transactional
    fun setup() {
        apiClientRepository.deleteAll()

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        testKid = UUID.randomUUID().toString()
        testPrivateKey = kp.private as ECPrivateKey
        jwksCache.loadKey(testKid, kp.public as ECPublicKey)

        every { objectStorage.putObject(any(), any(), any<Path>(), any()) } returns PutObjectResult("e", null)
        justRun { objectStorage.deleteObject(any(), any()) }
        every { objectStorage.getObject(any(), any()) } returns java.io.ByteArrayInputStream(byteArrayOf())
        every { objectStorage.presignGetObject(any(), any(), any()) } returns java.net.URI.create("http://x")
    }

    @AfterEach
    @Transactional
    fun cleanup() {
        apiClientRepository.deleteAll()
    }

    @Test
    fun `rate limit returns 429 after burst`() {
        val token = buildJwt(userId = "rl-user", appId = "rla")

        repeat(2) {
            given()
                .header("Authorization", "Bearer $token")
            .`when`().get("/api/documents")
            .then()
                .statusCode(200)
        }

        given()
            .header("Authorization", "Bearer $token")
        .`when`().get("/api/documents")
        .then()
            .statusCode(429)
            .body("error", equalTo("too_many_requests"))
            .body("status", equalTo(429))
    }

    private fun buildJwt(userId: String, appId: String?, ttlSeconds: Long = 300): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(userId)
            .claim("userId", userId)
            .apply { if (appId != null) claim("appId", appId) }
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .build()

        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(testKid).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(testPrivateKey))
        return jwt.serialize()
    }
}
