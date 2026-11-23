package me.raddatz.veyq.mobile.sharing

import io.ktor.client.statement.bodyAsText
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.PlatformApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.UpdateShareNodeRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.navigation.HeaderAction
import me.raddatz.veyq.mobile.navigation.LevelRow
import me.raddatz.veyq.mobile.navigation.LevelSelection
import me.raddatz.veyq.mobile.navigation.LevelTool
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.SelectionAction
import me.raddatz.veyq.mobile.session.NodeEpochWrite
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.tree.SiblingNames
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/**
 * A READ_WRITE link's guest moves a node within the link's folders (inbox #1063; the web's `core/node/move.ts` `guestMover`
 * over `core/sharing/guestTree.ts` `placeShareNodeAsGuest`): one placement write per node (`updateShareNode`) — the node's
 * own key unchanged but re-wrapped, its name and slug token sealed anew, under the destination's key, bound to (scope,
 * node, its scope epoch, its placement revision + 1) —, sent as the guest after the core's one naming flow
 * ([GuestAccess.naming]). The browser is the member's [MoveSheet] over the guest's tree ([door]), its top the link's root
 * and its places the link's folders only ([NodeMove]'s `top`, the platform's `MovePlan` with `guest`): share view =
 * member view, the guest's rights only remove functions — no copy, never the link's root, never in or of a guest's
 * identity folder (ADR 0080 decision 11). The server decides (`GuestNodeRules`); its refusals are worded as a member's.
 */
object GuestMove {
    /**
     * Whether the guest of [access] may move [nodeId] (the web's `nodeRights` for a READ_WRITE guest, narrowed by its
     * application's rights): a READ_WRITE link; a node a folder of the link holds — so never the link's root, whose parent
     * lies above the link —, not in or of a guest's identity folder, of a kind a guest moves ([AppKinds.guestMoves]: a
     * Pot, a chapter and a drop box do not).
     */
    fun moves(access: GuestAccess?, nodeId: String): Boolean {
        val a = access ?: return false
        if (a.role != GuestAccess.READ_WRITE) return false
        val tree = a.tree ?: return false
        val node = tree.node(nodeId) ?: return false
        if (node.id == tree.root.id) return false
        val parent = node.parentId?.let { tree.node(it) } ?: return false
        return parent.kind == FOLDER && !a.inIdentityFolder(nodeId) && AppKinds.guestMoves(node.kind)
    }

