package com.docbucket.service

import com.docbucket.config.PurgeConfig
import com.docbucket.storage.ObjectStorage
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.time.temporal.ChronoUnit

@ApplicationScoped
class SoftDeletePurgeJob @Inject constructor(
    private val purgeConfig: PurgeConfig,
    private val objectStorage: ObjectStorage,
    private val purgeHelper: PurgeHelper,
) {
    companion object {
        private val log: Logger = Logger.getLogger(SoftDeletePurgeJob::class.java)
    }

    /**
     * Runs without a DB transaction so that S3 deletes and DB row removal are in separate
     * transactions. S3 operations are not transactional; doing them inside a DB transaction
     * risks a scenario where the DB commit fails after S3 objects are already deleted — leaving
     * orphaned rows pointing to missing objects. With this approach:
     *   1. Stale entities are read (auto-transaction for the read).
     *   2. S3 objects are deleted outside any DB transaction (idempotent; safe to retry).
     *   3. DB rows are removed in a fresh transaction via [PurgeHelper.deleteByIds].
     */
    @Scheduled(every = "24h")
    fun purgeStaleSoftDeletes() {
        if (!purgeConfig.enabled()) {
            return
        }
        val retentionDays = purgeConfig.retentionDays()
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val stale = purgeHelper.loadStaleBefore(cutoff)
        if (stale.isEmpty()) {
            log.debugf("Purge: no soft-deleted documents older than %d days", retentionDays)
            return
        }
        log.infof("Purge: removing %d soft-deleted document row(s) older than cutoff=%s", stale.size, cutoff)

        for (item in stale) {
            try {
                objectStorage.deleteObject(item.bucket, item.objectKey)
            } catch (e: Exception) {
                log.warnf(e, "Purge: S3 delete failed id=%s — DB row will still be removed", item.id)
            }
        }
        purgeHelper.deleteByIds(stale.map { it.id })
    }
}
