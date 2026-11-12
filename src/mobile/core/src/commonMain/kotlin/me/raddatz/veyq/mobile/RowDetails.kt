package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.expenses.PotRows
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Piece

/**
 * What a tenant's list rows say beside their names, read in one go after every change both apps make (ADR 0129): a
 * Pot's currency, a file's size and change (or that it is not uploaded), a drop box's guests — and which cover each node
 * names, so the lists' pictures follow too. A part that does not read keeps what the previous [Read] held, so a passing
 * network error empties no row.
 */
object RowDetails {
    class Read internal constructor(
        internal val pots: Map<String, List<Piece>>,
        /** Every file of the tenant, by node id. */
        val files: Map<String, DepotFiles.File>,
        internal val boxes: Map<String, List<Piece>>,
        /** Each node's cover key ([NodeCover.framed]), for [NodeCover.changes]. */
        val covers: Map<String, String>,
    ) {
        /** Each row's facts, by node id. */
        val details: Map<String, List<Piece>> get() = pots + files.mapValues { DepotFiles.rowDetails(it.value) } + boxes

        companion object {
            val EMPTY = Read(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    /** The parts as read: each null where it did not read. */
    class Parts(
        val pots: Map<String, List<String>>?, val files: Map<String, DepotFiles.File>?, val nodes: List<NodeRef>?,
        val covers: Map<String, String>?,
    )

    /** [parts] over [previous]: a part that did not read keeps its rows as they were. */
    fun merge(parts: Parts, previous: Read): Read = Read(
        pots = parts.pots?.mapValues { (_, facts) -> facts.map { Piece.Plain(it) } } ?: previous.pots,
        files = parts.files ?: previous.files,
        boxes = parts.nodes?.let { n -> DropBoxes.people(n).mapValues { listOf(Piece.Said(it.value)) } } ?: previous.boxes,
        covers = parts.covers ?: previous.covers,
    )

    suspend fun read(tenant: TenantSession, previous: Read): Read = merge(
        Parts(
            pots = runCatching { PotRows.details(tenant) }.getOrNull(),
            files = runCatching { DepotFiles.list(tenant) }.getOrNull(),
            nodes = runCatching { tenant.nodes() }.getOrNull(),
            covers = runCatching { NodeCover.framed(tenant) }.getOrNull(),
        ),
        previous,
    )
}
