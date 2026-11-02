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
import me.raddatz.veyq.core.generated.model.ScopeRekeyGroupName
import me.raddatz.veyq.core.generated.model.ScopeRekeyIcon
import me.raddatz.veyq.core.generated.model.ScopeRekeyMember
import me.raddatz.veyq.core.generated.model.ScopeRekeyParts
import me.raddatz.veyq.core.generated.model.ScopeRekeyRewrap
import me.raddatz.veyq.core.generated.model.ScopeRekeyRootNode
import me.raddatz.veyq.core.generated.model.ScopeRekeyShare
import me.raddatz.veyq.core.generated.model.ScopeRekeyStage as ScopeRekeyStageRequest
import me.raddatz.veyq.core.generated.model.ScopeRekeyVerification
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
        "Authenticated, membership-gated re-key of one scope whose current grant the caller holds. Its volume is bounded by the " +
        "scope's own nodes, each staged at the contracts' caps; no anonymous surface and no external cost.",
)
class RekeyResource(
    private val handler: RekeyHandler,
    private val scopeRekey: ScopeRekeyHandler,
    private val identity: SecurityIdentity,
) : RekeyApi {

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
                            wrappedRecoveryValueKey = it.wrappedRecoveryValueKey,
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
        // A link named key-lost gets no key from this re-key: a wrap or a drop public key beside the name is malformed.
        if (nodeRekeyStage.shares.orEmpty().any { it.lostCopyDigest != null && (it.wrap != null || it.sealedDropPublicKey != null) }) {
            return refused(RekeyOutcome.InvalidRequest)
        }
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
                s.lostCopyDigest?.let { put(Key(s.shareId, RekeyStore.SHARE_LOST), Value(it, null)) }
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
            scopeEpoch = r.scopeEpoch.validated(),
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

    override fun listScopeRekeyParts(scopeId: UUID, since: String?, limit: Int?): Response =
        when (val o = scopeRekey.parts(identity.principal.name, scopeId.toString(), since, limit ?: SCOPE_PAGE)) {
            is RekeyOutcome.ScopeParts -> Response.ok(o.page.toWire()).build()
            else -> refused(o)
        }

    override fun stageScopeRekey(scopeId: UUID, rekeyId: UUID, scopeRekeyStage: ScopeRekeyStageRequest): Response {
        val r = scopeRekeyStage
        // Each wrap beside the wrap it replaces (ADR 0115 decision 2), which the commit compares with its row; a root
        // entry is held by its placement revision instead. No field here maps to a value's ciphertext: a rotation writes
        // only wraps.
        val values = buildMap {
            fun wrap(itemId: String, field: String, wrapped: String, replaced: String) =
                put(ScopeRekeyStage.Key(itemId, field), ScopeRekeyStage.Value(wrapped, replaced = replaced))
            fun wrap(itemId: String, field: String, w: ScopeRekeyRewrap?) = w?.let { wrap(itemId, field, it.wrappedValueKey, it.replacedValueKey) }
            wrap(ScopeRekeyCommit.GROUP_ITEM, ScopeRekeyCommit.GROUP_NAME_WRAP, r.groupName)
            wrap(ScopeRekeyCommit.ICON_ITEM, ScopeRekeyCommit.ICON_WRAP, r.icon)
            r.rootNodes.orEmpty().forEach { n ->
                val id = n.nodeId.toString()
                val revision = n.placementRevision.validated()
                fun entry(field: String, value: String) = put(ScopeRekeyStage.Key(id, field), ScopeRekeyStage.Value(value, revision = revision))
                entry(ScopeRekeyCommit.ROOT_WRAP, n.wrappedContentKey)
                entry(ScopeRekeyCommit.ROOT_NAME, n.encryptedName)
                n.slugToken?.let { entry(ScopeRekeyCommit.ROOT_SLUG, it) }
            }
            r.shares.orEmpty().forEach { wrap(it.shareId, ScopeRekeyCommit.SHARE_WRAP, it.wrappedValueKey, it.replacedValueKey) }
            r.members.orEmpty().forEach { m ->
                val id = m.userId.toString()
                wrap(id, ScopeRekeyCommit.MEMBER_NAME_WRAP, m.name)
                wrap(id, ScopeRekeyCommit.MEMBER_AVATAR_WRAP, m.avatar)
                wrap(id, ScopeRekeyCommit.MEMBER_JOIN_WRAP, m.joinRecord)
            }
            r.verifications.orEmpty().forEach { v ->
                wrap("${v.verifierUserId}|${v.subjectUserId}", ScopeRekeyCommit.VERIFICATION_WRAP, v.wrappedValueKey, v.replacedValueKey)
            }
        }
        return when (val o = scopeRekey.stage(
            identity.principal.name, scopeId.toString(), rekeyId.toString(), r.fromEpoch.validated(), ScopeRekeyStage.CORE, values,
        )) {
            RekeyOutcome.Staged -> Response.noContent().build()
            else -> refused(o)
        }
    }

    private fun ScopeRekeyPage.toWire() = ScopeRekeyParts(
        currentEpoch = currentEpoch,
        groupName = groupName?.let { (wrap, epoch) -> ScopeRekeyGroupName(nameEpoch = epoch, wrappedNameValueKey = wrap) },
        icon = icon?.let { (wrap, epoch) -> ScopeRekeyIcon(iconEpoch = epoch, wrappedIconValueKey = wrap) },
        rootNodes = rootNodes.map {
            ScopeRekeyRootNode(
                nodeId = UUID.fromString(it.id), trashed = it.deletedAt != null, placementRevision = it.placementRevision,
                scopeEpoch = it.scopeEpoch, encryptedName = it.encryptedName, wrappedContentKey = it.wrappedContentKey,
            )
        },
        shares = shares.map {
            ScopeRekeyShare(
                shareId = it.id, nodeId = UUID.fromString(it.nodeId), sealedShareKeyEpoch = it.sealedShareKeyEpoch ?: 0,
                wrappedRecoveryValueKey = it.wrappedRecoveryValueKey!!,
            )
        },
        members = members.map {
            ScopeRekeyMember(
                userId = UUID.fromString(it.userId), wrappedNameValueKey = it.wrappedNameValueKey,
                wrappedAvatarValueKey = it.wrappedAvatarValueKey, wrappedJoinRecordValueKey = it.wrappedJoinRecordValueKey,
            )
        },
        verifications = verifications.map {
            ScopeRekeyVerification(
                verifierUserId = UUID.fromString(it.verifierUserId), subjectUserId = UUID.fromString(it.subjectUserId),
                epoch = it.epoch, wrappedValueKey = it.wrappedValueKey,
            )
        },
        more = more,
        cursor = cursor,
    )

    private fun refused(o: RekeyOutcome): Response = when (o) {
        is RekeyOutcome.StaleMeta -> httpStaleSealedMeta(o, o.wrappedMeta, o.metaRevision)
        is HttpFailure -> httpError(o)
        else -> error("not a refusal: $o")
    }

    private companion object {
        /** `listScopeRekeyParts`' default page, the stage's cap (the contract's `ScopeRekeyLimit`). */
        const val SCOPE_PAGE = 256
    }

    private fun RekeyNodeRow.toWire(scopeId: String) = RekeyNode(
        id = UUID.fromString(node.id),
        kind = node.kind,
        trashed = trashed,
        ownerOnly = ownerOnly,
        epoch = node.epoch,
        scopeEpoch = node.scopeEpoch,
        due = node.rekeyDue,
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
