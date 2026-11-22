package me.raddatz.veyq.mobile.memories.people

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.memories.Participants
import me.raddatz.veyq.mobile.memories.place.MemoriesMap
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * Which Memories a person took part in (uc.memory.participants; ADR 0083 decision 6, ADR 0144 decision 12) — the port of
 * the web's `participantIndex.ts`. The server holds no such relation (operator-blind), so it is computed here, by the
 * device that holds the keys, over the trees the session already read ([TenantSession.trees]: no second read, the same
 * `ETag` revalidation, the same kept listings offline): the tenant's scope and every group scope — **never the personal
 * one** (no participants there; `trees()` reads it for other reasons) —, **MEMORY nodes only** (a chapter names none),
 * each Memory's sealed meta opened under its own key to its participants, date and place. Access is enforced for free: a
 * Memory the member cannot list is never read.
 *
 * **Bounded:** at most [MAX_MEMORIES_PER_SCOPE] Memories of a scope are opened; past it the scope is capped and the index
 * [Index.partial] — as it is where a scope's tree did not read (offline with nothing kept, a grant that does not open).
 *
 * **In memory only** ([TenantSession.fromTree]): built on the first profile, rebuilt when the tree is read anew, dropped
 * with the tree and when the tenant closes (a switch, a lock, a sign-out). A who-was-with-whom map is never written to
 * disk, and it holds no key: a row's cover and a pin are read by node id as a list's are.
 */
object ParticipantIndex {
    /** Memories opened per scope before the walk stops and the index is partial (the web's `MAX_MEMORIES_PER_SCOPE`). */
    const val MAX_MEMORIES_PER_SCOPE = 500

    /** One Memory the index found participants on. */
    data class Memory(
        val node: NodeRef,
        /** The space it lies in: a group's is named on its row, the tenant's is not. */
        val space: SpaceRef,
        val date: MemoryDate?,
        val location: MemoryLocation?,
        /** Its pin on the Memories map, or null where its place has no position (ADR 0143 D6's rule). */
        val pin: MemoriesMap.Pin?,
        val userIds: List<String>,
    ) {
        /** The place's name, null without one: the row's place. */
        val placeName: String? get() = location?.name?.takeIf { it.isNotEmpty() }
    }

    class Index(
        /** userId → the Memories they take part in. */
        val byUser: Map<String, Set<NodeRef>>,
        val memories: Map<NodeRef, Memory>,
        /** Some Memories were not read: a scope over the cap, or a scope whose tree did not read. */
        val partial: Boolean,
    ) {
        /** The Memories [userId] takes part in, unordered. */
        fun of(userId: String): List<Memory> = byUser[userId].orEmpty().mapNotNull { memories[it] }
    }

    /** One scope as the walk takes it: [nodes] its tree as read, null where it did not read. */
    class Scope(val space: SpaceRef, val nodes: List<OpenNode>?)

    /**
     * The index over [scopes] (only a tenant's and a group's are walked), each Memory's meta opened by [open] — null where
     * it does not open, which leaves that Memory out as the web's walk does.
     */
    fun build(scopes: List<Scope>, open: (OpenNode) -> JsonObject?): Index {
        val byUser = LinkedHashMap<String, LinkedHashSet<NodeRef>>()
        val memories = LinkedHashMap<NodeRef, Memory>()
        var partial = false
        for (scope in scopes) {
            if (scope.space.kind != TENANT && scope.space.kind != GROUP) continue
            val nodes = scope.nodes
            if (nodes == null) {
                partial = true
                continue
            }
            var opened = 0
            for (node in nodes) {
                if (node.kind != MEMORY) continue
                if (opened >= MAX_MEMORIES_PER_SCOPE) {
                    partial = true
                    break
                }
                opened++
                val meta = open(node) ?: continue
                val userIds = Participants.read(meta)
                if (userIds.isEmpty()) continue
                val ref = node.asNode()
                memories[ref] = Memory(ref, scope.space, MemoryDate.of(meta), MemoryLocation.of(meta), MemoriesMap.pinOf(ref, meta), userIds)
                for (id in userIds) byUser.getOrPut(id) { LinkedHashSet() } += ref
            }
        }
        return Index(byUser, memories, partial)
    }

    /**
     * The tenant's index, built from its tree as last read and held with it ([TenantSession.fromTree]): the same index
     * until the tree is read anew. A scope the session holds closed (a grant that did not open) counts as not read.
     */
    @Throws(Exception::class)
    suspend fun of(tenant: TenantSession): Index = tenant.fromTree(HELD) { trees ->
        val suite = tenant.account.suite
        val open = tenant.spaces().map { Scope(it, if (it.scopeId in tenant.unreadTrees) null else trees[it.scopeId].orEmpty()) }
        val closed = tenant.closedKinds.map { (id, kind) -> Scope(SpaceRef(id, kind, ""), null) }
        build(open + closed) { n -> runCatching { NodeMetaWrite.openForWrite(suite, n.scopeId, n.id, n.key, n.wrappedMeta) }.getOrNull() }
    }

    /** Whether [tenant] holds an index now: what a test of its lifetime reads. */
    @PlatformApi
    @HiddenFromObjC
    fun held(tenant: TenantSession): Boolean = tenant.holdsDerived(HELD)

    /** The key it is held under on the session. */
    private const val HELD = "memories.participantIndex"

    private const val TENANT = "TENANT"
    private const val GROUP = "GROUP"
    private const val MEMORY = "MEMORY"
}
