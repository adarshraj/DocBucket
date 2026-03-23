package com.docbucket

import com.docbucket.domain.ApiClientRepository
import com.docbucket.storage.ObjectStorage
import com.docbucket.storage.PutObjectResult
import io.mockk.every
import io.mockk.justRun
import io.quarkiverse.test.junit.mockk.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Path

@QuarkusTest
class DocumentResourceTest {

    @InjectMock
    lateinit var objectStorage: ObjectStorage

    @Inject
    lateinit var apiClientRepository: ApiClientRepository

    private val tenant = "test-tenant"
    private val app = "test-app"
    private val content = "hello docbucket".toByteArray()

    @BeforeEach
    @Transactional
    fun setupMocks() {
        apiClientRepository.deleteAll()
        every {
            objectStorage.putObject(any(), any(), any<Path>(), any())
        } returns PutObjectResult(etag = "abc123", versionId = null)

        every {
            objectStorage.getObject(any(), any())
        } returns ByteArrayInputStream(content)

        every {
            objectStorage.presignGetObject(any(), any(), any())
        } returns URI.create("http://presigned.example/doc")

        justRun { objectStorage.deleteObject(any(), any()) }
    }

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    fun `upload returns 200 with document metadata`() {
        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .contentType(ContentType.BINARY)
            .body(content)
        .`when`().post("/api/documents/upload")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("tenantId", equalTo(tenant))
            .body("appId", equalTo(app))
            .body("sizeBytes", equalTo(content.size))
            .body("contentPath", notNullValue())
    }

    @Test
    fun `upload with filename stores sanitised name and originalFilename`() {
        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .contentType(ContentType.BINARY)
            .body(content)
            .queryParam("filename", "my report.pdf")
        .`when`().post("/api/documents/upload")
        .then()
            .statusCode(200)
            .body("objectKey", containsString("my_report.pdf"))
            .body("originalFilename", equalTo("my report.pdf"))
    }

    @Test
    fun `upload rejects tenant with slash`() {
        given()
            .header("X-Tenant-Id", "bad/tenant")
            .header("X-App-Id", app)
            .contentType(ContentType.BINARY)
            .body(content)
        .`when`().post("/api/documents/upload")
        .then()
            .statusCode(400)
            .body("error", equalTo("bad_request"))
            .body("status", equalTo(400))
    }

    @Test
    fun `upload without tenant returns 400`() {
        given()
            .header("X-App-Id", app)
            .contentType(ContentType.BINARY)
            .body(content)
        .`when`().post("/api/documents/upload")
        .then()
            .statusCode(400)
    }

    @Test
    fun `upload without app returns 400`() {
        given()
            .header("X-Tenant-Id", tenant)
            .contentType(ContentType.BINARY)
            .body(content)
        .`when`().post("/api/documents/upload")
        .then()
            .statusCode(400)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list returns empty page when no documents`() {
        given()
            .header("X-Tenant-Id", "empty-tenant")
            .header("X-App-Id", "empty-app")
        .`when`().get("/api/documents")
        .then()
            .statusCode(200)
            .body("total", equalTo(0))
            .body("items.size()", equalTo(0))
            .body("hasNext", equalTo(false))
    }

    @Test
    fun `list returns uploaded document`() {
        val id = uploadOne()

        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
        .`when`().get("/api/documents")
        .then()
            .statusCode(200)
            .body("items.size()", org.hamcrest.Matchers.greaterThanOrEqualTo(1))
            .body("items.find { it.id == '$id' }.tenantId", equalTo(tenant))
    }

    @Test
    fun `list respects page size`() {
        repeat(3) { uploadOne() }

        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .queryParam("page", 0)
            .queryParam("size", 2)
        .`when`().get("/api/documents")
        .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .body("size", equalTo(2))
    }

    @Test
    fun `list without tenant returns 400`() {
        given()
            .header("X-App-Id", app)
        .`when`().get("/api/documents")
        .then()
            .statusCode(400)
    }

    // ── get metadata ──────────────────────────────────────────────────────────

    @Test
    fun `getMetadata returns document`() {
        val id = uploadOne()

        given()
        .`when`().get("/api/documents/$id")
        .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("tenantId", equalTo(tenant))
    }

    @Test
    fun `getMetadata returns 404 for unknown id`() {
        given()
        .`when`().get("/api/documents/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
    }

    // ── get content ───────────────────────────────────────────────────────────

    @Test
    fun `getContent streams bytes`() {
        val id = uploadOne()

        val bytes = given()
        .`when`().get("/api/documents/$id/content")
        .then()
            .statusCode(200)
            .header("Content-Length", equalTo(content.size.toString()))
            .header("ETag", equalTo("\"abc123\""))
            .extract().asByteArray()

        assert(bytes.contentEquals(content)) { "Response body did not match uploaded content" }
    }

    @Test
    fun `presign returns url`() {
        val id = uploadOne()

        given()
        .`when`().get("/api/documents/$id/presign?ttl=120")
        .then()
            .statusCode(200)
            .body("url", equalTo("http://presigned.example/doc"))
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete returns 204 and document is gone`() {
        val id = uploadOne()

        given()
        .`when`().delete("/api/documents/$id")
        .then()
            .statusCode(204)

        given()
        .`when`().get("/api/documents/$id")
        .then()
            .statusCode(404)
    }

    @Test
    fun `delete is idempotent`() {
        val id = uploadOne()

        given().`when`().delete("/api/documents/$id").then().statusCode(204)
        given().`when`().delete("/api/documents/$id").then().statusCode(204)
    }

    @Test
    fun `delete unknown id returns 404`() {
        given()
        .`when`().delete("/api/documents/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
    }

    // ── S3 error handling ─────────────────────────────────────────────────────

    @Test
    fun `delete succeeds even when S3 delete throws`() {
        val id = uploadOne()
        every { objectStorage.deleteObject(any(), any()) } throws RuntimeException("S3 unavailable")

        given()
        .`when`().delete("/api/documents/$id")
        .then()
            .statusCode(204) // soft-delete succeeds; S3 error is logged, not propagated
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun uploadOne(): String {
        return given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .contentType(ContentType.BINARY)
            .body(content)
        .`when`().post("/api/documents/upload")
        .then()
            .statusCode(200)
            .extract().path("id")
    }
}
