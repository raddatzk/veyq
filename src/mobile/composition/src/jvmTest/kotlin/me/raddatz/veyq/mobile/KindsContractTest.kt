package me.raddatz.veyq.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.models.Capability
import me.raddatz.veyq.mobile.composition.AppComposition
import me.raddatz.veyq.mobile.navigation.ShareSheet
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.LinkRow
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestLevel
import me.raddatz.veyq.mobile.tree.MovePlan
import me.raddatz.veyq.mobile.tree.TreeFacts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The core's kinds answer `contracts/platform/kinds/` (inbox #1061; ADR 0156 decision 2's registry as where they are read):
 * every kind of every owner's file, and every kind this build registers, asked through the members the apps read — the
 * registry's placement ([AppKinds.facts]), its header ([AppKinds.header]: the cover, whether it shares), its guest doors
 * ([AppKinds.guestDoor]), the share sheet's roles ([ShareSheet], [LinkRow.mint]), the creates a member's New and a guest's
 * level offer ([AppKinds.createsWhere], [GuestLevel.createsFor]) and the guest's move ([MovePlan.hereBlock]). The third
 * client held to the files, beside the backend's `KindsContractTest` and the web's `kindsContract.test.ts`: a fact
 * changed in a file or in the core alone is red here. `guest.writeOnlyCreates` is the backend's alone (the schema: the
 * drop page is the one place such a guest creates, and it is the application's own), as on the web.
 */
class KindsContractTest {
    init { AppComposition.install() }

    private class Kind(
        val owner: String, val root: Boolean, val parents: Set<String>, val cover: Boolean, val createNeeds: String?,
        val shareRoles: Set<String>, val creatableInFolder: Boolean, val container: Boolean,
    )

    private val dir = File(System.getProperty("veyq.repoRoot"), "contracts/platform/kinds")

