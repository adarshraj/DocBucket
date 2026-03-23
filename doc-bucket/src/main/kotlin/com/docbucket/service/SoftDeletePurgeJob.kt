package com.docbucket.service

import com.docbucket.config.PurgeConfig
import com.docbucket.domain.DocumentRepository
import com.docbucket.storage.ObjectStorage
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant
import java.time.temporal.ChronoUnit

@ApplicationScoped
class SoftDeletePurgeJob @Inject constructor(
    private val purgeConfig: PurgeConfig,
    private val documentRepository: DocumentRepository,
    private val objectStorage: ObjectStorage,
) {
    companion object {
        private val log: Logger = Logger.getLogger(SoftDeletePurgeJob::class.java)
    }

    @Scheduled(every = "24h")
    @Transactional
    fun purgeStaleSoftDeletes() {
        if (!purgeConfig.enabled()) {
            return
        }
        val retentionDays = purgeConfig.retentionDays()
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val stale = documentRepository.findDeletedBefore(cutoff)
        if (stale.isEmpty()) {
            log.debugf("Purge: no soft-deleted documents older than %d days", retentionDays)
            return
        }
        log.infof("Purge: removing %d soft-deleted document row(s) older than cutoff=%s", stale.size, cutoff)
        for (entity in stale) {
            try {
                objectStorage.deleteObject(entity.bucket, entity.objectKey)
            } catch (e: Exception) {
                log.warnf(e, "Purge: S3 delete failed id=%s — removing DB row anyway", entity.id)
            }
            documentRepository.delete(entity)
        }
    }
}
