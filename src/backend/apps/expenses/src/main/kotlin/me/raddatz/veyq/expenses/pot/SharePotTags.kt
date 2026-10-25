package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.items.ItemsRevisions
import jakarta.enterprise.context.ApplicationScoped

/**
 * The guest read's tag ([SharePotTag]) remembered per what it was computed from (inbox #543, Memories' `ShareMediaTags`
 * shape): so a guest's idle poll — a `304` — reads no revision and hashes nothing, however large the Pot. The tag
 * itself stays the digest of what the read serves (ADR 0081 decision 4); this is a cache of it, never a revision-shaped
 * tag.
 *
 * **What the read serves follows the key.** Every revision row of the Pot, and so every field the guest is served, is
 * written only by a writer that bumps the Pot's item revision in the same transaction — an append, an erasure, a re-key's
 * new wraps, a record's re-applied erasure (`PotRevisionStore`, `PotNodeRekeyParticipant`, `ExpensesErasures`); a
 * receipt's attach and purge and an item block bump it too. A tombstone's `purgeAfter` is derived from the process's
 * trash retention, constant per instance. The one input outside the rows is the rate table's address, which follows
 * the tenant's switch; it is in the key. A writer that bumps without changing what a guest is served (a receipt) costs
 * only a recompute to the same digest.
 *
 * **The key is read before the rows** (ADR 0081 decision 3's order): a change that commits in between lands in the
 * digest stored under the older key, which no later read asks for, since the revision has moved — a stale key only
 * costs a recompute, never a stale tag. Per instance, bounded (least recently used out): a miss on another instance, or
 * after an eviction, recomputes the same digest. The read does not depend on the link, so every link onto a Pot shares
 * its entry.
 */
@ApplicationScoped
class SharePotTags {
    data class Key(val nodeId: String, val revisions: ItemsRevisions, val exchangeRateService: String?)

    private val tags = object : LinkedHashMap<Key, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean = size > CAPACITY
    }

    fun get(key: Key): String? = synchronized(tags) { tags[key] }

    fun put(key: Key, etag: String) {
        synchronized(tags) { tags[key] = etag }
    }

    companion object {
        /** Open guest Pots remembered at once — an entry is a key and 34 characters. */
        const val CAPACITY = 4096
    }
}
