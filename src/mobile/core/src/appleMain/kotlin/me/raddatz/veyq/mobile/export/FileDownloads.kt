package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.expenses.PotExport
import me.raddatz.veyq.mobile.expenses.PotSession

/**
 * A download written to a file on an Apple device, chunk by chunk as the archive is produced (core's [FileSink]), so the
 * app holds a window of it and never the whole ZIP: the app writes into its temporary directory and hands the file to the
 * system's save sheet (`fileMover`). A failed download removes what it wrote.
 */
object FileDownloads {
    /** Writes [session]'s Pot to [path]; null when it has nothing to export in (nothing is left at [path] then). */
    @Throws(Exception::class)
    suspend fun pot(session: PotSession, deletedName: String, nowMs: Long, path: String): NodeExport.Written? = withContext(Dispatchers.Default) {
        FileSink.write(path, kept = { it != null }) { sink -> PotExport.download(session, deletedName, nowMs, sink) }
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
        FileSink.write(path) { sink ->
            NodeExport.write(walked.root, appVersion, nowMs, sink)
            walked.skipped
        }
    }
}