    private val contract: Map<String, Kind> = buildMap {
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "kinds.schema.json" }.orEmpty()
        assertTrue(files.isNotEmpty(), "no kinds file under $dir")
        for (file in files.sortedBy { it.name }) {
            val doc = Json.parseToJsonElement(file.readText()).jsonObject
            val owner = doc.getValue("owner").jsonPrimitive.content
            assertEquals(file.name.removeSuffix(".json"), owner, "${file.name} names its owner")
            for ((name, k) in doc.getValue("kinds").jsonObject) {
                val kind = k.jsonObject
                val placement = kind.getValue("placement").jsonObject
                val guest = kind.getValue("guest").jsonObject
                val previous = put(
                    name,
                    Kind(
                        owner = owner,
                        root = placement.getValue("root").jsonPrimitive.boolean,
                        parents = placement.strings("parents"),
                        cover = kind.getValue("cover").jsonPrimitive.boolean,
                        createNeeds = kind.getValue("createNeeds").let { if (it is JsonNull) null else it.jsonPrimitive.content },
                        shareRoles = kind.strings("shareRoles"),
                        creatableInFolder = guest.getValue("creatableInFolder").jsonPrimitive.boolean,
                        container = guest.getValue("container").jsonPrimitive.boolean,
                    ),
                )
                assertEquals(null, previous?.owner, "$name is stated in two files")
            }
        }
    }

    private fun JsonObject.strings(key: String): Set<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** Every kind this build registers: the registry's facts, core's and each application's. */
    private val registered: List<String> = AppKinds.facts.kinds.map { it.kind }

    @Test
    fun everyRegisteredKindIsInItsOwnersFileAndEveryKindOfTheFilesIsRegistered() {
        val ownerOf = registered.associateWith { kind ->
            AppComposition.applications.firstOrNull { kind in it.kinds }?.application?.id ?: "core"
        }
        assertEquals(contract.mapValues { it.value.owner }.toSortedMap(), ownerOf.toSortedMap())
    }

    @Test
    fun placementIsTheFiles() {
        for ((kind, k) in contract) {
            assertEquals(k.root, AppKinds.facts.placementAllows(kind, null), "$kind at the scope root")
            assertEquals(k.parents, registered.filter { AppKinds.facts.placementAllows(kind, it) }.toSet(), "$kind's parents")
            assertEquals(false, AppKinds.facts.placementAllows(kind, "NOT_A_KIND"), "$kind in a kind this build does not know")
        }
    }

    @Test
    fun coverIsTheFiles() {
        for ((kind, k) in contract) {
            val header = assertNotNull(AppKinds.header(kind), "$kind has a header")
            assertEquals(k.cover, header.takesCover, "$kind takes a cover")
            assertEquals(k.cover, AppKinds.coverSettable(kind, AppKinds.Facts()), "$kind's cover settable on a writable page")
        }
    }

    @Test
    fun shareRolesAreTheFilesOnTheHeaderTheGuestDoorsAndTheSheet() {
        for ((kind, k) in contract) {
            assertEquals(k.shareRoles.isNotEmpty(), AppKinds.header(kind)!!.shareable, "$kind's header offers a share")
            for (role in ROLES) {
                assertEquals(role in k.shareRoles, AppKinds.guestDoor(kind, role) != null, "$kind answers a $role link's guest")
            }
            if (k.shareRoles.isEmpty()) continue
            // a member with every leaf on: the roles the new-link form offers, each as it is minted on the node
            val sheet = ShareSheet(NodeAt("s", "n", kind, "x"), Door(ShareLinks.Rules.UNKNOWN), { NOW })
            sheet.addLink()
            val form = assertNotNull(sheet.current.form, "$kind's sheet opens a form")
            val rooted = form.roles.ifEmpty { listOf(form.role) }
                .map { LinkRow.mint(it, "label", onDropBox = sheet.dropBox, dropBoxes = !sheet.memory) }
                .filterNot { it.makesDropBox }
                .map { it.role }
            assertEquals(k.shareRoles, rooted.toSet(), "the roles of a link $kind's sheet roots at it")
        }
    }

    @Test
    fun everyKindIsCreatedSomewhereAndOnlyTheFilesCapabilityGatesIt() {
        // a member's New anywhere (a level of Home, with the kept link's dialog) and a Memory's own New: no capability asked
        val byNew = (AppKinds.createsWhere(isHome = true, bookmarks = true) + AppKinds.Create.CHAPTER).flatMap(::kindsOf).toSet()
        // the share sheet's "Add files only" on a folder: a drop box, offered by the tenant's capability set
        val bySheet = madeBySheet(ShareLinks.Rules.UNKNOWN)
        assertEquals(registered.toSet(), byNew + bySheet, "every kind is created by a member somewhere")
        for ((kind, k) in contract) {
            if (kind in byNew) assertEquals(null, k.createNeeds, "$kind is made by New, which no capability gates")
        }
        for (kind in bySheet) {
            val needs = assertNotNull(contract.getValue(kind).createNeeds, "$kind is made under a capability")
            val all = BASE_CAPABILITIES + needs
            assertTrue(kind in madeBySheet(ShareLinks.Rules.of(all.map(::capability))), "$kind made with $needs on")
            assertTrue(kind !in madeBySheet(ShareLinks.Rules.of(BASE_CAPABILITIES.map(::capability))), "$kind not made with $needs off")
        }
    }

    @Test
    fun aGuestCreatesTheFilesKindsInAFolderAndNothingElsewhere() {
        val inFolder = GuestLevel.createsFor(GuestAccess.READ_WRITE, inIdentityFolder = false).flatMap(::kindsOf).toSet()
        assertEquals(contract.filterValues { it.creatableInFolder }.keys, inFolder, "a READ_WRITE guest's creates in a folder")
        assertEquals(emptyList(), GuestLevel.createsFor(GuestAccess.READ_WRITE, inIdentityFolder = true))
        assertEquals(emptyList(), GuestLevel.createsFor(GuestAccess.READ, inIdentityFolder = false))
        assertEquals(emptyList(), GuestLevel.createsFor(GuestAccess.WRITE_ONLY, inIdentityFolder = false))
    }

    @Test
    fun aGuestsContainerIsTheFiles() {
        for ((kind, k) in contract) {
            // the guest's page of it is core's writing level
            assertEquals(k.container, AppKinds.guestDoor(kind, GuestAccess.READ_WRITE) === GuestLevel.door, "$kind opens as a guest's level")
            // a guest's move into it: any kind its placement takes, from elsewhere in the link
            val movedIn = registered.filter { AppKinds.facts.placementAllows(it, kind) }.any { child ->
                val tree = TreeFacts(
                    listOf(
                        TreeFacts.Node("at", null, kind, "at", null, identity = false),
                        TreeFacts.Node("from", null, "FOLDER", "from", null, identity = false),
                        TreeFacts.Node("moved", "from", child, "moved", null, identity = false),
                    ),
                )
                MovePlan.hereBlock(AppKinds.facts::placementAllows, tree, listOf(tree.node("moved")!!), guest = true, at = "at") == null
            }
            assertEquals(k.container, movedIn, "a guest moves a node into a $kind")
        }
    }

    /** The kinds of the nodes a New entry creates. Exhaustive: a new entry does not compile until it is answered here. */
    private fun kindsOf(create: AppKinds.Create): List<String> = when (create) {
        AppKinds.Create.FOLDER -> listOf("FOLDER")
        AppKinds.Create.POT -> listOf("EXPENSES_POT")
        AppKinds.Create.UPLOAD_FILES -> listOf(DepotFiles.FILE)
        AppKinds.Create.UPLOAD_FOLDER -> listOf("FOLDER", DepotFiles.FILE)
        AppKinds.Create.MEMORY -> listOf("MEMORY")
        AppKinds.Create.CHAPTER -> listOf("CHAPTER")
        AppKinds.Create.BOOKMARK -> listOf(AppKinds.BOOKMARK_KIND)
    }

    /** The kinds a folder's share sheet makes under [rules]: a drop box where a role offered makes one. */
    private fun madeBySheet(rules: ShareLinks.Rules): Set<String> {
        val sheet = ShareSheet(NodeAt("s", "f", "FOLDER", "x"), Door(rules), { NOW })
        sheet.addLink()
        val form = sheet.current.form ?: return emptySet()
        val makes = form.roles.any { LinkRow.mint(it, "label", onDropBox = sheet.dropBox, dropBoxes = !sheet.memory).makesDropBox }
        return if (makes) setOf(DepotFiles.DROPBOX) else emptySet()
    }

    /** A sheet's door that is only read for its rules: nothing here writes. */
    private class Door(override val rules: ShareLinks.Rules) : ShareSheet.Door {
        override suspend fun list(): List<ShareLinks.Link> = emptyList()
        override suspend fun mint(role: String, label: String, password: String, expiresAt: String?): ShareLinks.Minted = error("no write")
        override suspend fun createDropBox(name: String, password: String, expiresAt: String?): ShareSheet.Made = error("no write")
        override suspend fun edit(shareId: String, edit: ShareLinkChanges.Edit): ShareLinkChanges.Changed = error("no write")
        override suspend fun renew(shareId: String): ShareLinkChanges.Changed = error("no write")
        override suspend fun switchOff(shareId: String): ShareLinks.Closed = error("no write")
        override suspend fun reactivate(shareId: String): ShareLinkChanges.Changed = error("no write")
        override suspend fun delete(shareId: String): ShareLinkChanges.Changed = error("no write")
        override fun rekeyed() {}
        override fun changed() {}
    }

    private companion object {
        val ROLES = listOf(GuestAccess.READ, GuestAccess.READ_WRITE, GuestAccess.WRITE_ONLY)
        const val NOW = 1_700_000_000_000L

        /** Links on, with guests who add: every leaf a kind's creation could need but the one asked about. */
        val BASE_CAPABILITIES = listOf("core.public-shares", "core.public-shares.contribute")

        fun capability(id: String): Capability = Json { ignoreUnknownKeys = true }.decodeFromString(Capability.serializer(), """{"id":"$id"}""")
    }
}
