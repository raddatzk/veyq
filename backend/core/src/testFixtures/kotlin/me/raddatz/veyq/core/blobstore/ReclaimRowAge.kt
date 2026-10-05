package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import java.time.Duration

/**
 * Age reclaim rows as if they had been recorded [by] earlier — both `createdAt` and `nextAttemptAt` move, so a row
 * keeps its place in the oldest-first batch and its due time stays the same distance from its recording.
 *
 * This is how a test travels past the not-before `StoreReclaimStore.record` stamps, the same way the purge tests
 * backdate a session's `lastWindowAt` past `veyq.media.upload.retention`. There is no clock seam and no `%test`
 * override of the bound on purpose: a bound of zero in the suite would make the one property it exists for —
 * no key is deleted while a PUT signed for it can still land — invisible to the tests that have to prove it.
 */
fun backdateReclaimRows(by: Duration, which: (StoreReclaimEntity) -> Boolean = { true }) =
    QuarkusTransaction.requiringNew().run {
        StoreReclaimEntity.listAll().filter(which).forEach { row ->
            row.createdAt = row.createdAt.minus(by)
            row.nextAttemptAt = row.nextAttemptAt.minus(by)
            row.persist()
        }
    }

/** Age every reclaim row one second past the bound, so the next sweep tick may delete its objects. */
fun ageReclaimRowsPastThePresignBound(config: VeyqConfig) =
    backdateReclaimRows(config.media().upload().retention().plusSeconds(1))
