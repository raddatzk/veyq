package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.AppKinds

/**
 * An import's target the test plays (ADR 0152): what lies in the space already ([existing], by parent), every node made in
 * order ([made]: its kind, name, parent and the bytes of each payload its hook took), what was trashed, the storage answer
 * ([room]), and names the "server" holds without the local tree knowing ([serverTaken]: its own `slug_taken`).
 */
class PlayedImport(
    private val existing: Map<String?, List<Pair<String, String>>> = emptyMap(),
    var room: Boolean? = true,
    val serverTaken: MutableSet<String> = HashSet(),
) : SubtreeImport.Target {
    class Made(val id: String, val kind: String, val name: String, val parent: String?, val payloads: List<String>)

    val made = ArrayList<Made>()
    val trashed = ArrayList<String>()
    var askedRoom: Long? = null
    private var next = 0

    override val tenant = null

    override suspend fun siblings(parentId: String?) = existing[parentId].orEmpty()

    override suspend fun folder(parentId: String?, name: String): NodeImport.Made = make("FOLDER", name, parentId, emptyList())

    override suspend fun trash(nodeId: String): Boolean {
        trashed += nodeId
        return true
    }

    override suspend fun fits(bytes: Long): Boolean? {
        askedRoom = bytes
        return room
    }

    fun make(kind: String, name: String, parent: String?, payloads: List<String>): NodeImport.Made {
        if (name in serverTaken) return NodeImport.Made(null, nameTaken = true)
        val id = "new${++next}"
        made += Made(id, kind, name, parent, payloads)
        return NodeImport.Made(id)
    }

    /**
     * [kinds] with each own import played here: the application, schema, check and folder rule are the kind's, so the plan
     * is the build's; the import takes every payload of its node (checked by core) and records what it read.
     */
    fun played(kinds: SubtreeImport.Kinds = real()): SubtreeImport.Kinds = SubtreeImport.Kinds { kind ->
        kinds.hook(kind)?.let { h ->
            NodeImport.Hook(h.application, h.schema, h.validate, h.asFolder, h.import?.let {
                { ctx: NodeImport.Context ->
                    val bytes = ctx.node.payloads.map { p -> ctx.payload(p).read(0, p.size.toInt()).decodeToString() }
                    ctx.progress(1.0)
                    make(kind, ctx.name, ctx.parentId, bytes)
                }
            })
        }
    }

    companion object {
        /** The build's own kinds (`AppKinds.imports`), no platform deriver. */
        fun real(): SubtreeImport.Kinds = AppKinds.imports("Deleted people", { "00000000-0000-4000-8000-000000000000" }, null) { 0L }

        /** A name as both clients fold one (the build's `AppKinds.tokenInput`). */
        val tokenInput: (String, String) -> String? = AppKinds::tokenInput
    }
}
