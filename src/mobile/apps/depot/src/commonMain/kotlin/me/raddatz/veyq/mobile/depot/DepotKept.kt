package me.raddatz.veyq.mobile.depot

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.api.depot.models.FileListPage
import me.raddatz.veyq.mobile.offline.ApplicationKept
import me.raddatz.veyq.mobile.offline.EntryKey
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.offline.KeptReach
import me.raddatz.veyq.mobile.offline.keptArray
import me.raddatz.veyq.mobile.offline.keptField
import me.raddatz.veyq.mobile.offline.keptText

/** Depot's kept read (ADR 0140 decision 5): a folder's files, and the blobs they name. */
@PlatformApi
@HiddenFromObjC
object DepotKept : ApplicationKept {
    /** Per Depot folder: its files' blobs. */
    val FILES = KeptRead("listFiles", FileListPage.serializer())

    override val reads = listOf(FILES)
    override val listings = listOf(FILES)

    override suspend fun below(at: KeptReach, node: String, into: MutableSet<EntryKey>) {
        val files = at.key(FILES, node)
        into += files
        filesOf(at, files, into, null)
    }

    // a pinned file is listed in its folder's files: that listing is the pin's too, before it is kept
    override suspend fun inParent(at: KeptReach, parent: String, pinned: String, kind: String?, below: Set<String>, into: MutableSet<EntryKey>) {
        val listing = at.key(FILES, parent)
        val own = HashSet<EntryKey>()
        filesOf(at, listing, own, only = below)
        if (own.isNotEmpty() || kind == DepotFiles.FILE) into += own + listing
    }

    /** The blobs folder listing [files] names, into [into]; [only]: of these file nodes alone. */
    private suspend fun filesOf(at: KeptReach, files: EntryKey, into: MutableSet<EntryKey>, only: Set<String>?) {
        for (item in at.pages(files).flatMap { it.keptField("items").keptArray() }) {
            if (only != null && item.keptText("nodeId") !in only) continue
            item.keptField("fileBlob").keptText("blobId")?.let { into += at.blob(it) }
        }
    }
}
