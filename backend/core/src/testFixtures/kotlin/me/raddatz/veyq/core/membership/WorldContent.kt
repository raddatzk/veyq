package me.raddatz.veyq.core.membership

/**
 * An application's content in each [ScopeAccessWorld] space — what its probes' controls need beyond core's own (an
 * upload session and a job). Core's world names no application: each application under test contributes its
 * content as a CDI bean in its own test fixtures (Memories' `MemoriesWorldContent`: a `MEMORY` node, a medium, a
 * bucket and a share), found by [ScopeAccessWorld] through `Arc`. So a `:core` test builds the world with core alone,
 * and a second application's routes are covered by the gate probes the day it contributes its content the same way.
 *
 * A bean needs `@io.quarkus.arc.Unremovable`: nothing injects it, and Arc drops a bean nothing injects.
 */
interface WorldContent {

    /**
     * Seeds this application's content into [space] — a live GROUP whose owner is [ScopeAccessWorld.Space.ownerId],
     * every state flip still ahead — through the API with [auth] (the owner's bearer), and returns its ids by name.
     * The names are the application's; no two contributions may share one.
     */
    fun seed(space: ScopeAccessWorld.Space, auth: String): Map<String, String>
}
