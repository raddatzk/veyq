package me.raddatz.veyq.app

import me.raddatz.veyq.core.backup.ApplicationErasures
import me.raddatz.veyq.core.applicationblob.ApplicationBlobOwners
import me.raddatz.veyq.core.blob.MemberBlobRoles
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.moderation.block.UploaderContent
import me.raddatz.veyq.core.moderation.hold.HeldUploaders
import me.raddatz.veyq.core.moderation.hold.HoldSnapshotContributor
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.node.rekey.NodeRekeyParticipant
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.core.scope.MembershipEraser
import me.raddatz.veyq.core.identity.AccountEraser
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.scope.ScopeEraser
import me.raddatz.veyq.core.sharing.GuestCreatableKinds
import me.raddatz.veyq.core.sharing.GuestPlaceholders
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.core.sharing.GuestNodeOp
import me.raddatz.veyq.core.sharing.GuestNodeRules
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.sharing.ShareRoots
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.core.sharing.WriteOnlyGuestKinds
import me.raddatz.veyq.core.testing.MainOutputs
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.arc.All
import io.quarkus.arc.Arc
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.ws.rs.Path
import java.lang.reflect.Modifier
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composition (ADR 0079): the one application the root builds holds **every** module, indexed — and an
 * application that is missing fails here, not quietly in production.
 *
 * **Why it is worth a test.** A module whose jar is not indexed (no `META-INF/beans.xml`) does not fail the build of
 * the deliverable: measured when the module split was built, the packaged fast-jar with Memories' `beans.xml`
 * deleted **boots green**, Flyway still applies the Memories baseline, every Memories route answers 404 and every seam
 * core reaches Memories through is silently empty — an `@All` list shrinks, it does not fail. Since the module split
 * the Memories tests run in `:apps:memories`, where an unindexed Memories jar fails at their own injection points; the
 * composition of the root is what nothing else boots. So this test asks the root's application three questions:
 *
 * 1. **Every seam collects every implementation that was compiled.** For each interface core reaches an application
 *    through, the beans ArC deployed equal the concrete implementations in the compiled main outputs of every backend
 *    project ([MainOutputs], the posture sweeps' walk) — derived, so a third module's implementations are expected the
 *    day they compile, and an unindexed module's are missing by name. Anchored: the union holds core's own,
 *    Memories' and Depot's implementations, so a walk that read nothing cannot pass — and a root build that drops an
 *    application's module (`src/backend/build.gradle.kts`) is red here, not only in that module's own tests.
 * 2. **Every module's routes are deployed.** Each generated JAX-RS interface (`@Path`) of every module has a deployed
 *    resource bean implementing it — the 404s of step 7's packaged jar, asked of the container. Anchored on every
 *    module's generated API package (core's, Memories', Depot's).
 * 3. **The seams fail closed in the composed application.** A kind no application claims is placed nowhere, measured
 *    by no admission, takes no upload (`illegal_target`) and is shared by no one (`illegal_scope`), each beside a
 *    positive control on a `MEMORY` — the kind Memories claims — through the same code path. Core alone, where every
 *    seam is empty, is `:core`'s pin (step 8b); this is the same posture with Memories present.
 */
