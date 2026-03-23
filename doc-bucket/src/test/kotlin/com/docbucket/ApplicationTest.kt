package com.docbucket

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

@QuarkusTest
class ApplicationTest {

    @Test
    fun healthLive() {
        given()
            .`when`().get("/q/health/live")
            .then()
            .statusCode(200)
            .body(notNullValue())
    }

    @Test
    fun openApiAvailable() {
        given()
            .`when`().get("/q/openapi")
            .then()
            .statusCode(200)
    }
}
