package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.PersonProfile
import me.raddatz.veyq.mobile.sharing.GuestPage
import me.raddatz.veyq.mobile.sharing.GuestScreen

/**
 * A kind's page as an application registers it (the web's `KindDescriptor`, ADR 0154 decision 3): what the kind's page
 * contributes to core's header ([header]: null while the page cannot tell yet), the page's body under it ([body]; a
 * folder's is core's level), and the page a link's guest is shown of it ([guest], share view = member view). What the
 * header offers is core's (`NodeHeaderModel`, `AppKinds`); core draws it for every kind (`NodeHeaderActions`), never an
 * application.
 */
internal class KindPage(
    val header: @Composable (NodeAt, AppModel) -> HeaderContribution?,
    val body: (@Composable (ObjectRef, BarTab?, AppModel) -> Unit)? = null,
    val guest: (@Composable (GuestPage, GuestScreen, AppModel, SnackbarHostState) -> Unit)? = null,
)

/**
 * What an application registers for itself beside its kinds: its own list in the drawer ([list]; null: core's arranged
 * list), the dialogs of what it creates ([creates], the web's `createDialog`: opened by core's New with the space and
 * folder shown) and its section of a person's profile ([personSection]: it draws the profile under core's [frame]).
 */
internal class ApplicationUi(
    val id: ApplicationId,
    val list: (@Composable (Screen.Signed, AppModel, (ObjectRef) -> Unit) -> Unit)? = null,
    val creates: Map<AppKinds.Create, CreateDialog> = emptyMap(),
    val personSection: (@Composable (profile: PersonProfile, model: AppModel, frame: @Composable ColumnScope.() -> Unit) -> Unit)? = null,
)

/** A create dialog an application registers (the web's `createDialog`): opened over the space and folder shown, closed by [onClose]. */
internal typealias CreateDialog = @Composable (s: Screen.Signed, model: AppModel, space: String?, folder: String?, onClose: () -> Unit) -> Unit

/**
 * The core UI's one registry (the web's `core/applications/registry.ts`): each application registers into it from the
 * composition root (`Applications.kt`, the web's `applications.ts`), and the shell, a link's page, New and a person's
 * profile ask it — the core UI names no application. A kind or an application registered twice is refused, and a lookup
 * before the composition root ran fails rather than draw nothing.
 */
internal object Registry {
    private val kinds = HashMap<String, KindPage>()
    private val applications = HashMap<ApplicationId, ApplicationUi>()
    private var sealed = false

    fun kind(page: KindPage, vararg kind: String) {
        check(!sealed) { "the registry is sealed" }
        for (k in kind) check(kinds.put(k, page) == null) { "the kind $k is registered twice" }
    }

    fun application(ui: ApplicationUi) {
        check(!sealed) { "the registry is sealed" }
        check(applications.put(ui.id, ui) == null) { "the application ${ui.id} is registered twice" }
    }

    /** Called once by the composition root after every registration. */
    fun seal() {
        sealed = true
    }

    val isSealed: Boolean get() = sealed

    fun page(kind: String): KindPage? = ready { kinds[kind] }

    fun application(id: ApplicationId): ApplicationUi? = ready { applications[id] }

    fun create(create: AppKinds.Create): CreateDialog? =
        ready { applications.values.firstNotNullOfOrNull { it.creates[create] } }

    private inline fun <T> ready(read: () -> T): T {
        check(sealed) { "the registry is read before the composition root registered the applications" }
        return read()
    }
}
