package me.raddatz.veyq.mobile.composition

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.ApplicationKinds
import me.raddatz.veyq.mobile.depot.DepotKinds
import me.raddatz.veyq.mobile.expenses.ExpensesKinds
import me.raddatz.veyq.mobile.memories.MemoriesKinds

/**
 * The one place that names every application (ADR 0154 decision 3; the web's `src/web/src/applications.ts`): installs
 * each application's part into the platform's registry ([AppKinds]). Each client calls [install] on its start path,
 * before any session opens — Android's application, Apple's `AppModel`, the bridge's first call —, and every test of the
 * composed core before it reads a kind.
 */
object AppComposition {
    /** The applications this build holds, in the registry's order (the search lists their kinds in it). */
        @HiddenFromObjC
    val applications: List<ApplicationKinds> = listOf(DepotKinds, ExpensesKinds, MemoriesKinds)

    /** Installs [applications] into [AppKinds]; the same again is no change. */
    fun install() = AppKinds.install(applications)
}
