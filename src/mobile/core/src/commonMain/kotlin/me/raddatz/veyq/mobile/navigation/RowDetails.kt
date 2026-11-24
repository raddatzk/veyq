package me.raddatz.veyq.mobile.navigation

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Piece

/**
 * What a tenant's list rows say beside their names, read in one go after every change the apps make (ADR 0136): each
 * application's parts ([Part], registered through `ApplicationKinds.rowParts` — a Pot's currency, a file's size and change
 * or that it is not uploaded, a drop box's guests) and which cover each node names, so the lists' pictures follow too. A
 * part that does not read keeps what the previous [Read] held, so a passing network error empties no row.
 */
object RowDetails {
    /**
     * One part of the rows' facts an application reads ([key] unique in the build): each row's facts by node id. [read]
     * throws where it does not read; its rows are then the previous [Read]'s.
     */
    @HiddenFromObjC
    class Part(val key: String, val read: suspend (TenantSession) -> Map<String, List<Piece>>)

    class Read internal constructor(
        /** Each part's rows by its key, as last read. */
        internal val parts: Map<String, Map<String, List<Piece>>>,
        /** Each node's cover key ([NodeCover.framed]), for [NodeCover.changes]. */
        val covers: Map<String, String>,
    ) {
        /** Each row's facts, by node id. */
        val details: Map<String, List<Piece>> get() = parts.values.fold(emptyMap()) { all, rows -> all + rows }

        companion object {
            val EMPTY = Read(emptyMap(), emptyMap())
        }
    }

    /** [parts] (each null where it did not read) and [covers] (null: not read) over [previous]: what did not read keeps its rows. */
    @HiddenFromObjC
    fun merge(parts: Map<String, Map<String, List<Piece>>?>, covers: Map<String, String>?, previous: Read): Read = Read(
        parts = parts.mapValues { (key, rows) -> rows ?: previous.parts[key].orEmpty() },
        covers = covers ?: previous.covers,
    )

    suspend fun read(tenant: TenantSession, previous: Read): Read = merge(
        AppKinds.rowParts.associate { part -> part.key to runCatching { part.read(tenant) }.getOrNull() },
        runCatching { NodeCover.framed(tenant) }.getOrNull(),
        previous,
    )
}
