package me.raddatz.veyq.mobile.memories

import kotlin.native.HiddenFromObjC
import kotlinx.serialization.builtins.ListSerializer
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.api.memories.models.BucketListResponse
import me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesEntry
import me.raddatz.veyq.mobile.api.memories.models.MediaListPage
import me.raddatz.veyq.mobile.api.memories.models.ScopeMediaPage
import me.raddatz.veyq.mobile.offline.ApplicationKept
import me.raddatz.veyq.mobile.offline.EntryKey
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.offline.KeptReach
import me.raddatz.veyq.mobile.offline.keptArray
import me.raddatz.veyq.mobile.offline.keptField
import me.raddatz.veyq.mobile.offline.keptFlag
import me.raddatz.veyq.mobile.offline.keptText

/** Memories' kept reads (ADR 0140 decision 5, ADR 0149, ADR 0153 decision 7) and what they reach below a Memory or a chapter. */
@PlatformApi
@HiddenFromObjC
object MemoriesKept : ApplicationKept {
    /** Per Memory or chapter: its media, `mediaRevision` its revision. */
    val NODE_MEDIA = KeptRead("listNodeMedia", MediaListPage.serializer())

    /**
     * Per Memory or chapter: every member's sealed item-mark set (ADR 0149 decision 4), so a kept Memory shows its badges
     * offline. No member id: whose a set is, is its self-tag. No revision; replaced at each online read.
     */
    val ITEM_FAVORITES = KeptRead("getItemFavorites", ListSerializer(ItemFavoritesEntry.serializer()))

    /**
     * Per Memory or chapter: its buckets with their sealed names (ADR 0149 decision 6), so a guest stays named offline. No
     * revision; replaced at each online read.
     */
    val NODE_BUCKETS = KeptRead("listNodeBuckets", BucketListResponse.serializer())

    /**
     * Per scope: every live medium of the space (ADR 0153 decision 7), what the search opens (`MemoriesSearch`), `ETag` its
     * revision. The automatic tier only — never pinned, never among `KeptRead.PROTECTED` —, replaced and never merged, kept
     * while its scope is the account's (the sweep, from `KeptRead.MY_SCOPES`).
     */
    val SCOPE_MEDIA = KeptRead("listScopeMedia", ScopeMediaPage.serializer())

    override val reads = listOf(NODE_MEDIA, ITEM_FAVORITES, NODE_BUCKETS, SCOPE_MEDIA)
    override val listings = listOf(NODE_MEDIA)
    override val perScope = listOf(SCOPE_MEDIA)

    override suspend fun below(at: KeptReach, node: String, into: MutableSet<EntryKey>) {
        val media = at.key(NODE_MEDIA, node)
        into += media
        for (row in at.pages(media).flatMap { it.keptField("media").keptArray() }) {
            if (row.keptFlag("blockedByOperator")) continue
            row.keptText("blobId")?.let { into += at.blob(it) }
            for (d in row.keptField("derivatives").keptArray()) d.keptText("blobId")?.let { into += at.blob(it) }
        }
        // a Memory's marks and buckets (ADR 0149 decisions 4 and 6): its badges and its guests' names, kept as its listing is
        into += at.key(ITEM_FAVORITES, node)
        into += at.key(NODE_BUCKETS, node)
    }
}
