package me.raddatz.veyq.core.testing

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The one home of the backend's seeded test data (developer-styleguide §10: *test data comes from one fixture
 * per surface, not a local copy*). Every slice test seeds users, tenants, member scopes and nodes through here,
 * and mints its `Bearer` header here, so a new `NOT NULL` column costs one edit rather than one per test file.
 * A test keeps a local helper only for data no other test needs.
 *
 * A plain object, not a CDI bean: every `seed*` persists through static Panache calls inside its own
 * `QuarkusTransaction.requiringNew()`, so it works from a test method, a `@BeforeEach` and a companion object alike.
 * [bearer] is the one helper that needs a bean, and resolves it through `Arc.container()`.
 *
 * `scripts/validate_test_fixture_single_home.py` keeps [VeyqUser] construction in this file.
 */
object TestFixtures {

    /** The default key blobs of a seeded account: opaque base64 the server stores and never interprets. */
    const val IDENTITY_PUBLIC_KEY = "aWRlbnRpdHktcHVibGljLWtleQ=="
    const val WRAPPED_IDENTITY_PRIVATE_KEY = "aWRlbnRpdHktcHJpdg=="
    const val AUTH_PUBLIC_KEY = "YXV0aC1wdWJsaWMta2V5"

    /** The opaque wrapped grant a seeded membership carries. */
    const val GRANT = "Z3JhbnQ="

    /** The opaque wrapped content key (`K_node`) a seeded Memory or Chapter carries. */
    const val CONTENT_KEY = "a21lbQ=="

    /**
     * An account row, **not yet persisted** — for a test that writes it inside its own transaction beside the
     * account's other rows (its key wraps, say). Everyone else calls [seedUser].
     */
    fun newUser(
        id: String = UUID.randomUUID().toString(),
        email: String = "u-$id@example.com",
        serverRole: String = "MEMBER",
        identityPublicKey: String = IDENTITY_PUBLIC_KEY,
        wrappedIdentityPrivateKey: String = WRAPPED_IDENTITY_PRIVATE_KEY,
        authPublicKey: String = AUTH_PUBLIC_KEY,
        secretKeyProofPublicKey: String? = null,
        requireGroupAddConsent: Boolean = false,
    ): VeyqUser = VeyqUser().apply {
        this.id = id
        this.email = email
        this.serverRole = serverRole
        suiteId = "v1"
        this.identityPublicKey = identityPublicKey
        this.wrappedIdentityPrivateKey = wrappedIdentityPrivateKey
        this.authPublicKey = authPublicKey
        this.secretKeyProofPublicKey = secretKeyProofPublicKey
        this.requireGroupAddConsent = requireGroupAddConsent
    }

    /** Persist an account in its own transaction and return its id. */
    fun seedUser(
        id: String = UUID.randomUUID().toString(),
        email: String = "u-$id@example.com",
        serverRole: String = "MEMBER",
        identityPublicKey: String = IDENTITY_PUBLIC_KEY,
        wrappedIdentityPrivateKey: String = WRAPPED_IDENTITY_PRIVATE_KEY,
        authPublicKey: String = AUTH_PUBLIC_KEY,
        secretKeyProofPublicKey: String? = null,
        requireGroupAddConsent: Boolean = false,
    ): String {
        QuarkusTransaction.requiringNew().run {
            newUser(
                id, email, serverRole, identityPublicKey, wrappedIdentityPrivateKey, authPublicKey,
                secretKeyProofPublicKey, requireGroupAddConsent,
            ).persist()
        }
        return id
    }

    /**
     * Persist the account [id] unless it already exists — for a class that seeds fixed ids in a `@BeforeEach`.
     * The `%test` H2 database lives as long as the JVM, so a fixed id must be distinct per class; an existing row
     * is kept as it is.
     */
    fun ensureUser(id: String, email: String, serverRole: String) {
        QuarkusTransaction.requiringNew().run {
            if (VeyqUser.findById(id) == null) newUser(id, email, serverRole).persist()
        }
    }

