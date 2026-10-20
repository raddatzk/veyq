package me.raddatz.veyq.core.moderation.guestwrite

import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Instant

/**
 * The guest-write record's purge ([GuestWriteRecords.purge], ADR 0093 § 10): on `veyq.moderation.guest-write-record.purge.every`
 * and **at startup**, before the first request is served — so a restored backup's stale rows, or the live rows of a
 * record switched off while the instance was down, go at boot rather than at the first tick. It is the reader
 * `EveryStoredTimeHasAReaderTest` names for both of the table's times.
 */
@ApplicationScoped
class GuestWriteRecordPurge(private val records: GuestWriteRecords) {

    /** Uncaught on purpose: a purge that cannot run at boot is a database that cannot serve, and startup says so. */
    fun onStart(@Observes ev: StartupEvent) {
        records.purge(Instant.now())
    }

    @Scheduled(every = "{veyq.moderation.guest-write-record.purge.every}")
    fun run() {
        records.purge(Instant.now())
    }
}
