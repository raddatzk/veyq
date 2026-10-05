package me.raddatz.veyq.app

import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.core.scope.MembershipEraser
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.scope.ScopeEraser
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.core.testing.MainOutputs
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
 * the deliverable: measured at cr-platform-gradle-modules step 7, the packaged fast-jar with Memories' `beans.xml`
 * deleted **boots green**, Flyway still applies the Memories baseline, every Memories route answers 404 and every seam
 * core reaches Memories through is silently empty — an `@All` list shrinks, it does not fail. Since the module split
 * the Memories tests run in `:apps:memories`, where an unindexed Memories jar fails at their own injection points; the
 * composition of the root is what nothing else boots. So this test asks the root's application three questions:
 *
 * 1. **Every seam collects every implementation that was compiled.** For each interface core reaches an application
 *    through, the beans ArC deployed equal the concrete implementations in the compiled main outputs of every backend
 *    project ([MainOutputs], the posture sweeps' walk) — derived, so a third module's implementations are expected the
 *    day they compile, and an unindexed module's are missing by name. Anchored: the union holds core's own and
 *    Memories' implementations, so a walk that read nothing cannot pass.
 * 2. **Every module's routes are deployed.** Each generated JAX-RS interface (`@Path`) of every module has a deployed
 *    resource bean implementing it — the 404s of step 7's packaged jar, asked of the container. Anchored on both
 *    modules' generated API packages.
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
    @Inject @All lateinit var identityUsages: MutableList<GuestIdentityUsage>
    @Inject @All lateinit var contributors: MutableList<SharedSubtreeContributor>
    @Inject @All lateinit var guestWrites: MutableList<ShareGuestWrites>
    @Inject @All lateinit var completions: MutableList<UploadCompletion>
    @Inject @All lateinit var admissions: MutableList<UploadAdmission>
    @Inject @All lateinit var kindRules: MutableList<NodeKindRules>

    @Inject lateinit var quota: StorageQuota

    /** Every seam of ADR 0079 Consequences through which core reaches application code, with what ArC collected. */
    private fun seams(): Map<Class<*>, List<Any>> = mapOf(
        ScopeEraser::class.java to scopeErasers,
        NodeSubtreeEraser::class.java to subtreeErasers,
        MembershipEraser::class.java to membershipErasers,
        GuestIdentityUsage::class.java to identityUsages,
        SharedSubtreeContributor::class.java to contributors,
        ShareGuestWrites::class.java to guestWrites,
        UploadCompletion::class.java to completions,
        UploadAdmission::class.java to admissions,
        NodeKindRules::class.java to kindRules,
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
    }

    @Test
    fun everyModulesGeneratedRoutesHaveADeployedResource() {
        val deployed = Arc.container().beanManager()
            .getBeans(Object::class.java, jakarta.enterprise.inject.Any.Literal.INSTANCE)
            .map { it.beanClass }
        val routes = compiled.filter { it.isInterface && it.isAnnotationPresent(Path::class.java) }
        val unserved = routes.filter { api -> deployed.none { api.isAssignableFrom(it) } }.map { it.name }
        assertEquals(emptyList<String>(), unserved, "a generated API no deployed resource implements answers 404")
        for (pkg in listOf("me.raddatz.veyq.core.generated.api", "me.raddatz.veyq.memories.generated.api")) {
            assertTrue(routes.any { it.packageName == pkg }, "the walk saw $pkg: ${routes.map { it.name }}")
        }
    }

    @Test
    fun aKindNoApplicationClaimsIsRefusedByEverySeam() {
        val unclaimed = "NOT_A_KIND"
        val memory = "MEMORY"

        // Placement: nowhere — and the claimed kinds beside it are placed.
        assertFalse(NodeKindRules.parentAllows(kindRules, unclaimed, ParentSlot.Root), "an unclaimed kind is placed")
        assertTrue(NodeKindRules.parentAllows(kindRules, memory, ParentSlot.Root), "control: a Memory at the root")
        assertTrue(NodeKindRules.parentAllows(kindRules, NodeEntity.FOLDER, ParentSlot.Root), "control: a folder")

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
            .body("""{"items":[{"mediaId":"${UUID.randomUUID()}","originalUploadId":"${UUID.randomUUID()}","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)

    private fun share(userId: String, nodeId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"d3JhcA==","sealedName":"c2VhbGVk"}""")
            .`when`().post("/api/shares")
            .then()
}