    /**
     * Persist a tenant and return its id.
     * - [tenantScope] adds its root `TENANT` scope (`id == tenantId`); a tenant that is not active yet has none.
     * - [owner] makes that user the root scope's accepted `OWNER`, so it needs the scope.
     */
    fun seedTenant(
        id: String = UUID.randomUUID().toString(),
        domain: String = "t-$id.example.com",
        ownerEmail: String = "o-$id@example.com",
        status: String = TenantEntity.ACTIVE,
        tenantScope: Boolean = true,
        owner: String? = null,
    ): String {
        require(owner == null || tenantScope) { "an owner is a member of the tenant scope" }
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                this.id = id; name = "Fam"; this.domain = domain; this.ownerEmail = ownerEmail
                this.status = status
            }.persist()
            if (tenantScope) {
                ScopeEntity().apply {
                    this.id = id; tenantId = id; kind = ScopeEntity.TENANT
                }.persist()
            }
            if (owner != null) persistMembership(owner, id, id, MembershipEntity.OWNER)
        }
        return id
    }

    /** A seeded member's space: the user, the scope it owns, and the tenant both belong to. */
    data class MemberScope(val userId: String, val scopeId: String, val tenantId: String)

    /**
     * A fresh tenant with one scope of [scopeKind] that [userId] owns. The user is also an accepted member of the
     * tenant root ([TenantRoots.join]), which the scope-access gate requires of every writer.
     */
    fun seedMemberScope(
        scopeKind: String = ScopeEntity.PERSONAL,
        userId: String = seedUser(),
    ): MemberScope {
        val tenantId = seedTenant(tenantScope = false)
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = scopeKind
            }.persist()
            persistMembership(userId, tenantId, scopeId, MembershipEntity.OWNER)
            TenantRoots.join(userId, tenantId)
        }
        return MemberScope(userId, scopeId, tenantId)
    }

    /**
     * Persist a node and return its id. [kind] has no default on purpose: core knows no kind but `FOLDER`, and a
     * default `MEMORY` here would make core's fixtures name an application's kind (ADR 0079) — so each call site says
     * which kind it seeds. Every node carries a content key (ADR 0080 Decision 1, the column is `NOT NULL`), so
     * [wrappedContentKey] is never null; it has no default so a test that reads a wrap back says which one it seeded
     * ([CONTENT_KEY] for most). Seeded, not created: no `NodeKindRules` is asked.
     */
    fun seedNode(
        scopeId: String,
        kind: String,
        parentId: String = scopeId,
        wrappedContentKey: String,
        encryptedName: String = "bg==",
    ): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; this.parentId = parentId; this.kind = kind
                this.encryptedName = encryptedName; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                this.wrappedContentKey = wrappedContentKey; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** What [seedAttachment] persisted: the attachment row, its blob row, and the storage key the blob's chunks live under. */
    data class SeededAttachment(val attachmentId: String, val blobId: String, val storageKey: String)

    /**
     * Persist a blob in [scopeId] and attach it to [nodeId] in [slot] (core's `veyq_node_attachment`, cr-files-application
     * P3), uploaded by the guest [shareIdentityId] or, when that is null, by the member [uploaderUserId] (a fresh id by
     * default: `ck_attachment_uploader` holds exactly one uploader). [slot] has no default for the reason [seedNode]'s
     * kind has none: the slot is the owning application's token, and core's fixtures name none. Seeded, not uploaded:
     * the rows are written directly.
     */
    fun seedAttachment(
        scopeId: String,
        nodeId: String,
        slot: String,
        shareIdentityId: String? = null,
        uploaderUserId: String = UUID.randomUUID().toString(),
    ): SeededAttachment {
        val seeded = SeededAttachment(
            attachmentId = UUID.randomUUID().toString(),
            blobId = UUID.randomUUID().toString(),
            storageKey = UUID.randomUUID().toString().replace("-", ""),
        )
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = seeded.blobId; this.scopeId = scopeId; storageKey = seeded.storageKey
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize
                wrappedBlobKey = "YXR0YWNobWVudA=="
            }.persist()
            NodeAttachmentEntity().apply {
                id = seeded.attachmentId; this.scopeId = scopeId; this.nodeId = nodeId; this.slot = slot
                blobId = seeded.blobId; this.shareIdentityId = shareIdentityId
                this.uploaderUserId = if (shareIdentityId == null) uploaderUserId else null
            }.persist()
        }
        return seeded
    }

    /**
     * A complete placement write (ADR 0080 Decision 3) to [parentId] (null = the scope root), built on
     * [placementVersion] and sealed at [epoch]: a fresh slug token, so two placements never collide unless a test
     * says so, and opaque name and wrap bytes — the server opens neither.
     */
    fun placementTo(
        parentId: String?,
        placementVersion: Long = 0,
        epoch: Int = 0,
        encryptedName: String = "cGxhY2Vk",
        wrappedContentKey: String = "cmUtd3JhcHBlZA==",
        slugToken: String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray()),
    ): NodesHandler.PlacementRequest = NodesHandler.PlacementRequest(
        parentId = parentId, encryptedName = encryptedName, slugToken = slugToken, wrappedContentKey = wrappedContentKey,
        epoch = epoch, placementVersion = placementVersion,
    )

    /** The opaque sealed drop keypair halves and a wrapped WRITE_ONLY identity key (ADR 0031 point 7). */
    const val DROP_PUBLIC_KEY = "c2VhbGVkLWRyb3AtcHVibGljLWtleQ=="
    const val DROP_PRIVATE_KEY = "c2VhbGVkLWRyb3AtcHJpdmF0ZS1rZXk="
    const val WRAPPED_UPLOADER_KEY = "d3JhcHBlZC11cGxvYWRlci1rZXk="

    /**
     * The key fields of a `createShare` body for [role], as JSON members (ADR 0031 point 7): a READ or READ_WRITE
     * share carries [wrap]; a WRITE_ONLY share carries the drop public key instead, plus the drop private key while
     * the Memory [nodeId] has none yet — exactly what the owner's client sends.
     */
    fun shareKeysJson(role: String, nodeId: String, wrap: String = "d3JhcA=="): String {
        if (role != "WRITE_ONLY") return """"wrap":"$wrap""""
        val hasDropKey = QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)?.sealedDropPrivateKey != null }
        return """"sealedDropPublicKey":"$DROP_PUBLIC_KEY"""" +
            if (hasDropKey) "" else ""","sealedDropPrivateKey":"$DROP_PRIVATE_KEY""""
    }

    /** The key field of a `createShareIdentity` body for a share of [role], as a leading-comma JSON member (or ""). */
    fun identityKeyJson(role: String): String =
        if (role == "WRITE_ONLY") ""","wrappedIdentityKey":"$WRAPPED_UPLOADER_KEY"""" else ""

    /** A fresh session's `Authorization` header value for [userId]. */
    fun bearer(userId: String): String =
        "Bearer ${Arc.container().instance(SessionService::class.java).get().issue(userId).accessToken}"

    private fun persistMembership(userId: String, tenantId: String, scopeId: String, role: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
            this.scopeId = scopeId; this.role = role; wrappedGrant = GRANT; createdAt = Instant.now()
        }.persist()
    }
}
