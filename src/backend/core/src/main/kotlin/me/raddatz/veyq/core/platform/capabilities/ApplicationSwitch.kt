package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.node.NodeKindRules
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * **An application switched off is read-only** (ADR 0079 decision 8, ADR 0088 decision 5,
 * uc.platform.switch-application-off): its register entry (`memories`, `depot`, the application's own one-segment id) is
 * off for the instance or for the tenant the content lives in, and every write of it answers `422 capability_disabled`
 * while every read, download, delete to the trash and restore goes on. The one helper every write asks, so the answer
 * and the level arithmetic ([CapabilityRegistry.isActiveInScope]: the AND along the path, at both levels) are one.
 *
 * Two doors, one rule:
 *  - **by name** — an application's own write handler names its application ([requireWritable]), right after its scope
 *    gate, so a non-member still learns nothing (ADR 0042) and a refusal stores nothing;
 *  - **by kind** — core's writes of a node (create, update, cover, a link rooted on one, an upload's start) ask through
 *    the kind's own rule ([NodeKindRules.applicationOf]), so core names no application. Core's own kind answers null and
 *    is never refused here.
 *
 * **What never asks it**, each for its reason (the use case's E1, E2, E5): a delete to the trash and its restore (what
 * exists stays removable, and a restored node is read-only like the rest), the purge, erasures, moderation measures,
 * a rotation's re-key (the security of what exists) and a member's own item marks. `ApplicationSwitchSweepTest` derives
 * every operation of every application and holds each to one side of that line.
 */
@ApplicationScoped
class ApplicationSwitch(
    private val capabilities: CapabilityRegistry,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @param:All private val kindRules: MutableList<NodeKindRules>,
) {
    /** Whether [application] takes writes in the tenant [scopeId] lives in. */
    fun isWritable(application: String, scopeId: String): Boolean = capabilities.isActiveInScope(application, scopeId)

    /** Whether a node of [kind] takes writes in [scopeId]: always for a kind no application owns (core's `FOLDER`). */
    fun isKindWritable(kind: String, scopeId: String): Boolean =
        NodeKindRules.applicationOf(kindRules, kind)?.let { isWritable(it, scopeId) } ?: true

    /** Refuses with `422 capability_disabled` ([ApplicationSwitchedOff]) unless [application] takes writes in [scopeId]. */
    fun requireWritable(application: String, scopeId: String) {
        if (!isWritable(application, scopeId)) throw ApplicationSwitchedOff()
    }

    /** [requireWritable] for the application that owns [kind]; core's own kind passes. */
    fun requireKindWritable(kind: String, scopeId: String) {
        if (!isKindWritable(kind, scopeId)) throw ApplicationSwitchedOff()
    }
}

/**
 * A write of a switched-off application, thrown by [ApplicationSwitch] before anything is written. An exception rather
 * than one more variant of every handler's outcome, because it ends a write in two dozen handlers the same way; it
 * rolls back the transaction it is thrown in, and carries no stack trace — it is an answer, not a fault.
 */
class ApplicationSwitchedOff : RuntimeException(null, null, false, false)

/** The contract's `422 capability_disabled` (`_common`'s `ApplicationSwitchedOff`) for every resource, core's and each application's. */
@Provider
class ApplicationSwitchedOffMapper : ExceptionMapper<ApplicationSwitchedOff> {
    override fun toResponse(exception: ApplicationSwitchedOff): Response = httpError(Failures.capabilityDisabled)
}
