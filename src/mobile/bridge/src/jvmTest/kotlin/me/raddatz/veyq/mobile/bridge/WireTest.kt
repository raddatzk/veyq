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
        canCreateHere = true, topLevelShare = false, headed = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"),
        me = "user-1", pictures = listOf("user-1", "user-2"),
    )
    private val memory = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "m-1", "MEMORY", "Ostsee 2026")
    private val pin = PinShown(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "m-1", "MEMORY", "Ostsee 2026"), ObjectRef("scope-1", "m-1", "MEMORY", ApplicationId.MEMORIES, "Ostsee 2026"), "m-1")
    private val nowPlaying = NowPlayingShown("t1", "Fado", "Ostsee 2026", ObjectRef("scope-1", "m-1", "MEMORY", ApplicationId.MEMORIES, "Ostsee 2026"), true, 61_500, 187_000)
    private val photo = GalleryRow("m1", "IMG_0001.jpg", "image/jpeg", video = false, blocked = false, tile = "blob-t1", picture = "blob-p1",
        capturedAt = 1_790_000_000_000, width = 4000, height = 3000, said = null, canDelete = true)
    private val record = TrustedDevice("http://veyq.localhost:8080", "p@example.org", "user-1", TrustedDevice.Method.DEVICE, "wrap-1", "veyq.device.ab")

    /** Each root's values: every variant of a sealed root once. */
    private val samples: Map<KSerializer<*>, List<Any>> = mapOf(
        Config.serializer() to listOf(Config("Fedora 44", 0, ""), Config("WIN-VM", 0x2051C, "veyq wants to open your account", "C:\\Users\\p\\AppData\\Local\\veyq\\offline"), Config("Fedora 44", 0, "", "/home/p/.local/share/veyq/offline"), Config("Fedora 44", 0, "", null, "de"), Config("Fedora 44", 0, "", null, "en", netLog = true)),
        Call.serializer() to listOf(
            Call.SessionStart, Call.SessionSignIn("veyq.localhost:8080", "p@example.org", "typed", "A3-TYPED"),
            Call.SessionStaySignedIn(StaySignedIn.PRESENCE), Call.SessionStaySignedIn(StaySignedIn.LOGIN), Call.SessionNotNow,
            Call.ManageStaySignedIn("veyq.localhost#user-1", StaySignedIn.LOGIN), Call.ManagePasswordTrust("veyq.localhost#user-1", "typed"),
            Call.SessionUnlock("typed"), Call.SessionUnlock(""), Call.SessionOtherAccount, Call.SessionForgetDevice("veyq.localhost#user-1"),
            Call.SessionAddServer, Call.SessionCancelAdd, Call.ManageOpen, Call.ManageClose, Call.ManageHideTenant("veyq.localhost#user-1", "t2", hidden = true),
            Call.ManageHideSpace("scope-1", hidden = false), Call.SessionSwitchTo("veyq.localhost#user-1", "t"),
            Call.SessionSignOut("veyq.localhost#user-1"), Call.ShellChoose(null), Call.ShellSwitchSpace("scope-1"), Call.ShellEnter(folder),
            Call.ShellFilter(ApplicationId.DEPOT), Call.ShellBack, Call.ShellSelect(BarTab("expenses.overview")), Call.ShellOpen(ref), Call.ShellOpenAtTop(ref), Call.ShellSearch(true), Call.ShellRefresh, Call.ShellOfflineGoneSeen,
            Call.HomeGroups, Call.PotOpen(ref), Call.PotRefresh(3), Call.PotClose(3),
            Call.PotDownload(3, "/home/p/Downloads/Trip to Lyon.zip", "deleted person"), Call.PotArchiveName(3), Call.AvatarPicture("user-1"), Call.PictureDecode,
            Call.PotRecord(3, listOf(PotView.SettleRow("Ben", "Anna", 2100, "p2", "p1"))), Call.PotRestore(3, "e2"), Call.PotHistory(3, "e2"),
            Call.ReaderRead(5, 262_144, 65_536), Call.ReaderClose(5),
            Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = false), 2), Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "node-1", "EXPENSES_POT", "Trip to Lyon"), null, 0),
            Call.HeaderFacts(7, me.raddatz.veyq.mobile.AppKinds.Facts()), Call.HeaderClose(7), Call.HeaderPin(7), Call.HeaderOffline(7), Call.HeaderOfflineConfirm(7), Call.HeaderOfflineCancel(7), Call.HeaderTrash(7), Call.HeaderUndo(7),
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
            Call.ShareOpenTopLevel("scope-1"), Call.DropBoxOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "box-1", "DEPOT_DROPBOX", "Abgaben")),
            Call.DropBoxClose(30), Call.DropBoxMoveOut(30, "g1"), Call.DropBoxAskDelete(30, "g1"), Call.DropBoxCancelAsk(30), Call.DropBoxDelete(30, "g1"),
            Call.DropBoxUndo(30),
            Call.HeaderCoverPicture(7), Call.HeaderCoverSave(7, "/tmp/cover.jpg", me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.0, 0.5, 1.0)),
            Call.HeaderCoverSave(7, null, me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL), Call.HeaderCoverRemove(7),
            Call.CoverPose(4000.0, 3000.0, 640.0, 360.0, me.raddatz.veyq.mobile.session.CoverFrame.Pose(1.5, -10.0, -20.5), null, 3.0, -2.0, 1.1),
            Call.CoverPose(4000.0, 3000.0, 640.0, 360.0, null, me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL, 0.0, 0.0, 1.0), Call.CoverFit(4000, 3000),
            Call.CoverCut(me.raddatz.veyq.mobile.session.NodeCover.Crop(0.25, 0.0, 0.5, 1.0), 4000, 3000),
            Call.ReportOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays")), Call.ReportClose(11), Call.ReportWhole(11, false),
            Call.ReportCategory(11, "ILLEGAL_CONTENT"), Call.ReportReason(11, "why"), Call.ReportGoodFaith(11, true), Call.ReportContact(11, "me@example.org"), Call.ReportSend(11),
            Call.EditorOpen(3, null, ","), Call.EditorOpen(3, "e1", "."), Call.EditorSave(12), Call.EditorAskDelete(12), Call.EditorDelete(12), Call.EditorCancelAsk(12), Call.EditorClose(12),
            Call.EditorAddReceipts(12, listOf("/home/p/Bilder/Quittung März.jpg", "C:\\Users\\p\\scan.pdf")), Call.EditorDropReceipt(12, "r1"),
            Call.ReceiptOpen(3, "r1"), Call.ReceiptPages(25), Call.ReceiptSave(3, "r1", "/home/p/Downloads/r1.jpg"),
            Call.FileOpen(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "file-1", "FILE", "Plan März.pdf")), Call.FileBytes(20), Call.FilePages(1200), Call.FileClose(20), Call.FileReader(20),
            Call.FileSave(20, "C:\\Users\\p\\Downloads\\Plan März.pdf"), Call.SaveCancel(21),
            Call.FileChooseReplace(20), Call.UploadFiles("s1", null, "Familie", listOf("/home/p/Büro 中文.pdf", "C:\\Users\\p\\a.jpg")),
            Call.UploadFiles("s1", "f1", "Trip", emptyList()), Call.UploadFolder("s1", "f1", "/home/p/Trip"), Call.UploadReplace(20, "/tmp/b.bin"),
            Call.UploadCancel("i1"), Call.UploadRetry("i1"), Call.UploadCancelAll, Call.UploadAcknowledge("b1"), Call.UploadTakeOffer(3), Call.UploadSeen(4),
            Call.HeaderFacts(7, me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = true, hasContent = true), 20),
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
            // a Pot's settings and New pot
            Call.PotSettingsOpen(3), Call.PotSettingsClose(15), Call.PotSettingsCurrency(15, "CHF"), Call.PotSettingsAddCategory(15, "Snacks"),
            Call.PotSettingsRenameCategory(15, "c1", "Süßes"), Call.PotSettingsAddPerson(15, "Cleo"), Call.PotSettingsAddMember(15, "u-dana"),
            Call.PotSettingsRename(15, "p3", "Cleo B"), Call.PotSettingsToggle(15, "p3"), Call.PotSettingsAskErase(15, "p3"), Call.PotSettingsErase(15),
            Call.PotSettingsCancelAsk(15), Call.PotCreateOpen("de_DE.UTF-8"), Call.PotCreateClose(16), Call.PotCreateName(16, "Rom"),
            Call.PotCreateSpace(16, "scope-2"), Call.PotCreateCurrency(16, "EUR"), Call.PotCreateAddTyped(16, "Ben"), Call.PotCreateAddMember(16, "u-dana"),
            Call.PotCreateRemove(16, 0), Call.PotCreateCreate(16, ""), Call.PotCreateCreate(16, "Cleo"),
            // a QR scan
            Call.ScanOpen(ScanKind.SCHEME), Call.ScanOpen(ScanKind.KIT, "familie.example.org"),
            Call.SessionSignInKit("/home/p/Notfall-Kit.pdf", "familie.example.org"), Call.SessionSignInKitClear, Call.ScanCamera(40, me.raddatz.veyq.mobile.qr.QrScan.Camera.RUNNING),
            Call.ScanCamera(40, me.raddatz.veyq.mobile.qr.QrScan.Camera.NONE), Call.ScanCamera(40, me.raddatz.veyq.mobile.qr.QrScan.Camera.DENIED),
            Call.ScanFrame(40, 1280, 720, 1280), Call.ScanClose(40), Call.ScanOpen(ScanKind.LINK, link = 70),
            Call.LinkShowForNewDevice("veyq.localhost#user-1"), Call.LinkScanSignInCode("veyq.localhost#user-1"),
            Call.LinkScanFromTrusted("veyq.localhost:8080"), Call.LinkShowForSignIn("veyq.localhost:8080"), Call.LinkCode(70, 520),
            Call.LinkConfirm(70, "Büro-Laptop"), Call.LinkEnterCode(70, "042917"), Call.LinkClose(70),
            Call.SettingsOpen("veyq.localhost#user-1"), Call.SettingsClose(50), Call.SettingsRefresh(50), Call.SettingsRename(50, "Büro-Laptop"),
            Call.SettingsSignOut(50, "fam-2"), Call.SettingsSignOutOthers(50), Call.SettingsSignOutAndRemoveKey(50, "fam-2"),
            Call.SettingsRemoveSavedKey(50, "dev-3"), Call.SettingsRemoveOtherSavedKeys(50), Call.SettingsStaySignedIn(50, StaySignedIn.LOGIN),
            Call.SettingsPasswordTrust(50, "typed"), Call.SettingsStopTrusting(50), Call.SettingsSignOutHere(50),
            Call.SettingsOfflineLimit(50, "2,5", me.raddatz.veyq.mobile.offline.LimitUnit.GB), Call.SettingsOfflineLimitOff(50), Call.SettingsClearCache(50),
            Call.SettingsOfflineRemove(50, "n1"), Call.SettingsAskRemoveAll(50), Call.SettingsRemoveAll(50), Call.SettingsCancelRemoveAll(50),
            // Memories
            Call.ShellCreateMemory("scope-1", null, "Ostsee 2026"), Call.ShellCreateMemory("scope-1", "folder-1", "Tag eins"), Call.ShellRowCover("memory-1", null), Call.ShellRowCover("memory-1", "b-1@0.25,0.0,0.5,1.0"),
            Call.GalleryOpen(memory), Call.GalleryClose(60), Call.GalleryReload(60), Call.GalleryTile(60, "m1"), Call.GalleryChapterCover(60, "c1"), Call.GalleryLayout(60, 1024.5, 3200.25, 768.0), Call.GalleryLayout(60, 0.0, 0.0, 0.0),
            Call.GalleryTrash(60, "m1"), Call.GalleryUndo(60), Call.GalleryPurge(60), Call.GalleryCancelAsk(60), Call.GallerySeen(60), Call.GallerySearchOpenedSeen(60),
            Call.GalleryCreateChapter(60, "Tag zwei"), Call.MemoryUpload(60, listOf("/home/p/Bilder/Strand 中文.jpg", "C:\\Users\\p\\clip.webm")),
            Call.MemoryUpload(60, emptyList()), Call.LightboxOpen(60, 2), Call.LightboxClose(61), Call.LightboxGo(61, 3), Call.LightboxPicture(61, "m1"),
            Call.LightboxSlideshow(61, true), Call.LightboxDetails(61, "m1"), Call.LightboxTrash(61), Call.LightboxStop(61), Call.MediumReader(61),
            Call.MediumSave(61, "/home/p/Downloads/IMG_0001.jpg"), Call.HeaderFacts(7, me.raddatz.veyq.mobile.AppKinds.Facts(), null, 60),
            Call.GalleryMusic(60), Call.LightboxVideo(61, true), Call.PlayerPause, Call.PlayerResume, Call.PlayerNext, Call.PlayerPrevious,
            Call.PlayerSeek(61_500), Call.PlayerStop, Call.PlayerOpenNode, Call.MusicOpen(60), Call.MusicClose(80), Call.MusicPlay(80, "t1"),
            Call.MusicRename(80, "t1", "Fado für Ana"), Call.MusicRename(80, "t1", null), Call.MusicRemove(80, "t1"),
            Call.MusicDownload(80, "t1", "C:\\Users\\p\\Musik\\Fado.mp3"), Call.MusicReport(80, "t1"),
            Call.MusicAdd(80, listOf("/home/p/Musik/Fado.mp3", "/home/p/Musik/Alfama 中文.m4a")), Call.MusicSeen(80),
            Call.AudioEnded(90, 3), Call.AudioFailed(91, 4),
            Call.MapOpenMemories, Call.MapView(100, null, null, null, 800.0, 600.0, 2.0), Call.MapView(100, 0.537, 0.3278, 12.5, 800.0, 600.0, 1.25),
            Call.MapTile(100, "12/2200/1343"), Call.MapTap(100, "m-1"), Call.MapClose(100), Call.MapTurnOn, Call.MapTurnOff, Call.MemoriesDates,
            Call.LightboxMap(61, "m1"), Call.LightboxPlace(61, "m1"), Call.DateOpen(60), Call.DateChoose(101, DateMode.RANGE),
            Call.DateFrom(101, "2026-07-01"), Call.DateTo(101, ""), Call.DateSave(101), Call.DateClose(101), Call.PlaceOpen(60),
            Call.PlaceTyped(102, "Am Kupfergraben 中文"), Call.PlaceLevel(102, "city"), Call.PlaceToggleName(102), Call.PlaceFollow(102),
            Call.PlaceSave(102), Call.PlaceRemove(102), Call.PlaceClose(102), Call.TracksOpen(60), Call.TracksClose(103),
            Call.TracksShow(103, "t1", false), Call.TracksSelect(103, "t1"), Call.TracksSelect(103, null),
            Call.TracksAdd(103, listOf("/home/p/Touren/Spree.gpx", "C:\\Users\\p\\Touren\\Alpen.gpx")),
            Call.TracksConfirm(103, listOf(TrackLabelShown("Morgenrunde", "cycling"), TrackLabelShown(null, null))), Call.TracksCancel(103),
            Call.TracksEdit(103, "t1", TrackLabelShown("Spree", "running")), Call.TracksRemove(103, "t1"),
            Call.TracksDownload(103, "t1", "/home/p/Downloads/Spree.gpx"), Call.TracksReport(103, "t1"), Call.TracksSeen(103),
            // curating a Memory, a person's page (ADR 0144 decision 8)
            Call.ShellCreateMemory("scope-1", null, "Ostsee 2026", wasThere = true), Call.ShellCreateMemory("scope-1", null, "Sylt", autoPlace = true), Call.ShellOffersWasThere("scope-1"), Call.ShellOpenPerson("user-2"),
            Call.ParticipantsOpen(60), Call.ParticipantsLoad(110), Call.ParticipantsSearch(110, "Zoë"), Call.ParticipantsToggle(110, "user-2"),
            Call.ParticipantsSave(110), Call.ParticipantsClose(110), Call.CaptureTimeOpen(61, "m1"), Call.CaptureTimeSet(111, 1_790_000_040_000),
            Call.CaptureTimeReset(111), Call.CaptureTimeClose(111), Call.BannerPicture(60), Call.BannerPlay(60), Call.BannerFrame(1024.5, 768.0, 4000, 3000),
            Call.BannerOpen(60), Call.BannerPick(112, "C:\\Users\\p\\Bilder\\Strand 中文.jpg"), Call.BannerPreview(112), Call.BannerSave(112),
            Call.BannerCancel(112), Call.BannerRemove(112), Call.BannerClose(112), Call.GalleryTrashBannerPicture(60, true),
            Call.PersonOpen("user-2"), Call.PersonView(113, MemoriesView.MAP), Call.PersonRetry(113), Call.PersonMap(113), Call.PersonClose(113),
            Call.SearchOpen, Call.SearchFocus(114), Call.SearchText(114, "Kev 2024"), Call.SearchChoose(114, 0), Call.SearchRemoveChip(114, 1),
            Call.SearchRemoveLastChip(114), Call.SearchMore(114), Call.SearchOpenResult(114, 2), Call.SearchClose(114),
            Call.SettingsRenameFamily(50, "family-1", "Küchen-Tablet"), Call.ProfileOpen, Call.ProfileUsername(120, "kev"),
            Call.ProfileFirstName(120, "Kevin"), Call.ProfileLastName(120, "Müller"), Call.ProfileConsent(120, true),
            Call.ProfilePicture(120, "C:\\Users\\p\\Bilder\\ich.jpg", me.raddatz.veyq.mobile.session.CoverFrame.Pose(1.5, -12.25, 0.0)),
            Call.ProfileRemovePicture(120), Call.ProfileDiscardPicture(120), Call.ProfilePictureBytes(120), Call.ProfileSave(120), Call.ProfileClose(120),
            Call.TrustMine, Call.TrustMineCode(480), Call.TrustPerson("user-2"), Call.TrustCompareDigits("user-2", false), Call.TrustCompareScanned("user-2", 41),
            Call.ScanOpen(ScanKind.SAFETY),
            Call.PasswordChangeOpen("veyq.localhost#u1"), Call.PasswordChangeRun(121, "alt", "neu und lang", "neu und lang", "0C5126-0Z4RPK"),
            Call.PasswordChangeClose(121), Call.AddressChangeOpen("veyq.localhost#u1"), Call.AddressChangeRequest(122, "neu@example.org", "pw", "0C5126"),
            Call.AddressChangeKit(122, "pw", "0C5126"), Call.AddressChangeClose(122), Call.KitSave(125, "/home/p/kit.pdf", "de", 20_775),
            Call.KitConfirm(125, true), Call.KitClose(125), Call.KeyRotationOpen("veyq.localhost#u1"), Call.KeyRotationChooseNewPassword(123, false),
            Call.KeyRotationProve(123, "pw", "0C5126", "neu und lang", "neu und lang"), Call.KeyRotationRun(123), Call.KeyRotationClose(123),
            Call.ErasureOpen("veyq.localhost#u1"), Call.ErasureChoose(124, "t1", null), Call.ErasureChoose(124, "t2", "user-3"),
            Call.ErasureRun(124, "pw"), Call.ErasureClose(124),
        ),
        Watched.serializer() to listOf(Watched.Session, Watched.Shell, Watched.Pot(3), Watched.PotLive(3), Watched.Manage, Watched.Header(7), Watched.Trash(8), Watched.Move(9), Watched.Share(10), Watched.Report(11), Watched.Editor(12), Watched.PotSettings(15), Watched.PotCreate(16), Watched.File(20), Watched.Save(21), Watched.Uploads, Watched.DropBox(30), Watched.DropBoxLive(30), Watched.Scan(40), Watched.Link(70), Watched.Settings(50),
            Watched.Gallery(60), Watched.GalleryLive(60), Watched.SettingsStorageLive(50), Watched.Lightbox(61), Watched.Player, Watched.Music(80),
            Watched.Map(100), Watched.MapServices, Watched.Date(101), Watched.Place(102), Watched.Tracks(103),
            Watched.Participants(110), Watched.CaptureTime(111), Watched.Banner(112), Watched.Person(113), Watched.Search(114),
            Watched.Profile(120), Watched.PasswordChange(121), Watched.AddressChange(122), Watched.KeyRotation(123), Watched.Erasure(124), Watched.Kit(125)),
        HostReply.serializer() to listOf(HostReply.Value("s\tn\t1"), HostReply.Value(null), HostReply.Done, HostReply.Failed("disk full"), HostReply.Pixels(2400, 3394),
            HostReply.Tiers(2048, 1536, 412_000), HostReply.Tiers(null, null, 0), HostReply.Wav),
        Answer.serializer() to listOf(
            Answer.Done, Answer.Refused(Answer.Why.NO_TENANT_OPEN), Answer.Refused(Answer.Why.TRUST_CANCELLED), Answer.Failed("IllegalStateException"),
            Answer.Groups(listOf(Home.Group(true, lists.nodes.take(1), Msg.HOME_GROUP_FOLDERS), Home.Group(false, lists.nodes.drop(1), null))),
            Answer.PotOpened(3), Answer.Name("Trip to Lyon.zip"), Answer.Bytes("image/png"), Answer.Bytes(null),
            Answer.Reader(5, 9_000_000_000, "video/mp4"), Answer.Reader(6, 0, null),
            Answer.HeaderOpened(7), Answer.FileOpened(20), Answer.Saving(21), Answer.Queued(3), Answer.TrashOpened(8), Answer.Said(Notice(Msg.TRASH_MOVED, listOf("Trip"))), Answer.Said(null), Answer.Trashed(Notice(Msg.TRASH_MOVED, listOf("Trip")), undoable = true),
            Answer.Renamed(Notice(Msg.NODE_SLUG_TAKEN), null), Answer.Renamed(null, Notice(Msg.SHARE_NAME_RESEAL_FAILED)), Answer.Refused(Answer.Why.NO_SUCH_ROW),
            Answer.MoveOpened(9), Answer.ShareOpened(10), Answer.DropBoxOpened(30), Answer.Refused(Answer.Why.NOT_ASKED), Answer.ReportOpened(11), Answer.Refused(Answer.Why.NO_COVER),
            Answer.CoverPosed(me.raddatz.veyq.mobile.session.CoverFrame.Pose(1.0, 0.0, -12.5), 640.0, 385.0, me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.1, 1.0, 0.75)),
            Answer.CoverFit(2048, 1536, 0.85, 85), Answer.CoverCut(1000, 0, 2000, 3000),
            Answer.EntryHistory(listOf(HistoryShown(listOf(Piece.Said(Notice(Msg.HISTORY_DELETED))), "Anna", 1_700_000_000_000), HistoryShown(listOf(Piece.Plain("Taxi"), Piece.Money(999, "EUR", 2)), null, 1_699_000_000_000)),
                deleted = true, restorable = true, until = 1_702_000_000_000), Answer.Refused(Answer.Why.NO_SUCH_SHARE), Answer.MoveSent(Notice(Msg.MOVE_MOVED, listOf("Trip", Notice(Msg.SPACE_PERSONAL))), undoable = true, confirming = false, problem = null),
            Answer.MoveSent(null, undoable = false, confirming = true, problem = Notice(Msg.MOVE_REFUSED_GONE)),
            Answer.Downloaded(true, Notice(Msg.DOWNLOAD_DONE, listOf("Holidays")), Notice(Msg.DOWNLOAD_LEFT_OUT, listOf(Joined(listOf(Notice(Msg.LEFT_OUT_LINE, listOf("x", Notice(Msg.LEFT_OUT_UNSUPPORTED)))), "; ")))),
            Answer.Downloaded(false, Notice(Msg.DOWNLOAD_FAILED, listOf("Holidays")), null),
            Answer.EditorOpened(12), Answer.EditorSaved(true, Notice(Msg.RECEIPTS_NO_ROOM, listOf(Joined(listOf("a.jpg"), ", ")))), Answer.EditorSaved(false, null),
            Answer.Refused(Answer.Why.NO_SUCH_EDITOR),
            Answer.ReceiptPages(20, Notice(Msg.RECEIPTS_PAGES_CUT, listOf(20, 25))), Answer.ReceiptPages(3, null),
            Answer.Refused(Answer.Why.NO_RECEIPT), Answer.Refused(Answer.Why.NOT_WRITTEN),
            Answer.PotSettingsOpened(15), Answer.PotCreateOpened(16), Answer.Stored(true, null), Answer.Stored(false, Notice(Msg.SETTINGS_CURRENCY_FIXED)),
            Answer.Stored(true, Notice(Msg.CREATE_POT_PEOPLE_FAILED)), Answer.Refused(Answer.Why.NO_SUCH_SETTINGS), Answer.Refused(Answer.Why.NO_SUCH_CREATE),
            Answer.SettingsOpened(50), Answer.Refused(Answer.Why.NO_SUCH_ACCOUNT_PAGE), Answer.ScanOpened(40), Answer.FrameTaken(true), Answer.FrameTaken(false), Answer.Refused(Answer.Why.NOT_A_FRAME), Answer.Refused(Answer.Why.NO_SUCH_SCAN), Answer.Refused(Answer.Why.NO_KIT),
            Answer.LinkOpened(70), Answer.Refused(Answer.Why.NO_SUCH_LINK), Answer.Refused(Answer.Why.NO_SIGN_IN), Answer.Refused(Answer.Why.NOT_TAKEN),
            Answer.GalleryOpened(60), Answer.LightboxOpened(61), Answer.GridLayout(listOf(GridRow(4, 0.0, 183.0, listOf(GridTile("m1", 0.0, 244.0), GridTile("m2", 252.0, 137.25))),
                GridRow(5, 191.0, 150.0, listOf(GridTile("m3", 0.0, 200.0)))), 341.0, 9), Answer.GridLayout(emptyList(), 0.0, 0), Answer.Created(memory, null), Answer.RowCover("b-1@0.25,0.0,0.5,1.0", me.raddatz.veyq.mobile.session.NodeCover.Crop(0.25, 0.0, 0.5, 1.0), true, "image/jpeg"), Answer.RowCover("b-1@0,0,1,1", me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL, false, null), Answer.Created(null, Notice(Msg.MEMORY_SLUG_TAKEN)),
            Answer.Queued(2, Notice(Msg.MEMORY_NOT_MEDIA, listOf(1))), Answer.Refused(Answer.Why.NOT_OFFERED), Answer.Refused(Answer.Why.NO_SUCH_GALLERY),
            Answer.MediumDetails(
                listOf(
                    DetailRow(Msg.MEMORY_FIELD_CAPTURED, null, null, 1_790_000_000_000, null), DetailRow(Msg.MEMORY_FIELD_FILE_SIZE, null, 34_029, null, null),
                    DetailRow(Msg.MEMORY_FIELD_FLASH, Notice(Msg.MEMORY_FLASH_FIRED), null, null, null), DetailRow(Msg.MEMORY_FIELD_CAMERA, null, null, null, "Canon EOS R6"),
                ),
                Piece.Said(Notice(Msg.MEMORY_UPLOADER_GUEST)), 1,
            ),
            Answer.MediumDetails(emptyList(), null, 0),
            Answer.MusicOpened(80), Answer.MusicAdded(listOf(Notice(Msg.MUSIC_NOT_AUDIO, listOf("notes.txt")))), Answer.MusicAdded(emptyList()),
            Answer.Refused(Answer.Why.NO_PLAYER), Answer.Refused(Answer.Why.NO_SUCH_MUSIC), Answer.Refused(Answer.Why.NO_SUCH_DECK),
            Answer.MediumDetails(emptyList(), null, 0, map = true), Answer.MapOpened(100), Answer.MapTapped(pin, emptyList()), Answer.MapTapped(null, listOf(pin, pin)),
            Answer.MemoryDates(listOf(NodeDate("m-1", MemoryDateShown(DateMode.ONGOING, "2026-07-01", null)))), Answer.PlaceResolved("Mitte"), Answer.PlaceResolved(null),
            Answer.DateOpened(101), Answer.PlaceOpened(102, 100), Answer.PlaceOpened(102, null), Answer.TracksOpened(103, 100), Answer.TracksOpened(103, null),
            Answer.FactsSaved(FactsSave.SAVED), Answer.FactsSaved(FactsSave.REOPENED), Answer.FactsSaved(FactsSave.FAILED),
            Answer.TracksAdded(listOf(Notice(Msg.TRACK_PROBLEM_INVALID, listOf("notes.gpx")))),
            Answer.Refused(Answer.Why.NO_SUCH_MAP), Answer.Refused(Answer.Why.NO_MAP_SERVICES), Answer.Refused(Answer.Why.NO_SUCH_DATE),
            Answer.Refused(Answer.Why.NO_SUCH_PLACE), Answer.Refused(Answer.Why.NO_SUCH_TRACKS),
            Answer.MediumDetails(emptyList(), Piece.Plain("Ada"), 0, profile = "user-2"), Answer.ParticipantsOpened(110), Answer.CaptureTimeOpened(111),
            Answer.BannerOpened(112), Answer.BannerFramed(1024.5, 576.28125, true, 32, 24), Answer.BannerSaved(BannerSave.DONE),
            Answer.BannerSaved(BannerSave.CANCELLED), Answer.WasThere(true), Answer.WasThere(false, autoPlace = true), Answer.PersonOpened(113), Answer.SearchOpened(114),
            Answer.ProfileOpened(120), Answer.FlowOpened(121), Answer.KitOpened(125), Answer.SafetyNumber("12345 67890 12345 67890 12345 67890", "12345 67890"),
            Answer.PersonTrust(me.raddatz.veyq.mobile.session.PersonTrust.Shown("user-2", false, "1 2", "3 4", true, listOf("Sam"), false, 1_794_000_000_000, false), me.raddatz.veyq.mobile.session.PersonTrust.Status.VERIFIED),
            Answer.PersonTrust(null, null), Answer.TrustOutcome(me.raddatz.veyq.mobile.session.PersonTrust.Outcome.VERIFIED),
            Answer.Refused(Answer.Why.NO_SUCH_PARTICIPANTS), Answer.Refused(Answer.Why.NO_SUCH_CAPTURE_TIME), Answer.Refused(Answer.Why.NO_SUCH_BANNER),
            Answer.Refused(Answer.Why.NO_SUCH_PERSON),
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
                offersName = false,
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
                receipts = listOf(EditorReceipt("r1", "receipt.pdf", held = false, opens = true), EditorReceipt("r2", "Quittung März.jpg", held = true, opens = false)),
                canAddReceipt = true, refusedPick = true,
            )),
            Update.Editor(13, EditorShown(
                isNew = true, editable = false, invalid = false, kind = me.raddatz.veyq.mobile.expenses.EntryForm.Kind.TRANSFER, title = "", date = "2026-11-12",
                category = "other", categories = emptyList(), currency = "EUR", currencies = emptyList(), base = "EUR", rate = "1", rateFill = null,
                amount = "9", from = "p1", to = "p2", people = emptyList(), payers = emptyList(), canRemovePayer = false, canAddPayer = true,
                total = Piece.Money(0, "EUR", 2), items = emptyList(), rest = null, restIsWhole = true, preview = emptyList(), refusal = EditorRefusal(Notice(Msg.PROBLEM_TRANSFER), null, null),
                saving = false, notice = null, asking = false, done = true, receipts = emptyList(), canAddReceipt = false, refusedPick = false,
            )),
            Update.Editor(14, null),
            Update.PotSettings(15, PotSettingsShown(
                ready = true, canWrite = true, saving = false, notice = Notice(Msg.SETTINGS_CURRENCY_FIXED), currency = "EUR", currencies = listOf("CHF", "EUR"),
                currencyEditable = false, currencyFixed = true, canChangeSettings = true, categoriesShown = true, canAddCategory = true,
                categories = listOf(EditorChoice("c1", Piece.Plain("Snacks"))),
                people = listOf(
                    PotView.ShownPerson(PotView.PersonEntry("p1", "Anna", PotView.Standing.MEMBER, true, -1500, false, false, false, true, "u-anna"), listOf(Piece.Money(-1500, "EUR", 2))),
                    PotView.ShownPerson(PotView.PersonEntry("p3", "Cleo", PotView.Standing.NAME, false, 0, true, true, true, false), listOf(Piece.Said(Notice(Msg.PEOPLE_INACTIVE)))),
                ),
                deletedPeople = 1, settleFirst = true, members = listOf(PotMember("u-dana", "Dana")), asking = ErasingPerson("p3", "Cleo", named = false),
            )),
            Update.PotSettings(16, PotSettingsShown(
                ready = false, canWrite = false, saving = true, notice = null, currency = null, currencies = emptyList(), currencyEditable = true, currencyFixed = false,
                canChangeSettings = false, categoriesShown = false, canAddCategory = false, categories = emptyList(), people = emptyList(), deletedPeople = 0,
                settleFirst = false, members = emptyList(), asking = null,
            )),
            Update.PotSettings(17, null),
            Update.PotCreate(16, PotCreateShown(
                spaces = listOf(ShellShown.ShownSpace(SpaceRef("scope-1", "SHARED", "Familie"), Piece.Plain("Familie"))), space = "scope-1",
                currency = "EUR", currencies = listOf("EUR", "USD"), people = listOf(CreatePerson("Ben", member = false), CreatePerson("Dana", member = true)),
                offered = listOf(PotMember("u-me", "Me")), canCreate = true, saving = false, notice = Notice(Msg.CREATE_POT_NAME_TAKEN), done = false,
            )),
            Update.PotCreate(18, PotCreateShown(emptyList(), null, "USD", emptyList(), emptyList(), emptyList(), canCreate = false, saving = true, notice = null, done = true)),
            Update.PotCreate(19, null),
            Update.File(20, FileShown(
                me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "file-1", "FILE", "notes.txt"), FileShown.View.TEXT, null, null, null, "Erste Zeile\nzweite", Notice(Msg.FILE_TEXT_TRUNCATED),
                null, 2, listOf(me.raddatz.veyq.mobile.words.Piece.Plain("1.2 MB")), Notice(Msg.DEPOT_UPLOADED_BY, listOf("Anna")),
                me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = true, hasContent = true), "notes.txt",
            )),
            Update.File(20, FileShown(
                me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "file-2", "FILE", "a.png"), FileShown.View.IMAGE, null, "image/png", 4096, null, null,
                null, 1, emptyList(), null, me.raddatz.veyq.mobile.AppKinds.Facts(), "a.png",
            )),
            Update.File(20, FileShown(
                me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "file-3", "FILE", "clip.webm"), FileShown.View.MEDIA, Msg.FILE_UNSUPPORTED, "video/webm", null, null, null,
                629_145_600, 1, emptyList(), null, null, null,
            )),
            Update.File(22, null),
            Update.Save(21, SaveShown("Plan März.pdf", 262_144, 314_572_800, 262_144.0 / 314_572_800, null, Notice(Msg.DOWNLOAD_SAVING, listOf("Plan März.pdf", me.raddatz.veyq.mobile.words.Bytes(262_144), me.raddatz.veyq.mobile.words.Bytes(314_572_800))))),
            Update.Save(21, SaveShown("a.zip", 10, 10, 1.0, me.raddatz.veyq.mobile.depot.FileSave.End.SAVED, Notice(Msg.DOWNLOAD_DONE, listOf("a.zip")))),
            Update.Save(23, null),
            Update.Uploads(null),
            Update.Uploads(UploadsShown(
                Notice(Msg.UPLOAD_ACTIVE, listOf(1)), 1, 0.25, Notice(Msg.UPLOAD_PREPARING_FOLDER, listOf("Trip")),
                listOf(UploadsShown.Batch("b1", "Familie", false, listOf(
                    UploadsShown.Item("i1", "big.bin", 2_147_483_649, me.raddatz.veyq.mobile.blob.UploadQueue.Status.UPLOADING, 0.25, Notice(Msg.UPLOAD_STATUS_UPLOADING, emptyList()), false, true, false),
                    UploadsShown.Item("i2", "a.txt", 5, me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED, 0.0, Notice(Msg.UPLOAD_NO_SPACE, emptyList()), true, false, false),
                ))),
                UploadsShown.Said(3, listOf(Notice(Msg.DEPOT_REPLACE_CONFIRM, listOf("a.txt"))), Msg.DEPOT_REPLACE_TITLE),
            )),
            Update.Uploads(UploadsShown(null, 0, 1.0, null, emptyList(), UploadsShown.Said(4, listOf(Notice(Msg.DEPOT_FOLDER_LINK_SKIPPED, listOf("link"))), null))),
            Update.Share(10, ShareShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "folder-1", "FOLDER", "Holidays"), atTopLevel = false,
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
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "pot-1", "EXPENSES_POT", "Trip"), atTopLevel = true, title = Msg.SHARE_TITLE, intro = Msg.SHARE_INTRO,
                canMint = false, links = null, failed = true, busy = null, notice = null, form = null, editing = null, submit = Msg.SHARE_CREATE, saving = false,
                problem = null, freshAddress = null, renewing = null, deleting = "share-2", renewQuestion = Msg.SHARE_RENEW_CONFIRM, deleteQuestion = Msg.SHARE_DELETE_CONFIRM,
            )),
            Update.Share(10, null),
            Update.DropBox(30, DropBoxShown(
                node = me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "box-1", "DEPOT_DROPBOX", "Abgaben"),
                folders = listOf(DropBoxShown.ShownGuestFolder(
                    "g1", "Anna (2)", Notice(Msg.DROPBOX_GUEST_FILES, listOf(2)), Notice(Msg.DROPBOX_MOVE_FOLDER, listOf("Anna (2)")),
                    Notice(Msg.DROPBOX_DELETE_FOLDER, listOf("Anna (2)")), listOf(ObjectRef("scope-1", "file-9", "FILE", ApplicationId.DEPOT, "Übung 1.pdf")),
                )),
                failed = false, empty = Msg.DROPBOX_EMPTY, hint = Msg.DROPBOX_HINT, deleting = "g1",
                deleteTitle = Notice(Msg.DROPBOX_DELETE_FOLDER, listOf("Anna (2)")), deleteQuestion = Notice(Msg.DROPBOX_DELETE_FOLDER_CONFIRM, listOf("Anna (2)")),
                deleteAccept = Msg.DROPBOX_DELETE_FOLDER_ACCEPT,
            )),
            Update.DropBox(30, DropBoxShown(
                me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "box-1", "DEPOT_DROPBOX", "Abgaben"), null, true, Msg.DROPBOX_EMPTY, Msg.DROPBOX_HINT,
                null, null, null, Msg.DROPBOX_DELETE_FOLDER_ACCEPT,
            )),
            Update.DropBox(30, null),
            Update.Scan(40, ScanShown(ScanState.STARTING, null)), Update.Scan(40, ScanShown(ScanState.NO_CAMERA, null)),
            Update.Scan(40, ScanShown(ScanState.DENIED, null)), Update.Scan(40, ScanShown(ScanState.SCANNING, null)),
            Update.Scan(40, ScanShown(ScanState.FOUND, "veyqpair")), Update.Scan(40, ScanShown(ScanState.CLOSED, null)), Update.Scan(41, null),
            Update.Scan(42, ScanShown(ScanState.FOUND, "veyqsignin")),
            Update.Link(70, LinkShown(LinkRole.SHOW_FOR_NEW_DEVICE, LinkStep.CONFIRMING, "17893", null, null, null, null)),
            Update.Link(71, LinkShown(LinkRole.SCAN_SIGN_IN_CODE, LinkStep.CODE, null, "042917", null, null, LinkHint.SAME_SERVER)),
            Update.Link(72, LinkShown(LinkRole.SCAN_FROM_TRUSTED, LinkStep.DONE, "17893", null, "Büro-Laptop", null, null)),
            Update.Link(73, LinkShown(LinkRole.SHOW_FOR_SIGN_IN, LinkStep.ENDED, null, null, null, LinkRefusal.REFUSED, LinkHint.OTHER_DIRECTION)),
            Update.Link(74, null),
            Update.Settings(50, SettingsShown(
                email = "p@veyq.localhost", emailConfirmed = true, device = "Büro-Laptop", trusted = TrustedDevice.Method.DEVICE_KEY,
                level = StaySignedIn.LOGIN, levels = listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN),
                families = listOf(SettingsFamily("fam-1", 1_793_527_200_000, 1_794_475_800_000, current = true, label = "Büro-Laptop", removesKey = false),
                    SettingsFamily("fam-2", null, null, current = false, label = null, removesKey = true)),
                keptDevices = listOf(SettingsKeptDevice("dev-3", "Old phone")), otherSavedKeys = true, hasOthers = true, addDevice = true,
                working = false, refusal = Msg.ACCOUNT_SETTINGS_REVOKE_FAILED, registerRefusal = Msg.ACCOUNT_SETTINGS_REGISTER_UNREADABLE,
            )),
            Update.Settings(50, SettingsShown("p@veyq.localhost", null, "Fedora 44", null, null, emptyList(), null, emptyList(), false, false, false, true, null, null)),
            Update.Settings(51, null),
            Update.Manage(null), Update.Manage(listOf(
                ManagedShown("veyq.localhost#user-1", "veyq.localhost:8080", TrustedDevice.Method.DEVICE_KEY, StaySignedIn.LOGIN, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN), listOf(
                    ManagedShown.Tenant(tenant, hidden = false, current = true, shown = true, switchable = false, subtitle = Piece.Said(Notice(Msg.MANAGE_CURRENT)),
                        spaces = listOf(ManagedShown.Space(lists.spaces.first(), Piece.Said(Notice(Msg.SPACE_PERSONAL)), hidden = false, canHide = false))),
                    ManagedShown.Tenant(TenantRef("t2", "Family", "family.localhost"), hidden = true, current = false, shown = false, switchable = true, subtitle = Piece.Plain("family.localhost"), spaces = null),
                )),
                ManagedShown("other.example#user-9", "other.example", null, null, emptyList(), emptyList()),
            )),
            Update.Gallery(60, GalleryShown(
                node = memory, rows = listOf(photo, GalleryRow("m2", null, "video/webm", video = true, blocked = true, tile = null, picture = null,
                    capturedAt = null, width = null, height = null, said = Msg.MEMORY_BLOCKED, canDelete = false)),
                failed = true, failure = Msg.OFFLINE_NOT_KEPT, count = Notice(Msg.MEMORY_ITEMS, listOf(2)),
                chapters = listOf(me.raddatz.veyq.mobile.navigation.NodeAt("scope-1", "c1", "CHAPTER", "Tag eins")), canUpload = true, canSave = true,
                creates = listOf(ShellShown.ShownCreate(me.raddatz.veyq.mobile.AppKinds.Create.CHAPTER, Msg.MEMORY_ADD_CHAPTER)),
                undo = Notice(Msg.MEMORY_MOVED_TO_TRASH), purging = "m1", purgeQuestion = Notice(Msg.MEMORY_DELETE_OWN_CONFIRM),
                said = Notice(Msg.MEMORY_RESTORE_REFUSED, listOf("IMG_0001.jpg", Notice(Msg.MEMORY_REFUSED_GONE))),
            )),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null)),
            Update.Gallery(62, null),
            Update.Lightbox(61, LightboxShown(2, 5, Notice(Msg.MEMORY_ITEM_POSITION, listOf(3, 5)), photo, playing = true, slideshow = SlideshowState.PLAYING,
                downloadName = "IMG_0001.jpg", canTrash = true)),
            Update.Lightbox(61, LightboxShown(-1, 0, null, null, playing = false, slideshow = null, downloadName = null, canTrash = false)),
            Update.Lightbox(61, LightboxShown(0, 1, null, photo, false, SlideshowState.PAUSED, null, false)),
            Update.Lightbox(61, LightboxShown(0, 1, null, photo, false, SlideshowState.ENDED, null, false)),
            Update.Lightbox(63, null),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null,
                musicView = true, musicHere = true)),
            Update.Lightbox(61, LightboxShown(0, 1, null, photo, true, null, null, false, music = nowPlaying)),
            Update.Player(PlayerShown(nowPlaying, null)), Update.Player(PlayerShown(null, Notice(Msg.MUSIC_NOTHING_PLAYS))), Update.Player(null),
            Update.Music(80, MusicShown(
                memory, listOf(
                    MusicTrackShown("t1", "Fado", null, "Fado", "Fado.mp3", "3:07", playing = true, unplayable = false, blocked = false, canRemove = true),
                    MusicTrackShown("t2", null, Notice(Msg.MUSIC_UNTITLED, listOf(2)), null, null, null, playing = false, unplayable = true, blocked = true, canRemove = false),
                ),
                canAdd = true, canRename = true, canDownload = true, canReport = false, offline = false, checking = true, said = Notice(Msg.MUSIC_RENAME_CONFLICT),
            )),
            Update.Music(80, MusicShown(memory, null, false, false, false, false, true, false, null)), Update.Music(81, null),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null,
                date = MemoryDateShown(DateMode.RANGE, "2026-07-01", "2026-07-14"), place = "Berlin", editsFacts = true, tracks = 2)),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null,
                placePending = true, editsFacts = true)),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null,
                uploading = listOf(
                    UploadTileShown("upload:a1", video = false, progress = 0.25, preparing = false, failed = false, label = Notice(Msg.MEMORY_UPLOAD_TILE, listOf("IMG_0002.jpg"))),
                    UploadTileShown("upload:a2", video = true, progress = 0.0, preparing = false, failed = true, label = Notice(Msg.MEMORY_UPLOAD_TILE_FAILED, listOf("clip.mp4"))),
                ))),
            Update.Map(100, MapShown(
                MapStatus.Shown, MapViewShown(52.52, 13.405, 0.537236, 0.327917, 12.5, 800.0, 600.0, 1),
                listOf(TileShown("12/2200/1343", 2200.0 / 4096, 1343.0 / 4096, 1.0 / 4096)), "© OpenStreetMap contributors",
                listOf(TrackLineShown("t1", 0xFFE6194BL, listOf(10, 7), true, listOf(listOf(MapPointShown(0.5372, 0.3279), MapPointShown(0.5373, 0.3280))))),
                listOf(MapPointShown(0.5372, 0.3279)), listOf(ClusterShown(MapPointShown(0.5372, 0.3279), 2, pin)), PhotoShown(MapPointShown(0.5372, 0.3279), 270), centrePin = true,
            )),
            Update.Map(100, MapShown(MapStatus.Consent("tile.openstreetmap.org", null, renewal = true), null, emptyList(), null, emptyList(), emptyList(), emptyList(), null, false)),
            Update.Map(100, MapShown(MapStatus.NotOffered, null, emptyList(), null, emptyList(), emptyList(), emptyList(), null, false)),
            Update.Map(100, MapShown(MapStatus.Offline, null, emptyList(), null, emptyList(), emptyList(), emptyList(), null, false)), Update.Map(104, null),
            Update.MapServices(MapServicesShown(MapStatus.Shown, "tile.openstreetmap.org", "nominatim.openstreetmap.org")), Update.MapServices(null),
            Update.Date(101, DateShown(DateMode.RANGE, "2026-07-20", "2026-07-14", rangeInvalid = true, canSave = false, auto = MemoryDateShown(DateMode.AUTO, null, null))),
            Update.Date(101, null),
            Update.Place(102, PlaceShown(true, PlaceMode.FIXED, "Berlin", PlaceNaming.GEOCODED, listOf(LevelShown("suburb", "Mitte"), LevelShown("city", "Berlin")), "city",
                levelPicker = true, canSave = true, canFollow = true, canRemove = false)),
            Update.Place(102, PlaceShown(false, PlaceMode.AUTO, "", PlaceNaming.MANUAL, emptyList(), null, false, false, false, true)), Update.Place(102, null),
            Update.Tracks(103, TracksShown(
                memory, listOf(
                    TrackShown("t1", "Spree", 1, "Spree", null, "cycling", listOf(10, 7), "Spree.gpx", 0xFFE6194BL, TrackStatus.READY,
                        listOf(TrackFactShown(12.3, true, null), TrackFactShown(null, false, Notice(Msg.TRACK_DURATION_HOURS, listOf(1, 5)))),
                        shown = true, selected = false, blocked = false, canRemove = true),
                    TrackShown("t2", null, 2, null, null, null, null, null, 0xFF3CB44BL, TrackStatus.LOADING, emptyList(), false, false, true, false),
                ),
                listOf("hiking", "cycling"), canAdd = true, canEdit = true, canDownload = true, canReport = false, offline = false, checking = true,
                asking = listOf(AskedTrackShown("Alpen.gpx", "Alpen"), AskedTrackShown("track.gpx", null)), said = Notice(Msg.TRACK_EDIT_CONFLICT),
            )),
            Update.Tracks(103, TracksShown(memory, null, emptyList(), false, false, false, false, true, false, emptyList(), null)), Update.Tracks(104, null),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null,
                people = PeopleShown(
                    listOf(ParticipantShown("user-1", "Anna", false, true), ParticipantShown("user-2", null, true, false), ParticipantShown("user-3", "Ben 中文", false, true)),
                    listOf("user-1", "user-2", "user-3"), 0, named = true, edits = true, add = false,
                ),
                banner = BannerShown(BannerMedium.VIDEO, "blob-b1", "video/webm"), editsBanner = true, bannerQuestion = "m1", searchOpened = "m2")),
            Update.Gallery(60, GalleryShown(memory, null, false, null, Notice(Msg.MEMORY_ITEMS, listOf(0)), emptyList(), false, false, emptyList(), null, null, null, null,
                people = PeopleShown(emptyList(), emptyList(), 0, named = false, edits = true, add = true), banner = BannerShown(BannerMedium.IMAGE, "blob-b2", null))),
            Update.Participants(110, ParticipantsShown(
                listOf(MemberShown("user-1", "Anna", ticked = true, togglable = true), MemberShown("user-3", "Ben", ticked = false, togglable = false)),
                listOf(MemberShown("user-2", null, ticked = true, togglable = true)), failed = false, query = "An", full = true, noMatches = false, nobody = false,
                canSave = true, saving = false, said = CurationSaid.FAILED, saved = false,
            )),
            Update.Participants(110, ParticipantsShown(null, emptyList(), true, "", false, false, false, false, false, null, false)), Update.Participants(110, null),
            Update.CaptureTime(111, CaptureTimeShown(1_790_000_040_000, corrected = true, originalMs = 1_789_000_000_000, saving = false, said = CurationSaid.CONFLICT, saved = false)),
            Update.CaptureTime(111, CaptureTimeShown(null, false, null, true, CurationSaid.UNREADABLE, false)), Update.CaptureTime(111, null),
            Update.Banner(112, BannerEditorShown(true, false, BannerPickedShown(BannerMedium.VIDEO, "video/mp4", 2), saving = true, progress = 0.42, said = null, done = false, canSave = false, canRemove = false)),
            Update.Banner(112, BannerEditorShown(false, true, null, false, null, BannerSaid.TOO_LARGE, false, false, false)), Update.Banner(112, null),
            Update.Person(113, PersonShown(
                "user-2", "Anna Weiß", former = false, picture = true, username = "~anna", sections = listOf(ApplicationId.MEMORIES),
                memories = PersonMemoriesShown(
                    loading = false, failed = false, empty = false, partial = true, offersViews = true, view = MemoriesView.LIST,
                    rows = listOf(
                        PersonRowShown(ObjectRef("scope-g", "m-2", "MEMORY", ApplicationId.MEMORIES, "Harz"), MemoryDateShown(DateMode.SINGLE, "2026-08-01", null), "Wernigerode",
                            SpaceRef("scope-g", "GROUP", "scope-g"), Piece.Plain("Familie")),
                        PersonRowShown(ObjectRef("scope-1", "m-1", "MEMORY", ApplicationId.MEMORIES, "Ostsee 2026"), null, null, null, null),
                    ),
                    located = 1,
                ),
            )),
            Update.Person(113, PersonShown("user-9", null, true, false, null, emptyList(), null)), Update.Person(113, null),
            Update.Profile(120, me.raddatz.veyq.mobile.session.ProfileModel.State(loaded = true, username = "kev", firstName = "Kevin", hasKey = true, refusal = Msg.PROFILE_SAVE_FAILED)),
            Update.Profile(120, null),
            Update.PasswordChange(121, me.raddatz.veyq.mobile.session.PasswordChange.State(refusal = Msg.PASSWORD_CHANGE_MISMATCH)), Update.PasswordChange(121, null),
            Update.AddressChange(122, me.raddatz.veyq.mobile.session.AddressChange.State(sentTo = "neu@example.org")), Update.AddressChange(122, null),
            Update.KeyRotation(123, me.raddatz.veyq.mobile.session.rotation.KeyRotation.State(
                me.raddatz.veyq.mobile.session.rotation.KeyRotation.Step.DONE, "http://veyq.localhost:8080", done = true,
                scopes = listOf(me.raddatz.veyq.mobile.session.rotation.KeyRotation.ScopeStatus("s1", Msg.ROTATION_STATUS_BLOCKED, Msg.ROTATION_BLOCKED_SCOPE_DELETING)),
            )),
            Update.KeyRotation(123, null),
            Update.Erasure(124, me.raddatz.veyq.mobile.session.AccountErasure.State(
                loaded = true, server = "http://veyq.localhost:8080",
                tenants = listOf(me.raddatz.veyq.mobile.session.AccountErasure.Tenant("t1", "Familie", listOf(me.raddatz.veyq.mobile.session.AccountErasure.Member("u2", "Sam")), "u2", true)),
            )),
            Update.Erasure(124, null),
            Update.Kit(125, me.raddatz.veyq.mobile.account.KitStep.State("veyq-emergency-kit-p@a.example.pdf", saved = true)), Update.Kit(125, null),
            Update.Search(114, me.raddatz.veyq.mobile.search.SearchShown(
                text = "Kev", asked = true,
                chips = listOf(me.raddatz.veyq.mobile.search.ChipShown("year:2024", Msg.SEARCH_CHIP_YEAR, Piece.Plain("2024"), "calendar")),
                suggestions = listOf(me.raddatz.veyq.mobile.search.SuggestionShown(
                    "chip:in:s:n", Msg.SEARCH_CHIP_IN, Piece.Plain("Kevelaer"), "images", listOf(Piece.Said(Notice(Msg.SPACE_GROUP)), Piece.Plain("Trips")),
                )),
                results = listOf(
                    me.raddatz.veyq.mobile.search.ResultShown(
                        "s:MEMORY:n", "images", listOf(me.raddatz.veyq.mobile.search.PartShown("Kev", true), me.raddatz.veyq.mobile.search.PartShown("elaer", false)),
                        listOf(Piece.Plain("Family"), Piece.Plain("Trips"), Piece.Day("2024-05-15")),
                    ),
                    me.raddatz.veyq.mobile.search.ResultShown(
                        ":settings:sessions", "settings", listOf(me.raddatz.veyq.mobile.search.PartShown("Devices and sessions", false)),
                        listOf(Piece.Plain("Settings")), reason = listOf(me.raddatz.veyq.mobile.search.PartShown("Kev", true)),
                    ),
                ),
                more = 3, total = 13,
                status = listOf(Notice(Msg.SEARCH_LOADING, 2), Notice(Msg.SEARCH_NOT_SEARCHED, me.raddatz.veyq.mobile.words.Joined(listOf("Club"), ", "))),
                announcement = listOf(Notice(Msg.SEARCH_ANNOUNCE_RESULTS, 13)), openSettings = "maps", openSettingsCount = 1,
            )),
            Update.Search(114, me.raddatz.veyq.mobile.search.SearchShown()), Update.Search(114, null),
        ),
        HostRequest.serializer() to listOf(HostRequest.StoreRead("veyq.recent"), HostRequest.StoreWrite("veyq.recent", "s\tn\t1"), HostRequest.StoreRemove("veyq.recent"),
            HostRequest.PdfPages("/home/p/Notfall-Kit.pdf"), HostRequest.PdfPage("/home/p/Notfall-Kit.pdf", 2, 2400), HostRequest.PictureLuma("/home/p/kit.jpg", 2400),
            HostRequest.PictureSquare("/home/p/ich.jpg", me.raddatz.veyq.mobile.session.CoverFrame.Pose(1.0, 0.0, -40.5), 256),
            HostRequest.MediaDuration("/home/p/Videos/clip.webm"), HostRequest.MediaSlice("/home/p/Music/a.mp3", 0, 30_000), HostRequest.MediaSlice("/home/p/Music/a.mp3", 150_000, null),
            HostRequest.MediaTiers("C:\\Users\\p\\Bilder\\IMG_0001.HEIC", "image/heic", 2048, 0.85, 512, 0.8, 0),
            HostRequest.MediaTiers("/home/p/Videos/clip.webm", "video/webm", 2048, 0.85, 512, 0.8, 100),
            HostRequest.AudioLoad(90, 3, 95, 4_812_331, "audio/mpeg"), HostRequest.AudioPlay(90), HostRequest.AudioPause(90), HostRequest.AudioSeek(90, 12_000),
            HostRequest.AudioPosition(91), HostRequest.AudioRamp(91, 1.0, 5000), HostRequest.AudioRamp(90, 0.0, 0), HostRequest.AudioClear(90),
            HostRequest.ControlsBind, HostRequest.ControlsShow(nowPlaying), HostRequest.ControlsShow(NowPlayingShown("t2", "Track 2", null, null, false, 0, 0)),
            HostRequest.ControlsClear, HostRequest.WordsSay(Notice(Msg.MUSIC_UNTITLED, listOf(3))),
            HostRequest.NetLogLine("GET https://tile.openstreetmap.org/12/2200/1343.png ua=[veyq/1] referer=no cookie=no authorization=no -> 200")),
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
