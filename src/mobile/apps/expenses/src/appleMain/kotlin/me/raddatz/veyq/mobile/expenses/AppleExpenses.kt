package me.raddatz.veyq.mobile.expenses

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.session.AppleSessions
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

// Expenses' part of Swift's adapter ([AppleSessions]): a Pot opened, its receipts read and picked, as extensions of it, so
// Swift calls them where it did (`AppleSessions.shared.openPot(…)`, a category of `AppleSessions`).

/** An Expenses Pot of [tenant], opened and read; null when the tenant holds no such open Pot. */
@Throws(Exception::class)
suspend fun AppleSessions.openPot(tenant: TenantSession, scopeId: String, nodeId: String): PotSession? = PotSession.open(tenant, scopeId, nodeId)

/** A receipt of [pot] opened to its bytes, as Foundation data; null when blocked or unreadable. */
@Throws(Exception::class)
suspend fun AppleSessions.openReceipt(pot: PotSession, receiptId: String): NSData? = pot.openReceipt(receiptId)?.toNSData()

/**
 * Writes [session]'s Pot as the web's ZIP to [path], chunk by chunk as the archive is produced (core's [FileSink]: the app
 * writes into its temporary directory and hands the file to the save sheet, never holding the whole ZIP); null when it
 * has nothing to export in or did not finish (nothing is left at [path] then).
 */
@Throws(Exception::class)
suspend fun AppleSessions.downloadPot(session: PotSession, deletedName: String, nowMs: Long, path: String): NodeExport.Written? =
    withContext(Dispatchers.Default) {
        FileSink.write(path, kept = { it != null }) { sink -> PotExport.download(session, deletedName, nowMs, sink) }
    }

/** A file picked as a receipt for core's entry editor to judge ([data] nil: it did not read). */
fun AppleSessions.picked(data: NSData?, type: String, name: String?, modifiedAt: Long?): EntryEditor.Picked =
    EntryEditor.Picked(data?.toByteArray(), type, name, modifiedAt)

/** What the bytes of an opened receipt are (an image, a PDF, a download only). */
fun AppleSessions.receiptKind(data: NSData): Receipts.Kind = Receipts.kindOf(data.toByteArray())

/** What an opened receipt's bytes are as a media type (`Receipts.mediaTypeOf`), for the share sheet; nil for other bytes. */
fun AppleSessions.receiptMediaType(data: NSData): String? = Receipts.mediaTypeOf(data.toByteArray())

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
