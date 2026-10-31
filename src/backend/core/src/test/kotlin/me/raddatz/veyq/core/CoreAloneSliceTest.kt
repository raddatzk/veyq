package me.raddatz.veyq.core

import me.raddatz.veyq.core.node.rekey.NodeRekeyParticipant
import me.raddatz.veyq.core.node.rekey.NodeRekeyStageNodeSubtreeEraser
import me.raddatz.veyq.core.node.rekey.NodeRekeyStageScopeEraser
import me.raddatz.veyq.core.blob.AttachmentGuestIdentityUsage
import me.raddatz.veyq.core.blob.AttachmentNodeSubtreeEraser
import me.raddatz.veyq.core.items.NodeItemsRevisionNodeSubtreeEraser
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordNodeSubtreeEraser
import me.raddatz.veyq.core.blob.AttachmentScopeEraser
import me.raddatz.veyq.core.blob.AttachmentSharedSubtreeContributor
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.CoreTestMemberBlobRoles
import me.raddatz.veyq.core.blob.MemberBlobRoles
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.node.FolderNodeRules
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.content.CoreMemberBlobRoles
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.favorites.FavoritesScopeEraser
import me.raddatz.veyq.core.node.NodeScopeEraser
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.core.scope.MembershipEraser
import me.raddatz.veyq.core.admin.tenant.VerificationMembershipEraser
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.scope.ScopeEraser
import me.raddatz.veyq.core.sharing.CoreTestGuestCreatableKinds
import me.raddatz.veyq.core.sharing.CoreTestGuestPlaceholders
import me.raddatz.veyq.core.sharing.FolderGuestPlaceholders
import me.raddatz.veyq.core.sharing.GuestCreatableKinds
import me.raddatz.veyq.core.sharing.GuestPlaceholders
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.NodeGuestIdentityUsage
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.sharing.FolderShareRoots
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareRoots
import me.raddatz.veyq.core.sharing.WriteOnlyGuestKinds
import me.raddatz.veyq.core.sharing.ShareScopeEraser
import me.raddatz.veyq.core.sharing.ShareUploadOutcome
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.core.sharing.SharingHandler
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import io.quarkus.arc.All
import io.quarkus.arc.ClientProxy
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **Core alone** (ADR 0079): `:core`'s test application is the platform with no application in it — `:core` has no
 * project edge to one — so this class asks what the platform does when nothing has claimed a node kind.
 *
 * **Why it is worth a test.** The compiler already refuses a core file that imports an application. What it cannot see
 * is a **runtime** coupling: a core bean injecting an interface only an application implements (the boot fails), a
 * config key only an application defines (the boot fails), or a seam whose empty collection fails *open* — admits a
 * link, places a kind, completes an upload — instead of refusing. So:
 *
 * 1. **Every seam holds exactly core's own implementations**: `ScopeEraser` core's four (attachments, pins, nodes, shares),
 *    `NodeKindRules`, `ShareRoots` and `GuestPlaceholders` core's `FOLDER` answers, `NodeSubtreeEraser`, `GuestIdentityUsage` and
 *    `SharedSubtreeContributor` core's attachment one each (cr-files-application P3-S3), `MembershipEraser` core's tenant
 *    verifications (ADR 0111 decision 12), and every other seam — `ShareGuestWrites`, `UploadCompletion`, `UploadAdmission`, `WriteOnlyGuestKinds`,
 *    `GuestCreatableKinds`, `MemberBlobRoles` — none. The composed application's
 *    sets are the root's `ModuleCompositionTest`. (`GuestIdentityUsage` is therefore never empty: its fail-closed
 *    branch for no usage at all is `GuestIdentityUsageTest`'s, boot-free, and no longer reachable here.)
 * 2. **Every seam fails closed with nobody behind it**: a `FOLDER` is created and a `MEMORY` is `illegal_structure`;
 *    a share of any other kind, or a `WRITE_ONLY` one on a `FOLDER`, is `illegal_scope`; a completion is
 *    `illegal_target` for every kind; a link start on a `READ_WRITE` folder share is refused
 *    (`share_not_contributable`: no application takes uploads into a folder, and core alone none into any kind),
 *    no kind has an admission, and a member's upload start is `400 invalid_request` in every role (inbox #366: no
 *    application declares one, and nothing could attach it).
 * 3. **What core owns works alone**: an open upload session windows, and a seeded blob downloads, through core's
 *    `BlobResource`; a `FOLDER` is shared with `READ` or `READ_WRITE` and its guest resolves the subtree under it
 *    (ADR 0080); an unauthenticated core route answers.
 *
 * Each refusal sits beside a positive control through the same path; where core alone has nothing to accept, the
 * control is the composition's (`ModuleCompositionTest` shares a `MEMORY` and completes one through the same bodies).
 * [CoreAloneProfile]: the in-memory store cannot presign, and a window is nothing but presigned URLs; and `:core`'s
 * test sources hold one application stand-in, [CoreTestMemberBlobRoles], which this class alone excludes.
 */
