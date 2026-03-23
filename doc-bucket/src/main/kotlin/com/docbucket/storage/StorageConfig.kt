package com.docbucket.storage

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.net.URI

@ConfigMapping(prefix = "doc.storage")
interface StorageConfig {

    fun endpoint(): URI

    @WithDefault("garage")
    fun region(): String

    fun accessKeyId(): String

    fun secretAccessKey(): String

    @WithDefault("true")
    fun pathStyleAccess(): Boolean

    @WithDefault("documents")
    fun defaultBucket(): String
}
