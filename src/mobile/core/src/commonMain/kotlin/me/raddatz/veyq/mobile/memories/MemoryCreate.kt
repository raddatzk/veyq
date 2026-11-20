package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A Memory or a chapter made from New (Memories' `MemoryNodeDialog.vue` with `memoryNodes.ts`' `createMemory` and
 * `createChapter`): offered where the kinds' placement allows it ([offered]: a Memory at a space's top and in a folder, a
 * chapter in a Memory), the name trimmed and checked before anything is sent ([check]: empty, a sibling the loaded tree
 * shows that folds to the same name, named with its kind's noun — core's `collidingSibling`), then made through core's
 * `NodeCreate` under its kind's slug input (`memories/<slug>`); a refusal said as the dialog says it ([refused]). The new
 * node opens (the web's).
 */
object MemoryCreate {
    const val MEMORY = "MEMORY"
    const val CHAPTER = "CHAPTER"

    /**
     * What Memories' New makes at a level whose node is of [parentKind] (null: a space's top), in this order: a Memory where
     * its placement allows one (the top, a folder), a chapter where its placement does (a Memory).
     */
    fun offered(parentKind: String?): List<String> = listOf(MEMORY, CHAPTER).filter { AppKinds.placement.allows(it, parentKind) }

    /** A node beside the new one, as the loaded tree shows it. */
    class Sibling(val kind: String, val name: String)

    /**
     * Why a [kind] named [name] is not made under a parent of [parentKind] among [siblings] — its placement, an empty name, a
     * sibling folding to the same name (named by its kind) —, or null where it may be sent.
     */
    fun check(kind: String, parentKind: String?, name: String, siblings: List<Sibling>): Notice? {
        val n = name.trim()
        if (kind !in offered(parentKind)) return Notice(Msg.NODE_ILLEGAL_STRUCTURE)
        if (n.isEmpty()) return Notice(Msg.FOLDER_NAME_EMPTY)
        val input = AppKinds.tokenInput(kind, n) ?: return Notice(Msg.NODE_SAVE_FAILED)
        val taken = siblings.firstOrNull { AppKinds.tokenInput(it.kind, it.name) == input } ?: return null
        return Notice(Msg.NODE_NAME_TAKEN_BY, Notice(noun(taken.kind)), taken.name)
    }

    /** A kind's noun with its article, as a name's collision names it (the registry's `nounWithArticle`). */
    fun noun(kind: String): Msg = when (kind) {
        MEMORY -> Msg.MEMORY_A_MEMORY
        CHAPTER -> Msg.MEMORY_A_CHAPTER
        "EXPENSES_POT" -> Msg.POT_A
        else -> Msg.NODE_AN_ITEM
    }

    /** Why the server did not make it, as the dialog says it: a name taken (`409`), not allowed there (`422`), else failed. */
    fun refused(reason: NodeCreate.Reason?): Notice = when (reason) {
        NodeCreate.Reason.NAME_TAKEN -> Notice(Msg.MEMORY_SLUG_TAKEN)
        NodeCreate.Reason.NOT_ALLOWED -> Notice(Msg.NODE_ILLEGAL_STRUCTURE)
        else -> Notice(Msg.NODE_SAVE_FAILED)
    }

    /**
     * Whether New offers *I was there* for a [kind] in a scope of [scopeKind] (ADR 0144 decision 2, the web's
     * `showWasThere`): a Memory in a tenant or group scope — never a chapter, never a personal scope, which has no
     * participants.
     */
    fun offersWasThere(kind: String, scopeKind: String?): Boolean = kind == MEMORY && (scopeKind == "TENANT" || scopeKind == "GROUP")

    /**
     * Whether New offers *Determine location automatically* for a [kind] (uc.memory.place A1, uc.memory.place A1 as the web's
     * `MemoryNodeDialog` offers it): a Memory — never a chapter — where this device's map services are shown
     * ([mapsAvailable]: offered by the tenant and consented here, the web's `mapsAvailable`). Unticked by default.
     */
    fun offersAutoPlace(kind: String, mapsAvailable: Boolean): Boolean = kind == MEMORY && mapsAvailable

    /**
     * The first meta a [kind] is born with in a scope of [scopeKind]: where its maker ([userId]) ticked [wasThere], the maker
     * as its one participant ([offersWasThere]); where [autoPlace] was ticked, a pending `auto` place — no name yet, a
     * geocoded one to come from the first geotagged medium (the web's `{name: '', nameSource: 'geocoded', derivedMode:
     * 'auto'}`, which the settle materializes). Sealed at birth, no second write, in the web's order; null — no meta at all —
     * where nothing is to be sealed.
     */
    fun birthMeta(kind: String, scopeKind: String?, wasThere: Boolean, userId: String, autoPlace: Boolean = false): JsonObject? {
        val place = autoPlace && kind == MEMORY
        val participants = wasThere && offersWasThere(kind, scopeKind) && userId.isNotEmpty()
        if (!place && !participants) return null
        val meta = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        meta["v"] = JsonPrimitive(1)
        if (place) meta[MemoryLocation.FIELD] = PENDING_PLACE.toJson()
        if (participants) Participants.born(userId).let { (k, v) -> meta[k] = v }
        return JsonObject(meta)
    }

    /** The place a Memory is armed with at birth: pending, its name and position to come from its media. */
    private val PENDING_PLACE = MemoryLocation("", MemoryLocation.NameSource.GEOCODED, null, MemoryLocation.DerivedMode.AUTO)

    /**
     * What a create came to: the node made (to open), or why not — [refused] the server's answer where one was sent, [code]
     * the reason as a code where nothing was ([NAME_TAKEN] a sibling the tree shows, [ILLEGAL] its placement, [PARENT_GONE]).
     */
    class Made(val node: NodeAt?, val notice: Notice?, internal val refused: NodeCreate.Result.Refused? = null, internal val code: String? = null)

    internal const val NAME_TAKEN = "name_taken"
    internal const val ILLEGAL = "illegal_structure"

    /**
     * Makes a [kind] named [name] in [scopeId] under [parentId] (null: the space's top) over [tenant]: checked against the
     * loaded tree first, then sent; the tree read before is forgotten after, so the next read lists it. [wasThere]: the
     * maker listed among a Memory's participants from its birth, where it is offered; [autoPlace]: its place armed
     * ([birthMeta]).
     */
    suspend fun create(
        tenant: TenantSession, scopeId: String, parentId: String?, kind: String, name: String, wasThere: Boolean = false, autoPlace: Boolean = false,
        /** The id it is made with (a write queued offline minted it, ADR 0153); null: a fresh one. */
        nodeId: String? = null,
    ): Made {
        val n = name.trim()
        val tree = runCatching { tenant.trees()[scopeId].orEmpty() }.getOrNull() ?: return Made(null, refused(null))
        val parentKind = parentId?.let { p -> tree.firstOrNull { it.id == p }?.kind ?: return Made(null, refused(null), code = NodeCreate.PARENT_GONE) }
        check(kind, parentKind, n, tree.filter { it.parentId == parentId }.map { Sibling(it.kind, it.name) })?.let {
            return Made(null, it, code = if (it.msg == Msg.NODE_NAME_TAKEN_BY) NAME_TAKEN else if (it.msg == Msg.NODE_ILLEGAL_STRUCTURE) ILLEGAL else null)
        }
        val input = AppKinds.tokenInput(kind, n) ?: return Made(null, refused(null))
        val born = birthMeta(kind, tenant.scopeKind(scopeId), wasThere, tenant.account.userId, autoPlace)
        val sealMeta = born?.let { meta -> { key: ByteArray, nodeId: String -> NodeMetaWrite.seal(tenant.account.suite, scopeId, nodeId, key, meta) } }
        val r = runCatching { NodeCreate.create(tenant, scopeId, parentId, kind, n, input, sealMeta, nodeId = nodeId) }.getOrNull()
        tenant.forgetTree()
        return when (r) {
            is NodeCreate.Result.Done -> Made(NodeAt(scopeId, r.created.nodeId, kind, n), null)
            is NodeCreate.Result.Refused -> Made(null, refused(r.reason), refused = r)
            null -> Made(null, refused(null))
        }
    }
}
