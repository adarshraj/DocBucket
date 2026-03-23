package com.docbucket

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Resets dynamic config between test classes so stricter [QuarkusTestResource] settings
 * (upload limits, rate limiting) do not leak across `@QuarkusTest` classes in the same JVM.
 */
class IsolationTestConfigResource : QuarkusTestResourceLifecycleManager {
    override fun start(): MutableMap<String, String> = hashMapOf(
        "doc.bucket.upload.max-bytes" to "104857600",
        // Do not set mime-allowlist here: Quarkus merges QuarkusTestResource config from
        // all @QuarkusTest classes in the module, which would override %test YAML for every test.
        "doc.bucket.rate-limit.enabled" to "false",
    )

    override fun stop() {
        System.clearProperty("doc.bucket.upload.max-bytes")
        System.clearProperty("doc.bucket.rate-limit.enabled")
    }
}
