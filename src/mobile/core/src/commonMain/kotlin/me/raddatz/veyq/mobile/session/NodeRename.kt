package me.raddatz.veyq.mobile.session


/**
 * A node renamed, core's for every kind (the web's `renameMemberNode`, `core/node/nodeName.ts`, and `placeNode` in
 * `tree.ts`, ADR 0080 decision 3): a placement to the parent it is under — its own key re-wrapped and its name sealed
 * under the parent's key ([NodePlacement], the write a move sends too). Then the name is sealed anew into the node's open links
 * ([ShareNames]); a link that fails there is said, the rename stands.
 */
object NodeRename {
    enum class Outcome {
        RENAMED,
        /** A sibling already folds to this name — before sending (the loaded tree), or as the server answers any `409`. */
        NAME_TAKEN,
        /** Not allowed here (`422`: a structure rule, the application switched off). */
        NOT_ALLOWED,
        /** The parent is not re-keyed since its space's key changed, and still was not after waiting. */
        BUSY,
        FAILED,
    }

    /** What came of it: [taken] is the sibling the name collides with when the loaded tree shows one; [links] the re-seal. */
    class Result(val outcome: Outcome, val taken: OpenNode? = null, val links: ShareNames.Report? = null)

    /**
     * Renames node [nodeId] of [scopeId] to [name] (trimmed; empty or unchanged writes nothing). [tokenInput] folds a name
     * of a kind to its slug token's input, as its application folds it (`registry.tokenInputFor`; null: a kind this
     * client does not know, whose collision only the server sees).
     */
    @Throws(Exception::class)
    suspend fun rename(
        tenant: TenantSession, scopeId: String, nodeId: String, name: String, tokenInput: (kind: String, name: String) -> String?,
        nowMs: Long, waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Result {
        val trimmed = name.trim()
        val tree = tenant.trees()[scopeId].orEmpty()
        val node = tree.firstOrNull { it.id == nodeId } ?: return Result(Outcome.FAILED)
        if (trimmed.isEmpty() || trimmed == node.name) return Result(Outcome.RENAMED)
        val input = tokenInput(node.kind, trimmed) ?: return Result(Outcome.FAILED)
        // The loaded tree's sibling that folds to the same input: said by name before anything is sent (`collidingSibling`).
        tree.firstOrNull { it.parentId == node.parentId && it.id != nodeId && tokenInput(it.kind, it.name) == input }
            ?.let { return Result(Outcome.NAME_TAKEN, taken = it) }
        val ended = NodePlacement.place(
            tenant, scopeId, nodeId, parentOf = { it.parentId }, nameOf = { trimmed }, tokenInput = tokenInput, waitsMs = waitsMs,
        )
        if (ended == NodePlacement.Ended.Placed) {
            val links = runCatching { ShareNames.reseal(tenant, scopeId, nodeId, trimmed, nowMs) }.getOrNull()
            return Result(Outcome.RENAMED, links = links ?: ShareNames.Report(emptyList(), emptyList(), emptyList(), listOf("list")))
        }
        return Result(
            when (ended) {
                NodePlacement.Ended.Busy -> Outcome.BUSY
                // every 409 is a name already taken, whatever its code (`nodeNameErrorText`)
                is NodePlacement.Ended.Refused -> when (ended.status) {
                    409 -> Outcome.NAME_TAKEN
                    422 -> Outcome.NOT_ALLOWED
                    else -> Outcome.FAILED
                }
                else -> Outcome.FAILED
            },
        )
    }

}
