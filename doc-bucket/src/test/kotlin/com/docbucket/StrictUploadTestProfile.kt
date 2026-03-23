package com.docbucket

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Isolated Quarkus bootstrap for strict upload policy tests (small max size, PDF-only MIME).
 * Uses a separate profile so config is not merged with other @QuarkusTest classes.
 */
class StrictUploadTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "doc.bucket.upload.max-bytes" to "64",
            "doc.bucket.upload.mime-allowlist" to "application/pdf",
        )
}
