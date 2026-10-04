package com.veyq.core

import com.veyq.core.blob.BlobEntity
import com.veyq.core.blob.CompleteItem
import com.veyq.core.blob.CompleteItemResult
import com.veyq.core.blob.UploadCompletion
import com.veyq.core.blob.UploadCompletions
import com.veyq.core.blob.UploadOpener
import com.veyq.core.content.NodesStore
import com.veyq.core.crypto.Tokens
import com.veyq.core.node.FolderNodeRules
import com.veyq.core.node.NodeEntity
import com.veyq.core.node.NodeKindRules
import com.veyq.core.node.NodeScopeEraser
import com.veyq.core.node.NodeSubtreeEraser
import com.veyq.core.quota.StorageQuota
import com.veyq.core.quota.UploadAdmission
import com.veyq.core.scope.MembershipEraser
import com.veyq.core.scope.ScopeEntity
import com.veyq.core.scope.ScopeEraser
import com.veyq.core.sharing.GuestIdentityUsage
import com.veyq.core.sharing.ShareEntity
import com.veyq.core.sharing.ShareGuestWrites
import com.veyq.core.sharing.ShareIdentityEntity
import com.veyq.core.sharing.ShareScopeEraser
import com.veyq.core.sharing.ShareUploadOutcome
import com.veyq.core.sharing.ShareWriteProof
import com.veyq.core.sharing.SharedSubtreeContributor
import com.veyq.core.sharing.SharingHandler
import com.veyq.core.sharing.SharingStore
import com.veyq.core.testing.FakePresignProfile
import com.veyq.core.testing.TestBlob
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.arc.All
import io.quarkus.arc.ClientProxy
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
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
 * 1. **Every seam holds exactly core's own implementations**: `ScopeEraser` core's two (nodes, shares),
 *    `NodeKindRules` core's `FOLDER` rule, and every other seam — `NodeSubtreeEraser`, `MembershipEraser`,
 *    `GuestIdentityUsage`, `SharedSubtreeContributor`, `ShareGuestWrites`, `UploadCompletion`, `UploadAdmission` —
 *    none. The composed application's sets are the root's `ModuleCompositionTest`.
 * 2. **Every seam fails closed with nobody behind it**: a `FOLDER` is created and a `MEMORY` is `illegal_structure`;
 *    a share is `illegal_scope`; a completion is `illegal_target` for every kind; a link start against a seeded share
 *    row (one cannot be *created* core-alone) is refused (`share_not_contributable`) and no kind has an admission.
 * 3. **What core owns works alone**: a blob upload starts and windows, and a seeded blob downloads, through core's
 *    `BlobResource`; an unauthenticated core route answers.
 *
 * Each refusal sits beside a positive control through the same path; where core alone has nothing to accept, the
 * control is the composition's (`ModuleCompositionTest` shares a `MEMORY` and completes one through the same bodies).
 * [FakePresignProfile]: the in-memory store cannot presign, and a window is nothing but presigned URLs.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class CoreAloneSliceTest {

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

    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var dispatcher: UploadCompletions
    @Inject lateinit var quota: StorageQuota
    @Inject lateinit var sharing: SharingHandler
    @Inject lateinit var sharingStore: SharingStore

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
        assertEquals(setOf(NodeScopeEraser::class.java, ShareScopeEraser::class.java), classes(scopeErasers),
            "ScopeEraser: core's own two, and no application's")
        assertEquals(setOf(FolderNodeRules::class.java), classes(kindRules), "NodeKindRules: core's FOLDER rule only")
        for ((seam, beans) in mapOf(
            "NodeSubtreeEraser" to subtreeErasers, "MembershipEraser" to membershipErasers,
            "GuestIdentityUsage" to identityUsages, "SharedSubtreeContributor" to contributors,
            "ShareGuestWrites" to guestWrites, "UploadCompletion" to completions, "UploadAdmission" to admissions,
        )) {
            assertEquals(emptySet<Class<*>>(), classes(beans), "$seam: core implements none, and no application is here")
        }
    }

    @Test
    fun aFolderIsPlacedAndAnApplicationKindIsNot() {
        val (userId, scopeId) = seedMemberScope()
        // control: the platform's own kind lands at the root
        createNode(userId, scopeId, NodeEntity.FOLDER).statusCode(201)
        createNode(userId, scopeId, "MEMORY").statusCode(422).body("code", equalTo("illegal_structure"))
    }

    @Test
    fun aShareIsRefusedForEveryKind() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        for (kind in kinds) {
            val node = seedNode(scopeId, kind, wrappedContentKey = CONTENT_KEY)
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{${ShareWriteProof.newShare()},"nodeId":"$node","role":"VIEWER","wrap":"d3JhcA==","sealedName":"c2VhbGVk"}""")
                .`when`().post("/api/shares")
                .then().statusCode(422).body("code", equalTo("illegal_scope"))
        }
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
                override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean) =
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
                id = shareId; ownerAccount = ownerId; this.scopeId = scopeId; nodeId = folder; role = "CONTRIBUTOR"
                wrap = "d3JhcA=="; sealedName = "c2VhbGVk"; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); this.shareId = shareId; sealedDisplayName = "bmFtZQ=="
                tokenHash = Tokens.hash(token); createdAt = Instant.now()
            }.persist()
        }
        // control: every gate before the writer's passes — the link is live and the guest's token resolves
        assertNotNull(QuarkusTransaction.requiringNew().call { sharingStore.findIdentity(shareId, token) })

        val outcome = QuarkusTransaction.requiringNew().call {
            sharing.startUploads(shareId, null, token, listOf(TestBlob.SMALL.upload("d3JhcA==")), null)
        }
        assertEquals(ShareUploadOutcome.NotContributable, outcome, "a link start with no writer and no admission")
    }

    @Test
    fun aBlobUploadStartsWindowsAndDownloadsThroughCore() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.ofChunks(WINDOW + 1).declare("d3JhcA==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(WINDOW))
            .extract().path<String>("results[0].uploadId")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"uploadId":"$uploadId","fromChunk":$WINDOW}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-upload-windows")
            .then().statusCode(200).body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(1))

        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString().replace("-", "")
                chunkCount = 1; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "d3JhcA=="; createdAt = Instant.now()
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
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"$kind","encryptedName":"bg==","slugToken":"${java.util.Base64.getEncoder().encodeToString(kind.toByteArray())}","orderIndex":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then()

    private fun download(userId: String, scopeId: String, blobId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)

    /** One item for an unknown upload to [nodeId] through [through]; its result. */
    private fun completeOne(through: UploadCompletions, scopeId: String, nodeId: String): CompleteItemResult {
        val item = CompleteItem(UUID.randomUUID().toString(), nodeId, UUID.randomUUID().toString(), emptyList(), null, null)
        return QuarkusTransaction.requiringNew().call {
            through.complete(scopeId, listOf(item), UploadOpener.Member(UUID.randomUUID().toString()))
        }.single()
    }

    private companion object {
        /** `veyq.blob.presign-window`'s default — the tests run on it. */
        const val WINDOW = 64
    }
}
