package me.raddatz.veyq.core.sharing

import jakarta.enterprise.context.ApplicationScoped

/**
 * An application stand-in of `:core`'s test application: one kind declared guest-creatable ([GuestCreatableKinds]) that
 * no `NodeKindRules` places anywhere. With no application in `:core` a guest creates folders only, so without it the
 * placement rule behind the declaration (ADR 0080 bite test 17: a kind `NodeKindRules` refuses under the folder) could
 * not be reached by a guest at all — `ShareNodesCoreSliceTest` holds that layer with the declaration's held open.
 *
 * `CoreAloneSliceTest` excludes it (`CoreAloneProfile`, `quarkus.arc.exclude-types`), so the core-alone pin still boots
 * core with nothing declared.
 */
@ApplicationScoped
class CoreTestGuestCreatableKinds : GuestCreatableKinds {
    override fun kinds(): Set<String> = setOf(KIND)

    companion object {
        /** Declared guest-creatable here, placed by no rule. */
        const val KIND = "GUEST_TEST_KIND"
    }
}
