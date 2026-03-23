package com.docbucket.storage

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration

@ApplicationScoped
class S3ObjectStorage @Inject constructor(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
) : ObjectStorage {

    override fun putObject(bucket: String, key: String, body: Path, contentType: String?): PutObjectResult {
        val builder = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
        if (contentType != null) {
            builder.contentType(contentType)
        }
        val resp = s3Client.putObject(builder.build(), RequestBody.fromFile(body))
        return PutObjectResult(etag = resp.eTag(), versionId = resp.versionId())
    }

    override fun getObject(bucket: String, key: String): InputStream {
        val req = GetObjectRequest.builder().bucket(bucket).key(key).build()
        return s3Client.getObject(req, ResponseTransformer.toInputStream())
    }

    override fun deleteObject(bucket: String, key: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(key).build(),
        )
    }

    override fun presignGetObject(bucket: String, key: String, signatureDuration: Duration): URI {
        val get = GetObjectRequest.builder().bucket(bucket).key(key).build()
        val presign = GetObjectPresignRequest.builder()
            .signatureDuration(signatureDuration)
            .getObjectRequest(get)
            .build()
        return s3Presigner.presignGetObject(presign).url().toURI()
    }
}
