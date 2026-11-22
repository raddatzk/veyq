package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * What a level's selection may do, in the web's bar's order (`LevelView.vue`'s selection bar, inbox #421): move, copy,
 * report, delete. Every platform draws every entry of [LevelSelection.Picking.entries], in this order (ADR 0154 decision 2).
 */
@Plan
@Serializable
enum class SelectionAction {
    @SerialName("move") MOVE,
    @SerialName("copy") COPY,
    @SerialName("report") REPORT,
    @SerialName("delete") DELETE,
    ;

    /** The row's menu entry the same action is there (a selection acts only where each row's own plan offers it). */
    val entry: HeaderAction get() = when (this) {
        MOVE -> HeaderAction.MOVE
        COPY -> HeaderAction.COPY
        REPORT -> HeaderAction.REPORT
        DELETE -> HeaderAction.DELETE
    }

    /** Its words in the bar (the web's `node.move.action`, `node.copy.action`, `report.action`, `node.delete`). */
    val word: Msg get() = when (this) {
        MOVE -> Msg.NODE_MOVE
        COPY -> Msg.COPY_ACTION
        REPORT -> Msg.REPORT_ACTION
        DELETE -> Msg.ENTRY_DELETE
    }
}

/**
 * A level's selection (the web's `selection.ts` and `LevelView.vue`'s Select mode, inbox #421 and #1049): a set of the
 * level's node ids while its Select mode is on, and the bar the level draws over it — the count, *All*, *None*, the
 * actions, *Cancel*. Core's, like the level: it names no kind; what a selected node may do is its own row's plan
 * ([Rights.of] over [LevelRow.plan], the web's `LevelRow.selection`), read here and nowhere else.
 *
 * The rules are the web's, held by `contracts/platform/plans/v1-vectors.json`'s `selection` cases on both sides
 * ([bar], the web's `selectionBar`): an action is shown where some row of the level allows it, enabled where some
 * SELECTED row does, and it says how many of the selected it cannot reach (the report says nothing: anyone who sees a node
 * reports it). Each action reaches only the selected rows that allow it ([reached]): a row's rights are never widened by
 * its neighbours'. The selection is the LEVEL's, not its filter's: *All* adds what the level shows, *None* takes exactly
 * that away, and the count says how many of the selected the filter hides. Leaving the mode or the level clears it, and a
 * node that left the level drops out ([retained]).
 */
object LevelSelection {
    /** What one row lets a selection do with it: the entries of its own menu ([LevelRow.plan]). */
    @Serializable
    data class Rights(val move: Boolean, val copy: Boolean, val report: Boolean, val delete: Boolean) {
        fun allows(action: SelectionAction): Boolean = when (action) {
            SelectionAction.MOVE -> move
            SelectionAction.COPY -> copy
            SelectionAction.REPORT -> report
            SelectionAction.DELETE -> delete
        }

        companion object {
            val NONE = Rights(move = false, copy = false, report = false, delete = false)

            /** A row's rights as its menu offers them: an action of the selection where the row's plan holds its entry. */
            fun of(plan: LevelRow.Plan): Rights {
                val all = plan.menu + plan.last
                return Rights(HeaderAction.MOVE in all, HeaderAction.COPY in all, HeaderAction.REPORT in all, HeaderAction.DELETE in all)
            }
        }
    }

    /** One action of the bar: [enabled] where some selected row allows it; [cannot] how many of the selected it does not reach. */
    @Serializable
    data class BarEntry(val action: SelectionAction, val enabled: Boolean, val cannot: Int)

    /** The bar's rule over numbers only (what the vectors pin): [entries] in the plan's order, the [count] and how many are [hidden]. */
    data class Bar(val entries: List<BarEntry>, val count: Int, val hidden: Int)

