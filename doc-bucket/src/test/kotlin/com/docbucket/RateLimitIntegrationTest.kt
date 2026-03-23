package com.docbucket

import com.docbucket.domain.ApiClientRepository
import com.docbucket.storage.ObjectStorage
import com.docbucket.storage.PutObjectResult
import io.mockk.every
import io.mockk.justRun
import io.quarkiverse.test.junit.mockk.InjectMock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

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

    private val adminKey = "test-admin-plaintext"

    @BeforeEach
    @Transactional
    fun setup() {
        apiClientRepository.deleteAll()
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
        val path = given()
            .header("X-Admin-Key", adminKey)
            .contentType(ContentType.JSON)
            .body("""{"tenantId":"rlt","appId":"rla"}""")
        .`when`().post("/api/clients")
        .then()
            .statusCode(201)
            .extract().path<String>("apiKey")

        repeat(2) {
            given()
                .header("X-API-Key", path)
            .`when`().get("/api/documents")
            .then()
                .statusCode(200)
        }

        given()
            .header("X-API-Key", path)
        .`when`().get("/api/documents")
        .then()
            .statusCode(429)
            .body("error", equalTo("too_many_requests"))
            .body("status", equalTo(429))
    }
}
