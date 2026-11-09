package me.raddatz.veyq.mobile.export

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.expenses.PotExport
import me.raddatz.veyq.mobile.expenses.PotSession
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove

/**
 * A download written to a file on an Apple device, chunk by chunk as the archive is produced, so the app holds a window of
 * it and never the whole ZIP: the app writes into its temporary directory and hands the file to the system's save sheet
 * (`fileMover`). A failed download removes what it wrote.
 */
@OptIn(ExperimentalForeignApi::class)
object FileDownloads {
    /** Writes [session]'s Pot to [path]; null when it has nothing to export in (nothing is left at [path] then). */
    @Throws(Exception::class)
    suspend fun pot(session: PotSession, deletedName: String, nowMs: Long, path: String): NodeExport.Written? = withContext(Dispatchers.Default) {
        val file = fopen(path, "wb") ?: throw IllegalStateException("cannot write $path")
        var done = false
        try {
            val written = PotExport.download(session, deletedName, nowMs) { bytes ->
                if (bytes.isEmpty()) return@download
                val n = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) }
                if (n != bytes.size.toULong()) throw IllegalStateException("short write to $path")
            }
            done = written != null
            written
        } finally {
            fclose(file)
            if (!done) remove(path)
        }
    }

    /**
     * Writes node [nodeId] of [scopeId] with everything under it [kinds] exports to [path] (`NodeExport.walk`, `write`); what
     * it left out, or null when there is nothing to export (nothing is left at [path] then).
     */
    @Throws(Exception::class)
    suspend fun subtree(
        tenant: me.raddatz.veyq.mobile.session.TenantSession, scopeId: String, nodeId: String, kinds: NodeExport.Kinds,
        appVersion: String, nowMs: Long, path: String,
    ): List<NodeExport.Skipped>? = withContext(Dispatchers.Default) {
        val walked = NodeExport.walk(tenant, scopeId, nodeId, kinds) ?: return@withContext null
        val file = fopen(path, "wb") ?: throw IllegalStateException("cannot write $path")
        var done = false
        try {
            NodeExport.write(walked.root, appVersion, nowMs) { bytes ->
                if (bytes.isEmpty()) return@write
                val n = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) }
                if (n != bytes.size.toULong()) throw IllegalStateException("short write to $path")
            }
            done = true
            walked.skipped
        } finally {
            fclose(file)
            if (!done) remove(path)
        }
    }
}
