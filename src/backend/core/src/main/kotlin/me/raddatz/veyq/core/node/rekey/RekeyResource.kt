package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.generated.api.RekeyApi
import me.raddatz.veyq.core.generated.model.CommitNodeRekeyRequest
import me.raddatz.veyq.core.generated.model.NodeRekeyParts
import me.raddatz.veyq.core.generated.model.NodeRekeyStage as NodeRekeyStageRequest
import me.raddatz.veyq.core.generated.model.RekeyBlob
import me.raddatz.veyq.core.generated.model.RekeyIdentity
import me.raddatz.veyq.core.generated.model.RekeyNode
import me.raddatz.veyq.core.generated.model.RekeyNodeList
import me.raddatz.veyq.core.generated.model.RekeyShare
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpStaleSealedMeta
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Value
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for a scope's re-key (ADR 0109): implements the generated [RekeyApi], thin (ADR 0001), delegating to
 * [RekeyHandler]. The scope root's parent slot is the scope id; the wire names it `parentId = null`, as everywhere.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated, membership- and owner-gated re-key of one scope the caller owns. Its volume is bounded by the " +
        "scope's own nodes, each staged at the contracts' caps; no anonymous surface and no external cost.",
)
class RekeyResource(private val handler: RekeyHandler, private val identity: SecurityIdentity) : RekeyApi {

    override fun listRekeyNodes(scopeId: UUID): Response {
        val sid = scopeId.toString()
        return when (val o = handler.list(identity.principal.name, sid)) {
            is RekeyOutcome.Nodes -> Response.ok(RekeyNodeList(o.currentEpoch, o.rows.map { it.toWire(sid) })).build()
            else -> refused(o)
        }
    }

    override fun getNodeRekeyParts(scopeId: UUID, nodeId: UUID): Response =
        when (val o = handler.parts(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            is RekeyOutcome.Parts -> Response.ok(
                NodeRekeyParts(
                    blobs = o.parts.blobs.map { RekeyBlob(UUID.fromString(it.blobId), it.role, it.wrappedBlobKey) },
                    shares = o.parts.shares.map {
                        RekeyShare(
                            shareId = it.shareId, role = RekeyShare.Role.valueOf(it.role), sealedShareKey = it.sealedShareKey,
                            hasWrap = it.hasWrap, hasDropPublicKey = it.hasDropPublicKey, sealedShareKeyEpoch = it.sealedShareKeyEpoch,
                        )
                    },
                    identities = o.parts.identities.map {
                        RekeyIdentity(UUID.fromString(it.identityId), it.shareId, it.wrappedIdentityKey, it.sealedDisplayName)
                    },
                ),
            ).build()
            else -> refused(o)
        }

    override fun stageNodeRekey(scopeId: UUID, nodeId: UUID, rekeyId: UUID, nodeRekeyStage: NodeRekeyStageRequest): Response {
        val values = buildMap {
            nodeRekeyStage.children.orEmpty().forEach { c ->
                val id = c.nodeId.toString()
                val revision = c.placementRevision.validated()
                put(Key(id, RekeyStore.CHILD_NAME), Value(c.encryptedName, revision))
                put(Key(id, RekeyStore.CHILD_SLUG), Value(c.slugToken, revision))
                put(Key(id, RekeyStore.CHILD_WRAP), Value(c.wrappedContentKey, revision))
            }
            nodeRekeyStage.blobs.orEmpty().forEach { put(Key(it.blobId.toString(), RekeyStore.BLOB_WRAP), Value(it.wrappedBlobKey, null)) }
            nodeRekeyStage.shares.orEmpty().forEach { s ->
                s.wrap?.let { put(Key(s.shareId, RekeyStore.SHARE_WRAP), Value(it, null)) }
                s.sealedDropPublicKey?.let { put(Key(s.shareId, RekeyStore.SHARE_DROP), Value(it, null)) }
            }
            nodeRekeyStage.identities.orEmpty().forEach { i ->
                val id = i.identityId.toString()
                i.wrappedIdentityKey?.let { put(Key(id, RekeyStore.IDENTITY_KEY), Value(it, null)) }
                i.sealedDisplayName?.let { put(Key(id, RekeyStore.IDENTITY_NAME), Value(it, null)) }
            }
        }
        return when (val o = handler.stage(
            identity.principal.name, scopeId.toString(), nodeId.toString(), rekeyId.toString(),
            nodeRekeyStage.fromEpoch.validated(), values,
        )) {
            RekeyOutcome.Staged -> Response.noContent().build()
            else -> refused(o)
        }
    }

    override fun commitNodeRekey(scopeId: UUID, nodeId: UUID, rekeyId: UUID, commitNodeRekeyRequest: CommitNodeRekeyRequest): Response {
        val r = commitNodeRekeyRequest
        val commit = RekeyCommit(
            nodeEpoch = r.nodeEpoch.validated(),
            epoch = r.epoch.validated(),
            parentId = r.parentId?.toString(),
            parentEpoch = r.parentEpoch,
            placementRevision = r.placementRevision.validated(),
            metaRevision = r.metaRevision.validated(),
            encryptedName = r.encryptedName,
            slugToken = r.slugToken,
            wrappedContentKey = r.wrappedContentKey,
            wrappedMeta = r.wrappedMeta,
            sealedDropPrivateKey = r.sealedDropPrivateKey,
        )
        return when (val o = handler.commit(identity.principal.name, scopeId.toString(), nodeId.toString(), rekeyId.toString(), commit)) {
            RekeyOutcome.Committed -> Response.noContent().build()
            else -> refused(o)
        }
    }

    private fun refused(o: RekeyOutcome): Response = when (o) {
        is RekeyOutcome.StaleMeta -> httpStaleSealedMeta(o, o.wrappedMeta, o.metaRevision)
        is HttpFailure -> httpError(o)
        else -> error("not a refusal: $o")
    }

    private fun RekeyNodeRow.toWire(scopeId: String) = RekeyNode(
        id = UUID.fromString(node.id),
        kind = node.kind,
        trashed = trashed,
        epoch = node.epoch,
        placementRevision = node.placementRevision,
        metaRevision = node.metaRevision,
        encryptedName = node.encryptedName,
        slugToken = node.slugToken,
        wrappedContentKey = node.wrappedContentKey,
        parentId = node.parentId.takeIf { it != scopeId }?.let(UUID::fromString),
        wrappedMeta = node.wrappedMeta,
        sealedDropPrivateKey = node.sealedDropPrivateKey,
        shareIdentityId = node.shareIdentityId?.let(UUID::fromString),
        wrappedIdentityKey = wrappedIdentityKey,
    )
}
