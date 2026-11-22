package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.words.Msg

/**
 * An application's tests compose that application alone: [install] puts its part into the platform's registry
 * ([AppKinds]) beside an inert part for every other [ApplicationId] — one that owns no kind and answers nothing — so the
 * registry is whole, as `AppKinds.install` requires, and nothing of another application is reachable. A test across
 * several applications runs in `:composition`'s tests over `AppComposition.install()`.
 */
object TestApplications {
    private val inert = ApplicationId.entries.associateWith { Inert(it) }

    /** [own] and an inert part for every other application; the same again is no change. */
    fun install(own: ApplicationKinds) = AppKinds.install(ApplicationId.entries.map { if (it == own.application) own else inert.getValue(it) })

    private class Inert(override val application: ApplicationId) : ApplicationKinds {
        override val kinds: List<String> = emptyList()
        override val word: Msg = Msg.NOT_BUILT
    }
}
