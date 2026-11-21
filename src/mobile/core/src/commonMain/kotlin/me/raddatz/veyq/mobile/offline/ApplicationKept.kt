package me.raddatz.veyq.mobile.offline

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.PlatformApi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * An application's part of the offline store (ADR 0140 decision 5; [me.raddatz.veyq.mobile.ApplicationKinds.kept]): the
 * reads it keeps and what they reach. The store walks a scope's tree and asks each part what lies below a node, so the
 * sweep keeps what a listing names and a pin reaches what is under it — the store itself reads no application's answer.
 */
@PlatformApi
@HiddenFromObjC
interface ApplicationKept {
    /** Its kept reads. */
    val reads: List<KeptRead>

    /** Those of [reads] that list what is below a node: a write to one has the pins' reach read anew. */
    val listings: List<KeptRead>

    /** Those of [reads] kept per scope while the scope is the account's (a space's media for the search): no pin reaches them. */
    val perScope: List<KeptRead> get() = emptyList()

    /**
     * The kept answers below node [node] of [at]'s scope (`""`: the space's top) and the blobs they name, into [into]: a
     * read below a node that is not reached here is dropped by the sweep right after its first keep (seen with Expenses'
     * ledger), so a new kept read is added here in the same change.
     */
    suspend fun below(at: KeptReach, node: String, into: MutableSet<EntryKey>)

    /**
     * What a pin of node [pinned] (of [kind]; [below] the nodes it reaches) reaches in its parent [parent]'s listings (a
     * Depot file listed in its folder's files, before that listing is kept), into [into].
     */
    suspend fun inParent(at: KeptReach, parent: String, pinned: String, kind: String?, below: Set<String>, into: MutableSet<EntryKey>) {}
}

/** What an [ApplicationKept] reads with: one scope's kept answers ([pages]), by their keys ([key], [blob]). */
@PlatformApi
@HiddenFromObjC
class KeptReach internal constructor(private val offline: AccountOffline, val tenant: String, val scope: String) {
    /** The key of kept read [read] of node [id] in this scope. */
    fun key(read: KeptRead, id: String): EntryKey = offline.key(read, tenant, scope, id)

    /** The key of blob [blobId]'s frames in this scope. */
    fun blob(blobId: String): EntryKey = offline.blobKey(tenant, scope, blobId)

    /** The pages kept under [key], parsed; none where nothing is kept. */
    suspend fun pages(key: EntryKey): List<JsonElement> = offline.json(key)
}

/** A kept page's array; empty where it is none. */
@PlatformApi
@HiddenFromObjC
fun JsonElement?.keptArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

/** A kept page's field [name]. */
@PlatformApi
@HiddenFromObjC
fun JsonElement?.keptField(name: String): JsonElement? = (this as? JsonObject)?.get(name)

/** A kept page's text field [name]; null where it is none. */
@PlatformApi
@HiddenFromObjC
fun JsonElement?.keptText(name: String): String? = (keptField(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Whether a kept page's field [name] is true (an operator's block). */
@PlatformApi
@HiddenFromObjC
fun JsonElement?.keptFlag(name: String): Boolean = (keptField(name) as? JsonPrimitive)?.booleanOrNull == true