@QuarkusTest
class ModuleCompositionTest {

    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @Inject @All lateinit var scopeErasers: MutableList<ScopeEraser>
    @Inject @All lateinit var subtreeErasers: MutableList<NodeSubtreeEraser>
    @Inject @All lateinit var membershipErasers: MutableList<MembershipEraser>
    @Inject @All lateinit var accountErasers: MutableList<AccountEraser>
    @Inject @All lateinit var identityUsages: MutableList<GuestIdentityUsage>
    @Inject @All lateinit var contributors: MutableList<SharedSubtreeContributor>
    @Inject @All lateinit var guestWrites: MutableList<ShareGuestWrites>
    @Inject @All lateinit var completions: MutableList<UploadCompletion>
    @Inject @All lateinit var admissions: MutableList<UploadAdmission>
    @Inject @All lateinit var kindRules: MutableList<NodeKindRules>
    @Inject @All lateinit var shareRoots: MutableList<ShareRoots>
    @Inject @All lateinit var guestNodeRules: MutableList<GuestNodeRules>
    @Inject @All lateinit var writeOnlyGuestKinds: MutableList<WriteOnlyGuestKinds>
    @Inject @All lateinit var guestCreatableKinds: MutableList<GuestCreatableKinds>
    @Inject @All lateinit var guestPlaceholders: MutableList<GuestPlaceholders>
    @Inject @All lateinit var memberRoles: MutableList<MemberBlobRoles>
    @Inject @All lateinit var blobOwners: MutableList<ApplicationBlobOwners>
    @Inject @All lateinit var reportableItems: MutableList<ReportableItems>
    @Inject @All lateinit var holdContributors: MutableList<HoldSnapshotContributor>
    @Inject @All lateinit var rekeyParticipants: MutableList<NodeRekeyParticipant>
    @Inject @All lateinit var uploaderContent: MutableList<UploaderContent>
    @Inject @All lateinit var heldUploaders: MutableList<HeldUploaders>
    @Inject @All lateinit var applicationErasures: MutableList<ApplicationErasures>

    @Inject lateinit var quota: StorageQuota

    /** Every seam through which core reaches application code (docs/apps/README.md § The seams core asks), with what ArC collected. */
    private fun seams(): Map<Class<*>, List<Any>> = mapOf(
        ScopeEraser::class.java to scopeErasers,
        NodeSubtreeEraser::class.java to subtreeErasers,
        MembershipEraser::class.java to membershipErasers,
        AccountEraser::class.java to accountErasers,
        GuestIdentityUsage::class.java to identityUsages,
        SharedSubtreeContributor::class.java to contributors,
        ShareGuestWrites::class.java to guestWrites,
        UploadCompletion::class.java to completions,
        UploadAdmission::class.java to admissions,
        NodeKindRules::class.java to kindRules,
        ShareRoots::class.java to shareRoots,
        GuestNodeRules::class.java to guestNodeRules,
        WriteOnlyGuestKinds::class.java to writeOnlyGuestKinds,
        GuestCreatableKinds::class.java to guestCreatableKinds,
        GuestPlaceholders::class.java to guestPlaceholders,
        MemberBlobRoles::class.java to memberRoles,
        ApplicationBlobOwners::class.java to blobOwners,
        ReportableItems::class.java to reportableItems,
        HoldSnapshotContributor::class.java to holdContributors,
        NodeRekeyParticipant::class.java to rekeyParticipants,
        UploaderContent::class.java to uploaderContent,
        HeldUploaders::class.java to heldUploaders,
        ApplicationErasures::class.java to applicationErasures,
    )

    private val compiled: List<Class<*>> by lazy {
        val loader = Thread.currentThread().contextClassLoader
        MainOutputs.classNames().mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
    }

    /**
     * The bean class behind what ArC handed out: a client proxy is unwrapped, and an intercepted bean (a
     * `@Transactional` method) is an ArC-generated `_Subclass` of it.
     */
    private fun beanClass(bean: Any): String {
        var cls: Class<*> = ClientProxy.unwrap(bean).javaClass
        while (cls.name.endsWith("_Subclass")) cls = cls.superclass
        return cls.name
    }

