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
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
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
 * Every registered root round-trips (ADR 0123's Consequences, the bridge, the JVM half): one value of each — of every variant of each sealed root —
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
        canGoBack = true, isHome = false, drawerOpensAtStart = false, creates = listOf(ShellShown.ShownCreate(me.raddatz.veyq.mobile.AppKinds.Create.FOLDER, Msg.FOLDER_NEW)),
        canCreateHere = true, headed = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"),
        me = "user-1", pictures = listOf("user-1", "user-2"),
    )
    private val record = TrustedDevice("http://veyq.localhost:8080", "p@example.org", "user-1", TrustedDevice.Method.DEVICE, "wrap-1", "veyq.device.ab")

    /** Each root's values: every variant of a sealed root once. */
    private val samples: Map<KSerializer<*>, List<Any>> = mapOf(
        Config.serializer() to listOf(Config("Fedora 44", 0, ""), Config("WIN-VM", 0x2051C, "veyq wants to open your account")),
        Call.serializer() to listOf(
            Call.SessionStart, Call.SessionSignIn("veyq.localhost:8080", "p@example.org", "typed", "A3-TYPED"),
            Call.SessionStaySignedIn(StaySignedIn.PRESENCE), Call.SessionStaySignedIn(StaySignedIn.LOGIN), Call.SessionNotNow,
            Call.ManageStaySignedIn("veyq.localhost#user-1", StaySignedIn.LOGIN), Call.ManagePasswordTrust("veyq.localhost#user-1", "typed"),
            Call.SessionUnlock("typed"), Call.SessionUnlock(""), Call.SessionOtherAccount, Call.SessionForgetDevice("veyq.localhost#user-1"),
            Call.SessionAddServer, Call.SessionCancelAdd, Call.ManageOpen, Call.ManageClose, Call.ManageHideTenant("veyq.localhost#user-1", "t2", hidden = true),
            Call.ManageHideSpace("scope-1", hidden = false), Call.SessionSwitchTo("veyq.localhost#user-1", "t"),
            Call.SessionSignOut("veyq.localhost#user-1"), Call.ShellChoose(null), Call.ShellSwitchSpace("scope-1"), Call.ShellEnter(folder),
            Call.ShellFilter(ApplicationId.DEPOT), Call.ShellBack, Call.ShellSelect(BarTab("expenses.overview")), Call.ShellOpen(ref), Call.ShellOpenAtTop(ref), Call.ShellSearch(true), Call.ShellRefresh,
            Call.HomeGroups, Call.SearchQuery("lyon"), Call.PotOpen(ref), Call.PotRefresh(3), Call.PotClose(3),
            Call.PotDownload(3, "/home/p/Downloads/Trip to Lyon.zip", "deleted person"), Call.PotArchiveName(3), Call.AvatarPicture("user-1"),
            Call.PotRecord(3, listOf(PotView.SettleRow("Ben", "Anna", 2100, "p2", "p1"))), Call.PotRestore(3, "e2"), Call.PotHistory(3, "e2"),
            Call.ReaderRead(5, 262_144, 65_536), Call.ReaderClose(5),
            Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = false), 2), Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), null, 0),
            Call.HeaderFacts(7, me.raddatz.veyq.mobile.AppKinds.Facts()), Call.HeaderClose(7), Call.HeaderPin(7), Call.HeaderTrash(7), Call.HeaderUndo(7),
            Call.HeaderRename(7, "Trip to Lyon 2"), Call.ShellCreateFolder("Plans"), Call.TrashOpen("scope-1"), Call.TrashClose(8),
            Call.TrashRestore(8, "node-1"), Call.TrashType(8, "Trip 2"), Call.TrashRetry(8, "node-1"), Call.TrashCancelRename(8), Call.TrashPurge(8, "node-1"),
            Call.HeaderDownload(7, "/home/p/Downloads/Holidays.zip", "deleted person"),
            Call.MoveOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), copy = true, deletedName = "deleted person"),
            Call.MoveClose(9), Call.MoveGo(9, "folder-1"), Call.MoveGo(9, null), Call.MoveUp(9), Call.MoveHere(9), Call.MoveConfirm(9),
            Call.MoveCancelConfirm(9), Call.MoveUndo(9),
            Call.ShareOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays")), Call.ShareClose(10), Call.ShareAdd(10),
            Call.ShareEdit(10, "share-1"), Call.ShareRole(10, "READ_WRITE"), Call.ShareLabel(10, "Family"), Call.ShareLockOn(10, true),
            Call.SharePassword(10, "typed by the person"), Call.ShareEndOn(10, false), Call.ShareEndDay(10, 20_800), Call.ShareCancelForm(10),
            Call.ShareSave(10), Call.ShareFreshSeen(10), Call.ShareAskRenew(10, "share-1"), Call.ShareAskDelete(10, "share-1"), Call.ShareCancelAsk(10),
            Call.ShareRenew(10), Call.ShareDelete(10), Call.ShareSwitchOff(10, "share-1"), Call.ShareReactivate(10, "share-1"),
            Call.HeaderCoverPicture(7), Call.HeaderCoverSave(7, "/tmp/cover.jpg", me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.0, 0.5, 1.0)),
            Call.HeaderCoverSave(7, null, me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL), Call.HeaderCoverRemove(7),
            Call.CoverPose(4000.0, 3000.0, 640.0, 360.0, me.raddatz.veyq.mobile.session.CoverFrame.Pose(1.5, -10.0, -20.5), null, 3.0, -2.0, 1.1),
            Call.CoverPose(4000.0, 3000.0, 640.0, 360.0, null, me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL, 0.0, 0.0, 1.0), Call.CoverFit(4000, 3000),
            Call.CoverCut(me.raddatz.veyq.mobile.session.NodeCover.Crop(0.25, 0.0, 0.5, 1.0), 4000, 3000),
            Call.ReportOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays")), Call.ReportClose(11), Call.ReportWhole(11, false),
            Call.ReportCategory(11, "ILLEGAL_CONTENT"), Call.ReportReason(11, "why"), Call.ReportGoodFaith(11, true), Call.ReportContact(11, "me@example.org"), Call.ReportSend(11),
            Call.EditorOpen(3, null, ","), Call.EditorOpen(3, "e1", "."), Call.EditorSave(12), Call.EditorAskDelete(12), Call.EditorDelete(12), Call.EditorCancelAsk(12), Call.EditorClose(12),
            // every edit the editor takes
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Kind(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.REFUND)), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Day("2026-11-12")),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Title("Museum")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Category("food")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Currency("CHF")),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.RateTyped("1,08")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Amount("20,00")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.From("p1")),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.To("p2")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.AddPayer), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.RemovePayer(1)),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Payer(0, "p2")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Paid(0, "30")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.AddItem),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.RemoveItem(0)), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.ItemLabel(0, "Tickets")), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.ItemAmount(0, "20")),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.SplitMode(0, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.PERCENT)), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.SplitMode(null, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.AMOUNTS)),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Toggle(null, "p1", false)), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Share(0, "p1", "70")),
            Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Equally(null)), Call.EditorChange(12, me.raddatz.veyq.mobile.expenses.EditorChange.Expand(1)),
        ),
        Watched.serializer() to listOf(Watched.Session, Watched.Shell, Watched.Pot(3), Watched.PotLive(3), Watched.Manage, Watched.Header(7), Watched.Trash(8), Watched.Move(9), Watched.Share(10), Watched.Report(11), Watched.Editor(12)),
        HostReply.serializer() to listOf(HostReply.Value("s\tn\t1"), HostReply.Value(null), HostReply.Done, HostReply.Failed("disk full")),
        Answer.serializer() to listOf(
            Answer.Done, Answer.Refused(Answer.Why.NO_TENANT_OPEN), Answer.Refused(Answer.Why.TRUST_CANCELLED), Answer.Failed("IllegalStateException"),
            Answer.Groups(listOf(Home.Group(true, lists.nodes.take(1), Msg.HOME_GROUP_FOLDERS), Home.Group(false, lists.nodes.drop(1), null))),
            Answer.Hits(listOf(ref)), Answer.PotOpened(3), Answer.Name("Trip to Lyon.zip"), Answer.Bytes("image/png"), Answer.Bytes(null),
            Answer.Reader(5, 9_000_000_000, "video/mp4"), Answer.Reader(6, 0, null),
            Answer.HeaderOpened(7), Answer.TrashOpened(8), Answer.Said(Notice(Msg.TRASH_MOVED, listOf("Trip"))), Answer.Said(null), Answer.Trashed(Notice(Msg.TRASH_MOVED, listOf("Trip")), undoable = true),
            Answer.Renamed(Notice(Msg.NODE_SLUG_TAKEN), null), Answer.Renamed(null, Notice(Msg.SHARE_NAME_RESEAL_FAILED)), Answer.Refused(Answer.Why.NO_SUCH_ROW),
            Answer.MoveOpened(9), Answer.ShareOpened(10), Answer.ReportOpened(11), Answer.Refused(Answer.Why.NO_COVER),
            Answer.CoverPosed(me.raddatz.veyq.mobile.session.CoverFrame.Pose(1.0, 0.0, -12.5), 640.0, 385.0, me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.1, 1.0, 0.75)),
            Answer.CoverFit(2048, 1536, 0.85, 85), Answer.CoverCut(1000, 0, 2000, 3000),
            Answer.EntryHistory(listOf(HistoryShown(listOf(Piece.Said(Notice(Msg.HISTORY_DELETED))), "Anna", 1_700_000_000_000), HistoryShown(listOf(Piece.Plain("Taxi"), Piece.Money(999, "EUR", 2)), null, 1_699_000_000_000)),
                deleted = true, restorable = true, until = 1_702_000_000_000), Answer.Refused(Answer.Why.NO_SUCH_SHARE), Answer.MoveSent(Notice(Msg.MOVE_MOVED, listOf("Trip", Notice(Msg.SPACE_PERSONAL))), undoable = true, confirming = false, problem = null),
            Answer.MoveSent(null, undoable = false, confirming = true, problem = Notice(Msg.MOVE_REFUSED_GONE)),
            Answer.Downloaded(true, Notice(Msg.DOWNLOAD_DONE, listOf("Holidays")), Notice(Msg.DOWNLOAD_LEFT_OUT, listOf(Joined(listOf(Notice(Msg.LEFT_OUT_LINE, listOf("x", Notice(Msg.LEFT_OUT_UNSUPPORTED)))), "; ")))),
            Answer.Downloaded(false, Notice(Msg.DOWNLOAD_FAILED, listOf("Holidays")), null),
            Answer.EditorOpened(12), Answer.EditorSaved(true, Notice(Msg.RECEIPTS_NO_ROOM, listOf(Joined(listOf("a.jpg"), ", ")))), Answer.EditorSaved(false, null),
            Answer.Refused(Answer.Why.NO_SUCH_EDITOR),
        ),
        Update.serializer() to listOf(
            Update.Session(Gate.Starting), Update.Session(Gate.NeedsApp), Update.Session(Gate.SignIn(error = Msg.SIGNIN_ERROR_CREDENTIALS, server = "veyq.localhost:8080")),
            Update.Session(Gate.SignIn()), Update.Session(Gate.SignIn(adding = true)),
            Update.Session(Gate.Signed("veyq.localhost#user-1", "t", offer = SessionModel.Offer("veyq.localhost#user-1", listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)))),
            Update.Session(Gate.Unlock(record)), Update.Session(Gate.Unlock(record.copy(method = TrustedDevice.Method.DEVICE_KEY, level = StaySignedIn.LOGIN))), Update.Session(Gate.Signed("veyq.localhost#user-1", "t", switching = true)),
            Update.Shell(ShellModel.State(shell, lists), shown), Update.Shell(null),
            Update.Pot(3, PotModel.State(ref, failed = false, saving = true, message = "REFUSED"), listOf("user-1")), Update.Pot(4, null),
            Update.Header(7, HeaderShown(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), pinned = true,
                plan = HeaderShown.Plan(listOf(HeaderShown.PlanAction("favorite", Msg.NODE_UNFAVORITE)), listOf(HeaderShown.PlanAction("own", null)), listOf(HeaderShown.PlanAction("delete", Msg.POT_DELETE))),
                deleteQuestion = Notice(Msg.POT_DELETE_CONFIRM, listOf("Trip to Lyon")), undo = Notice(Msg.TRASH_MOVED, listOf("Trip to Lyon")), gone = true, archiveName = "Trip to Lyon.zip", cover = me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.25, 0.5, 0.5))),
            Update.Header(7, HeaderShown(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), pinned = null, plan = null, deleteQuestion = Notice(Msg.POT_DELETE_CONFIRM, listOf("x")), undo = null, gone = false, archiveName = "x.zip", cover = null)),
            Update.Header(9, null),
            Update.Trash(8, TrashShown(listOf(TrashShown.Row("node-1", "FOLDER", "Trip", "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", restorable = true, retryable = false)),
                failed = false, busy = "node-1", errorRow = "node-1", error = Msg.NODE_SLUG_TAKEN, renaming = "node-1", retryName = "Trip 2")),
            Update.Trash(8, TrashShown(null, failed = true, busy = null, errorRow = null, error = null, renaming = null, retryName = "")), Update.Trash(9, null),
            Update.Move(9, MoveShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"), copying = true,
                title = Notice(Msg.COPY_TITLE, listOf("Holidays")), hint = Msg.COPY_HINT, submit = Msg.COPY_SUBMIT, loaded = true,
                trail = listOf(MoveShown.MoveCrumb(null, Piece.Said(Notice(Msg.SPACE_PERSONAL))), MoveShown.MoveCrumb("folder-2", Piece.Plain("Trips"))),
                places = listOf(MoveShown.MovePlace("folder-1", "FOLDER", "Holidays", Msg.MOVE_BLOCKED_SELF), MoveShown.MovePlace("folder-3", "FOLDER", "2026", null)),
                empty = null, here = Msg.MOVE_HERE_CURRENT, problem = Notice(Msg.COPY_NAME_TAKEN), noRoom = Notice(Msg.COPY_NO_ROOM, listOf(Bytes(4096))),
                losses = listOf(Notice(Msg.COPY_LOSS_ALWAYS), Notice(Msg.COPY_LOSS_FOLDER_COVER)), leftOut = listOf(Notice(Msg.LEFT_OUT_LINE, listOf("x", Notice(Msg.LEFT_OUT_NOT_COPYABLE)))),
                busy = false, canChoose = false, confirming = true, confirmQuestion = Notice(Msg.MOVE_IDENTITY_CONFIRM, listOf("Holidays")),
                done = Notice(Msg.MOVE_MOVED, listOf("Holidays", "Trips")), undo = Notice(Msg.MOVE_MOVED, listOf("Holidays", "Trips")),
            )),
            Update.Move(9, null),
            Update.Report(11, ReportShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"),
                categories = listOf(ReportShown.ShownCategory("CHILD_SEXUAL_ABUSE", Msg.REPORT_CAT_CSAM), ReportShown.ShownCategory("TERMS_BREACH", Msg.REPORT_CAT_TERMS)),
                whole = false, scopeHint = Msg.REPORT_SCOPE_NAME_HINT, category = "CHILD_SEXUAL_ABUSE", note = Msg.REPORT_CSAM_NOTE, reasonMax = 2000,
                contactMax = 254, goodFaith = true, canSend = false, sending = false, problem = Notice(Msg.REPORT_TOO_MANY_OPEN), sent = true,
                reference = "abcd1234", receivedAt = "2026-11-11T10:00:00Z", privacyUrl = "http://veyq.localhost:8080/privacy#reporter-contact",
            )),
            Update.Report(11, ReportShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"), categories = emptyList(), whole = true,
                scopeHint = Msg.REPORT_SCOPE_WHOLE_HINT, category = null, note = null, reasonMax = 2000, contactMax = 254, goodFaith = false, canSend = false,
                sending = true, problem = null, sent = false, reference = null, receivedAt = null, privacyUrl = "https://veyq.example/privacy#reporter-contact",
            )),
            Update.Report(11, null),
            Update.Editor(12, EditorShown(
                isNew = false, editable = true, invalid = true, kind = me.raddatz.veyq.mobile.expenses.EntryForm.Kind.EXPENSE, title = "Museum", date = "2026-11-12",
                category = "food", categories = listOf(EditorChoice("food", Piece.Said(Notice(Msg.CAT_FOOD))), EditorChoice("c1", Piece.Plain("Snacks"))),
                currency = "CHF", currencies = listOf("CHF", "EUR"), base = "EUR", rate = "1,082954",
                rateFill = me.raddatz.veyq.mobile.expenses.EntryEditor.Rate(me.raddatz.veyq.mobile.expenses.EntryEditor.RateState.FILLED, "2026-11-11"),
                amount = "", from = "p1", to = "p2", people = listOf(EditorPerson("p1", "Anna"), EditorPerson("p2", "Ben")),
                payers = listOf(EditorPayer("p1", "30,50"), EditorPayer("p2", "20")), canRemovePayer = true, canAddPayer = false,
                total = Piece.Money(5050, "CHF", 2),
                items = listOf(EditorItem("Tickets", "20", EditorSplit(me.raddatz.veyq.mobile.expenses.EntryForm.Mode.PERCENT, true, Notice(Msg.ED_SPLIT_EVERYONE, listOf(2)),
                    listOf(EditorSplitPerson("p1", "Anna", true, "70"), EditorSplitPerson("p2", "Ben", false, "")), Notice(Msg.ED_PERCENT_TOTAL, listOf("70.00"))))),
                rest = EditorSplit(me.raddatz.veyq.mobile.expenses.EntryForm.Mode.SHARES, false, Notice(Msg.ED_SPLIT_ONLY, listOf("Anna")), listOf(EditorSplitPerson("p1", "Anna", true, "1")), null),
                restIsWhole = false, preview = listOf(EditorShare("Anna", Piece.Money(3303, "EUR", 2), Piece.Money(2500, "EUR", 2)), EditorShare("Ben", null, Piece.Money(803, "EUR", 2))),
                refusal = EditorRefusal(Notice(Msg.PROBLEM_ITEMS), "item", 0), saving = true, notice = Notice(Msg.ED_STALE), asking = true, done = false,
            )),
            Update.Editor(13, EditorShown(
                isNew = true, editable = false, invalid = false, kind = me.raddatz.veyq.mobile.expenses.EntryForm.Kind.TRANSFER, title = "", date = "2026-11-12",
                category = "other", categories = emptyList(), currency = "EUR", currencies = emptyList(), base = "EUR", rate = "1", rateFill = null,
                amount = "9", from = "p1", to = "p2", people = emptyList(), payers = emptyList(), canRemovePayer = false, canAddPayer = true,
                total = Piece.Money(0, "EUR", 2), items = emptyList(), rest = null, restIsWhole = true, preview = emptyList(), refusal = EditorRefusal(Notice(Msg.PROBLEM_TRANSFER), null, null),
                saving = false, notice = null, asking = false, done = true,
            )),
            Update.Editor(14, null),
            Update.Share(10, ShareShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"),
                title = Msg.SHARE_FOLDER_TITLE, intro = Msg.SHARE_FOLDER_INTRO, canMint = true,
                links = listOf(
                    ShareShown.ShownLink("share-1", Piece.Plain("Family"), listOf(Notice(Msg.SHARE_ROLE_VIEWER), Notice(Msg.SHARE_PASSWORD_BADGE)),
                        "2026-11-01T10:00:00Z", "2026-12-01T22:59:59.999Z", "http://veyq.localhost:8080/s/share-1#k=AAAA", canCopy = true, canEdit = true,
                        canRenew = true, canSwitchOff = true, canReactivate = false, canDelete = false),
                    ShareShown.ShownLink("share-2", Piece.Said(Notice(Msg.SHARE_ROLE_VIEWER)), listOf(Notice(Msg.SHARE_SWITCHED_OFF)), "2026-10-01T10:00:00Z", null,
                        null, canCopy = false, canEdit = false, canRenew = true, canSwitchOff = false, canReactivate = true, canDelete = true),
                ),
                failed = false, busy = "share-2", notice = Notice(Msg.SHARE_CLOSED_REKEYING),
                form = ShareShown.ShownForm(
                    listOf(ShareShown.ShownForm.ShownRole("READ", Msg.SHARE_ROLE_VIEWER), ShareShown.ShownForm.ShownRole("WRITE_ONLY", Msg.SHARE_ROLE_UPLOADER)),
                    "READ", Msg.SHARE_FOLDER_ROLE_VIEWER_HINT, namesIt = true, nameLabel = Msg.SHARE_LABEL, label = "Family",
                    lock = ShareShown.ShownForm.ShownLock(on = true, optional = false, kept = true, tooShort = false, hint = Notice(Msg.SHARE_PASSWORD_BY_RULE, listOf(10))),
                    end = ShareShown.ShownForm.ShownEnd(on = true, optional = true, day = 20_800, today = 20_768, lastDay = null, hint = Notice(Msg.SHARE_EXPIRY_HINT)),
                    canSave = true,
                ),
                editing = "share-1", submit = Msg.SHARE_SAVE, saving = false, problem = Notice(Msg.SHARE_REQUIREMENT_REFUSED),
                freshAddress = "http://veyq.localhost:8080/s/share-3#k=BBBB", renewing = "share-1", deleting = null,
                renewQuestion = Msg.SHARE_RENEW_CONFIRM, deleteQuestion = Msg.SHARE_FOLDER_DELETE_CONFIRM,
            )),
            Update.Share(10, ShareShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "pot-1", "EXPENSES_POT", "Trip"), title = Msg.SHARE_TITLE, intro = Msg.SHARE_INTRO,
                canMint = false, links = null, failed = true, busy = null, notice = null, form = null, editing = null, submit = Msg.SHARE_CREATE, saving = false,
                problem = null, freshAddress = null, renewing = null, deleting = "share-2", renewQuestion = Msg.SHARE_RENEW_CONFIRM, deleteQuestion = Msg.SHARE_DELETE_CONFIRM,
            )),
            Update.Share(10, null),
            Update.Manage(null), Update.Manage(listOf(
                ManagedShown("veyq.localhost#user-1", "veyq.localhost:8080", TrustedDevice.Method.DEVICE_KEY, StaySignedIn.LOGIN, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN), listOf(
                    ManagedShown.Tenant(tenant, hidden = false, current = true, shown = true, switchable = false, subtitle = Piece.Said(Notice(Msg.MANAGE_CURRENT)),
                        spaces = listOf(ManagedShown.Space(lists.spaces.first(), Piece.Said(Notice(Msg.SPACE_PERSONAL)), hidden = false, canHide = false))),
                    ManagedShown.Tenant(TenantRef("t2", "Family", "family.localhost"), hidden = true, current = false, shown = false, switchable = true, subtitle = Piece.Plain("family.localhost"), spaces = null),
                )),
                ManagedShown("other.example#user-9", "other.example", null, null, emptyList(), emptyList()),
            )),
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
            deleted = listOf(PotView.ShownEntry(PotView.EntryRow("e2", PotView.Kind.TRANSFER, null, "2026-11-09", null, listOf("Ben", "Anna"), 900, null, null, 2, Ledger.Status.DELETED, false),
                listOf(Piece.Said(n(Msg.POT_TRANSFER_BETWEEN, "Ben", "Anna"))), null)),
            balances = listOf(PotView.ShownBalance(person, Piece.Plain("Anna"), PotView.Balance(n(Msg.POT_IS_OWED, eur(2100)), PotView.Tone.OWED), listOf(Piece.Said(n(Msg.POT_PAID_CONSUMED, eur(4200), eur(2100)))))),
            settleUp = listOf(PotView.ShownSettle(PotView.SettleRow("Ben", "Anna", 2100, "p2", "p1"), n(Msg.POT_PAYS, "Ben", "Anna"))),
            canRecordAll = false,
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
