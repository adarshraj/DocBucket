package com.docbucket

import com.docbucket.domain.ApiClientRepository
import com.docbucket.storage.ObjectStorage
import com.docbucket.storage.PutObjectResult
import io.mockk.every
import io.mockk.justRun
import io.quarkiverse.test.junit.mockk.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

@QuarkusTest
@TestProfile(StrictUploadTestProfile::class)
class StrictUploadPolicyTest {

    @InjectMock
    lateinit var objectStorage: ObjectStorage

    @Inject
    lateinit var apiClientRepository: ApiClientRepository

    private val tenant = "strict-tenant"
    private val app = "strict-app"

    @BeforeEach
    @Transactional
    fun setup() {
        apiClientRepository.deleteAll()
        every {
            objectStorage.putObject(any(), any(), any<Path>(), any())
        } returns PutObjectResult(etag = "e", versionId = null)
        justRun { objectStorage.deleteObject(any(), any()) }
    }

    @Test
    fun `upload rejects body larger than configured max`() {
        val body = ByteArray(128) { 1 }
        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .contentType(ContentType.BINARY)
            .body(body)
            .`when`().post("/api/documents/upload")
            .then()
            .statusCode(400)
            .body("error", equalTo("bad_request"))
    }

    @Test
    fun `upload rejects content-type not in allowlist`() {
        val body = byteArrayOf(1, 2, 3)
        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .contentType("text/plain")
            .body(body)
            .`when`().post("/api/documents/upload")
            .then()
            .statusCode(400)
            .body("error", equalTo("bad_request"))
    }

    @Test
    fun `upload accepts allowed content-type within size`() {
        val body = byteArrayOf(1, 2, 3)
        given()
            .header("X-Tenant-Id", tenant)
            .header("X-App-Id", app)
            .contentType("application/pdf")
            .body(body)
            .`when`().post("/api/documents/upload")
            .then()
            .statusCode(200)
            .body("contentType", containsString("application/pdf"))
    }
}