@QuarkusTest
@TestProfile(CoreAloneSliceTest.CoreAloneProfile::class)
class CoreAloneSliceTest {

    /** `FakePresignProfile`'s store, without the member roles `:core`'s other tests declare ([CoreTestMemberBlobRoles]). */
    class CoreAloneProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakePresigningBlobStore::class.java)
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("quarkus.arc.exclude-types" to listOf(CoreTestMemberBlobRoles::class, CoreTestGuestCreatableKinds::class, CoreTestGuestPlaceholders::class).joinToString(",") { it.java.name })
    }

    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @Inject @All lateinit var scopeErasers: MutableList<ScopeEraser>
    @Inject @All lateinit var subtreeErasers: MutableList<NodeSubtreeEraser>
    @Inject @All lateinit var membershipErasers: MutableList<MembershipEraser>
    @Inject @All lateinit var identityUsages: MutableList<GuestIdentityUsage>
    @Inject @All lateinit var contributors: MutableList<SharedSubtreeContributor>
    @Inject @All lateinit var guestWrites: MutableList<ShareGuestWrites>
    @Inject @All lateinit var completions: MutableList<UploadCompletion>
    @Inject @All lateinit var admissions: MutableList<UploadAdmission>
    @Inject @All lateinit var kindRules: MutableList<NodeKindRules>
    @Inject @All lateinit var shareRoots: MutableList<ShareRoots>
    @Inject @All lateinit var writeOnlyGuestKinds: MutableList<WriteOnlyGuestKinds>
    @Inject @All lateinit var guestCreatableKinds: MutableList<GuestCreatableKinds>
    @Inject @All lateinit var guestPlaceholders: MutableList<GuestPlaceholders>
    @Inject @All lateinit var memberRoles: MutableList<MemberBlobRoles>
    @Inject @All lateinit var rekeyParticipants: MutableList<NodeRekeyParticipant>

    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var dispatcher: UploadCompletions
    @Inject lateinit var quota: StorageQuota
    @Inject lateinit var sharing: SharingHandler
    @Inject lateinit var sharingStore: SharingStore
    @Inject lateinit var uploadStore: BlobUploadStore

    /** The kinds a client can send: core's own, the two Memories owns (as the wire spells them), and a forged one. */
    private val kinds = listOf(NodeEntity.FOLDER, "MEMORY", "CHAPTER", "NOT_A_KIND")

    /** The bean class behind what ArC handed out (a client proxy unwrapped, an intercepted bean's `_Subclass` too). */
    private fun beanClass(bean: Any): Class<*> {
        var cls: Class<*> = ClientProxy.unwrap(bean).javaClass
        while (cls.name.endsWith("_Subclass")) cls = cls.superclass
        return cls
    }

    private fun classes(beans: List<Any>): Set<Class<*>> = beans.map(::beanClass).toSet()

    @Test
    fun everySeamHoldsExactlyCoresOwnImplementations() {
        assertEquals(
            setOf(
                AttachmentScopeEraser::class.java, FavoritesScopeEraser::class.java, NodeScopeEraser::class.java, ShareScopeEraser::class.java,
                NodeRekeyStageScopeEraser::class.java,
            ),
            classes(scopeErasers), "ScopeEraser: core's own five, and no application's",
        )
        assertEquals(
            setOf(
                AttachmentNodeSubtreeEraser::class.java, NodeItemsRevisionNodeSubtreeEraser::class.java, GuestWriteRecordNodeSubtreeEraser::class.java,
                NodeRekeyStageNodeSubtreeEraser::class.java,
            ),
            classes(subtreeErasers),
            "NodeSubtreeEraser: core's attachments, item-list counters, guest-write records and re-key stages only",
        )
        assertEquals(setOf(AttachmentGuestIdentityUsage::class.java, NodeGuestIdentityUsage::class.java), classes(identityUsages),
            "GuestIdentityUsage: core's attachments and identity folders only")
        assertEquals(setOf(AttachmentSharedSubtreeContributor::class.java), classes(contributors),
            "SharedSubtreeContributor: core's attachments only")
        assertEquals(setOf(FolderNodeRules::class.java), classes(kindRules), "NodeKindRules: core's FOLDER rule only")
        assertEquals(setOf(FolderShareRoots::class.java), classes(shareRoots), "ShareRoots: core's FOLDER answer only")
        assertEquals(setOf(FolderGuestPlaceholders::class.java), classes(guestPlaceholders), "GuestPlaceholders: core's FOLDER answer only")
        assertEquals(setOf(VerificationMembershipEraser::class.java), classes(membershipErasers),
            "MembershipEraser: core's tenant verifications only (ADR 0111 decision 12)")
        for ((seam, beans) in mapOf(
            "NodeRekeyParticipant" to rekeyParticipants,
            "ShareGuestWrites" to guestWrites, "UploadCompletion" to completions, "UploadAdmission" to admissions,
            "WriteOnlyGuestKinds" to writeOnlyGuestKinds, "GuestCreatableKinds" to guestCreatableKinds,
        )) {
            assertEquals(emptySet<Class<*>>(), classes(beans), "$seam: core implements none, and no application is here")
        }
        // core's one role is its own cover (inbox #411), declared by core and by no application
        assertEquals(setOf(CoreMemberBlobRoles::class.java), classes(memberRoles), "MemberBlobRoles: core's COVER only")
    }

    @Test
    fun onlyAFolderTakesACoverWithCoreAlone() {
        assertTrue(NodeKindRules.takesCover(kindRules, NodeEntity.FOLDER), "core's own kind takes a cover")
        for (kind in listOf("MEMORY", "CHAPTER", "FILE", "DEPOT_DROPBOX", "NOT_A_KIND")) {
            assertFalse(NodeKindRules.takesCover(kindRules, kind), "$kind: no rule here opts it in (fail closed)")
        }
    }

    @Test
    fun aFolderIsPlacedAndAnApplicationKindIsNot() {
        val (userId, scopeId) = seedMemberScope()
        // control: the platform's own kind lands at the root
        createNode(userId, scopeId, NodeEntity.FOLDER).statusCode(201)
        createNode(userId, scopeId, "MEMORY").statusCode(422).body("code", equalTo("illegal_structure"))
    }

    /** Owner decision 2026-10-14: with no application declaring a kind ([GuestCreatableKinds]), a folder's `READ_WRITE`
     *  guest creates folders only — every other kind, the applications' as the wire spells them, is `illegal_scope`. */
    @Test
    fun aFolderGuestCreatesFoldersOnly() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, folder)
        for (kind in kinds - NodeEntity.FOLDER + listOf("FILE", "DEPOT_DROPBOX")) {
            GuestTreeShare.create(link, folder, kind).statusCode(422).body("code", equalTo("illegal_scope"))
        }
        GuestTreeShare.create(link, folder, NodeEntity.FOLDER).statusCode(201) // control
    }

    @Test
    fun aFolderIsSharedToReadOrContributeAndNothingElseIs() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        // ADR 0080 decision 6 / uc.sharing.share-public-viewer:folder-share-root: a FOLDER is a share root although no
        // application writes under it
        for (role in listOf("READ", "READ_WRITE")) {
            val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            postShare(userId, folder, role).statusCode(201)
        }
        // decision 11: a drop box is a node of its own, never an existing folder
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        postShare(userId, folder, "WRITE_ONLY").statusCode(422).body("code", equalTo("illegal_scope"))
        // every kind core does not own: nobody here declares it, so nobody shares it
        for (kind in kinds - NodeEntity.FOLDER) {
            val node = seedNode(scopeId, kind, wrappedContentKey = CONTENT_KEY)
            postShare(userId, node, "READ").statusCode(422).body("code", equalTo("illegal_scope"))
        }
    }

    /**
     * ADR 0080 decisions 2, 6 and 9 (uc.sharing.share-public-viewer:folder-share-root): a guest of a `FOLDER` share
     * resolves the live subtree under it — `rootKind` FOLDER, every descendant's name and wrap as stored (the guest
     * opens them down the chain from the root's key), the root's own entry not at all, and nothing beside or above it.
     * A blob attached under the subtree is presigned for the guest; one beside it is not. All of it with core alone.
     */
    @Test
    fun aGuestOfAFolderShareResolvesTheSubtreeUnderIt() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val outer = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = "b3V0ZXItd3JhcA==", encryptedName = "b3V0ZXItbmFtZQ==")
        val root = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = "cm9vdC13cmFw", encryptedName = "cm9vdC1uYW1l")
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = "Y2hpbGQtd3JhcA==", encryptedName = "Y2hpbGQtbmFtZQ==")
        val grandchild = seedNode(scopeId, NodeEntity.FOLDER, parentId = child, wrappedContentKey = "Z3JhbmQtd3JhcA==", encryptedName = "Z3JhbmQtbmFtZQ==")
        val sibling = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = "c2liLXdyYXA=", encryptedName = "c2liLW5hbWU=")
        val inside = seedCover(scopeId, grandchild)
        val beside = seedCover(scopeId, sibling)
        val shareId: String = postShare(userId, root, "READ").statusCode(201).extract().path("shareId")

        val body = given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("rootKind", equalTo(NodeEntity.FOLDER))
            .body("nodes.size()", equalTo(3))
            .body("nodes.find { it.id == '$child' }.encryptedName", equalTo("Y2hpbGQtbmFtZQ=="))
            .body("nodes.find { it.id == '$child' }.wrappedContentKey", equalTo("Y2hpbGQtd3JhcA=="))
            .body("nodes.find { it.id == '$grandchild' }.encryptedName", equalTo("Z3JhbmQtbmFtZQ=="))
            .body("nodes.find { it.id == '$grandchild' }.wrappedContentKey", equalTo("Z3JhbmQtd3JhcA=="))
            .body("nodes.find { it.id == '$grandchild' }.parentId", equalTo(child))
            .body("nodes.find { it.id == '$root' }", notNullValue())
            .body("nodes.find { it.id == '$root' }.parentId", nullValue())
            .extract().asString()
        for (absent in listOf("cm9vdC1uYW1l", "cm9vdC13cmFw", outer, "b3V0ZXItbmFtZQ==", sibling, "c2liLW5hbWU=")) {
            assertEquals(false, body.contains(absent), "the root's own entry and nothing beside or above it: $absent")
        }

        given().`when`().get("/api/shares/$shareId/blobs/$inside").then().statusCode(200)
        given().`when`().get("/api/shares/$shareId/blobs/$beside").then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun aCompletionIsIllegalTargetForEveryKind() {
        val (_, scopeId) = seedMemberScope()
        for (kind in kinds) {
            val node = seedNode(scopeId, kind, wrappedContentKey = CONTENT_KEY)
            assertEquals("illegal_target", completeOne(dispatcher, scopeId, node).error, "a completion on a $kind")

            // control: the same item through a dispatcher with one acceptor of the kind reaches it — so the refusal is
            // the empty seam's, not the item's
            val forged = object : UploadCompletion {
                override fun accepts(nodeKind: String) = nodeKind == kind
                override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean, attached: (String) -> Unit) =
                    CompleteItemResult(null, null, "forged")
            }
            assertEquals("forged", completeOne(UploadCompletions(nodes, mutableListOf(forged)), scopeId, node).error)
        }
    }

    @Test
    fun aLinkStartIsRefusedAndNoKindHasAnAdmission() {
        for (kind in kinds) assertNull(quota.admissionFor(kind), "a link rooted at a $kind is measured by someone")

        val (ownerId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val shareId = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x')
        val token = Tokens.random()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = ownerId; this.scopeId = scopeId; nodeId = folder; role = "READ_WRITE"
                wrap = "d3JhcA=="; sealedName = "c2VhbGVk"; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); this.shareId = shareId; sealedDisplayName = "bmFtZQ=="
                tokenHash = Tokens.hash(token)
            }.persist()
        }
        // control: every gate before the writer's passes — the link is live and the guest's token resolves
        assertNotNull(QuarkusTransaction.requiringNew().call { sharingStore.findIdentity(shareId, token) })

        val outcome = QuarkusTransaction.requiringNew().call {
            sharing.startUploads(shareId, null, token, listOf(TestBlob.SMALL.upload("d3JhcA==")), null)
        }
        assertEquals(ShareUploadOutcome.NotContributable, outcome, "a link start with no writer and no admission")
    }

    /**
     * Inbox #366: no application declares a member's role here, so a member's start is refused whole in every role an
     * application owns and in a forged one - and no session is opened. The one exception is core's own `COVER`
     * (inbox #411: a folder takes a cover with no application). The control is the composition's
     * (`ModuleCompositionTest.aMemberStartTakesTheRolesTheApplicationsDeclare`), and `:core`'s other tests, which boot
     * [CoreTestMemberBlobRoles]: the same body opens a session there.
     */
    @Test
    fun aMemberUploadStartIsRefusedInEveryRole() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        for (role in listOf("ORIGINAL", "FILE_CONTENT", "NOT_A_ROLE")) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", role = role)}]}""")
                .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
                .then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("scopeId", scopeId) }, "no session opened")
        // the control: core's own role opens one
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", role = "COVER")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
        // a non-member still learns nothing but its 404 — the role is asked only past the access check
        val (stranger, _) = seedMemberScope(ScopeEntity.GROUP)
        given().header("Authorization", bearer(stranger)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", role = "NOT_A_ROLE")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun anOpenUploadWindowsAndABlobDownloadsThroughCore() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        // Opened through the store: core alone starts no member upload (above), and a window is core's alone.
        val uploadId = uploadStore.createSession(
            scopeId, UploadOpener.Member(userId), UUID.randomUUID().toString().replace("-", ""),
            TestBlob.ofChunks(WINDOW + 1).upload("d3JhcA=="),
        )
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"uploadId":"$uploadId","fromChunk":0}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-upload-windows")
            .then().statusCode(200).body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(WINDOW))
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"uploadId":"$uploadId","fromChunk":$WINDOW}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-upload-windows")
            .then().statusCode(200).body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(1))

        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString().replace("-", "")
                chunkCount = 1; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "d3JhcA=="
            }.persist()
        }
        download(userId, scopeId, blobId).body("results[0].error", nullValue())
            .body("results[0].presignedGets", notNullValue())
        // control: a blob the scope does not hold is not served
        download(userId, scopeId, UUID.randomUUID().toString()).body("results[0].error", equalTo("upload_not_found"))
    }

    @Test
    fun anUnauthenticatedCoreRouteAnswers() {
        given().`when`().get("/api/setup").then().statusCode(200)
        given().`when`().get("/api/scopes").then().statusCode(401)
    }

    private fun createNode(userId: String, scopeId: String, kind: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"$kind","encryptedName":"bg==","slugToken":"${java.util.Base64.getEncoder().encodeToString(kind.toByteArray())}","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then()

    private fun postShare(userId: String, nodeId: String, role: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId)},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then()

    /** A blob in [scopeId] attached to [nodeId] — core's own arm of a share's blob gate (an application's cover is one). */
    private fun seedCover(scopeId: String, nodeId: String): String = seedAttachment(scopeId, nodeId, "CORE_TEST").blobId

    private fun download(userId: String, scopeId: String, blobId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)

    /** One item for an unknown upload to [nodeId] through [through]; its result. */
    private fun completeOne(through: UploadCompletions, scopeId: String, nodeId: String): CompleteItemResult {
        val item = CompleteItem(UUID.randomUUID().toString(), nodeId, UUID.randomUUID().toString(), emptyList(), emptyMap())
        return QuarkusTransaction.requiringNew().call {
            through.complete(scopeId, listOf(item), UploadOpener.Member(UUID.randomUUID().toString()))
        }.single()
    }

    private companion object {
        /** `veyq.blob.presign-window`'s default — the tests run on it. */
        const val WINDOW = 64
    }
}