    /**
     * Moves [nodeId] under [to] (a folder of the link) as the guest of [session] over [access]'s tree, keeping its name:
     * refused before sending where the plan blocks the place ([NodeMove.Outcome.BLOCKED]) or a sibling there folds to its
     * name ([NodeMove.Outcome.NAME_TAKEN]); `409 stale_placement` reads the link anew ([reread]) and builds once more, a
     * re-key in flight waits and `stale_epoch` re-reads ([NodeEpochWrite]). The caller reads the link anew after it.
     */
    @PlatformApi
    @HiddenFromObjC
    suspend fun place(
        access: GuestAccess,
        session: GuestSession,
        nodeId: String,
        to: String,
        reread: suspend () -> GuestTree?,
        waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): NodeMove.Outcome {
        var tree = access.tree ?: return NodeMove.Outcome.GONE
        // the root's entry is under a key no guest holds
        if (nodeId == tree.root.id) return NodeMove.Outcome.NOT_ALLOWED
        val first = tree.node(nodeId) ?: return NodeMove.Outcome.GONE
        if (tree.node(to) == null) return NodeMove.Outcome.GONE
        val refs = tree.nodes.map { it.asNode() }
        if (NodeMove.hereBlock(refs, first.asNode(), to, AppKinds.placement, top = tree.root.id) != null) return NodeMove.Outcome.BLOCKED
        AppKinds.tokenInput(first.kind, first.name) ?: return NodeMove.Outcome.FAILED
        // a node at the destination that folds to the same input: said before anything is sent
        if (SiblingNames.colliding(tree.children(to), { it.id }, { it.kind }, { it.name }, first.kind, first.name, nodeId, AppKinds::tokenInput) != null) {
            return NodeMove.Outcome.NAME_TAKEN
        }
        var node: OpenNode = first
        var destination: OpenNode = tree.node(to)!!
        fun read(t: GuestTree): Boolean {
            node = t.node(nodeId) ?: return false
            destination = t.node(to) ?: return false
            tree = t
            return true
        }
        var placements = 0
        while (true) {
            var placed = false
            val result = NodeEpochWrite.run(
                waitsMs = waitsMs,
                // a guest holds no floor of its own: the server's `node_rekey_pending` is what it waits on
                belowFloor = { false },
                reread = { reread()?.let(::read) ?: false },
                attempt = {
                    val (status, code) = send(access, session, node, destination)
                    val answer = when {
                        status in 200..299 -> {
                            placed = true
                            NodeEpochWrite.Answer.DONE
                        }
                        status == 409 && code == "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                        status == 409 && code == "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to (status to code)
                },
            )
            if (placed) return NodeMove.Outcome.MOVED
            val answered = (result as? NodeEpochWrite.Result.Answered)?.value
            if (answered != null && answered.first == 409 && answered.second == STALE_PLACEMENT && placements++ < PLACEMENT_RETRIES) {
                // another guest or a member placed it meanwhile: the link read anew, the placement built on what it holds now
                val fresh = reread() ?: return NodeMove.Outcome.GONE
                if (!read(fresh)) return NodeMove.Outcome.GONE
                continue
            }
            return when (result) {
                NodeEpochWrite.Result.Busy -> NodeMove.Outcome.BUSY
                NodeEpochWrite.Result.Gone -> NodeMove.Outcome.GONE
                NodeEpochWrite.Result.StaleAgain -> NodeMove.Outcome.FAILED
                is NodeEpochWrite.Result.Answered -> outcomeOf(result.value.first, result.value.second)
            }
        }
    }

    /** A refusal of the guest's placement as the member's sheet words it (the web's `moveRefusalOf`). */
    @PlatformApi
    @HiddenFromObjC
    fun outcomeOf(status: Int, code: String?): NodeMove.Outcome = when {
        status == 404 || status == 403 -> NodeMove.Outcome.GONE
        status == 422 -> NodeMove.Outcome.NOT_ALLOWED
        status == 409 && code == "slug_taken" -> NodeMove.Outcome.NAME_TAKEN
        status == 409 && code == STALE_PLACEMENT -> NodeMove.Outcome.CONFLICT
        status == 409 && code == "scope_deleting" -> NodeMove.Outcome.SCOPE_DELETING
        status == 409 && code == "node_rekey_pending" -> NodeMove.Outcome.BUSY
        else -> NodeMove.Outcome.FAILED
    }

    /** One placement write of [node] under [destination]: its status and the server's code. */
    private suspend fun send(access: GuestAccess, session: GuestSession, node: OpenNode, destination: OpenNode): Pair<Int, String?> {
        val input = AppKinds.tokenInput(node.kind, node.name) ?: return 0 to "no_token_input"
        val suite = access.suite
        val binding = arrayOf<Pair<String, Any?>>(
            "scopeId" to access.scopeId, "nodeId" to node.id, "epoch" to node.scopeEpoch.toLong(), "placementRevision" to node.placementRevision + 1,
        )
        val r = SharingApi(access.client.origin, access.client.http).updateShareNode(
            access.shareId, node.id, session.contributorToken,
            UpdateShareNodeRequest(
                parentEpoch = destination.epoch,
                parentId = destination.id,
                encryptedName = b64(suite.sealField(Context("node-name", *binding), destination.key, utf8(node.name))),
                slugToken = b64(suite.slugToken(destination.key, destination.id, input)),
                wrappedContentKey = b64(suite.seal(Context("content-key", *binding), destination.key, node.key)),
                placementRevision = node.placementRevision,
            ),
            access.pwToken,
        )
        if (r.success) return r.status to null
        return r.status to GuestBlobs.codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
    }

    /**
     * The member's move sheet's door over the link [view] shows (`MoveSheet.Door`): the guest's tree, the kinds' placement,
     * the link's root as the top and its name; a move asks the guest's name at the write (declined: nothing is sent) and
     * places through [place]; the link read anew after each write. A guest copies nothing. Null before the gates passed or
     * on a link that serves no tree.
     */
    fun door(view: GuestView): MoveSheet.Door? {
        val tree = view.access?.tree ?: return null
        val rootId = tree.root.id
        val rootName = tree.root.name
        return object : MoveSheet.Door {
            override suspend fun tree(scopeId: String) = (view.access?.tree ?: tree).nodes.map { it.asNode() }
            override val placement get() = AppKinds.placement
            override fun rootName(scopeId: String) = Piece.Plain(rootName)
            override val top: String get() = rootId
            override suspend fun move(ref: NodeAt, to: String?): NodeMove.Outcome {
                if (to == null) return NodeMove.Outcome.NOT_ALLOWED // no guest holds the scope root's key
                val a = view.access ?: return NodeMove.Outcome.GONE
                val session = a.naming.request() ?: return NodeMove.Outcome.DECLINED
                return place(a, session, ref.nodeId, to, reread = {
                    view.reload()
                    view.access?.tree
                })
            }
            override suspend fun duplicateOpen(ref: NodeAt, to: String?): Notice = Notice(Msg.COPY_FAILED)
            override suspend fun duplicateSubtree(ref: NodeAt, to: String?): Notice = Notice(Msg.COPY_FAILED)
            override suspend fun preview(ref: NodeAt) = null
            override suspend fun noRoom(ref: NodeAt) = null
            // the link read anew: what moved lists at its new place, under its new wrap
            override fun changed() = view.reloadSoon()
        }
    }

    private const val FOLDER = "FOLDER"
    private const val STALE_PLACEMENT = "stale_placement"

    /** How often a `409 stale_placement` is built again on the link read anew (the web's `PLACEMENT_RETRIES`). */
    private const val PLACEMENT_RETRIES = 1
}

/**
 * The move of a guest's level (inbox #1063; the web's `ShareTreePage` over core's `LevelView`): each row's menu — Move
 * where the guest moves its node ([GuestMove.moves]) —, the level's Select where some row moves, the selection while its
 * Select mode is on (core's [LevelSelection], the member's rules: a row's rights never widened by its neighbours'), and
 * the member's [MoveSheet] over one row or over the selected rows that move. Held by [GuestLevel.move]; a reload after a
 * write draws a new level, its selection cleared.
 */
class GuestLevelMoves internal constructor(
    private val node: NodeAt,
    private val tree: GuestTree,
    private val access: GuestAccess?,
    private val view: GuestView?,
) {
    private val rowIds: List<String> get() = tree.children(node.nodeId).map { it.id }

    /** What row [nodeId] lets a selection do: move where the guest moves it, nothing else (a guest copies, deletes nothing). */
    private fun rights(nodeId: String): LevelSelection.Rights =
        if (GuestMove.moves(access, nodeId)) LevelSelection.Rights(move = true, copy = false, report = false, delete = false) else LevelSelection.Rights.NONE

    /** Row [nodeId]'s menu ([LevelRow]'s order): Move where the guest moves it; empty otherwise. */
    fun rowPlan(nodeId: String): LevelRow.Plan {
        val menu = ArrayList<HeaderAction>()
        val last = ArrayList<HeaderAction>()
        LevelRow.planInto(if (GuestMove.moves(access, nodeId)) listOf(HeaderAction.MOVE) else emptyList(), emptyList(), menu, last)
        return LevelRow.Plan(menu, last)
    }

    /** The level's tools: Select where some row moves ([LevelSelection.offered]); none otherwise. */
    val tools: List<LevelTool> get() = if (LevelSelection.offered(rowIds.map(::rights))) listOf(LevelTool.SELECT) else emptyList()

    private val _selection = MutableStateFlow<LevelSelection.Picking?>(null)

    /** The level's selection while its Select mode is on; null otherwise. */
    val selection: StateFlow<LevelSelection.Picking?> = _selection.asStateFlow()

    private fun pick(selected: Set<String>) {
        val ids = rowIds
        _selection.value = LevelSelection.Picking.of(node.nodeId, ids.associateWith(::rights), selected, ids)
    }

    /** Select on: nothing selected yet; nothing where the level offers no Select. */
    fun selectStart() {
        if (_selection.value == null && LevelTool.SELECT in tools) pick(emptySet())
    }

    /** Select off (*Cancel*): the selection cleared. */
    fun selectCancel() {
        _selection.value = null
    }

    /** Row [nodeId] turned. */
    fun selectToggle(nodeId: String) {
        val s = _selection.value ?: return
        pick(LevelSelection.toggle(s.selected, nodeId))
    }

    /** *All*: every row of the level. */
    fun selectAll() {
        val s = _selection.value ?: return
        pick(LevelSelection.all(s.selected, rowIds))
    }

    /** *None*. */
    fun selectNone() {
        val s = _selection.value ?: return
        pick(LevelSelection.none(s.selected, rowIds))
    }

    private fun at(n: OpenNode) = NodeAt(n.scopeId, n.id, n.kind, n.name)

    /** Row [nodeId]'s *Move to…*: the member's sheet over the guest's door; null where the guest does not move it. */
    fun sheet(nodeId: String): MoveSheet? {
        if (!GuestMove.moves(access, nodeId)) return null
        val n = tree.node(nodeId) ?: return null
        val door = view?.let(GuestMove::door) ?: return null
        return MoveSheet(at(n), false, door)
    }

    /** The selection's *Move to…*: one sheet over every selected row that moves, in the level's order; null where none does. */
    fun selectionSheet(): MoveSheet? {
        val s = _selection.value ?: return null
        val nodes = LevelSelection.reached(SelectionAction.MOVE, tree.children(node.nodeId), { it.id }, s.selected) { rights(it.id) }
        if (nodes.isEmpty()) return null
        val door = view?.let(GuestMove::door) ?: return null
        return MoveSheet(nodes.map(::at), false, door)
    }
}
