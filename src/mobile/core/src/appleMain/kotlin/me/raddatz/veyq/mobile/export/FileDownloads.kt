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
     * Writes node [ref] with everything under it the build exports to [path] ([ShellModel.download]); what it left out, or
     * null when there is nothing to export or it did not finish (nothing is left at [path] then).
     */
    @Throws(Exception::class)
    suspend fun subtree(
        shell: me.raddatz.veyq.mobile.navigation.ShellModel, ref: me.raddatz.veyq.mobile.navigation.NodeAt, deletedName: String,
        nowMs: Long, path: String,
    ): List<NodeExport.Skipped>? = withContext(Dispatchers.Default) { shell.download(ref, deletedName, nowMs, path) }
}
