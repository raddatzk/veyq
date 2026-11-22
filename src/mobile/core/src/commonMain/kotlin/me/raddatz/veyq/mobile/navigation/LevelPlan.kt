package me.raddatz.veyq.mobile.navigation


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds

/**
 * A level's tools (ADR 0154 decision 2; the web's `LevelView` tools row, owner 2026-11-04): beside the tabs, in the web's
 * order — the space's trash, then the view switch. The web's Select stands before them there; the apps' levels select
 * nothing yet (no level selection is built), so it is no entry until one is (inbox #1049).
 */
@Plan
@Serializable
enum class LevelTool {
    /** The space's node trash (`ShellModel.trashSheet`): every application's deleted nodes, the level's own. */
    @SerialName("trash") TRASH,
    /** The view switch, rows or tiles (`ViewMode.LEVEL`, ADR 0149 decision 8): governs the whole list. */
    @SerialName("view") VIEW,
}

/**
 * What a shown level offers and where (ADR 0154 decision 2, owner 2026-11-20 on the CR's Q1 and Q2): its New in the
 * level's head ([creates], the web's `LevelView` head; none: no New) and its tools in the level's tools row ([tools]).
 * Every platform draws both, in this order, on every level — the same place on all four apps. The drawer's New is
 * [DrawerSection.NEW] (ADR 0122 decision 2: what it makes lands at the space's top, from anywhere). A level's tabs are
 * the bar's ([Shell.barTabs], ADR 0122 decision 6), no entry here.
 */
data class LevelPlan(val creates: List<AppKinds.Create>, val tools: List<LevelTool>) {
    companion object {
        /**
         * The plan of a level: [creates] New's entries where New is offered (`ShellModel.createsHere` under
         * `canCreateHere`); the trash where [trash] (the web's `trashOffered`: a member's space, online, not in a guest's
         * folder); the view switch where the level shows a list ([views]: not the Memories map tab).
         */
        fun of(creates: List<AppKinds.Create>, trash: Boolean, views: Boolean): LevelPlan =
            LevelPlan(creates, listOfNotNull(LevelTool.TRASH.takeIf { trash }, LevelTool.VIEW.takeIf { views }))
    }
}

/**
 * A level row's menu (ADR 0154 decision 2; the web's `levelRow.ts` `splitRowActions`): rename and move first, then the
 * other actions in their merge order, then — after a separator — report and the delete, the delete last. The apps draw no
 * row buttons: a platform opens the menu through its own gesture (a context menu, a long press, a flyout) and draws every
 * entry of it. The entries are [HeaderAction]s, the same a header's plan holds, so one exhaustive drawing serves both.
 */
object LevelRow {
    /** The menu's two groups: [menu], then after a separator [last]. */
    data class Plan(val menu: List<HeaderAction>, val last: List<HeaderAction>) {
        val isEmpty: Boolean get() = menu.isEmpty() && last.isEmpty()
    }

    /** The ids the row's menu opens with, in this order (the web's `ROW_MENU_LEAD`). */
    val LEAD: List<HeaderAction> = listOf(HeaderAction.RENAME, HeaderAction.MOVE)

    /**
     * The menu of a row whose node grants [rights] (`NodeHeader.Rights`, as its header would), with the kind's [own]
     * entries: core's around the standard ones (`placeCoreActions`, a row's cover after the heart), [LEAD] first, the
     * menu-only ones (move's copy aside, report, delete) last.
     */
    fun plan(rights: NodeHeader.Rights, own: List<HeaderAction> = emptyList()): Plan {
        val all = NodeHeader.placed(NodeHeader.core(rights), NodeHeader.standard(rights) + own)
        // the web's `isMenuOnly`: report and delete end the menu, report first
        val menuOnly = listOf(HeaderAction.REPORT, HeaderAction.REPORT_SHARED, HeaderAction.DELETE)
        val rest = all.filter { it !in menuOnly }
        val lead = LEAD.filter { it in rest }
        return Plan(lead + rest.filter { it !in lead }, menuOnly.filter { it in all })
    }
}
