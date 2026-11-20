package me.raddatz.veyq.mobile.android

import android.content.Intent
import androidx.compose.runtime.Composable
import me.raddatz.veyq.mobile.android.apps.depot.DepotUi
import me.raddatz.veyq.mobile.android.apps.expenses.ExpensesUi
import me.raddatz.veyq.mobile.android.apps.memories.DevMemory
import me.raddatz.veyq.mobile.android.apps.memories.MemoriesUi
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.KindPages
import me.raddatz.veyq.mobile.android.core.Registry

/**
 * The composition root (ADR 0154 decision 3; the web's `src/web/src/applications.ts`): the one file of the app that names
 * the applications' UIs. Core's own kinds and each application register into the core UI's [Registry] here, once, before
 * anything is drawn; `scripts/validate_mobile_ui_direction.py` holds every other file of the core UI from naming an
 * application, and an application from naming another.
 */
internal object Applications {
    fun register() {
        if (Registry.isSealed) return
        KindPages.register(Registry)
        MemoriesUi.register(Registry)
        DepotUi.register(Registry)
        ExpensesUi.register(Registry)
        Registry.seal()
    }

    /** A debug build's page an application offers over played data (Memories' `DevMemory`), asked by [intent]; else null. */
    fun devPage(intent: Intent?): (@Composable (AppModel) -> Unit)? =
        if (DevMemory.requested(intent)) { model -> DevMemory.Screen(model) } else null
}
