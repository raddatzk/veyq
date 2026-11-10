package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.Serializable

/**
 * The applications the app holds (ADR 0122 decision 1): the built ones. The id is the capability register's application
 * id (contracts/platform/capabilities.json).
 */
enum class ApplicationId(val id: String) {
    MEMORIES("memories"),
    DEPOT("depot"),
    EXPENSES("expenses"),
    ;

    companion object {
        fun of(id: String): ApplicationId? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Whose views the bottom bar shows while an application is chosen (ADR 0122 decision 4): the application's own, or
 * those of the object open in it. Each application decides; the drawer and Search around it are the same for all.
 */
enum class BarLevel { APPLICATION, OBJECT }

/** One place in an application's bottom bar. [key] is stable and names its string in each platform's catalog. */
@Serializable
data class BarTab(val key: String)

data class ApplicationShape(
    val id: ApplicationId,
    val barLevel: BarLevel,
    /** The bar's places before Search, which every bar ends with. */
    val tabs: List<BarTab>,
    /**
     * Whether the drawer lists it. Depot does not (owner 2026-11-08): its files are what Home's folders hold, so its
     * own flat list would be Home without the folders; its bar still serves a file that is open.
     */
    val inDrawer: Boolean = true,
)

/**
 * The bars the applications start with. Expenses works inside one Pot at a time, so its bar is the open Pot's, its
 * views as the web's Pot has them (entries, overview, settings); Memories and Depot are browsed, so their bars are the
 * application's own.
 */
val APPLICATION_SHAPES: List<ApplicationShape> = listOf(
    ApplicationShape(ApplicationId.MEMORIES, BarLevel.APPLICATION, listOf(BarTab("memories.all"), BarTab("memories.map"))),
    ApplicationShape(ApplicationId.DEPOT, BarLevel.OBJECT, listOf(BarTab("depot.file")), inDrawer = false),
    ApplicationShape(
        ApplicationId.EXPENSES,
        BarLevel.OBJECT,
        listOf(BarTab("expenses.entries"), BarTab("expenses.overview"), BarTab("expenses.settings")),
    ),
)

/**
 * What the drawer offers, given which applications the operator or the tenant owner left on (ADR 0088): a switched-off
 * application is absent, as it is from the web's navigation (ADR 0122 decision 1).
 */
fun availableApplications(applicationsOff: Set<String>): List<ApplicationShape> =
    APPLICATION_SHAPES.filterNot { it.id.id in applicationsOff }

/** The applications the drawer lists: the available ones it shows (ADR 0122 decision 2). */
fun drawerApplications(applicationsOff: Set<String>): List<ApplicationShape> = availableApplications(applicationsOff).filter { it.inDrawer }
