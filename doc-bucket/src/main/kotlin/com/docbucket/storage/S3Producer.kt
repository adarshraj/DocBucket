package com.docbucket.storage

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@ApplicationScoped
class S3Producer(private val config: StorageConfig) {

    @Produces
    @Singleton
    fun s3Client(): S3Client {
        val creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey()),
        )
        val s3Conf = S3Configuration.builder()
            .pathStyleAccessEnabled(config.pathStyleAccess())
            .build()
        return S3Client.builder()
            .endpointOverride(config.endpoint())
            .region(Region.of(config.region()))
            .credentialsProvider(creds)
            .serviceConfiguration(s3Conf)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
    }

    @Produces
    @Singleton
    fun s3Presigner(): S3Presigner {
        val creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey()),
        )
        val s3Conf = S3Configuration.builder()
            .pathStyleAccessEnabled(config.pathStyleAccess())
            .build()
        return S3Presigner.builder()
            .endpointOverride(config.endpoint())
            .region(Region.of(config.region()))
            .credentialsProvider(creds)
            .serviceConfiguration(s3Conf)
            .build()
    }
}
