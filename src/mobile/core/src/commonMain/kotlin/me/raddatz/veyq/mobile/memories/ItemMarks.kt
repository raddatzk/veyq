package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import me.raddatz.veyq.mobile.api.memories.apis.ItemFavoritesApi
import me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesEntry
import me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.ValueKeys
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A Memory's item marks (`en.memory.favorites`, ADR 0068, ADR 0149 decision 4; the web's `itemFavorites.ts` with
 * `itemFavoritesStore.ts`): every member's own sealed set of media ids for the node, each under a fresh value key wrapped
 * under the scope key (ADR 0115), its plaintext `tag ‖ ids` with the tag an HMAC under the member's `itemMarkSelfKey`. The
 * listing carries no member id: a set is the caller's only where the server flags it `self` AND its tag verifies (the first
 * such; ADR 0072 § 5), so a medium's **count** is how many sets hold it and **markedByMe** is the caller's set's — never
 * derived from the count. A set that does not open is an empty set that is nobody's (ADR 0068 § 9).
 *
 * A toggle replaces the caller's whole set, so it is **refused until the own set was read** for this node (ADR 0077); it
 * writes optimistically, puts the set back where the write did not land and says why ([Toggled]): the 12,285th mark
 * (`tooMany`, the contract's 262,144 chars) or a failed save. A `409 stale_epoch` is sealed once more under the scope
 * opened again. In a personal scope there is no count (E4: only the owner marks). The listing is a kept read (ADR 0140
 * decision 5): offline the marks show as last read; nothing is marked offline (decision 7).
 */
class ItemMarks internal constructor(
    val scopeId: String,
    val nodeId: String,
    /** A personal scope: no count is shown. */
    val personal: Boolean,
    private val suite: V1,
    private val random: Primitives,
    private val store: Store,
) {
    /** One member's set, as opened: whether it is the caller's own, and its media ids. */
    class MemberSet(val own: Boolean, val ids: Set<String>)

    /** Where the sets are read and the caller's is written: the server's ([of]); a test's own. */
    internal interface Store {
        /** Every entry for the node; a throw where it does not read. */
        suspend fun list(): List<ItemFavoritesEntry>

        /** A copy of the scope key this device holds, with its epoch (zeroed by the caller); null for a scope not open. */
        fun key(): Pair<ByteArray, Long>?

        /** The caller's self-tag key (from the Master Key, zeroed by the caller); null while there is none. */
        fun selfKey(): ByteArray?

        /** The caller's set stored: 0 taken, 409 a rotation replaced the key (`stale_epoch`), anything else refused. */
        suspend fun put(request: PutItemFavoritesRequest): Int

        /** The scope key at its current epoch, read anew after a `stale_epoch`. */
        suspend fun reopen(): Pair<ByteArray, Long>?
    }

    /** The sets as last read ([loaded] false until a read succeeded). */
    var sets: List<MemberSet> = emptyList()
        private set
    var loaded: Boolean = false
        private set

    /** How many members marked [mediaId]; null in a personal scope (no count, E4). */
    fun count(mediaId: String): Int? = if (personal) null else sets.count { mediaId in it.ids }

    /** Whether the caller's own set holds [mediaId] — its self-tagged set, never the count. */
    fun markedByMe(mediaId: String): Boolean = sets.firstOrNull { it.own }?.ids?.contains(mediaId) == true

    /** Every medium anyone marked (the filter's *everyone*). */
    fun markedByAnyone(): Set<String> = sets.flatMapTo(HashSet()) { it.ids }

    /** The caller's own marks (the filter's *mine*). */
    fun mine(): Set<String> = sets.firstOrNull { it.own }?.ids.orEmpty()

    /** Reads every member's set; false where the listing did not read (the toggle stays refused). */
    suspend fun load(): Boolean {
        val entries = try {
            store.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val key = store.key() ?: return false
        val selfKey = store.selfKey()
        try {
            var mineFound = false
            sets = entries.map { e ->
                val opened = open(suite, scopeId, nodeId, key.first, key.second, e.ciphertext, e.wrappedValueKey)
                val ids = opened?.second.orEmpty()
                val own = !mineFound && e.self && selfKey != null && opened != null &&
                    me.raddatz.veyq.mobile.crypto.ItemMarks(suite).selfTagVerifies(selfKey, scopeId, nodeId, ids, opened.first)
                if (own) mineFound = true
                MemberSet(own, ids.toSet())
            }
            loaded = true
            return true
        } finally {
            selfKey?.fill(0)
            key.first.fill(0)
        }
    }

    /** What a toggle came to. */
    enum class Toggled(val notice: Notice?) {
        DONE(null),
        /** The own set is not read yet: nothing changed, nothing written (ADR 0077). */
        NOT_LOADED(null),
        TOO_MANY(Notice(Msg.MEDIA_MARKS_TOO_MANY)),
        SAVE_FAILED(Notice(Msg.MEDIA_MARKS_SAVE_FAILED)),
    }

    /** The caller's mark on [mediaId] flipped and the whole own set written; put back where the write did not land. */
    suspend fun toggle(mediaId: String): Toggled {
        if (!loaded) return Toggled.NOT_LOADED
        val before = sets
        val mine = mine().toMutableSet()
        if (!mine.remove(mediaId)) mine += mediaId
        sets = before.filter { !it.own } + MemberSet(true, mine)
        val out = try {
            write(mine.toList())
        } catch (e: CancellationException) {
            sets = before
            throw e
        } catch (e: Exception) {
            Toggled.SAVE_FAILED
        }
        if (out != Toggled.DONE) sets = before
        return out
    }

    /**
     * The caller's mark on [mediaId] set to [marked] (a mark queued offline, ADR 0153): written only where the own set as
     * read now holds otherwise — so the person's choice stands over whatever their set held meanwhile.
     */
    internal suspend fun set(mediaId: String, marked: Boolean): Toggled {
        if (!loaded) return Toggled.NOT_LOADED
        if (markedByMe(mediaId) == marked) return Toggled.DONE
        return toggle(mediaId)
    }

    private suspend fun write(ids: List<String>): Toggled {
        val selfKey = store.selfKey() ?: return Toggled.SAVE_FAILED
        var key = store.key()
        try {
            if (key == null) return Toggled.SAVE_FAILED
            var status = store.put(seal(suite, random, scopeId, nodeId, key.first, key.second, selfKey, ids) ?: return Toggled.TOO_MANY)
            if (status == 409) {
                // a rotation replaced the scope key between the read and the write: once more under the key read anew
                val again = store.reopen() ?: return Toggled.SAVE_FAILED
                key.first.fill(0)
                key = again
                status = store.put(seal(suite, random, scopeId, nodeId, again.first, again.second, selfKey, ids) ?: return Toggled.TOO_MANY)
            }
            return if (status == 0) Toggled.DONE else Toggled.SAVE_FAILED
        } finally {
            key?.first?.fill(0)
            selfKey.fill(0)
        }
    }

    companion object {
        /** The contract's cap on one member's sealed marks for one Memory (`PutItemFavoritesRequest.ciphertext`). */
        internal const val CIPHERTEXT_MAX = 262_144

        /** Every member's marks on one node, bound to (scope, node) — the address their value key's wrap is bound to. */
        internal fun context(scopeId: String, nodeId: String) = Context("item-favorites", "scopeId" to scopeId, "nodeId" to nodeId)

        /** One entry opened: its value key from the wrap, the set under it, decoded (tag, ids); null for anything that does not open. */
        internal fun open(suite: V1, scopeId: String, nodeId: String, scopeKey: ByteArray, epoch: Long, ciphertext: String, wrapped: String): Pair<ByteArray, List<String>>? =
            runCatching {
                val ctx = context(scopeId, nodeId)
                val valueKey = ValueKeys(suite).unwrap(scopeKey, epoch, ctx, unb64(wrapped))
                try {
                    me.raddatz.veyq.mobile.crypto.ItemMarks(suite).decode(suite.open(ctx, valueKey, unb64(ciphertext)))
                } finally {
                    valueKey.fill(0)
                }
            }.getOrNull()

        /** `tag ‖ ids` sealed under a fresh value key, wrapped under the scope key at [epoch]; null past the contract's cap. */
        internal fun seal(
            suite: V1, random: Primitives, scopeId: String, nodeId: String, scopeKey: ByteArray, epoch: Long, selfKey: ByteArray, ids: List<String>,
        ): PutItemFavoritesRequest? {
            val marks = me.raddatz.veyq.mobile.crypto.ItemMarks(suite)
            val ctx = context(scopeId, nodeId)
            val plaintext = marks.encode(marks.selfTag(selfKey, scopeId, nodeId, ids), ids)
            val valueKey = random.randomBytes(ValueKeys.VALUE_KEY_BYTES)
            try {
                val sealed = b64(suite.seal(ctx, valueKey, plaintext))
                if (sealed.length > CIPHERTEXT_MAX) return null
                return PutItemFavoritesRequest(epoch.toInt(), sealed, b64(ValueKeys(suite).wrap(scopeKey, epoch, ctx, valueKey)))
            } finally {
                valueKey.fill(0)
            }
        }

        /**
         * The marks of node [nodeId] of [scopeId] over [tenant]'s server: the listing a kept read (offline the sets as last
         * read), the caller's self-tag key from the account's Master Key, a write at the scope's held epoch.
         */
        fun of(tenant: TenantSession, scopeId: String, nodeId: String): ItemMarks {
            val account = tenant.account
            val api = ItemFavoritesApi(tenant.base, account.http)
            val store = object : Store {
                override suspend fun list(): List<ItemFavoritesEntry> {
                    val read = account.kept(KeptRead.ITEM_FAVORITES, tenant.tenant.tenantId, scopeId, nodeId) {
                        account.fetched(account.respond { api.getItemFavorites(scopeId, nodeId) }, null)
                    }
                    return VeyqAccount.KEPT_JSON.decodeFromString(ListSerializer(ItemFavoritesEntry.serializer()), read.pages.single().decodeToString())
                }

                override fun key(): Pair<ByteArray, Long>? = tenant.scopeKey(scopeId)?.let { (k, e) -> k.copyOf() to e.toLong() }

                override fun selfKey(): ByteArray = me.raddatz.veyq.mobile.crypto.ItemMarks(account.suite).selfKey(account.masterKey)

                override suspend fun put(request: PutItemFavoritesRequest): Int {
                    val r = account.respond { api.putItemFavorites(scopeId, nodeId, request) }
                    return if (r.success) 0 else if (r.status == 409 && account.errorCode(r) == "stale_epoch") 409 else r.status
                }

                override suspend fun reopen(): Pair<ByteArray, Long>? = account.reopenScope(tenant.tenant, scopeId)
            }
            return ItemMarks(scopeId, nodeId, tenant.scopeKind(scopeId) == "PERSONAL", account.suite, account.primitives, store)
        }
    }
}
