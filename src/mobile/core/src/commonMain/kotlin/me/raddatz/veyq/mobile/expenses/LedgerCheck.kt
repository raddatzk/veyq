package me.raddatz.veyq.mobile.expenses

/**
 * What a read of a Pot found wrong with its ledger (ADR 0108 decision 5), a port of `checkLedger` in
 * `src/web/src/features/expenses/domain/heads.ts`. A seal binds a revision to its own address, not to its neighbours, so
 * a server could serve the list with a revision left out and every balance would come out wrong unseen; each revision
 * therefore names the heads its writer read, with their digests, and every read checks them.
 *
 * It catches a revision dropped that somebody wrote after (it is named), a body swapped under its address (its digest
 * moved), a hole in a record's revisions (`n + 1` is only taken on `n`) and a row that does not open where it is served.
 * It cannot catch the newest revisions held back, which looks exactly like a stale read (the platform's freshness limit,
 * stated, not closed). The one legitimate absence is an erasure: a revision that OPENED to `erasure` covers its own
 * record's earlier revisions and nothing else; the server's `erasure` flag is never read.
 */
class LedgerCheck private constructor(
    /** Named by a held revision, or a hole below a record's latest, not held, and no erasure of its own record covers it. */
    val missing: List<Ref>,
    /** Named and held, but its body is not the one that was named (the digest differs). */
    val altered: List<Ref>,
    /** Held, but it does not open where it is served, or not to a v1 body. */
    val unreadable: List<Ref>,
) {
    data class Ref(val entryId: String, val revision: Long)

    /** The records any of the three lists touch: what the ledger notice names. */
    val entries: List<String> = (missing + altered + unreadable).map { it.entryId }.distinct().sorted()

    /** True only when nothing is missing, altered or unreadable. */
    val complete: Boolean get() = entries.isEmpty()

    companion object {
        private val order = compareBy<Ref>({ it.entryId }, { it.revision })

        fun of(held: List<HeldRevision>): LedgerCheck {
            val present = held.associateBy { it.id }
            val erasedBelow = HashMap<String, Long>()
            for (r in held) if (r.body?.content is Erasure) erasedBelow[r.entryId] = maxOf(erasedBelow[r.entryId] ?: 0, r.revision)
            fun covered(entryId: String, revision: Long) = revision < (erasedBelow[entryId] ?: 0)

            val missing = LinkedHashMap<String, Ref>()
            val altered = LinkedHashMap<String, Ref>()
            for (r in held) for (h in r.body?.heads.orEmpty()) {
                val id = "${h.entryId}:${h.revision}"
                val found = present[id]
                if (found != null) {
                    if (found.digest != h.digest) altered[id] = Ref(h.entryId, h.revision)
                } else if (!covered(h.entryId, h.revision)) {
                    missing[id] = Ref(h.entryId, h.revision)
                }
            }
            for ((entryId, revisions) in Revisions.byRecord(held)) {
                val latest = revisions.last().revision
                for (revision in 1 until latest) {
                    val id = "$entryId:$revision"
                    if (id !in present && !covered(entryId, revision)) missing[id] = Ref(entryId, revision)
                }
            }
            val unreadable = held.filter { it.body == null }.map { Ref(it.entryId, it.revision) }
            return LedgerCheck(missing.values.sortedWith(order), altered.values.sortedWith(order), unreadable.sortedWith(order))
        }
    }
}
