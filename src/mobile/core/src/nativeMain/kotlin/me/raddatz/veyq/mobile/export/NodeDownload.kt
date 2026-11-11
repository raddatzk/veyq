package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ShellModel

/**
 * Node [ref] with everything under it as the web's ZIP into the file at [path], which the platform's save dialog gave
 * (ADR 0128 decision 3, [ShellModel.download]): what it left out, or null when there was nothing to export or it did not
 * finish — nothing is left at [path] then ([FileSink] removes a half-written archive).
 */
@Throws(Exception::class)
suspend fun ShellModel.download(ref: NodeAt, deletedName: String, nowMs: Long, path: String): List<NodeExport.Skipped>? =
    runCatching {
        FileSink.write(path, kept = { it != null }) { sink -> download(ref, deletedName, nowMs, sink) }
    }.getOrNull()
