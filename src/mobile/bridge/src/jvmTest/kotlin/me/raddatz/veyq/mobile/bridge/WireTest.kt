@file:OptIn(ExperimentalSerializationApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Amount
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Moment
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every registered root round-trips (CR D7, the JVM half): one value of each — of every variant of each sealed root —
 * is written as the bridge writes it, read back and written again to the same JSON, and the JSON is held to the
 * descriptor the generator reads (every field present by its name, no other, each of its kind; a sealed value tagged
 * with one of its variants). Every variant of every sealed type the roots reach must occur in what was written: a
 * variant or a root added without a sample is red by derivation. The samples go to `build/generated/bridge/samples`,
 * which the Rust half deserializes field for field.
 */
class WireTest {
    private val ref = ObjectRef("scope-1", "node-1", "EXPENSE_POT", ApplicationId.EXPENSES, "Trip to Lyon")
    private val folder = FolderRef("folder-1", "Holidays")
    private val shell = Shell(application = ApplicationId.EXPENSES, openObject = ref, tab = BarTab("expenses.entries"), space = "scope-1",
        path = listOf(folder), filter = ApplicationId.MEMORIES, back = Shell(application = null, space = "scope-1", path = listOf(folder)), top = false)
    private val lists = ShellModel.Lists(
        spaces = listOf(SpaceRef("scope-1", "PERSONAL", "Mine")),
        nodes = listOf(NodeRef("scope-1", "folder-1", null, NodeRef.FOLDER, "Holidays"), NodeRef("scope-1", "node-1", "folder-1", "EXPENSE_POT", "Trip to Lyon", identity = true)),
        objects = listOf(ref), favorites = listOf(ref), applications = listOf(ApplicationId.MEMORIES, ApplicationId.EXPENSES),
        filters = listOf(null, ApplicationId.EXPENSES), drawer = ArrangedList(listOf(ref), emptyList(), listOf(ref)),
    )
    private val tenant = TenantRef("t", "Turnverein", "verein.localhost")
    private val shown = ShellShown(
        tenant = tenant, server = "veyq.localhost:8080", connection = "veyq.localhost#user-1",
        servers = listOf(ShellShown.ShownServer("veyq.localhost#user-1", "veyq.localhost:8080", listOf(tenant))),
        space = lists.spaces.first(), spaceName = Piece.Said(Notice(Msg.SPACE_PERSONAL)), switcherSubtitle = "Turnverein · veyq.localhost:8080",
        spaces = listOf(ShellShown.ShownSpace(lists.spaces.first(), Piece.Plain("Mine"))),
        applicationWords = listOf(ShellShown.ShownApplication(ApplicationId.EXPENSES, Msg.APP_EXPENSES)),
        groups = listOf(Home.Group(true, lists.nodes.take(1), null)), list = lists.drawer,
        tabs = listOf(ShellShown.ShownTab(BarTab("expenses.entries"), Msg.TAB_EXPENSES_ENTRIES)), selectedTab = BarTab("expenses.entries"),
        canGoBack = true, isHome = false, drawerOpensAtStart = false, creates = listOf(Msg.FOLDER_NEW),
    )
    private val record = TrustedDevice("http://veyq.localhost:8080", "p@example.org", "user-1", TrustedDevice.Method.DEVICE, "wrap-1", "veyq.device.ab")

    /** Each root's values: every variant of a sealed root once. */
    private val samples: Map<KSerializer<*>, List<Any>> = mapOf(
        Config.serializer() to listOf(Config("Fedora 44")),
        Call.serializer() to listOf(
            Call.SessionStart, Call.SessionSignIn("veyq.localhost:8080", "p@example.org", "typed", "A3-TYPED"), Call.SessionSwitchTo("veyq.localhost#user-1", "t"),
            Call.SessionSignOut("veyq.localhost#user-1"), Call.ShellChoose(null), Call.ShellSwitchSpace("scope-1"), Call.ShellEnter(folder),
            Call.ShellFilter(ApplicationId.DEPOT), Call.ShellBack, Call.ShellSelect(BarTab("expenses.overview")), Call.ShellOpen(ref), Call.ShellOpenAtTop(ref), Call.ShellSearch(true), Call.ShellRefresh,
            Call.HomeGroups, Call.SearchQuery("lyon"), Call.PotOpen(ref), Call.PotRefresh(3), Call.PotClose(3),
        ),
        Watched.serializer() to listOf(Watched.Session, Watched.Shell, Watched.Pot(3)),
        HostReply.serializer() to listOf(HostReply.Value("s\tn\t1"), HostReply.Value(null), HostReply.Done, HostReply.Failed("disk full")),
        Answer.serializer() to listOf(
            Answer.Done, Answer.Refused(Answer.Why.NO_TENANT_OPEN), Answer.Failed("IllegalStateException"),
            Answer.Groups(listOf(Home.Group(true, lists.nodes.take(1), Msg.HOME_GROUP_FOLDERS), Home.Group(false, lists.nodes.drop(1), null))),
            Answer.Hits(listOf(ref)), Answer.PotOpened(3),
        ),
        Update.serializer() to listOf(
            Update.Session(Gate.Starting), Update.Session(Gate.NeedsApp), Update.Session(Gate.SignIn(error = Msg.SIGNIN_ERROR_CREDENTIALS, server = "veyq.localhost:8080")),
            Update.Session(Gate.Unlock(record)), Update.Session(Gate.Signed("veyq.localhost#user-1", "t", switching = true)),
            Update.Shell(ShellModel.State(shell, lists), shown), Update.Shell(null),
            Update.Pot(3, PotModel.State(ref, failed = false, saving = true, message = "REFUSED")), Update.Pot(4, null),
        ),
        HostRequest.serializer() to listOf(HostRequest.StoreRead("veyq.recent"), HostRequest.StoreWrite("veyq.recent", "s\tn\t1"), HostRequest.StoreRemove("veyq.recent")),
    )

    /**
     * A Pot's view as the bridge writes it (`PotView.AsShown`: its [PotView.Shown]). There is no view to build without
     * a Pot's session, so its JSON is the [PotView.Shown] it writes, held to the view's own descriptor.
     */
    private val potView: PotView.Shown = run {
        fun n(m: Msg, vararg a: Any) = Notice(m, a.toList())
        val eur = { v: Long -> Amount(v, "EUR", 2) }
        val entry = PotView.EntryRow("e1", PotView.Kind.EXPENSE, "Dinner", "2026-11-10", "food", listOf("Anna", ""), 4200, 4500, "CHF", 2, Ledger.Status.LIVE, true)
        val person = PotView.PersonRow("p1", "Anna", 4200, 2100, 2100, true, 0, "user-1")
        PotView.Shown(
            name = "Trip to Lyon", subtitle = n(Msg.POT_IN_CURRENCY, "EUR"), currency = "EUR", minorUnits = 2, ready = true, canWrite = true,
            entries = listOf(PotView.ShownEntry(entry, listOf(Piece.Day("2026-11-10"), Piece.Said(n(Msg.CAT_FOOD)), Piece.Plain("Anna"),
                Piece.Said(n(Msg.POT_EXPENSE_BY, Joined(listOf("Anna", n(Msg.POT_DELETED_PERSON)), ", "))), Piece.Money(4500, "CHF", 2), Piece.Size(2048)),
                PotView.Badge(n(Msg.POT_EDITED), false))),
            balances = listOf(PotView.ShownBalance(person, Piece.Plain("Anna"), PotView.Balance(n(Msg.POT_IS_OWED, eur(2100)), PotView.Tone.OWED), listOf(Piece.Said(n(Msg.POT_PAID_CONSUMED, eur(4200), eur(2100)))))),
            settleUp = listOf(PotView.ShownSettle(PotView.SettleRow("Ben", "Anna", 2100, "p2", "p1"), n(Msg.POT_PAYS, "Ben", "Anna"))),
            total = 4200,
            byCategory = listOf(PotView.ShownCategory(PotView.CategoryRow("food", null, 4200), Piece.Said(n(Msg.CAT_FOOD)))),
            people = listOf(PotView.ShownPerson(PotView.PersonEntry("p1", "Anna", PotView.Standing.MEMBER, true, 2100, false, true, false, true, "user-1"), listOf(Piece.Money(2100, "EUR", 2)))),
            deletedPeople = 1,
            // the other kinds a notice's argument takes, so the Rust half reads each
            missing = listOf(Piece.Said(n(Msg.POT_LEDGER_UNKNOWN_ENTRY)), Piece.Said(Notice(Msg.POT_IN_CURRENCY, listOf(3, 3L, Bytes(9), Moment(1_700_000_000_000))))),
        )
    }

    private val schema = Schema(Roots.all)
    private val rustNames = Bindings.rustNames(schema.types.keys)
    private val variantsSeen = HashSet<Pair<String, String>>()

    @Test
    fun everyRootRoundTripsAndItsJsonIsWhatItsDescriptorSays() {
        assertEquals(Roots.all.map { it.descriptor.serialName }.toSet(), samples.keys.map { it.descriptor.serialName }.toSet(), "a root without samples")
        val out = System.getProperty("veyq.samples")?.let { File(it).apply { deleteRecursively(); mkdirs() } }
        for ((serializer, values) in samples) {
            @Suppress("UNCHECKED_CAST") val s = serializer as KSerializer<Any>
            values.forEachIndexed { i, v ->
                val json = wire.encodeToString(s, v)
                val again = wire.encodeToString(s, wire.decodeFromString(s, json))
                assertEquals(json, again, "${s.descriptor.serialName} did not round-trip")
                conform(wire.parseToJsonElement(json), s.descriptor, s.descriptor.serialName)
                out?.resolve("${rustNames.getValue(s.descriptor.serialName)}-$i.json")?.writeText(json)
            }
        }
        val view = wire.encodeToString(PotView.Shown.serializer(), potView)
        conform(wire.parseToJsonElement(view), PotView.AsShown.descriptor, "PotView")
        out?.resolve("${rustNames.getValue("PotView")}-0.json")?.writeText(view)

        val missing = schema.types.values.filter { it.isSealed }.flatMap { t -> t.variants.map { t.serialName to it.serialName } }.filter { it !in variantsSeen }
        assertTrue(missing.isEmpty(), "variants no sample writes: $missing")
    }

    @Test
    fun aPotViewIsWrittenAsWhatItShowsUnderItsOwnName() {
        val view = PotView.AsShown.descriptor
        val shown = PotView.Shown.serializer().descriptor
        assertEquals("PotView", view.serialName)
        assertEquals(shown.elementNames.toList(), view.elementNames.toList())
        assertEquals(shown.elementDescriptors.map { it.serialName }, view.elementDescriptors.map { it.serialName })
    }

    /** [e] is what [d] describes, exactly. */
    private fun conform(e: JsonElement, d: SerialDescriptor, at: String) {
        if (e is JsonNull) return if (d.isNullable) Unit else fail("$at: null where ${d.serialName} is not nullable")
        fun prim(ok: (JsonPrimitive) -> Boolean) = assertTrue(e is JsonPrimitive && ok(e), "$at: $e is no ${d.kind}")
        when (d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> prim { it.isString }
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> prim { !it.isString && it.longOrNull != null }
            PrimitiveKind.BOOLEAN -> prim { !it.isString && it.booleanOrNull != null }
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> prim { !it.isString && it.doubleOrNull != null }
            SerialKind.ENUM -> prim { it.isString && it.content in d.elementNames }
            StructureKind.LIST -> (e as? JsonArray ?: fail("$at: no array")).forEachIndexed { i, x -> conform(x, d.getElementDescriptor(0), "$at[$i]") }
            StructureKind.MAP -> (e as? JsonObject ?: fail("$at: no map")).forEach { (k, x) -> conform(x, d.getElementDescriptor(1), "$at.$k") }
            StructureKind.CLASS, StructureKind.OBJECT -> fields(e, d, at, d)
            PolymorphicKind.SEALED -> {
                val o = e as? JsonObject ?: fail("$at: no object")
                val tag = (o["type"] as? JsonPrimitive)?.content ?: fail("$at: no type")
                val variant = d.getElementDescriptor(1).elementDescriptors.firstOrNull { it.serialName == tag } ?: fail("$at: $tag is no variant of ${d.serialName}")
                variantsSeen += Schema.nameOf(d) to tag
                fields(JsonObject(o - "type"), variant, "$at<$tag>", variant)
            }
            else -> fail("$at: ${d.kind} has no form on the wire")
        }
    }

    private fun fields(e: JsonElement, d: SerialDescriptor, at: String, of: SerialDescriptor) {
        val o = e as? JsonObject ?: fail("$at: no object")
        assertEquals(of.elementNames.toSet(), o.keys, "$at: fields")
        (0 until of.elementsCount).forEach { i -> conform(o.getValue(of.getElementName(i)), of.getElementDescriptor(i), "$at.${of.getElementName(i)}") }
    }
}
