package me.raddatz.veyq.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.sharing.GuestCreatableKinds
import me.raddatz.veyq.core.sharing.GuestNodeOp
import me.raddatz.veyq.core.sharing.GuestNodeRules
import me.raddatz.veyq.core.sharing.ShareRoots
import me.raddatz.veyq.core.sharing.WriteOnlyGuestKinds
import me.raddatz.veyq.core.testing.MainOutputs
import io.quarkus.arc.All
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.io.File
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The kinds contract (`contracts/platform/kinds/`, its schema `kinds.schema.json`): every fact a file states of a kind is
 * what the deployed beans of core and every application answer — and the web's `src/web/src/kindsContract.test.ts`
 * holds the registry and the rights functions to the same files, so the two clients cannot drift apart unseen.
 *
 * Here, in the composition, because only here run core and every application together: a kind two owners claim, or a
 * bean of one owner answering for another's kind, shows only here. Both directions are asked:
 * - **contract → beans.** For each kind of each file, every seam answers exactly the file — placement over the root and
 *   every kind of the contract as a parent (and one no file names), cover, the capability its creation needs, the
 *   application it belongs to (and the code of that owner answering it), its share roles, and what a guest may do around
 *   it (`GuestNodeRules` over every child kind, `GuestCreatableKinds`, `WriteOnlyGuestKinds`).
 * - **beans → contract.** Every bean of these seams answers for some kind of the contract (a bean that answers only for
 *   kinds no file names fails), every kind a `GuestCreatableKinds` declares is in it, and every kind constant the
 *   compiled code defines (`NodeEntity.FOLDER`, each `*NodeKinds` object's strings) is in a file — a `handles` cannot be
 *   enumerated, so a kind a module adds without writing it down is found by its constant.
 */
@QuarkusTest
class KindsContractTest {

    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @Inject @All lateinit var kindRules: MutableList<NodeKindRules>
    @Inject @All lateinit var shareRoots: MutableList<ShareRoots>
    @Inject @All lateinit var guestNodeRules: MutableList<GuestNodeRules>
    @Inject @All lateinit var writeOnlyGuestKinds: MutableList<WriteOnlyGuestKinds>
    @Inject @All lateinit var guestCreatableKinds: MutableList<GuestCreatableKinds>

    /** One kind as its file states it. */
    private data class Kind(val kind: String, val owner: String, val facts: JsonNode)

    private val dir = File("../../contracts/platform/kinds")

    private val kinds: List<Kind> by lazy {
        val files = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") }.orEmpty().sortedBy { it.name }
        files.flatMap { file ->
            val doc = ObjectMapper().readTree(file)
            val owner = doc.path("owner").asText()
            assertEquals(file.name.removeSuffix(".json"), owner, "${file.name}: a file is named for its owner")
            doc.path("kinds").properties().map { (kind, facts) -> Kind(kind, owner, facts) }
        }
    }

    private val byKind: Map<String, Kind> by lazy { kinds.associateBy { it.kind } }

    /** Every kind the contract names, and one it does not: each seam is asked over all of them. */
    private val probes: List<String> by lazy { kinds.map { it.kind } + "NOT_A_KIND" }

    private fun Kind.strings(vararg path: String): Set<String> =
        path.fold(facts) { n, p -> n.path(p) }.map { it.asText() }.toSet()

    private fun Kind.flag(vararg path: String): Boolean = path.fold(facts) { n, p -> n.path(p) }.asBoolean()

    /** The application a kind of [owner] belongs to: none for core's own. */
    private fun applicationOf(owner: String): String? = if (owner == "core") null else owner

    /** The bean class behind what ArC handed out (a client proxy unwrapped, an ArC `_Subclass` climbed). */
    private fun beanClass(bean: Any): String {
        var cls: Class<*> = ClientProxy.unwrap(bean).javaClass
        while (cls.name.endsWith("_Subclass")) cls = cls.superclass
        return cls.name
    }

    /** Whether [bean] is the code of [owner]: core's package, or the application's (ADR 0079, named for the owner). */
    private fun ownedBy(bean: Any, owner: String) = beanClass(bean).startsWith("me.raddatz.veyq.$owner.")

    @Test
    fun theContractIsWholeAndSane() {
        assertTrue(dir.isDirectory, "contracts/platform/kinds is read from ${dir.absolutePath}")
        // Anchored: core's file and every application module's, so a walk that read nothing cannot pass.
        assertEquals(setOf("core", "memories", "depot", "expenses"), kinds.map { it.owner }.toSet(), "the owners with a kinds file")
        assertEquals(kinds.size, byKind.size, "a kind appears in one file only: ${kinds.groupBy { it.kind }.filterValues { it.size > 1 }.keys}")
        for (k in kinds) {
            for (parent in k.strings("placement", "parents") + k.strings("guest", "writeOnlyCreates")) {
                assertTrue(parent in byKind, "${k.kind} names $parent, which no file states")
            }
        }
    }

    @Test
    fun placementCoverCreationAndApplicationAreTheNodeKindRules() {
        for (k in kinds) {
            val claiming = kindRules.filter { it.handles(k.kind) }
            assertEquals(1, claiming.size, "${k.kind} is handled by exactly one NodeKindRules: ${claiming.map(::beanClass)}")
            assertTrue(ownedBy(claiming.single(), k.owner), "${k.kind}'s NodeKindRules is ${k.owner}'s: ${beanClass(claiming.single())}")
            assertEquals(applicationOf(k.owner), NodeKindRules.applicationOf(kindRules, k.kind), "${k.kind}'s application")
            assertEquals(k.flag("placement", "root"), NodeKindRules.parentAllows(kindRules, k.kind, ParentSlot.Root), "${k.kind} at the root")
            val parents = k.strings("placement", "parents")
            for (parent in probes) {
                assertEquals(parent in parents, NodeKindRules.parentAllows(kindRules, k.kind, ParentSlot.Node(parent)), "${k.kind} in a $parent")
            }
            assertEquals(k.flag("cover"), NodeKindRules.takesCover(kindRules, k.kind), "${k.kind} takes a cover")
            val needs = k.facts.path("createNeeds").let { if (it.isNull) null else it.asText() }
            assertEquals(needs, NodeKindRules.capabilityToCreate(kindRules, k.kind), "what creating a ${k.kind} needs")
        }
    }

    @Test
    fun shareRolesAreTheShareRoots() {
        for (k in kinds) {
            val roles = k.strings("shareRoles")
            assertEquals(roles, ShareRoots.roles(shareRoots, k.kind), "the roles a ${k.kind} link may have")
            val answering = shareRoots.filter { it.roles(k.kind) != null }
            assertEquals(if (roles.isEmpty()) 0 else 1, answering.size, "${k.kind}'s ShareRoots: ${answering.map(::beanClass)}")
            answering.forEach { assertTrue(ownedBy(it, k.owner), "${k.kind}'s ShareRoots is ${k.owner}'s: ${beanClass(it)}") }
        }
    }

    @Test
    fun aGuestsTreeOperationsAreTheGuestSeams() {
        for (container in kinds) {
            val inside = container.flag("guest", "container")
            for (childKind in probes) {
                val creatable = byKind[childKind]?.flag("guest", "creatableInFolder") ?: false
                fun allows(op: GuestNodeOp) = GuestNodeRules.readWriteAllows(guestNodeRules, container.kind, op)
                assertEquals(inside && creatable, allows(GuestNodeOp.CreateChild(childKind)), "a guest creates a $childKind in a ${container.kind}")
                for (op in listOf(GuestNodeOp.MoveChildIn(childKind), GuestNodeOp.MoveChildOut(childKind), GuestNodeOp.RenameChild(childKind))) {
                    assertEquals(inside, allows(op), "a guest's $op in a ${container.kind}")
                }
            }
            guestNodeRules.filter { it.handles(container.kind) }.forEach {
                assertTrue(inside, "${beanClass(it)} answers for ${container.kind}, which is no guest container")
                assertTrue(ownedBy(it, container.owner), "${container.kind}'s GuestNodeRules is ${container.owner}'s: ${beanClass(it)}")
            }

            val creates = container.strings("guest", "writeOnlyCreates")
            for (childKind in probes) {
                assertEquals(childKind in creates, WriteOnlyGuestKinds.allows(writeOnlyGuestKinds, container.kind, childKind), "a WRITE_ONLY guest of a ${container.kind} link creates a $childKind")
            }
            writeOnlyGuestKinds.filter { it.creatable(container.kind) != null }.forEach {
                assertTrue(ownedBy(it, container.owner), "${container.kind}'s WriteOnlyGuestKinds is ${container.owner}'s: ${beanClass(it)}")
            }
        }
        // A kind an application declares guest-creatable is its own, and the contract says so; core's FOLDER is core's rule.
        for (bean in guestCreatableKinds) {
            for (kind in bean.kinds()) {
                val k = byKind[kind]
                assertTrue(k != null && k.flag("guest", "creatableInFolder"), "${beanClass(bean)} declares $kind guest-creatable, the contract does not")
                assertTrue(ownedBy(bean, k!!.owner), "$kind is declared guest-creatable by ${beanClass(bean)}, not by ${k.owner}")
            }
        }
    }

    @Test
    fun everyBeanAndEveryKindConstantIsInTheContract() {
        val known = kinds.map { it.kind }
        for (bean in kindRules) assertTrue(known.any(bean::handles), "${beanClass(bean)} handles no kind of the contract")
        for (bean in shareRoots) assertTrue(known.any { bean.roles(it) != null }, "${beanClass(bean)} roots no kind of the contract")
        for (bean in guestNodeRules) assertTrue(known.any(bean::handles), "${beanClass(bean)} answers for no kind of the contract")
        for (bean in writeOnlyGuestKinds) assertTrue(known.any { bean.creatable(it) != null }, "${beanClass(bean)} answers for no kind of the contract")

        val loader = Thread.currentThread().contextClassLoader
        val constants = MainOutputs.classNames()
            .filter { it.substringAfterLast('.').endsWith("NodeKinds") }
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .flatMap { cls ->
                cls.declaredFields
                    .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }
                    .filter { it.type == String::class.java }
                    .map { it.get(null) as String }
            }
            .toSet() + NodeEntity.FOLDER
        // Anchored: each application's kinds object was read.
        assertTrue(constants.containsAll(listOf("MEMORY", "FILE", "EXPENSES_POT")), "the kind constants read: $constants")
        assertEquals(emptySet<String>(), constants - known.toSet(), "kind constants of the compiled code no kinds file states")
    }
}
