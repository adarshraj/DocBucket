package com.docbucket.service

import java.io.InputStream

data class DocumentContentStream(
    val stream: InputStream,
    val contentType: String?,
    val sizeBytes: Long?,
    val etag: String?,
    val originalFilename: String?,
    /** Last segment of the storage key when original filename is absent. */
    val objectKey: String,
)
