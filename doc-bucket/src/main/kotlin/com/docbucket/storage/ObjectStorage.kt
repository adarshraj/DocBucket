package com.docbucket.storage

import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration

data class PutObjectResult(
    val etag: String?,
    val versionId: String?,
)

/**
 * Provider-agnostic object storage (implemented with S3 API — Garage, R2, AWS, etc.).
 */
interface ObjectStorage {

    fun putObject(bucket: String, key: String, body: Path, contentType: String?): PutObjectResult

    fun getObject(bucket: String, key: String): InputStream

    fun deleteObject(bucket: String, key: String)

    /** Time-limited GET URL for direct client download (S3 presigned URL). */
    fun presignGetObject(bucket: String, key: String, signatureDuration: Duration): URI
}
