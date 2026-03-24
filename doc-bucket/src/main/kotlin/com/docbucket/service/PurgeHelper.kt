package com.docbucket.service

import com.docbucket.domain.DocumentRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Transactional helper for [SoftDeletePurgeJob].
 *
 * Keeping this in a separate bean lets the scheduler method call S3 deletes *outside*
 * any DB transaction and then commit the DB removals in a fresh transaction — working
 * around the CDI proxy self-invocation limitation that would arise if both methods lived
 * on the same bean.
 */
@ApplicationScoped
class PurgeHelper @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    data class PurgeBatchItem(val id: UUID, val bucket: String, val objectKey: String)

    /** Reads stale soft-deleted rows within an explicit transaction and maps to plain data. */
    @Transactional
    fun loadStaleBefore(cutoff: Instant): List<PurgeBatchItem> =
        documentRepository.findDeletedBefore(cutoff)
            .map { PurgeBatchItem(it.id, it.bucket, it.objectKey) }

    @Transactional
    fun deleteByIds(ids: List<UUID>) {
        ids.forEach { documentRepository.deleteById(it) }
    }
}