    /**
     * The bar over a level whose rows grant [rows] (every row of the level, by id, whatever the filter shows), the [selected]
     * ids and the ids the level [shown] on screen: the web's `LevelView.vue` (`anyMove`/`anyCopy`/`anyReport`/`anyDelete`,
     * `movable`, `cannotMoveText`, `hiddenCount`). An id selected that the level no longer holds counts nowhere.
     */
    fun bar(rows: Map<String, Rights>, selected: Collection<String>, shown: Collection<String>): Bar {
        val chosen = selected.filter { it in rows }.distinct()
        val shownSet = shown.toSet()
        val entries = SelectionAction.entries.filter { a -> rows.values.any { it.allows(a) } }.map { a ->
            val reach = chosen.count { rows.getValue(it).allows(a) }
            BarEntry(a, enabled = reach > 0, cannot = if (a == SelectionAction.REPORT) 0 else chosen.size - reach)
        }
        return Bar(entries, chosen.size, chosen.count { it !in shownSet })
    }

    /** Whether a level whose rows grant [rows] offers Select at all (the web's `canSelect`): some row allows some action. */
    fun offered(rows: Collection<Rights>): Boolean = rows.any { r -> SelectionAction.entries.any(r::allows) }

    /** The selected rows [action] reaches, in the level's order: each selected row that itself allows it. */
    fun <R> reached(action: SelectionAction, level: List<R>, id: (R) -> String, selected: Set<String>, rights: (R) -> Rights): List<R> =
        level.filter { id(it) in selected && rights(it).allows(action) }

    /** [selected] without what the level no longer holds ([level], every id there). */
    fun retained(selected: Set<String>, level: Collection<String>): Set<String> = selected.intersect(level.toSet())

    /** [selected] with every id the level [shown] (*All*): the rest stays. */
    fun all(selected: Set<String>, shown: Collection<String>): Set<String> = selected + shown

    /** [selected] without every id the level [shown] (*None*): what the filter hides stays selected. */
    fun none(selected: Set<String>, shown: Collection<String>): Set<String> = selected - shown.toSet()

    /** [selected] with [id] turned. */
    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    /**
     * What a level in Select mode shows: the [selected] ids (each row drawn checked or not, a tap turning it), the
     * [count]'s words (the web's `node.select.count` / `countHidden`), and the bar's [entries], each with its words and
     * what it cannot reach ([Picking.BarAction.cannot]: the web's `node.select.cannotMove` hints).
     */
    @Serializable
    data class Picking(
        /** The place it belongs to (space, folder): another place clears it. */
        val place: String,
        val selected: Set<String>,
        val count: Notice,
        val entries: List<BarAction>,
    ) {
        @Serializable
        data class BarAction(val action: SelectionAction, val enabled: Boolean, val cannot: Notice?) {
            val word: Msg get() = action.word
        }

        /** Whether row [id] is selected. */
        fun has(id: String): Boolean = id in selected

        /** The bar's *All*, *None* and *Cancel* words (the web's `node.select.all`, `none`; `common.cancel`). */
        val all: Msg get() = Msg.SELECT_ALL
        val none: Msg get() = Msg.SELECT_NONE
        val cancel: Msg get() = Msg.ENTRY_CANCEL

        /** The bar's name for assistive technology (the web's `node.select.bar`). */
        val label: Msg get() = Msg.SELECT_BAR

        companion object {
            /** The shown selection at [place] over [rows], [selected] and [shown] ([bar]), in words. */
            fun of(place: String, rows: Map<String, Rights>, selected: Set<String>, shown: Collection<String>): Picking {
                val kept = retained(selected, rows.keys)
                val b = bar(rows, kept, shown)
                val count = if (b.hidden > 0) Notice(Msg.SELECT_COUNT_HIDDEN, b.count, b.hidden) else Notice(Msg.SELECT_COUNT, b.count)
                return Picking(place, kept, count, b.entries.map { BarAction(it.action, it.enabled, cannot(it, b.count)) })
            }

            private fun cannot(e: BarEntry, of: Int): Notice? = if (e.cannot <= 0) null else when (e.action) {
                SelectionAction.MOVE -> Notice(Msg.SELECT_CANNOT_MOVE, e.cannot, of)
                SelectionAction.COPY -> Notice(Msg.SELECT_CANNOT_COPY, e.cannot, of)
                SelectionAction.DELETE -> Notice(Msg.SELECT_CANNOT_DELETE, e.cannot, of)
                SelectionAction.REPORT -> null
            }
        }
    }

    /** The Select tool's words (the web's `node.select.action`). */
    val word: Msg get() = Msg.SELECT_ACTION
}