    @Test
    fun everySeamCollectsEveryCompiledImplementation() {
        val concrete = compiled.filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) }
        for ((seam, deployed) in seams()) {
            val expected = concrete.filter { seam.isAssignableFrom(it) }.map { it.name }.toSortedSet()
            val actual = deployed.map(::beanClass).toSortedSet()
            assertEquals(expected, actual, "${seam.simpleName}: the deployed implementations are the compiled ones")
        }
        val union = seams().values.flatten().map(::beanClass)
        assertTrue(union.any { it.startsWith("me.raddatz.veyq.core.") }, "core's own implementations are collected: $union")
        assertTrue(union.any { it.startsWith("me.raddatz.veyq.memories.") }, "Memories' implementations are collected: $union")
        assertTrue(union.any { it.startsWith("me.raddatz.veyq.depot.") }, "Depot's implementations are collected: $union")
        assertTrue(union.any { it.startsWith("me.raddatz.veyq.expenses.") }, "Expenses' implementations are collected: $union")
    }

    @Test
    fun everyModulesGeneratedRoutesHaveADeployedResource() {
        val deployed = Arc.container().beanManager()
            .getBeans(Any::class.java, jakarta.enterprise.inject.Any.Literal.INSTANCE)
            .map { it.beanClass }
        val routes = compiled.filter { it.isInterface && it.isAnnotationPresent(Path::class.java) }
        val unserved = routes.filter { api -> deployed.none { api.isAssignableFrom(it) } }.map { it.name }
        assertEquals(emptyList<String>(), unserved, "a generated API no deployed resource implements answers 404")
        for (pkg in listOf("me.raddatz.veyq.core.generated.api", "me.raddatz.veyq.memories.generated.api", "me.raddatz.veyq.depot.generated.api", "me.raddatz.veyq.expenses.generated.api")) {
            assertTrue(routes.any { it.packageName == pkg }, "the walk saw $pkg: ${routes.map { it.name }}")
        }
    }

    /**
     * The placement partition across core and EVERY application — the one place both applications run together, so
     * the one place a kind two of them claim would show (each module's own partition test sees core and itself only).
     * Exactly one rule per kind, the right owner for each, and no rule of any of them admits a `FILE` as a parent: a
     * file has no children, whichever application's kind the child is. The applications' kinds and classes are named as
     * the wire and the jar spell them, not imported — as for Memories in this class: a root build that drops a module
     * must fail here at run time, not stop this file from compiling.
     */
    @Test
    fun theRulesOfCoreAndEveryApplicationPartitionTheKinds() {
        val file = "FILE"
        val dropBox = "DEPOT_DROPBOX"
        val pot = "EXPENSES_POT"
        val kinds = listOf(NodeEntity.FOLDER, "MEMORY", "CHAPTER", file, dropBox, pot)
        for (kind in kinds) {
            val claiming = kindRules.filter { it.handles(kind) }.map(::beanClass)
            assertEquals(1, claiming.size, "node kind $kind must be handled by exactly one NodeKindRules: $claiming")
        }
        assertEquals("me.raddatz.veyq.depot.DepotNodeKindRules", beanClass(kindRules.single { it.handles(file) }), "FILE is Depot's")
        assertTrue(beanClass(kindRules.single { it.handles("MEMORY") }).startsWith("me.raddatz.veyq.memories."), "MEMORY is Memories'")

        assertEquals("me.raddatz.veyq.depot.DepotNodeKindRules", beanClass(kindRules.single { it.handles(dropBox) }), "DEPOT_DROPBOX is Depot's")
        assertEquals("me.raddatz.veyq.expenses.ExpensesNodeKindRules", beanClass(kindRules.single { it.handles(pot) }), "EXPENSES_POT is Expenses'")
        assertEquals("expenses", NodeKindRules.applicationOf(kindRules, pot), "a Pot is switched with Expenses")
        assertTrue(NodeKindRules.parentAllows(kindRules, pot, ParentSlot.Node(NodeEntity.FOLDER)), "control: a Pot in a folder")
        assertFalse(NodeKindRules.parentAllows(kindRules, pot, ParentSlot.Node("MEMORY")), "a Pot in a Memory")
        // ADR 0108 decision 2: a Pot is shared to read or to write, never WRITE_ONLY — one answer across every application
        assertEquals(setOf("READ", "READ_WRITE"), ShareRoots.roles(shareRoots, pot), "a Pot's share roles")
        for (child in kinds) {
            // ADR 0108 decision 3: a Pot's entries are records, never child nodes
            assertFalse(NodeKindRules.parentAllows(kindRules, child, ParentSlot.Node(pot)), "a $child inside a Pot")
            assertFalse(NodeKindRules.parentAllows(kindRules, child, ParentSlot.Node(file)), "a $child inside a file")
            // ADR 0080 decision 11: nothing is placed into a drop box — its identity folders are core's own placement
            assertFalse(NodeKindRules.parentAllows(kindRules, child, ParentSlot.Node(dropBox)), "a $child placed in a drop box")
        }
        assertTrue(NodeKindRules.parentAllows(kindRules, dropBox, ParentSlot.Node(NodeEntity.FOLDER)), "control: a drop box in a folder")
        // control: a file at the root and in a folder, never in a Memory; a chapter in its Memory
        assertTrue(NodeKindRules.parentAllows(kindRules, file, ParentSlot.Root), "a file at the root")
        assertTrue(NodeKindRules.parentAllows(kindRules, file, ParentSlot.Node(NodeEntity.FOLDER)), "a file in a folder")
        assertFalse(NodeKindRules.parentAllows(kindRules, file, ParentSlot.Node("MEMORY")), "a file in a Memory")
        assertTrue(NodeKindRules.parentAllows(kindRules, "CHAPTER", ParentSlot.Node("MEMORY")), "control: a chapter in a Memory")
    }

    /**
     * Which kinds take a cover (inbox #411), across core and every application: each kind's own rule opts it in -
     * core's `FOLDER`, Memories' two, Depot's drop box - and a file does not (its one blob is its content). A kind no
     * rule claims takes none, so core never needs to name an application kind.
     */
    @Test
    fun everyKindThatTakesACoverIsOptedInByItsOwnRule() {
        for (kind in listOf(NodeEntity.FOLDER, "MEMORY", "CHAPTER", "DEPOT_DROPBOX")) {
            assertTrue(NodeKindRules.takesCover(kindRules, kind), "$kind takes a cover")
        }
        for (kind in listOf("FILE", "EXPENSES_POT", "NOT_A_KIND")) {
            assertFalse(NodeKindRules.takesCover(kindRules, kind), "$kind takes none")
        }
    }

    /**
     * What a folder's `READ_WRITE` guest may create, across core and every application (owner decision 2026-10-14): a
     * folder, and the kinds their own applications declare guest-creatable — Depot's `FILE`, declared once and by Depot;
     * never a Memory, a chapter or a drop box. Only here does a kind two applications declare show (it would fail the
     * boot of this very test: `GuestCreatableKinds.requireDisjoint`). Moving and renaming any kind stay core's to allow.
     */
    @Test
    fun aFolderGuestCreatesFoldersAndTheDeclaredKindsOnly() {
        val declared = guestCreatableKinds.flatMap { bean -> bean.kinds().map { it to beanClass(bean) } }
        assertEquals(listOf("FILE" to "me.raddatz.veyq.depot.DepotGuestCreatableKinds"), declared, "the kinds the applications declare guest-creatable")
        fun creates(kind: String) = GuestNodeRules.readWriteAllows(guestNodeRules, NodeEntity.FOLDER, GuestNodeOp.CreateChild(kind))
        assertTrue(creates(NodeEntity.FOLDER), "a folder")
        assertTrue(creates("FILE"), "a file")
        for (kind in listOf("MEMORY", "CHAPTER", "DEPOT_DROPBOX", "EXPENSES_POT", "NOT_A_KIND")) assertFalse(creates(kind), "a guest creates a $kind in a folder")
        for (op in listOf(GuestNodeOp.MoveChildOut("MEMORY"), GuestNodeOp.MoveChildIn("MEMORY"), GuestNodeOp.RenameChild("DEPOT_DROPBOX"))) {
            assertTrue(GuestNodeRules.readWriteAllows(guestNodeRules, NodeEntity.FOLDER, op), "unchanged: $op")
        }
    }

    /**
     * The upload partition across core and every application (cr-files-application F1a, F2): each kind that takes
     * uploads has exactly one completion, one link admission and one guest writer, the right application's — or core's
     * blob service's for a kind whose application stores its blobs through it (ADR 0112: a Pot's receipts), with exactly
     * one application answering for the kind's blobs; a `FOLDER` has none of them. Two acceptors
     * of a kind make core's dispatcher refuse it (`illegal_target`) and two admissions refuse its links — only here,
     * where both applications run, would an application claiming another's kind show.
     */
    @Test
    fun theUploadSeamsOfEveryApplicationPartitionTheKinds() {
        val owners = mapOf(
            "MEMORY" to "me.raddatz.veyq.memories.", "CHAPTER" to "me.raddatz.veyq.memories.", "FILE" to "me.raddatz.veyq.depot.",
            "EXPENSES_POT" to "me.raddatz.veyq.core.applicationblob.", // its receipts, core's blob service (ADR 0112)
        )
        val stored = mapOf("EXPENSES_POT" to "me.raddatz.veyq.expenses.")
        for (kind in owners.keys + NodeEntity.FOLDER) {
            val answering = blobOwners.filter { it.accepts(kind) }.map(::beanClass)
            val expected = stored[kind]
            if (expected == null) {
                assertTrue(answering.isEmpty(), "a $kind stores no blob through core's service: $answering")
            } else {
                assertEquals(1, answering.size, "a $kind's stored blobs have exactly one application: $answering")
                assertTrue(answering.single().startsWith(expected), "$kind's stored blobs are $expected's: $answering")
            }
        }
        for ((kind, owner) in owners) {
            val accepting = completions.filter { it.accepts(kind) }.map(::beanClass)
            assertEquals(1, accepting.size, "an upload into a $kind must have exactly one completion: $accepting")
            assertTrue(accepting.single().startsWith(owner), "$kind's completion is $owner's: $accepting")
            val measuring = admissions.filter { it.supports(kind) }.map(::beanClass)
            assertEquals(1, measuring.size, "a $kind link must have exactly one admission: $measuring")
            assertTrue(measuring.single().startsWith(owner), "$kind's admission is $owner's: $measuring")
            val writing = guestWrites.filter { it.supports(kind) }.map(::beanClass)
            assertEquals(1, writing.size, "a guest's upload into a $kind must have exactly one writer: $writing")
            assertTrue(writing.single().startsWith(owner), "$kind's guest writer is $owner's: $writing")
        }
        assertTrue(guestWrites.none { it.supports(NodeEntity.FOLDER) }, "a guest uploads into no folder")
        assertTrue(completions.none { it.accepts(NodeEntity.FOLDER) }, "a folder takes no upload")
        assertTrue(admissions.none { it.supports(NodeEntity.FOLDER) }, "a folder link is measured by no application")
    }

    /**
     * An application's own completion route never hands an upload to another application's completion: Depot's
     * `completeFile` on a `MEMORY` is refused before core's dispatcher would pick Memories' — which would otherwise
     * read the item as a medium whose id is the node's, and attach it, had the upload been started as that medium's
     * original. Only the composed application holds both, so only here can it show.
     */
    @Test
    fun filesCompletionRouteNeverReachesMemoriesCompletion() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, "MEMORY", parentId = scopeId, wrappedContentKey = "aw==")
        val asMedium = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = memory, role = "ORIGINAL")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$asMedium","nodeEpoch":0}""")
            .`when`().post("/api/scopes/$scopeId/files/$memory/content")
            .then().statusCode(422).body("code", equalTo("illegal_target"))
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("media.size()", equalTo(0))
        // control: the same upload is a medium through Memories' own route
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"$memory","nodeEpoch":0,"originalUploadId":"$asMedium","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", equalTo(null))
    }

    /**
     * Inbox #366: a member's start takes every role an application declares — Memories' five (a medium's three and a
     * Memory's banner and poster), core's cover, Depot's one and Expenses' receipt, which only
     * this composition holds together — and refuses a well-formed token none declares, whole and before any session.
     */
    @Test
    fun aMemberStartTakesTheRolesTheApplicationsDeclare() {
        val declared = memberRoles.flatMap { it.roles() }.toSet()
        assertEquals(
            setOf("ORIGINAL", "THUMBNAIL", "PREVIEW", "BANNER", "BANNER_POSTER", "COVER", "FILE_CONTENT", "EXPENSES_RECEIPT"), declared,
            "the roles the applications declare",
        )
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        fun start(role: String) = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then()
        for (role in declared) start(role).statusCode(200).body("results[0].error", equalTo(null))
        start("NOT_A_ROLE").statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun aKindNoApplicationClaimsIsRefusedByEverySeam() {
        val unclaimed = "NOT_A_KIND"
        val memory = "MEMORY"

        // Placement: nowhere — and the claimed kinds beside it are placed.
        assertFalse(NodeKindRules.parentAllows(kindRules, unclaimed, ParentSlot.Root), "an unclaimed kind is placed")
        assertTrue(NodeKindRules.parentAllows(kindRules, memory, ParentSlot.Root), "control: a Memory at the root")
        assertTrue(NodeKindRules.parentAllows(kindRules, NodeEntity.FOLDER, ParentSlot.Root), "control: a folder")

        // A guest's tree operation inside a container: an unclaimed kind allows none, and so does a Memory — Memories
        // declares no guest tree operation (ADR 0080 decision 6); core's folder is the control.
        val create = GuestNodeOp.CreateChild(NodeEntity.FOLDER)
        assertFalse(GuestNodeRules.readWriteAllows(guestNodeRules, unclaimed, create), "a guest creates in an unclaimed kind")
        assertFalse(GuestNodeRules.readWriteAllows(guestNodeRules, memory, create), "a guest creates inside a Memory")
        assertTrue(GuestNodeRules.readWriteAllows(guestNodeRules, NodeEntity.FOLDER, create), "control: inside a folder")
        // A WRITE_ONLY guest's create: a root kind no application declares lets it create nothing — a Memory root
        // neither (its guests' uploads are media, not nodes); Depot's drop box takes FILEs (wire tokens, as this test
        // spells Depot's kinds).
        assertFalse(WriteOnlyGuestKinds.allows(writeOnlyGuestKinds, unclaimed, NodeEntity.FOLDER), "a write-only guest creates under an unclaimed root")
        assertFalse(WriteOnlyGuestKinds.allows(writeOnlyGuestKinds, memory, NodeEntity.FOLDER), "a write-only guest creates under a Memory link")
        assertTrue(WriteOnlyGuestKinds.allows(writeOnlyGuestKinds, "DEPOT_DROPBOX", "FILE"), "control: a FILE in a drop box")

        // A link's usage: no admission measures it, so the link start is refused.
        assertNull(quota.admissionFor(unclaimed), "an unclaimed kind's link is measured")
        assertNotNull(quota.admissionFor(memory), "control: a Memory link is measured")

        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val forged = seedNode(scopeId, unclaimed, parentId = scopeId, wrappedContentKey = "aw==")
        val claimed = seedNode(scopeId, memory, parentId = scopeId, wrappedContentKey = "aw==")

        // An upload's completion: no acceptor, `illegal_target`; the Memory reaches its acceptor, which then refuses
        // the unknown upload — so the refusal above is the dispatch's, not the item's.
        complete(userId, scopeId, forged).body("results[0].error", equalTo("illegal_target"))
        complete(userId, scopeId, claimed).body("results[0].error", equalTo("upload_not_found"))

        // A share: no guest writer supports the kind, `illegal_scope`; the Memory is shared.
        share(userId, forged).statusCode(422).body("code", equalTo("illegal_scope"))
        share(userId, claimed).statusCode(201)
    }

    private fun complete(userId: String, scopeId: String, nodeId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${UUID.randomUUID()}","nodeEpoch":0,"originalUploadId":"${UUID.randomUUID()}","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)

    private fun share(userId: String, nodeId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then()
}
