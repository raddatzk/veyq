package me.raddatz.veyq.core.backup

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jboss.logging.Logger

/**
 * `erasures.log`, the erasure ledger's file (ADR 0056 decision 8): **outside the three legs**, beside leg (c)'s key, in the
 * state directory both deployment targets persist (Compose's `veyq-config-secret` volume, the cluster's kept
 * `veyq-config-secret` claim) and no restore or rollback touches. One JSON object per line ([LedgerLine]).
 *
 * Two writers, one lock: [sweep] appends the outbox's unsent rows (every minute, and first at start), [rewrite] drops
 * the lines older than B atomically (at start, and hourly through [scheduledTrim]), and nothing else writes the file. An append is forced to disk before its
 * rows are stamped sent, so a crash between the two appends a line twice — harmless, re-applying is idempotent — and
 * never stamps one the file lacks. The window ADR 0056 states as its limit is the sweep's interval: an erasure not yet
 * appended when a crash also loses the file is not re-applied.
 */
@ApplicationScoped
class ErasureLedgerFile(config: VeyqConfig, private val retention: BackupRetention) {
    private val log = Logger.getLogger(ErasureLedgerFile::class.java)
    private val lock = ReentrantLock()

    /** The file: `veyq.erasure-ledger.file`, or [NAME] in the directory of the config-secret key. */
    val path: Path = config.erasureLedger().file().map { Path.of(it) }
        .orElseGet { Path.of(config.security().configSecretFile()).toAbsolutePath().parent.resolve(NAME) }
        .toAbsolutePath()

    @Scheduled(every = "{veyq.erasure-ledger.sweep.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledSweep() {
        try {
            sweep()
        } catch (e: Exception) {
            // Loud, never swallowed: an unsent row stays unsent and the next tick tries again; until then the erasure is
            // in the database only, which a restore of an earlier capture replaces.
            log.error("the erasure ledger's outbox could not be appended to ${path.fileName}; the next sweep tries again", e)
        }
    }

    /**
     * The file held to B while the instance runs (ADR 0056 decision 8, inbox #592): the start trims it too, but an
     * instance that runs for months without a restart would otherwise keep account and moderator ids past B. A purge's
     * shape and cadence (`veyq.erasure-ledger.purge.every`, hourly), so a line outlives B by at most one tick.
     */
    @Scheduled(every = "{veyq.erasure-ledger.purge.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledTrim() {
        try {
            trim()
        } catch (e: Exception) {
            // The file is left as it was (the rewrite moves a finished copy over it, or nothing); the next tick tries again.
            log.error("the erasure ledger ${path.fileName} could not be trimmed to B; the next run tries again", e)
        }
    }

    /** Drop the lines older than B before [now] ([rewrite]); answers how many went. */
    fun trim(now: Instant = Instant.now()): Int = rewrite(cutoff(now))

    private fun cutoff(now: Instant): Instant =
        now.minus(Duration.ofDays(QuarkusTransaction.requiringNew().call { retention.retention() }.days.toLong()))

    /**
     * Append every unsent outbox row to the file, oldest first, force it to disk, then stamp the rows sent; and delete the
     * sent rows older than B, which no capture that could be restored predates. Answers how many lines it appended.
     */
    fun sweep(now: Instant = Instant.now()): Int = lock.withLock {
        val unsent = QuarkusTransaction.requiringNew().call {
            ErasureOutboxEntity.list("sentAt is null order by recordedAt, id").map { it.id to it.line }
        }
        if (unsent.isNotEmpty()) {
            append(unsent.map { it.second })
            QuarkusTransaction.requiringNew().run {
                unsent.map { it.first }.chunked(500).forEach { ids ->
                    ErasureOutboxEntity.update("sentAt = ?1 where id in ?2", now, ids)
                }
            }
        }
        val cutoff = cutoff(now)
        QuarkusTransaction.requiringNew().run { ErasureOutboxEntity.delete("sentAt is not null and recordedAt < ?1", cutoff) }
        unsent.size
    }

    /** Every line of the file in its order, and how many could not be read; an absent file holds none. */
    fun read(): Read = lock.withLock {
        if (!Files.exists(path)) return@withLock Read(emptyList(), 0)
        var unreadable = 0
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { text ->
            LedgerLine.parse(text) ?: run { unreadable++; null }
        }
        Read(lines, unreadable)
    }

    /**
     * Keep only the lines at or after [cutoff] (B before now) — written to a temporary file in the same directory,
     * forced, and moved over the ledger atomically, so a crash leaves the old file or the new one, never half of one. A
     * line this cannot read is dropped with the old ones: it can be neither applied nor dated. Answers how many went.
     */
    fun rewrite(cutoff: Instant): Int = lock.withLock {
        if (!Files.exists(path)) return@withLock 0
        val all = Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotBlank() }
        val kept = all.filter { text -> LedgerLine.parse(text)?.let { !it.at.isBefore(cutoff) } ?: false }
        if (kept.size == all.size) return@withLock 0
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.deleteIfExists(tmp)
        create(tmp)
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            writeAll(channel, kept)
            channel.force(true)
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        all.size - kept.size
    }

    private fun append(lines: List<String>) {
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) create(path)
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            writeAll(channel, lines)
            channel.force(true)
        }
    }

    private fun writeAll(channel: FileChannel, lines: List<String>) {
        val bytes = ByteBuffer.wrap(lines.joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    /** Created owner-only where the filesystem has POSIX modes, as the key beside it is (`ConfigSecretSeal`). */
    private fun create(file: Path) {
        Files.createDirectories(file.parent)
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(file)
        }
    }

    data class Read(val lines: List<LedgerLine>, val unreadable: Int)

    companion object {
        /** Beside leg (c)'s key; `src/tools/instance_backup.py` (`LEDGER_FILE`) and `veyqctl` name the same file. */
        const val NAME = "erasures.log"
    }
}
