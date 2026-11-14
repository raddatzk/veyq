package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.expenses.EditorChange as E
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustedDevice
import kotlin.test.assertNull
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject
import me.raddatz.veyq.mobile.session.LiveRun
import kotlin.test.AfterTest

/**
 * The dispatch over the real core (ADR 0123's Consequences, the bridge), as a host drives it, on the JVM's suite and
 * HTTP client: signs in through a call as the seeded principal (`.run/.env`, or `VEYQ_ENV_FILE`), watches the shell
 * open, reads Home's groups, searches a name and opens the hit, and opens a Pot and reads its view to balances that sum
 * to zero. Then ADR 0137's channels: the principal's own picture through `Avatar.Picture`, the same bytes as the core's
 * own read in a session of its own (it needs a picture set: Settings, your profile, in the web); and Amsterdam
 * downloaded through `Pot.Download` into a file, every payload of its ZIP to the SHA-256 its manifest names. The device
 * store is a host this test plays, answering from its own thread, and the files a JVM stand-in for core's `FileSink`.
 * Runs only when `VEYQ_LIVE` names an origin of a running dev instance (`./gradlew backend:seed`).
 */
class LiveBridgeTest {
    /** Every device this case signs in with, taken off the account again after it, whatever it came to. */
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun theSeededPrincipalSignsInAndReadsTheShellSearchAndAPotThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()

        val kept = liveRun.track(ConcurrentHashMap<String, String>())
        lateinit var host: Host
        host = Host { token, json ->
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
                    else -> HostReply.Failed("this host draws nothing")
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply))
            }
        }
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge test"), HostStore(host), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        val shell = b.watching(Watched.Shell)

        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        assertIs<Gate.SignIn>(gate.until { (it as Update.Session).gate.takeIf { g -> g is Gate.SignIn } })
        val signIn = Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"))
        assertEquals(Answer.Done, b.ask(signIn, timeout = 2.minutes))
        val signed = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        println("signed in: $signed")

        val update = shell.until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.spaces?.isNotEmpty() == true } }
        val state = update.state!!
        // what the shell shows beside it: the switcher's tenants of this account, Home's groups, each the core's
        val shown = assertNotNull(update.shown, "a shell state without what it shows")
        println("switcher: ${shown.switcherSubtitle}; servers: " + shown.servers.joinToString { s -> s.label + "=" + s.tenants.joinToString("/") { it.name } })
        assertTrue(shown.servers.any { s -> s.connection == shown.connection && shown.tenant in s.tenants }, "the open tenant is not in the switcher")
        assertEquals(state.lists.groups(state.shell).map { it.nodes.size }, shown.groups.map { it.nodes.size })
        println("spaces: " + state.lists.spaces.joinToString { "${it.kind}:${it.name}" } + "; objects: ${state.lists.objects.size}")
        assertTrue(state.lists.spaces.any { it.kind == "PERSONAL" }, "the personal space did not open")
        assertTrue(kept.isNotEmpty(), "nothing reached the host's store") // the device id, the trust marks

        // "Manage servers"' switches: a space hidden leaves the shell's spaces and a tenant the switcher's, each back when
        // shown again — the shell's rebuild and the switcher's re-read, which only the real ShellModel shows
        val hideable = assertNotNull(shown.spaces.firstOrNull { it.space != shown.space }, "the seeded principal has one space only").space
        assertEquals(Answer.Done, b.ask(Call.ManageHideSpace(hideable.scopeId, hidden = true)))
        shell.until(1.minutes) { (it as Update.Shell).shown?.takeIf { s -> s.spaces.none { sp -> sp.space == hideable } } }
        assertEquals(Answer.Done, b.ask(Call.ManageHideSpace(hideable.scopeId, hidden = false)))
        shell.until(1.minutes) { (it as Update.Shell).shown?.takeIf { s -> s.spaces.any { sp -> sp.space == hideable } } }
        val listed = shown.servers.single { it.connection == shown.connection }.tenants
        val otherTenant = listed.firstOrNull { it != shown.tenant }
        if (otherTenant == null) println("one tenant only: the tenant's switch is not walked") else {
            fun tenants(u: Update) = (u as Update.Shell).shown?.servers?.single { it.connection == shown.connection }?.tenants
            assertEquals(Answer.Done, b.ask(Call.ManageHideTenant(shown.connection, otherTenant.tenantId, hidden = true)))
            shell.until(1.minutes) { u -> tenants(u)?.takeIf { otherTenant !in it } }
            assertEquals(Answer.Done, b.ask(Call.ManageHideTenant(shown.connection, otherTenant.tenantId, hidden = false)))
            shell.until(1.minutes) { u -> tenants(u)?.takeIf { otherTenant in it } }
            println("hid and showed again: space ${hideable.name}, tenant ${otherTenant.name}")
        }

        val groups = b.ask(Call.HomeGroups) as Answer.Groups
        println("Home: " + groups.groups.joinToString { g -> "${g.heading?.key}=${g.nodes.size}" })

        val pots = state.lists.objects.filter { it.application == ApplicationId.EXPENSES }
        val first = pots.first()
        val hits = b.ask(Call.SearchQuery(first.name.take(4).uppercase())) as Answer.Hits
        assertTrue(first in hits.hits, "search for ${first.name.take(4)} did not find ${first.name}")
        assertEquals(Answer.Done, b.ask(Call.ShellOpen(first)))
        assertEquals(first, shell.until { (it as Update.Shell).state?.shell?.openObject })

        // each Pot opened as its own model until one with entries, read as the host reads it: the view is what it shows,
        // each row with the core's words for it
        var withEntries = 0
        for (pot in pots) {
            val id = (b.ask(Call.PotOpen(pot)) as Answer.PotOpened).pot
            val view = b.watching(Watched.Pot(id)).untilJson(1.minutes) { u ->
                val s = u["state"] as? JsonObject
                assertTrue(s?.get("failed")?.jsonPrimitive?.boolean != true, "${pot.name} did not open")
                s?.get("view") as? JsonObject
            }
            assertEquals(Answer.Done, b.ask(Call.PotClose(id)))
            val entries = view.getValue("entries").jsonArray.map { it.jsonObject }
            val balances = view.getValue("balances").jsonArray.map { it.jsonObject }
            println("${view.getValue("name").jsonPrimitive.content}: ${entries.size} entries, balances " +
                balances.joinToString { "${it.getValue("row").jsonObject["name"]}=${it.getValue("row").jsonObject.getValue("balance")}" })
            assertEquals(0L, balances.sumOf { it.getValue("row").jsonObject.getValue("balance").jsonPrimitive.long }, "${pot.name}: the balances do not sum to zero")
            entries.forEach { assertTrue(it.getValue("line").jsonArray.isNotEmpty() || it.getValue("row").jsonObject["kind"] is JsonNull) }
            balances.forEach { assertTrue("msg" in it.getValue("balance").jsonObject.getValue("notice").jsonObject) }
            if (entries.isNotEmpty() && ++withEntries == 2) break
        }
        assertTrue(withEntries > 0, "no seeded Pot has entries")

        // The principal's own picture: its id in what the shell shows once the core read the pictures, its bytes beside
        // the answer, equal to what the core reads in a session of its own
        val me = assertNotNull(shown.me, "the shell names no signed-in member")
        // a watch of its own: the update that brought the pictures may lie behind the ones read above, and nothing has
        // moved the shell since
        val withPictures = b.watching(Watched.Shell).until(1.minutes) { u -> (u as Update.Shell).shown?.takeIf { me in it.pictures } }
        println("pictures: ${withPictures.pictures.size} members")
        val (pictured, picture) = b.askWithPayload(Call.AvatarPicture(me))
        assertIs<Answer.Bytes>(pictured)
        val coreOwn = coreOwnPicture(origin, env, withPictures.tenant.tenantId, me)
        assertEquals(sha256(coreOwn), sha256(picture!!), "the bridge's picture is not the core's")
        println("picture: ${picture.size} bytes, ${pictured.mediaType}, sha256 ${sha256(picture)}")
        // ... and a Pot whose people hold the principal carries its id among the pictures
        val link = pots.first { it.name == "App link" }
        val linkId = (b.ask(Call.PotOpen(link)) as Answer.PotOpened).pot
        // a Pot's update is read as JSON (its view is sent, never read back): its people, and the ids with a picture
        val linkUpdate = b.watching(Watched.Pot(linkId)).untilJson(1.minutes) { u -> u.takeIf { (u["state"] as? JsonObject)?.get("view") is JsonObject } }
        val people = linkUpdate.getValue("state").jsonObject.getValue("view").jsonObject.getValue("people").jsonArray
        assertTrue(people.any { me in it.toString() }, "App link's people do not hold the principal")
        assertTrue(me in linkUpdate.getValue("pictures").jsonArray.map { it.jsonPrimitive.content }, "App link's update carries no picture id for the principal")
        assertEquals(Answer.Done, b.ask(Call.PotClose(linkId)))

        // Amsterdam downloaded into a file the host named, read back as VEYQ_LIVE_EXPORT reads it
        val amsterdam = pots.first { it.name == "Amsterdam" }
        val potId = (b.ask(Call.PotOpen(amsterdam)) as Answer.PotOpened).pot
        b.watching(Watched.Pot(potId)).untilJson(1.minutes) { u -> (u["state"] as? JsonObject)?.get("view") as? JsonObject }
        assertEquals(Answer.Name("Amsterdam.zip"), b.ask(Call.PotArchiveName(potId)))
        val dir = kotlin.io.path.createTempDirectory("veyq-bridge").toFile()
        val zip = File(dir, "Amsterdam.zip")
        assertEquals(Answer.Done, b.ask(Call.PotDownload(potId, zip.path, "deleted person"), timeout = 2.minutes))
        val payloads = readBack(zip)
        println("Amsterdam: ${zip.length()} bytes of ZIP, $payloads payloads to their SHA-256")
        // a path that does not open fails the call, and nothing is written
        val nowhere = File(dir, "missing/Amsterdam.zip")
        assertIs<Answer.Failed>(b.ask(Call.PotDownload(potId, nowhere.path, "deleted person"), timeout = 2.minutes))
        assertTrue(!nowhere.exists())
        assertEquals(Answer.Done, b.ask(Call.PotClose(potId)))
        dir.deleteRecursively()
        b.shutdown()
    }

    /**
     * The device trust through calls (ADR 0123 decision 8; its Consequences, the device trust), over core's
     * [SoftwareDeviceKeys] whose prompt the test plays and a device store the host keeps across bridges — each new bridge
     * the app started again. The sign-in holds the password trust and carries the offer; LOGIN taken, a fresh bridge
     * resumes with nothing asked; changed to PRESENCE in Manage servers, a fresh bridge resumes after the person's
     * confirmation, a cancelled prompt stays on the unlock, the device key opens it; back to the password trust (a wrong
     * password said so), a fresh bridge unlocks with the password and the offer follows, answered "Not now"; "Stop
     * trusting" ends it, and the next start signs in. Everything enrolled is removed again.
     */
    @Test
    fun theOfferAndManageTakeEachLevelAndTheWayBackAndAFreshBridgeResumesAtEach() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val password = env.getValue("VEYQ_PASSWORD")
        val kept = ConcurrentHashMap<String, String>()
        val keys = SoftwareDeviceKeys(BouncyCastlePrimitives)
        fun started() = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge trust test"), hostStore(kept), { keys }, JvmFiles)
        val signIn = Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), password, env.getValue("VEYQ_SECRET_KEY"))
        val both = listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN)

        // signed in: the password trust, and the offer of the fake's levels; LOGIN taken, nothing asked
        val first = started()
        val gate = first.watching(Watched.Session)
        assertEquals(Answer.Done, first.ask(Call.SessionStart))
        assertEquals(Answer.Done, first.ask(signIn, timeout = 2.minutes))
        val signed = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        val connection = signed.connectionKey
        assertEquals(SessionModel.Offer(connection, both), signed.offer)
        assertEquals(Answer.Done, first.ask(Call.SessionStaySignedIn(StaySignedIn.LOGIN), timeout = 2.minutes))
        assertNull((first.gateNow() as Gate.Signed).offer, "the offer outlived its answer")
        assertEquals(0, keys.prompts, "the login level asked the person")
        first.shutdown() // the app closed, not signed out

        // started again: resumed, nothing asked; Manage servers says LOGIN and changes it to PRESENCE (the person asked)
        started().apply {
            assertEquals(Answer.Done, ask(Call.SessionStart, timeout = 2.minutes))
            assertEquals(Gate.Signed(connection, signed.tenantId), gateNow(), "a remembered account did not resume, or offered again")
            assertEquals(0, keys.prompts)
            val manage = watching(Watched.Manage)
            assertEquals(Answer.Done, ask(Call.ManageOpen))
            val atLogin = manage.until { (it as Update.Manage).servers?.single { s -> s.connection == connection } }
            assertEquals(TrustedDevice.Method.DEVICE_KEY, atLogin.trusted)
            assertEquals(StaySignedIn.LOGIN, atLogin.level)
            assertEquals(both, atLogin.levels)
            assertEquals(Answer.Done, ask(Call.ManageStaySignedIn(connection, StaySignedIn.PRESENCE), timeout = 2.minutes))
            manage.until { (it as Update.Manage).servers?.single { s -> s.connection == connection }?.takeIf { s -> s.level == StaySignedIn.PRESENCE } }
            assertEquals(1, keys.prompts, "the presence level did not ask the person at enrolment")
            assertEquals(Answer.Done, ask(Call.ManageClose))
        }.shutdown()

        // started again: resumed after the person's confirmation
        started().apply {
            assertEquals(Answer.Done, ask(Call.SessionStart, timeout = 2.minutes))
            assertIs<Gate.Signed>(gateNow(), "the presence level did not resume")
        }.shutdown()
        assertEquals(2, keys.prompts)

        // started again with the prompt cancelled: the unlock, which a cancel keeps and the device key opens
        keys.authenticates = false
        val third = started()
        assertEquals(Answer.Done, third.ask(Call.SessionStart, timeout = 2.minutes))
        val unlock = assertIs<Gate.Unlock>(third.gateNow())
        assertEquals(TrustedDevice.Method.DEVICE_KEY, unlock.record.method)
        assertEquals(StaySignedIn.PRESENCE, unlock.record.level)
        assertEquals(Answer.Done, third.ask(Call.SessionUnlock(""), timeout = 2.minutes))
        assertEquals(Gate.Unlock(unlock.record), third.gateNow(), "a cancelled prompt left the unlock")
        keys.authenticates = true
        assertEquals(Answer.Done, third.ask(Call.SessionUnlock(""), timeout = 2.minutes))
        assertIs<Gate.Signed>(third.gateNow(), "the device key did not unlock")

        // back to the password trust in Manage servers: a wrong password said so, the right one replaces the wrap
        val manage = third.watching(Watched.Manage)
        assertEquals(Answer.Done, third.ask(Call.ManageOpen))
        manage.until { (it as Update.Manage).servers?.single { s -> s.connection == connection } }
        assertEquals(Answer.Refused(Answer.Why.WRONG_PASSWORD), third.ask(Call.ManagePasswordTrust(connection, password + "x"), timeout = 2.minutes))
        assertEquals(Answer.Done, third.ask(Call.ManagePasswordTrust(connection, password), timeout = 2.minutes))
        val back = manage.until { (it as Update.Manage).servers?.single { s -> s.connection == connection }?.takeIf { s -> s.trusted == TrustedDevice.Method.DEVICE } }
        assertNull(back.level)
        assertEquals(Answer.Done, third.ask(Call.ManageClose))
        third.shutdown()
        val prompts = keys.prompts

        // started again: the unlock asks for the password alone; the offer follows it and is answered "Not now"
        val fourth = started()
        assertEquals(Answer.Done, fourth.ask(Call.SessionStart, timeout = 2.minutes))
        val byPassword = assertIs<Gate.Unlock>(fourth.gateNow())
        assertEquals(TrustedDevice.Method.DEVICE, byPassword.record.method)
        assertEquals(Answer.Done, fourth.ask(Call.SessionUnlock(password), timeout = 2.minutes))
        assertEquals(SessionModel.Offer(connection, both), assertIs<Gate.Signed>(fourth.gateNow(), "the password did not unlock").offer)
        assertEquals(Answer.Done, fourth.ask(Call.SessionNotNow))
        assertNull((fourth.gateNow() as Gate.Signed).offer)

        // "Stop trusting this device": Manage servers shows no trust, and the next start signs in
        val managed = fourth.watching(Watched.Manage)
        assertEquals(Answer.Done, fourth.ask(Call.ManageOpen))
        assertEquals(TrustedDevice.Method.DEVICE, managed.until { (it as Update.Manage).servers?.single { s -> s.connection == connection } }.trusted)
        assertEquals(Answer.Done, fourth.ask(Call.SessionForgetDevice(connection), timeout = 2.minutes))
        assertNull(managed.until { (it as Update.Manage).servers?.single { s -> s.connection == connection }?.takeIf { s -> s.trusted == null } }.trusted)
        assertEquals(Answer.Done, fourth.ask(Call.ManageClose))
        assertEquals(Answer.Done, fourth.ask(Call.SessionSignOut(connection)))
        fourth.shutdown()
        started().apply {
            assertEquals(Answer.Done, ask(Call.SessionStart))
            assertIs<Gate.SignIn>(gateNow(), "a device no longer trusted did not sign in")
        }.shutdown()
        assertEquals(prompts, keys.prompts, "the password trust asked for the person")
        for (level in StaySignedIn.entries) assertTrue(!keys.hasUnlockKey(level), "the $level unlock key outlived every trust")
    }

    /**
     * A node's header and the space's trash through calls: in the personal space's root, New
     * folder makes "App header <n>"; its header pins and unpins it, renames it, trashes it and undoes that, trashes it
     * again; the space's trash restores it, then it is trashed and purged. Each step is read back by a fresh read of the
     * server (`Shell.Refresh`, a fresh watch; the trash opened anew), and nothing is left behind: the folder is purged.
     */
    @Test
    fun aFolderIsMadePinnedRenamedTrashedUndoneRestoredAndPurgedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge header test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
        gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        val first = b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.spaces?.isNotEmpty() == true } }
        val personal = first.state!!.lists.spaces.first { it.kind == "PERSONAL" }

        /** The shell as the server has it now: the lists read anew, then a fresh watch's first state. */
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun node(u: Update.Shell, name: String) = u.state!!.lists.nodes.firstOrNull { it.scopeId == personal.scopeId && it.name == name && it.parentId == null }

        // Home on the personal space's root, where New offers a folder
        assertEquals(Answer.Done, b.ask(Call.ShellChoose(null)))
        assertEquals(Answer.Done, b.ask(Call.ShellSwitchSpace(personal.scopeId)))
        val home = b.watching(Watched.Shell).until { u -> (u as Update.Shell).takeIf { it.state?.shell?.space == personal.scopeId && it.state.shell.path.isEmpty() && it.state.shell.openObject == null } }
        assertTrue(home.shown!!.canCreateHere)
        assertTrue(home.shown.creates.any { it.create == me.raddatz.veyq.mobile.AppKinds.Create.FOLDER })
        val name = "App header ${System.currentTimeMillis() % 100_000}"
        assertEquals(Answer.Said(null), b.ask(Call.ShellCreateFolder(name), timeout = 1.minutes))
        val made = assertNotNull(node(fresh(), name), "the new folder is not listed")
        println("made $name")
        val at = me.raddatz.veyq.mobile.navigation.NodeAt(made.scopeId, made.nodeId, made.kind, made.name)

        val header = (b.ask(Call.HeaderOpen(at, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        val watched = b.watching(Watched.Header(header))
        val plan = watched.until { u -> (u as Update.Header).shown?.takeIf { it.pinned != null }?.plan }
        println("plan: " + listOf(plan.buttons, plan.menu, plan.last).joinToString(" | ") { g -> g.joinToString { it.id } })
        assertEquals("delete", plan.last.last().id)

        // pinned and unpinned, each read back
        // a fresh read: the lists (and the pin set) read anew, a fresh header asked whether it is pinned
        suspend fun pinnedNow(): Boolean? {
            fresh()
            val h = (b.ask(Call.HeaderOpen(at, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            return ((b.watching(Watched.Header(h)).next() as Update.Header).shown!!.pinned).also { b.ask(Call.HeaderClose(h)) }
        }
        assertEquals(false, pinnedNow())
        assertEquals(Answer.Said(null), b.ask(Call.HeaderPin(header), timeout = 1.minutes))
        assertEquals(true, pinnedNow(), "the pin did not land")
        assertEquals(Answer.Said(null), b.ask(Call.HeaderPin(header), timeout = 1.minutes))
        assertEquals(false, pinnedNow(), "the unpin did not land")
        println("pinned and unpinned")

        // renamed, read back under the new name; a header of the renamed node from now on (the host opens one per name)
        val renamed = "$name renamed"
        assertEquals(Answer.Renamed(null, null), b.ask(Call.HeaderRename(header, renamed), timeout = 1.minutes))
        assertNotNull(node(fresh(), renamed), "the rename did not land")
        assertEquals(Answer.Done, b.ask(Call.HeaderClose(header)))
        val at2 = at.copy(name = renamed)
        val h2 = (b.ask(Call.HeaderOpen(at2, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        val w2 = b.watching(Watched.Header(h2))
        println("renamed to $renamed")

        // trashed, its undo offered; undone, read back where it was
        assertEquals(me.raddatz.veyq.mobile.words.Msg.TRASH_MOVED, (b.ask(Call.HeaderTrash(h2), timeout = 1.minutes) as Answer.Trashed).notice.msg)
        w2.until { u -> (u as Update.Header).shown?.takeIf { it.undo != null && it.gone } }
        assertNull(node(fresh(), renamed), "the trash did not land")
        assertEquals(Answer.Said(null), b.ask(Call.HeaderUndo(h2), timeout = 1.minutes))
        assertNotNull(node(fresh(), renamed), "the undo did not bring it back")
        println("trashed and undone")

        // trashed again, its undo left to the core's window (6 s): the offer ends by itself
        assertEquals(me.raddatz.veyq.mobile.words.Msg.TRASH_MOVED, (b.ask(Call.HeaderTrash(h2), timeout = 1.minutes) as Answer.Trashed).notice.msg)
        val offeredAt = System.currentTimeMillis()
        w2.until { u -> (u as Update.Header).shown?.takeIf { it.undo != null } }
        w2.until(30.seconds) { u -> (u as Update.Header).shown?.takeIf { it.undo == null } }
        val held = System.currentTimeMillis() - offeredAt
        println("the undo was offered for ${held} ms")
        assertTrue(held >= me.raddatz.veyq.mobile.navigation.ShellModel.TRASH_UNDO_MS - 500, "the undo ended early")
        assertNull(node(fresh(), renamed))

        // the space's trash: listed, restored, read back; trashed once more, purged, gone from the trash
        suspend fun trashRows(): List<TrashShown.Row> {
            val t = (b.ask(Call.TrashOpen(personal.scopeId)) as Answer.TrashOpened).trash
            val rows = b.watching(Watched.Trash(t)).until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.rows != null || s.failed } }
            assertTrue(!rows.failed, "the trash did not read")
            assertEquals(Answer.Done, b.ask(Call.TrashClose(t)))
            return rows.rows!!
        }
        val t = (b.ask(Call.TrashOpen(personal.scopeId)) as Answer.TrashOpened).trash
        val tw = b.watching(Watched.Trash(t))
        val row = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows?.firstOrNull { it.id == at.nodeId } }
        assertTrue(row.restorable)
        assertEquals(renamed, row.name)
        assertEquals(Answer.Done, b.ask(Call.TrashRestore(t, row.id), timeout = 1.minutes))
        tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == at.nodeId } == true } }
        assertNotNull(node(fresh(), renamed), "the restore did not land")
        assertTrue(trashRows().none { it.id == at.nodeId })
        println("restored from the space's trash")

        val h3 = (b.ask(Call.HeaderOpen(at2, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
        assertEquals(me.raddatz.veyq.mobile.words.Msg.TRASH_MOVED, (b.ask(Call.HeaderTrash(h3), timeout = 1.minutes) as Answer.Trashed).notice.msg)
        assertEquals(Answer.Done, b.ask(Call.TrashClose(t)))
        val t2 = (b.ask(Call.TrashOpen(personal.scopeId)) as Answer.TrashOpened).trash
        val tw2 = b.watching(Watched.Trash(t2))
        tw2.until(1.minutes) { u -> (u as Update.Trash).shown?.rows?.firstOrNull { it.id == at.nodeId } }
        assertEquals(Answer.Done, b.ask(Call.TrashPurge(t2, at.nodeId), timeout = 1.minutes))
        val after = tw2.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == at.nodeId } == true } }
        assertNull(after.error)
        assertTrue(trashRows().none { it.id == at.nodeId }, "the purge did not land")
        assertNull(node(fresh(), renamed))
        println("trashed and purged: nothing left")
        assertEquals(Answer.Done, b.ask(Call.TrashClose(t2)))
        b.shutdown()
    }

    /**
     * The move and copy sheet and a folder's download through calls, over the real core: a folder
     * "App move <n>" made beside the seeded Pot Amsterdam; Amsterdam copied beside itself (its open Pot model's copy), the
     * copy moved into the folder and undone, read back where it was, then moved in again with its undo left to the core's
     * window; the folder copied (with the Pot in it) and downloaded into a file the host named, read back as
     * `VEYQ_LIVE_FOLDER` reads an archive. Everything made is trashed and purged at the end.
     */
    @Test
    fun aPotIsCopiedMovedAndUndoneAFolderCopiedAndDownloadedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge move test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
        gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        val first = b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.objects?.any { o -> o.name == "Amsterdam" } == true } }
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val scope = amsterdam.scopeId
        val home = first.state.lists.nodes.first { it.nodeId == amsterdam.nodeId }.parentId

        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun nodes(u: Update.Shell) = u.state!!.lists.nodes.filter { it.scopeId == scope }
        fun at(n: me.raddatz.veyq.mobile.navigation.NodeRef) = me.raddatz.veyq.mobile.navigation.NodeAt(n.scopeId, n.nodeId, n.kind, n.name)

        /** A sheet opened and read: its id and its first loaded state (a copy's: once its losses are read too). */
        suspend fun sheet(node: me.raddatz.veyq.mobile.navigation.NodeAt, copy: Boolean): Pair<Long, MoveShown> {
            val id = (b.ask(Call.MoveOpen(node, copy, "deleted person")) as Answer.MoveOpened).move
            val shown = b.watching(Watched.Move(id)).until(2.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded && (!copy || it.losses.size > 1) } }
            return id to shown
        }
        /** Goes to [place] (null: the root) in sheet [id], read back from its state. */
        suspend fun go(id: Long, place: String?) {
            assertEquals(Answer.Done, b.ask(Call.MoveGo(id, place)))
            b.watching(Watched.Move(id)).until { u -> (u as Update.Move).shown?.takeIf { it.trail.last().nodeId == place } }
        }

        // a folder beside Amsterdam (Home in its space, its folder)
        assertEquals(Answer.Done, b.ask(Call.ShellChoose(null)))
        assertEquals(Answer.Done, b.ask(Call.ShellSwitchSpace(scope)))
        home?.let { h -> nodes(first).first { it.nodeId == h }.asFolder()?.let { assertEquals(Answer.Done, b.ask(Call.ShellEnter(it))) } }
        val n = System.currentTimeMillis() % 100_000
        val name = "App move $n"
        assertEquals(Answer.Said(null), b.ask(Call.ShellCreateFolder(name), timeout = 1.minutes))
        val folder = assertNotNull(nodes(fresh()).firstOrNull { it.name == name && it.parentId == home }, "the new folder is not listed")
        println("made $name")
        val made = mutableListOf(folder)
        try {
            // Amsterdam copied beside itself by its open Pot model: "Amsterdam (2)" (or the next free name)
            val potId = (b.ask(Call.PotOpen(amsterdam)) as Answer.PotOpened).pot
            b.watching(Watched.Pot(potId)).untilJson(1.minutes) { u -> (u["state"] as? JsonObject)?.get("view") as? JsonObject }
            val (copyPot, opened) = sheet(at(nodes(first).first { it.nodeId == amsterdam.nodeId }), copy = true)
            assertEquals(home, opened.trail.last().nodeId)
            assertNull(opened.here, "a copy goes beside its original")
            assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_POT, opened.losses[1].msg)
            val copied = b.ask(Call.MoveHere(copyPot), timeout = 3.minutes) as Answer.MoveSent
            assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_DONE, copied.done?.msg, "${copied.problem}")
            assertTrue(!copied.undoable)
            b.ask(Call.MoveClose(copyPot))
            b.ask(Call.PotClose(potId))
            val before = first.state.lists.nodes.map { it.nodeId }.toSet()
            val potCopy = assertNotNull(nodes(fresh()).firstOrNull { it.kind == amsterdam.kind && it.parentId == home && it.nodeId !in before }, "the Pot's copy is not listed")
            made += potCopy
            println("copied Amsterdam: ${potCopy.name}")

            // the copy moved into the folder, undone (back beside Amsterdam), moved again with the undo left to its window
            val (move, browsing) = sheet(at(potCopy), copy = false)
            assertEquals(me.raddatz.veyq.mobile.words.Msg.MOVE_HERE_CURRENT, browsing.here)
            go(move, folder.nodeId)
            val sent = b.ask(Call.MoveHere(move), timeout = 1.minutes) as Answer.MoveSent
            assertEquals(me.raddatz.veyq.mobile.words.Msg.MOVE_MOVED, sent.done?.msg, "${sent.problem}")
            assertTrue(sent.undoable)
            assertEquals(folder.nodeId, nodes(fresh()).first { it.nodeId == potCopy.nodeId }.parentId, "the move did not land")
            assertEquals(Answer.Said(null), b.ask(Call.MoveUndo(move), timeout = 1.minutes))
            assertEquals(home, nodes(fresh()).first { it.nodeId == potCopy.nodeId }.parentId, "the undo did not move it back")
            b.ask(Call.MoveClose(move))
            println("moved into $name and undone")
            val (again, _) = sheet(at(potCopy), copy = false)
            go(again, folder.nodeId)
            assertTrue((b.ask(Call.MoveHere(again), timeout = 1.minutes) as Answer.MoveSent).undoable)
            val offeredAt = System.currentTimeMillis()
            b.watching(Watched.Move(again)).until(30.seconds) { u -> (u as Update.Move).shown?.takeIf { it.undo == null } }
            val held = System.currentTimeMillis() - offeredAt
            println("the move's undo was offered for $held ms")
            assertTrue(held >= me.raddatz.veyq.mobile.navigation.ShellModel.TRASH_UNDO_MS - 500, "the undo ended early")
            assertEquals(folder.nodeId, nodes(fresh()).first { it.nodeId == potCopy.nodeId }.parentId)
            b.ask(Call.MoveClose(again))

            // the folder copied beside itself, the Pot in it with it
            val (copyFolder, folderSheet) = sheet(at(folder), copy = true)
            assertEquals(listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_ALWAYS), folderSheet.losses.take(1).map { it.msg })
            val folderCopied = b.ask(Call.MoveHere(copyFolder), timeout = 5.minutes) as Answer.MoveSent
            assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_DONE, folderCopied.done?.msg, "${folderCopied.problem}")
            b.ask(Call.MoveClose(copyFolder))
            val after = nodes(fresh())
            val folderCopy = assertNotNull(after.firstOrNull { it.kind == "FOLDER" && it.parentId == home && it.name.startsWith("$name (") }, "the folder's copy is not listed")
            made += folderCopy
            assertTrue(after.any { it.parentId == folderCopy.nodeId && it.kind == amsterdam.kind }, "the folder's copy holds no Pot")
            println("copied $name: ${folderCopy.name}")

            // the folder downloaded into a file the host named, read back
            val header = (b.ask(Call.HeaderOpen(at(folder), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            val shown = (b.watching(Watched.Header(header)).until { u -> (u as Update.Header).shown?.takeIf { it.plan != null } })
            assertTrue(shown.plan!!.menu.any { it.id == "download" } || shown.plan.buttons.any { it.id == "download" }, "a folder offers no download")
            val dir = kotlin.io.path.createTempDirectory("veyq-bridge").toFile()
            val zip = File(dir, shown.archiveName)
            val downloaded = b.ask(Call.HeaderDownload(header, zip.path, "deleted person"), timeout = 3.minutes) as Answer.Downloaded
            assertEquals(me.raddatz.veyq.mobile.words.Msg.DOWNLOAD_DONE, downloaded.notice.msg)
            assertTrue(downloaded.written)
            val payloads = readBackFolder(zip, amsterdam.kind)
            println("$name downloaded: ${zip.length()} bytes of ZIP, $payloads payloads to their SHA-256, left out: ${downloaded.leftOut}")
            // a path that does not open: failed, nothing written
            val nowhere = File(dir, "missing/${shown.archiveName}")
            assertEquals(me.raddatz.veyq.mobile.words.Msg.DOWNLOAD_FAILED, (b.ask(Call.HeaderDownload(header, nowhere.path, "deleted person"), timeout = 3.minutes) as Answer.Downloaded).notice.msg)
            assertTrue(!nowhere.exists())
            dir.deleteRecursively()
            b.ask(Call.HeaderClose(header))
        } finally {
            // everything made goes to the trash and is purged (what lies in a folder made goes with it), and any "App move"
            // row a run stopped by force left in the space's trash is purged too
            val listed = nodes(fresh())
            val ids = made.map { it.nodeId }.toSet()
            val roots = listed.filter { it.nodeId in ids && it.parentId !in ids }
            for (m in roots) {
                val h = (b.ask(Call.HeaderOpen(at(m), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
                println("trash ${m.name}: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
                b.ask(Call.HeaderClose(h))
            }
            val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
            val tw = b.watching(Watched.Trash(t))
            val rows = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
            for (row in rows.filter { r -> r.id in ids || (r.kind == "FOLDER" && Regex("App move \\d+( \\(\\d+\\))?").matches(r.name)) }) {
                val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                println("purge ${row.name}: $purged")
                if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
            }
            b.ask(Call.TrashClose(t))
            assertTrue(nodes(fresh()).none { it.nodeId in ids }, "something made was left")
            println("trashed and purged: nothing left")
        }
        b.shutdown()
    }

    /**
     * The share sheet through calls, over the real core: a folder "App share <n>" made beside the
     * seeded Pot Amsterdam; a link minted on it through the form (its address shown once), edited in place (its label read
     * back), renewed (a new address, the old one gone from the list), switched off and on again (its address kept), switched
     * off and deleted — each read back from the sheet's list. The folder is trashed and purged at the end, its links with it.
     */
    @Test
    fun aLinkIsMintedEditedRenewedSwitchedOffAndOnAndDeletedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge share test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
        gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        val first = b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.objects?.any { o -> o.name == "Amsterdam" } == true } }
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val scope = amsterdam.scopeId
        val home = first.state.lists.nodes.first { it.nodeId == amsterdam.nodeId }.parentId
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun nodes(u: Update.Shell) = u.state!!.lists.nodes.filter { it.scopeId == scope }
        fun at(n: me.raddatz.veyq.mobile.navigation.NodeRef) = me.raddatz.veyq.mobile.navigation.NodeAt(n.scopeId, n.nodeId, n.kind, n.name)

        assertEquals(Answer.Done, b.ask(Call.ShellChoose(null)))
        assertEquals(Answer.Done, b.ask(Call.ShellSwitchSpace(scope)))
        home?.let { h -> nodes(first).first { it.nodeId == h }.asFolder()?.let { assertEquals(Answer.Done, b.ask(Call.ShellEnter(it))) } }
        val n = System.currentTimeMillis() % 100_000
        val name = "App share $n"
        assertEquals(Answer.Said(null), b.ask(Call.ShellCreateFolder(name), timeout = 1.minutes))
        val folder = assertNotNull(nodes(fresh()).firstOrNull { it.name == name && it.parentId == home }, "the new folder is not listed")
        println("made $name")
        try {
            val id = (b.ask(Call.ShareOpen(at(folder))) as Answer.ShareOpened).share
            val w = b.watching(Watched.Share(id))
            suspend fun now(): ShareShown = b.watching(Watched.Share(id)).until(1.minutes) { u -> (u as Update.Share).shown?.takeIf { it.links != null && it.busy == null && !it.saving } }
            val empty = w.until(1.minutes) { u -> (u as Update.Share).shown?.takeIf { it.links != null } }
            assertEquals(emptyList(), empty.links)
            assertTrue(empty.canMint, "links are off in this tenant")

            // mint: the form as the tenant's rules have it (a password, an end where they require one), the address shown once
            assertEquals(Answer.Done, b.ask(Call.ShareAdd(id)))
            val form = w.until { u -> (u as Update.Share).shown?.form }
            assertEquals(Answer.Done, b.ask(Call.ShareLabel(id, "App label")))
            if (!form.lock.optional) assertEquals(Answer.Done, b.ask(Call.SharePassword(id, "app link password $n")))
            w.until { u -> (u as Update.Share).shown?.form?.takeIf { it.label == "App label" && it.canSave } }
            assertEquals(Answer.Said(null), b.ask(Call.ShareSave(id), timeout = 1.minutes))
            val minted = now().also { assertNotNull(it.freshAddress, "no address was shown") }
            val link = minted.links!!.single()
            assertEquals(minted.freshAddress, link.address)
            assertTrue(link.address!!.startsWith("$origin/s/${link.shareId}#k="))
            assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("App label"), link.title)
            assertEquals(Answer.Done, b.ask(Call.ShareFreshSeen(id)))
            println("minted ${link.shareId}")

            // edited in place: the label read back, the address kept
            assertEquals(Answer.Done, b.ask(Call.ShareEdit(id, link.shareId)))
            w.until { u -> (u as Update.Share).shown?.takeIf { it.editing == link.shareId } }
            assertEquals(Answer.Done, b.ask(Call.ShareLabel(id, "App label edited")))
            w.until { u -> (u as Update.Share).shown?.form?.takeIf { it.label == "App label edited" } }
            assertEquals(Answer.Said(null), b.ask(Call.ShareSave(id), timeout = 1.minutes))
            val edited = now().links!!.single()
            assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("App label edited"), edited.title)
            assertEquals(link.address, edited.address)
            println("edited ${link.shareId}")

            // renewed: a new address, the old link gone from the list (the folder re-keyed)
            assertEquals(Answer.Done, b.ask(Call.ShareAskRenew(id, link.shareId)))
            w.until { u -> (u as Update.Share).shown?.takeIf { it.renewing == link.shareId } }
            assertEquals(Answer.Said(null), b.ask(Call.ShareRenew(id), timeout = 3.minutes))
            val renewedState = now()
            val renewed = renewedState.links!!.single()
            assertTrue(renewed.shareId != link.shareId, "the renewed link kept its id")
            assertEquals(renewedState.freshAddress, renewed.address)
            assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("App label edited"), renewed.title, "the renew did not carry the label")
            assertEquals(Answer.Done, b.ask(Call.ShareFreshSeen(id)))
            println("renewed ${link.shareId} as ${renewed.shareId}")

            // switched off (re-keyed), on again at its old address, off again and deleted
            val offSaid = b.ask(Call.ShareSwitchOff(id, renewed.shareId), timeout = 3.minutes)
            assertEquals(Answer.Said(null), offSaid)
            val off = now().links!!.single()
            assertTrue(!off.canCopy && off.canReactivate && off.canDelete, "switched off, it is not offered again or for deletion")
            assertEquals(Answer.Said(null), b.ask(Call.ShareReactivate(id, renewed.shareId), timeout = 3.minutes))
            val on = now().links!!.single()
            assertEquals(renewed.address, on.address, "switched on again at another address")
            println("switched ${renewed.shareId} off and on")
            assertEquals(Answer.Said(null), b.ask(Call.ShareSwitchOff(id, renewed.shareId), timeout = 3.minutes))
            now()
            assertEquals(Answer.Done, b.ask(Call.ShareAskDelete(id, renewed.shareId)))
            w.until { u -> (u as Update.Share).shown?.takeIf { it.deleting == renewed.shareId } }
            assertEquals(Answer.Said(null), b.ask(Call.ShareDelete(id), timeout = 1.minutes))
            assertEquals(emptyList(), now().links)
            println("deleted ${renewed.shareId}")
            b.ask(Call.ShareClose(id))
        } finally {
            // the folder made goes to the trash and is purged, and an "App share" folder a run stopped by force left there
            val h = (b.ask(Call.HeaderOpen(at(folder), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            println("trash $name: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
            b.ask(Call.HeaderClose(h))
            val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
            val tw = b.watching(Watched.Trash(t))
            val rows = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
            for (row in rows.filter { r -> r.id == folder.nodeId || (r.kind == "FOLDER" && Regex("App share \\d+").matches(r.name)) }) {
                val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                println("purge ${row.name}: $purged")
                if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
            }
            b.ask(Call.TrashClose(t))
            assertTrue(nodes(fresh()).none { it.nodeId == folder.nodeId }, "the folder made was left")
        }
        b.shutdown()
    }

    /**
     * A Pot's live poll, settle-up and restore through calls, over the real core: Amsterdam copied beside itself and
     * renamed "App pot <n>"; the copy opened and its poll watched (`Watched.PotLive`); an entry written by a second session
     * of its own (another device) seen through the poll within its cadence; one settle-up row recorded, then all, each
     * read back from the Pot's watch (every balance settled); the entry deleted by the second session, its history read
     * (`Pot.History`: deleted, restorable) and restored (`Pot.Restore`), read back live. The copy is trashed and purged.
     */
    @Test
    fun aPotIsPolledSettledAndRestoredThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val (b, first) = signedInLive(origin, "bridge live poll test")
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val scope = amsterdam.scopeId
        val home = first.state.lists.nodes.first { it.nodeId == amsterdam.nodeId }.parentId
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun nodes(u: Update.Shell) = u.state!!.lists.nodes.filter { it.scopeId == scope }
        fun at(n: me.raddatz.veyq.mobile.navigation.NodeRef) = me.raddatz.veyq.mobile.navigation.NodeAt(n.scopeId, n.nodeId, n.kind, n.name)
        fun JsonObject.view() = (this["state"] as? JsonObject)?.get("view") as? JsonObject
        fun ids(view: JsonObject, list: String) = view.getValue(list).jsonArray.map { it.jsonObject.getValue("row").jsonObject.getValue("id").jsonPrimitive.content }
        fun settle(view: JsonObject) = view.getValue("settleUp").jsonArray.map { it.jsonObject.getValue("row").jsonObject }.map { r ->
            me.raddatz.veyq.mobile.expenses.PotView.SettleRow(
                r["from"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, r["to"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                r.getValue("amount").jsonPrimitive.long, r.getValue("fromId").jsonPrimitive.content, r.getValue("toId").jsonPrimitive.content,
            )
        }

        // Amsterdam copied beside itself through its open Pot model, the copy renamed "App pot <n>"
        val source = (b.ask(Call.PotOpen(amsterdam)) as Answer.PotOpened).pot
        b.watching(Watched.Pot(source)).untilJson(1.minutes) { it.view() }
        val sheet = (b.ask(Call.MoveOpen(at(nodes(first).first { it.nodeId == amsterdam.nodeId }), true, "deleted person")) as Answer.MoveOpened).move
        b.watching(Watched.Move(sheet)).until(2.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded && it.losses.size > 1 } }
        val copied = b.ask(Call.MoveHere(sheet), timeout = 3.minutes) as Answer.MoveSent
        assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_DONE, copied.done?.msg, "${copied.problem}")
        b.ask(Call.MoveClose(sheet))
        b.ask(Call.PotClose(source))
        val before = first.state.lists.nodes.map { it.nodeId }.toSet()
        val copy = assertNotNull(nodes(fresh()).firstOrNull { it.kind == amsterdam.kind && it.parentId == home && it.nodeId !in before }, "the Pot's copy is not listed")
        val name = "App pot ${System.currentTimeMillis() % 100_000}"
        try {
            val header = (b.ask(Call.HeaderOpen(at(copy), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            b.watching(Watched.Header(header)).until { u -> (u as Update.Header).shown?.takeIf { it.plan != null } }
            assertEquals(null, (b.ask(Call.HeaderRename(header, name), timeout = 1.minutes) as Answer.Renamed).problem)
            b.ask(Call.HeaderClose(header))
            println("copied Amsterdam as $name")
            val ref = me.raddatz.veyq.mobile.navigation.ObjectRef(copy.scopeId, copy.nodeId, copy.kind, amsterdam.application, name)

            // the copy opened, its poll watched as a desktop's page does while it shows
            val id = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
            val pot = b.watching(Watched.Pot(id))
            pot.untilJson(1.minutes) { it.view() }
            val live = b.watching(Watched.PotLive(id))

            // another device writes an entry: the poll brings it, within its cadence (20 s ± 20 %)
            val store = liveRun.store()
            val account = VeyqAccount.signIn(
                BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
                ThisDevice(store, NoDeviceKeys, "bridge live poll test, the other device", BouncyCastlePrimitives),
            ) { block -> HttpClient(OkHttp, block) }
            val tenant = account.open(account.tenants.first { it.tenantId == first.shown!!.tenant.tenantId })
            val other = assertNotNull(me.raddatz.veyq.mobile.expenses.PotSession.open(tenant, ref.scopeId, ref.nodeId), "the other device did not open $name")
            val payer = other.ledger!!.participants.first { it.active && !it.erased }
            val form = me.raddatz.veyq.mobile.expenses.PotView(other).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "Live check", payers = listOf(me.raddatz.veyq.mobile.expenses.EntryForm.Payer(payer.id, "7")))
            val entry = other.newId()
            assertEquals(me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN, other.saveEntry(entry, form, null))
            val written = System.currentTimeMillis()
            pot.untilJson(90.seconds) { u -> u.view()?.takeIf { entry in ids(it, "entries") } }
            val seenAfter = System.currentTimeMillis() - written
            println("the other device's entry seen through the poll after $seenAfter ms")
            assertTrue(seenAfter < 30_000, "the poll took longer than its cadence")
            assertNull(live.pending(), "the poll's watch delivered an update of its own")

            // settle-up: one row, then all; each read back from the Pot's watch (where one row was all there was, the other
            // device unsettles the Pot again with an entry another person paid)
            val now = assertNotNull(b.watching(Watched.Pot(id)).untilJson { it.view() })
            val rows = settle(now)
            assertTrue(rows.isNotEmpty(), "the copy has nothing to settle")
            assertEquals(Answer.Said(null), b.ask(Call.PotRecord(id, rows.take(1)), timeout = 1.minutes))
            var afterOne = b.watching(Watched.Pot(id)).untilJson(1.minutes) { u -> u.view()?.takeIf { v -> settle(v).none { it.fromId == rows[0].fromId && it.toId == rows[0].toId && it.amount == rows[0].amount } } }
            println("recorded one row of ${rows.size}; ${settle(afterOne).size} left")
            if (settle(afterOne).isEmpty()) {
                other.refresh()
                val second = other.ledger!!.participants.filter { it.active && !it.erased }.first { it.id != payer.id }
                val unsettle = me.raddatz.veyq.mobile.expenses.PotView(other).blankForm(java.time.LocalDate.now().toString())!!
                    .copy(title = "Live check 2", payers = listOf(me.raddatz.veyq.mobile.expenses.EntryForm.Payer(second.id, "5")))
                val second2 = other.newId()
                assertEquals(me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN, other.saveEntry(second2, unsettle, null))
                // a fresh watch: the state as it is now, then each change — never an update queued from before the record
                afterOne = b.watching(Watched.Pot(id)).untilJson(90.seconds) { u -> u.view()?.takeIf { second2 in ids(it, "entries") } }
            }
            assertEquals(Answer.Said(null), b.ask(Call.PotRecord(id, settle(afterOne)), timeout = 1.minutes))
            val settled = b.watching(Watched.Pot(id)).untilJson(1.minutes) { u -> u.view()?.takeIf { settle(it).isEmpty() } }
            val balances = settled.getValue("balances").jsonArray.map { it.jsonObject.getValue("row").jsonObject.getValue("balance").jsonPrimitive.long }
            assertTrue(balances.all { it == 0L }, "not every balance settled: $balances")
            println("recorded all: every balance settled")

            // the other device deletes the entry; its history and its restore through calls
            other.refresh()
            val basedOn = me.raddatz.veyq.mobile.expenses.PotView(other).editing(entry)!!.basedOn
            assertEquals(me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN, other.deleteEntry(entry, basedOn))
            pot.untilJson(90.seconds) { u -> u.view()?.takeIf { entry in ids(it, "deleted") } }
            val history = assertIs<Answer.EntryHistory>(b.ask(Call.PotHistory(id, entry)))
            assertTrue(history.deleted && history.restorable, "the deleted entry is not offered a restore")
            assertEquals(2, history.rows.size)
            println("the deleted entry's history: " + history.rows.joinToString { "${it.line} by ${it.writer}" } + "; restorable until ${history.until}")
            assertEquals(Answer.Said(null), b.ask(Call.PotRestore(id, entry), timeout = 1.minutes))
            b.watching(Watched.Pot(id)).untilJson(1.minutes) { u -> u.view()?.takeIf { entry in ids(it, "entries") && entry !in ids(it, "deleted") } }
            assertEquals(3, (b.ask(Call.PotHistory(id, entry)) as Answer.EntryHistory).rows.size)
            println("restored: live again")
            b.unwatch(live.id)
            assertEquals(Answer.Done, b.ask(Call.PotClose(id)))
        } finally {
            val h = (b.ask(Call.HeaderOpen(at(copy), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            println("trash $name: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
            b.ask(Call.HeaderClose(h))
            val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
            val tw = b.watching(Watched.Trash(t))
            val rows = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
            rows.firstOrNull { it.id == copy.nodeId }?.let { row ->
                val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                println("purge ${row.name}: $purged")
                if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
            }
            b.ask(Call.TrashClose(t))
            assertTrue(nodes(fresh()).none { it.nodeId == copy.nodeId }, "the copy was left")
        }
        b.shutdown()
    }

    /**
     * The entry editor through calls on an `App pot <n>` copy of Amsterdam: an expense with two payers and items split by
     * shares, percent and amounts; a refund; a transfer; another currency with its rate filled from the tenant's service
     * (typed where it names none); a save refused; a stale save (another device edited meanwhile); a delete — each read
     * back from a fresh Pot open, the copy purged at the end.
     */
    @Test
    fun anEntryIsWrittenEditedAndDeletedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val (b, first) = signedInLive(origin, "bridge live editor test")
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val scope = amsterdam.scopeId
        val home = first.state.lists.nodes.first { it.nodeId == amsterdam.nodeId }.parentId
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun nodes(u: Update.Shell) = u.state!!.lists.nodes.filter { it.scopeId == scope }
        fun at(n: me.raddatz.veyq.mobile.navigation.NodeRef) = me.raddatz.veyq.mobile.navigation.NodeAt(n.scopeId, n.nodeId, n.kind, n.name)
        fun JsonObject.view() = (this["state"] as? JsonObject)?.get("view") as? JsonObject
        fun rows(view: JsonObject, list: String) = view.getValue(list).jsonArray.map { it.jsonObject.getValue("row").jsonObject }
        fun idOf(view: JsonObject, title: String) = rows(view, "entries").firstOrNull { it["title"]?.takeIf { t -> t !is JsonNull }?.jsonPrimitive?.content == title }
            ?.getValue("id")?.jsonPrimitive?.content

        val source = (b.ask(Call.PotOpen(amsterdam)) as Answer.PotOpened).pot
        b.watching(Watched.Pot(source)).untilJson(1.minutes) { it.view() }
        val sheet = (b.ask(Call.MoveOpen(at(nodes(first).first { it.nodeId == amsterdam.nodeId }), true, "deleted person")) as Answer.MoveOpened).move
        b.watching(Watched.Move(sheet)).until(2.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded && it.losses.size > 1 } }
        val copied = b.ask(Call.MoveHere(sheet), timeout = 3.minutes) as Answer.MoveSent
        assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_DONE, copied.done?.msg, "${copied.problem}")
        b.ask(Call.MoveClose(sheet))
        b.ask(Call.PotClose(source))
        val before = first.state.lists.nodes.map { it.nodeId }.toSet()
        val copy = assertNotNull(nodes(fresh()).firstOrNull { it.kind == amsterdam.kind && it.parentId == home && it.nodeId !in before }, "the Pot's copy is not listed")
        val name = "App pot ${System.currentTimeMillis() % 100_000}"
        try {
            val header = (b.ask(Call.HeaderOpen(at(copy), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            b.watching(Watched.Header(header)).until { u -> (u as Update.Header).shown?.takeIf { it.plan != null } }
            assertEquals(null, (b.ask(Call.HeaderRename(header, name), timeout = 1.minutes) as Answer.Renamed).problem)
            b.ask(Call.HeaderClose(header))
            println("copied Amsterdam as $name")
            val ref = me.raddatz.veyq.mobile.navigation.ObjectRef(copy.scopeId, copy.nodeId, copy.kind, amsterdam.application, name)
            var pot = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
            b.watching(Watched.Pot(pot)).untilJson(1.minutes) { it.view() }

            /** A fresh open of the copy: its view as read now. */
            suspend fun reopened(): JsonObject {
                b.ask(Call.PotClose(pot))
                pot = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
                return b.watching(Watched.Pot(pot)).untilJson(1.minutes) { it.view() }
            }
            suspend fun open(entry: String?): Pair<Long, Watching> {
                val e = (b.ask(Call.EditorOpen(pot, entry, ".")) as Answer.EditorOpened).editor
                return e to b.watching(Watched.Editor(e))
            }
            suspend fun shown(w: Watching, pick: (EditorShown) -> Boolean = { true }) = w.until(1.minutes) { u -> (u as Update.Editor).shown?.takeIf(pick) }
            suspend fun edit(e: Long, vararg cs: me.raddatz.veyq.mobile.expenses.EditorChange) = cs.forEach { assertEquals(Answer.Done, b.ask(Call.EditorChange(e, it))) }
            suspend fun saved(e: Long) = b.ask(Call.EditorSave(e), timeout = 2.minutes) as Answer.EditorSaved
            suspend fun reread(title: String): EditorShown {
                val view = reopened()
                val id = assertNotNull(idOf(view, title), "$title is not read back")
                val (e, w) = open(id)
                return shown(w).also { b.ask(Call.EditorClose(e)) }
            }

            // a save refused: nothing written, the refusal at its field
            val (refused, rw) = open(null)
            val people = shown(rw).people
            assertTrue(people.size >= 2, "the copy has fewer than two people")
            val (p1, p2) = people[0].id to people[1].id
            val others = people.drop(2).map { it.id }
            assertEquals(Answer.EditorSaved(false, null), saved(refused))
            assertEquals("payers", shown(rw) { it.refusal != null }.refusal?.at)
            b.ask(Call.EditorClose(refused))
            println("a save refused: at payers")

            // an expense: two payers, items by shares, percent and amounts, the rest equally
            val (ex, xw) = open(null)
            edit(ex, E.Title("Live expense"), E.Paid(0, "40"), E.AddPayer, E.Payer(1, p2), E.Paid(1, "20"))
            edit(ex, E.AddItem, E.ItemLabel(0, "By shares"), E.ItemAmount(0, "12"), E.Expand(0), E.Share(0, p1, "2"))
            edit(ex, E.AddItem, E.ItemLabel(1, "By percent"), E.ItemAmount(1, "18"), E.Expand(1), E.SplitMode(1, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.PERCENT))
            others.forEach { edit(ex, E.Toggle(1, it, false)) }
            edit(ex, E.Share(1, p1, "60"), E.Share(1, p2, "40"))
            edit(ex, E.AddItem, E.ItemLabel(2, "By amounts"), E.ItemAmount(2, "10"), E.Expand(2), E.SplitMode(2, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.AMOUNTS))
            others.forEach { edit(ex, E.Toggle(2, it, false)) }
            edit(ex, E.Share(2, p1, "7"), E.Share(2, p2, "3"))
            assertTrue(shown(xw) { it.preview.isNotEmpty() }.refusal == null)
            assertEquals(Answer.EditorSaved(true, null), saved(ex))
            val expense = reread("Live expense")
            assertEquals(listOf(p1 to "40.00", p2 to "20.00"), expense.payers.map { it.person to it.amount })
            assertEquals(
                listOf(me.raddatz.veyq.mobile.expenses.EntryForm.Mode.SHARES, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.PERCENT, me.raddatz.veyq.mobile.expenses.EntryForm.Mode.AMOUNTS),
                expense.items.map { it.split.mode },
            )
            assertEquals(listOf("2", "60.00", "7.00"), expense.items.map { it.split.people.first { p -> p.person == p1 }.value })
            println("an expense with two payers and three items read back: ${expense.items.map { "${it.label} ${it.split.mode}" }}")

            // a refund and a transfer
            val (rf, _) = open(null)
            edit(rf, E.Kind(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.REFUND), E.Title("Live refund"), E.Paid(0, "5"))
            assertEquals(Answer.EditorSaved(true, null), saved(rf))
            assertEquals(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.REFUND, reread("Live refund").kind)
            val (tr, _) = open(null)
            edit(tr, E.Kind(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.TRANSFER), E.Title("Live transfer"), E.Amount("3"), E.From(p1), E.To(p2))
            assertEquals(Answer.EditorSaved(true, null), saved(tr))
            val transfer = reread("Live transfer")
            assertEquals(Triple(me.raddatz.veyq.mobile.expenses.EntryForm.Kind.TRANSFER, p1, p2), Triple(transfer.kind, transfer.from, transfer.to))
            println("a refund and a transfer read back")

            // another currency: its rate filled from the tenant's service, or typed where it names none
            val (fx, fw) = open(null)
            edit(fx, E.Title("Live CHF"), E.Paid(0, "9"), E.Currency("CHF"))
            // the fill starts with the currency's change (FILLING) where the tenant names a service; none: nothing comes
            val answered = kotlinx.coroutines.withTimeoutOrNull(20.seconds) {
                shown(fw) { it.currency == "CHF" && it.rateFill != null && it.rateFill?.state != me.raddatz.veyq.mobile.expenses.EntryEditor.RateState.FILLING }
            }
            val rate = if (answered?.rateFill?.state == me.raddatz.veyq.mobile.expenses.EntryEditor.RateState.FILLED) {
                println("the rate filled: ${answered.rate} from ${answered.rateFill?.day}")
                answered.rate
            } else {
                edit(fx, E.RateTyped("1.05"))
                println("no rate from the tenant's service (${answered?.rateFill?.state ?: "none named"}): the rate typed")
                "1.05"
            }
            assertEquals(Answer.EditorSaved(true, null), saved(fx))
            val chf = reread("Live CHF")
            assertEquals("CHF" to rate, chf.currency to chf.rate)

            // a stale save: another device edits the expense meanwhile; the save says so and shows theirs, the next goes on top
            val store = liveRun.store()
            val account = VeyqAccount.signIn(
                BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
                ThisDevice(store, NoDeviceKeys, "bridge live editor test, the other device", BouncyCastlePrimitives),
            ) { block -> HttpClient(OkHttp, block) }
            val tenant = account.open(account.tenants.first { it.tenantId == first.shown!!.tenant.tenantId })
            val other = assertNotNull(me.raddatz.veyq.mobile.expenses.PotSession.open(tenant, ref.scopeId, ref.nodeId), "the other device did not open $name")
            val expenseId = assertNotNull(idOf(reopened(), "Live expense"))
            val (st, sw) = open(expenseId)
            shown(sw)
            val theirs = me.raddatz.veyq.mobile.expenses.PotView(other).editing(expenseId)!!
            assertEquals(
                me.raddatz.veyq.mobile.expenses.PotSession.WriteOutcome.WRITTEN,
                other.saveEntry(expenseId, theirs.form.copy(title = "Live expense (theirs)"), theirs.basedOn),
            )
            edit(st, E.Title("Live expense (mine)"))
            assertEquals(Answer.EditorSaved(false, me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.ED_STALE)), saved(st))
            assertEquals("Live expense (theirs)", shown(sw) { it.title == "Live expense (theirs)" }.title)
            edit(st, E.Title("Live expense (mine)"))
            assertEquals(Answer.EditorSaved(true, null), saved(st))
            assertEquals("Live expense (mine)", reread("Live expense (mine)").title)
            println("a stale save said, theirs shown, mine on top")

            // the transfer deleted, asked first
            val transferId = assertNotNull(idOf(reopened(), "Live transfer"))
            val (dl, _) = open(transferId)
            assertEquals(Answer.Done, b.ask(Call.EditorAskDelete(dl)))
            assertEquals(Answer.EditorSaved(true, null), b.ask(Call.EditorDelete(dl), timeout = 1.minutes))
            val after = reopened()
            assertTrue(rows(after, "deleted").any { it.getValue("id").jsonPrimitive.content == transferId }, "the transfer is not among the deleted")
            assertNull(idOf(after, "Live transfer"))
            println("the transfer deleted")
            b.ask(Call.PotClose(pot))
        } finally {
            val h = (b.ask(Call.HeaderOpen(at(copy), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            println("trash $name: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
            b.ask(Call.HeaderClose(h))
            val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
            val tw = b.watching(Watched.Trash(t))
            val trashed = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
            trashed.firstOrNull { it.id == copy.nodeId }?.let { row ->
                val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                println("purge ${row.name}: $purged")
                if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
            }
            b.ask(Call.TrashClose(t))
            assertTrue(nodes(fresh()).none { it.nodeId == copy.nodeId }, "the copy was left")
        }
        b.shutdown()
    }

    /**
     * An entry's receipts through calls (ADR 0137) on an `App pot <n>` copy of Amsterdam: a JPEG and a PDF attached by the
     * paths an open dialog would give, saved with the entry, read back from a fresh open, each opened (its payload's SHA-256
     * the file's) and saved to a path and read back; the PDF taken off by an edit and gone. With
     * `VEYQ_LIVE_RECEIPT_NO_ROOM=1` the tenant's quota is set to one byte (the seeded principal is the Server Owner; the
     * admin listener is `VEYQ_ADMIN_BASE`, default `http://127.0.0.1:8090`, as `DepotLiveTest.aCopyOrAFolder*`'s
     * `VEYQ_LIVE_STORAGE_FIT`) and put back however the run ends: an entry saved with a picture names it left out. The
     * copy purged.
     */
    /**
     * New pot and a Pot's settings through calls (`PotCreate.*`, `PotSettings.*`), in a folder made beside Amsterdam ("App
     * folder <n>"): "App pot <n>" created there with a member and a person typed, the shell opening it; its base currency
     * changed while it is empty, a category added and renamed, a person added, renamed, deactivated and erased (asked first),
     * another member added, an entry saved through the editor and then the currency refused; the Pot copied (CR 3a's path,
     * `Move.*`) and the copy read back as the original — its people, currency, own category and entry. The folder, with both
     * Pots, is trashed and purged.
     */
    @Test
    fun aPotIsCreatedSetUpAndCopiedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val (b, first) = signedInLive(origin, "bridge live create test")
        val n = System.currentTimeMillis() % 100_000
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        val made = mutableListOf<String>()
        withFolder(b, first, "App folder $n", purge = true) { folder ->
            assertEquals(Answer.Done, b.ask(Call.ShellEnter(me.raddatz.veyq.mobile.navigation.FolderRef(folder.nodeId, folder.name))))

            // New pot in the folder shown: the space shown, English's currency, the space's members offered
            val c = (b.ask(Call.PotCreateOpen("en-US")) as Answer.PotCreateOpened).create
            val start = b.watching(Watched.PotCreate(c)).until(1.minutes) { u -> (u as Update.PotCreate).shown?.takeIf { it.offered.size >= 2 } }
            assertEquals(folder.scopeId to "USD", start.space to start.currency)
            val (member, other) = start.offered[0] to start.offered[1]
            val name = "App pot $n"
            assertEquals(Answer.Done, b.ask(Call.PotCreateName(c, name)))
            assertEquals(Answer.Done, b.ask(Call.PotCreateAddMember(c, member.userId)))
            assertTrue(b.watching(Watched.PotCreate(c)).until(1.minutes) { u -> (u as Update.PotCreate).shown?.takeIf { it.people.size == 1 } }.canCreate)
            assertEquals(Answer.Stored(true, null), b.ask(Call.PotCreateCreate(c, "Anna $n"), timeout = 2.minutes))
            assertEquals(Answer.Done, b.ask(Call.PotCreateClose(c)))
            val created = b.watching(Watched.Shell).until(1.minutes) { u -> (u as Update.Shell).state?.shell?.openObject?.takeIf { it.name == name } }
            made += created.nodeId
            assertEquals(folder.nodeId, fresh().state!!.lists.nodes.first { it.nodeId == created.nodeId }.parentId, "the Pot is not in the folder shown")
            println("created $name with ${member.name} and Anna $n")

            val pot = (b.ask(Call.PotOpen(created)) as Answer.PotOpened).pot
            b.watching(Watched.Pot(pot)).untilJson(1.minutes) { (it["state"] as? JsonObject)?.get("view") as? JsonObject }
            val set = (b.ask(Call.PotSettingsOpen(pot)) as Answer.PotSettingsOpened).settings
            suspend fun settings(pick: (PotSettingsShown) -> Boolean): PotSettingsShown =
                b.watching(Watched.PotSettings(set)).let { w -> w.until(1.minutes) { u -> (u as Update.PotSettings).shown?.takeIf { !it.saving && pick(it) } }.also { b.unwatch(w.id) } }
            suspend fun stored(call: Call) = b.ask(call, timeout = 2.minutes)
            val s0 = settings { true }
            assertEquals(listOf(member.name, "Anna $n"), s0.people.map { it.entry.name })
            assertEquals("USD" to true, s0.currency to s0.currencyEditable)

            // the base currency while the Pot is empty
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsCurrency(set, "CHF")))
            settings { it.currency == "CHF" }
            // a category added and renamed
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsAddCategory(set, "Snacks $n")))
            val category = settings { it.categories.size == 1 }.categories.single()
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsRenameCategory(set, category.id, "Snacks $n renamed")))
            settings { s -> s.categories.any { (it.label as? me.raddatz.veyq.mobile.words.Piece.Plain)?.text == "Snacks $n renamed" } }
            // a person added, renamed, deactivated, asked about and erased
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsAddPerson(set, "Cleo $n")))
            val cleo = settings { s -> s.people.any { it.entry.name == "Cleo $n" } }.people.first { it.entry.name == "Cleo $n" }.entry
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsRename(set, cleo.id, "Cleo $n B")))
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsToggle(set, cleo.id)))
            assertEquals(false to "Cleo $n B", settings { s -> s.people.any { it.entry.id == cleo.id && !it.entry.active } }.people.first { it.entry.id == cleo.id }.entry.let { it.active to it.name })
            assertEquals(Answer.Done, b.ask(Call.PotSettingsAskErase(set, cleo.id)))
            assertEquals(cleo.id, settings { it.asking != null }.asking!!.person)
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsErase(set)))
            settings { s -> s.asking == null && s.people.none { it.entry.id == cleo.id } }
            // another member added
            assertEquals(Answer.Stored(true, null), stored(Call.PotSettingsAddMember(set, other.userId)))
            val people = settings { s -> s.people.any { it.entry.userId == other.userId } }.people.map { it.entry.name }
            println("set up: CHF, Snacks $n renamed, people $people")

            // an entry: the currency is fixed now, and refused
            val ed = (b.ask(Call.EditorOpen(pot, null, ".")) as Answer.EditorOpened).editor
            b.watching(Watched.Editor(ed)).until(1.minutes) { u -> (u as Update.Editor).shown }
            assertEquals(Answer.Done, b.ask(Call.EditorChange(ed, E.Title("Live create $n"))))
            assertEquals(Answer.Done, b.ask(Call.EditorChange(ed, E.Paid(0, "12"))))
            val saved = b.ask(Call.EditorSave(ed), timeout = 2.minutes) as Answer.EditorSaved
            assertTrue(saved.done, "${saved.notice}")
            b.ask(Call.EditorClose(ed))
            val fixed = me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.SETTINGS_CURRENCY_FIXED)
            assertEquals(Answer.Stored(false, fixed), stored(Call.PotSettingsCurrency(set, "EUR")))
            assertTrue(settings { it.currencyFixed }.let { !it.currencyEditable && it.currency == "CHF" })
            assertEquals(Answer.Done, b.ask(Call.PotSettingsClose(set)))

            // copied beside itself (CR 3a's path) and read back as the original
            val at = me.raddatz.veyq.mobile.navigation.NodeAt(created.scopeId, created.nodeId, created.kind, created.name)
            val before = fresh().state!!.lists.nodes.map { it.nodeId }.toSet()
            val sheet = (b.ask(Call.MoveOpen(at, true, "deleted person")) as Answer.MoveOpened).move
            b.watching(Watched.Move(sheet)).until(2.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded } }
            val copied = b.ask(Call.MoveHere(sheet), timeout = 3.minutes) as Answer.MoveSent
            assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_DONE, copied.done?.msg, "${copied.problem}")
            b.ask(Call.MoveClose(sheet))
            val copy = assertNotNull(fresh().state!!.lists.nodes.firstOrNull { it.kind == created.kind && it.parentId == folder.nodeId && it.nodeId !in before }, "the copy is not listed")
            made += copy.nodeId
            suspend fun shownOf(ref: me.raddatz.veyq.mobile.navigation.ObjectRef): Triple<PotSettingsShown, List<String>, Long> {
                val p = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
                val view = b.watching(Watched.Pot(p)).untilJson(1.minutes) { (it["state"] as? JsonObject)?.get("view") as? JsonObject }
                val titles = view.getValue("entries").jsonArray.map { it.jsonObject.getValue("row").jsonObject.getValue("title").jsonPrimitive.content }
                val sid = (b.ask(Call.PotSettingsOpen(p)) as Answer.PotSettingsOpened).settings
                val s = b.watching(Watched.PotSettings(sid)).until(1.minutes) { u -> (u as Update.PotSettings).shown }
                return Triple(s, titles, p)
            }
            val (original, originalTitles, op) = shownOf(created)
            val (theCopy, copyTitles, cp) = shownOf(me.raddatz.veyq.mobile.navigation.ObjectRef(copy.scopeId, copy.nodeId, copy.kind, created.application, copy.name))
            fun facts(s: PotSettingsShown) = listOf(s.currency, s.currencyFixed, s.categories.map { (it.label as me.raddatz.veyq.mobile.words.Piece.Plain).text }, s.people.map { it.entry.name to it.entry.active }, s.deletedPeople)
            assertEquals(facts(original), facts(theCopy))
            assertEquals(listOf("Live create $n"), originalTitles)
            assertEquals(originalTitles, copyTitles)
            println("copied $name as ${copy.name}: ${facts(theCopy)}")
            listOf(op, cp, pot).forEach { b.ask(Call.PotClose(it)) }
        }
        // the folder purged took both Pots with it: neither is listed, nor left in the trash
        assertTrue(fresh().state!!.lists.nodes.none { it.nodeId in made }, "a Pot made was left")
        val t = (b.ask(Call.TrashOpen(first.state!!.lists.objects.first { it.name == "Amsterdam" }.scopeId)) as Answer.TrashOpened).trash
        val rows = b.watching(Watched.Trash(t)).until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
        assertTrue(rows.none { it.id in made }, "a Pot made was left in the trash")
        b.ask(Call.TrashClose(t))
        b.shutdown()
    }

    @Test
    fun aReceiptIsAttachedShownAndSavedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val (b, first) = signedInLive(origin, "bridge live receipt test")
        val dir = kotlin.io.path.createTempDirectory("veyq-live-receipts").toFile()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        try {
            // a drawn JPEG and a two-page PDF, as a person's files
            val jpeg = java.io.ByteArrayOutputStream().also { out ->
                val img = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
                img.createGraphics().apply { color = java.awt.Color(30, 90, 200); fillRect(0, 0, 160, 200); dispose() }
                javax.imageio.ImageIO.write(img, "jpg", out)
            }.toByteArray()
            val photo = File(dir, "Live Quittung.jpg").also { it.writeBytes(jpeg) }
            val scan = File(dir, "Live scan.pdf").also { it.writeBytes(pdfOf(2)) }
            withAmsterdamCopy(b, first) { ref, reopen ->
                var pot = reopen(null)
                suspend fun open(entry: String?): Pair<Long, Watching> {
                    val e = (b.ask(Call.EditorOpen(pot, entry, ".")) as Answer.EditorOpened).editor
                    return e to b.watching(Watched.Editor(e))
                }
                suspend fun shown(w: Watching, pick: (EditorShown) -> Boolean = { true }) = w.until(1.minutes) { u -> (u as Update.Editor).shown?.takeIf(pick) }
                suspend fun saved(e: Long) = b.ask(Call.EditorSave(e), timeout = 2.minutes) as Answer.EditorSaved
                fun idOf(view: JsonObject, title: String) = view.getValue("entries").jsonArray.map { it.jsonObject.getValue("row").jsonObject }
                    .firstOrNull { it["title"]?.takeIf { t -> t !is JsonNull }?.jsonPrimitive?.content == title }?.getValue("id")?.jsonPrimitive?.content

                // attached by path, saved with the entry
                val (ed, ew) = open(null)
                b.ask(Call.EditorChange(ed, E.Title("Live receipts")))
                b.ask(Call.EditorChange(ed, E.Paid(0, "4")))
                assertEquals(Answer.Done, b.ask(Call.EditorAddReceipts(ed, listOf(photo.path, scan.path)), timeout = 1.minutes))
                val picked = shown(ew) { it.receipts.size == 2 }
                assertEquals(listOf("Live Quittung.jpg" to true, "Live scan.pdf" to true), picked.receipts.map { it.name to it.held })
                assertTrue(!picked.refusedPick)
                assertEquals(Answer.EditorSaved(true, null), saved(ed))
                println("a JPEG and a PDF attached by path and saved with the entry")

                // read back from a fresh open: both kept, each opened to its file's bytes and saved to a path
                pot = reopen(pot)
                val entry = assertNotNull(idOf(b.watching(Watched.Pot(pot)).untilJson(1.minutes) { u -> (u["state"] as? JsonObject)?.get("view") as? JsonObject }, "Live receipts"))
                val (kept, kw) = open(entry)
                val back = shown(kw) { it.receipts.size == 2 }
                assertTrue(back.receipts.all { !it.held && it.opens }, "${back.receipts.map { it.name }} are not kept")
                for ((file, type) in listOf(photo to "image/jpeg", scan to "application/pdf")) {
                    val r = back.receipts.single { it.name == file.name }
                    val (answer, bytes) = b.askWithPayload(Call.ReceiptOpen(pot, r.id), timeout = 1.minutes)
                    assertEquals(Answer.Bytes(type), answer)
                    assertEquals(sha(file.readBytes()), sha(bytes!!), "${file.name} opened to other bytes")
                    val copy = File(dir, "saved-${file.name}")
                    assertEquals(Answer.Done, b.ask(Call.ReceiptSave(pot, r.id, copy.path), timeout = 1.minutes))
                    assertEquals(sha(file.readBytes()), sha(copy.readBytes()), "${file.name} saved as other bytes")
                    println("${file.name}: opened as $type and saved, SHA-256 ${sha(bytes).take(16)}… both")
                }

                // the PDF taken off by an edit: gone from a fresh open
                assertEquals(Answer.Done, b.ask(Call.EditorDropReceipt(kept, back.receipts.single { it.name == scan.name }.id)))
                assertEquals(Answer.EditorSaved(true, null), saved(kept))
                pot = reopen(pot)
                b.watching(Watched.Pot(pot)).untilJson(1.minutes) { u -> (u["state"] as? JsonObject)?.get("view") as? JsonObject }
                val (again, aw) = open(entry)
                assertEquals(listOf(photo.name), shown(aw).receipts.map { it.name })
                b.ask(Call.EditorClose(again))
                println("the PDF taken off by an edit is gone")

                // the space's storage full: the entry saved, the receipt named left out
                if (System.getenv("VEYQ_LIVE_RECEIPT_NO_ROOM") != null) withOneByteQuota(origin, first.shown!!.tenant.tenantId) {
                    val (nr, nw) = open(null)
                    b.ask(Call.EditorChange(nr, E.Title("Live no room")))
                    b.ask(Call.EditorChange(nr, E.Paid(0, "2")))
                    assertEquals(Answer.Done, b.ask(Call.EditorAddReceipts(nr, listOf(photo.path))))
                    shown(nw) { it.receipts.size == 1 }
                    val full = saved(nr)
                    assertEquals(
                        Answer.EditorSaved(true, me.raddatz.veyq.mobile.words.Notices.receiptsSkipped(listOf(photo.name))), full,
                        "the save under a full storage did not name the receipt left out",
                    )
                    println("under a one-byte quota: saved, ${full.notice}")
                }
            }
        } finally {
            dir.deleteRecursively()
            b.shutdown()
        }
    }

    /**
     * The tenant's quota set to one byte for [body] on the admin listener (`VEYQ_ADMIN_BASE`), as the Server Owner the
     * seeded principal is, and put back however [body] ends; a quota of exactly one byte found is taken for a run's own
     * leftover (`DepotLiveTest.aCopyOrAFolder*`'s rule). The admin API is reached by a second sign-in's client: its
     * bearer-carrying client is the core's, internal to it, so read by reflection — a test's reach, never the bridge's.
     */
    private suspend fun withOneByteQuota(origin: String, tenantId: String, body: suspend () -> Unit) {
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val store = liveRun.store()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, "bridge live receipt test, the Server Owner", BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val http = VeyqAccount::class.java.methods.single { it.name.startsWith("getHttp$") }.invoke(account) as HttpClient
        val api = me.raddatz.veyq.mobile.api.apis.TenantsApi(System.getenv("VEYQ_ADMIN_BASE") ?: "http://127.0.0.1:8090", http)
        val prior = api.getTenantStorage(tenantId).also { assertTrue(it.success, "the quota did not read: ${it.status}") }.body().quotaBytes?.takeIf { it != 1L }
        suspend fun restore() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val r = if (prior == null) api.clearTenantStorageQuota(tenantId) else api.setTenantStorageQuota(tenantId, me.raddatz.veyq.mobile.api.models.TenantStorageQuotaUpdate(prior))
            println("the tenant's quota put back (${prior ?: "none"}): ${r.status}")
        }
        try {
            assertTrue(api.setTenantStorageQuota(tenantId, me.raddatz.veyq.mobile.api.models.TenantStorageQuotaUpdate(1L)).success, "the quota was not set")
            body()
        } finally {
            restore()
        }
    }

    /**
     * A file's page and its "Save a copy" through calls, over the real core: a folder "App depot <n>" made at the personal
     * space's top level, filled by the core's own upload in a second session (`DepotFiles.upload`; the upload calls are a later
     * phase's) with a JPEG, a text past `TEXT_CAP`, a PDF and a 300 MiB file of drawn bytes; each opened through `File.Open`
     * and its payload (`File.Bytes`) or text held to the original's SHA-256 (a text's first megabyte); the large one saved
     * through `File.Save` to a path at its SHA-256, a second save cancelled midway with nothing left at its path. The folder is
     * trashed and purged however it ends. Runs with `VEYQ_LIVE` alone.
     */
    @Test
    fun aFileIsShownAndSavedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        // the folder and its files made by the core in a session of its own first: the bridge's tree, read at its sign-in,
        // then holds them (a session's tree is cached; a refresh does not read another device's writes)
        val store = liveRun.store()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, "bridge live file test, the uploader", BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.tenants.first { origin.contains(it.domain) })
        val personalScope = tenant.spaces().first { it.kind == "PERSONAL" }.scopeId
        val folderName = "App depot ${System.currentTimeMillis() % 100_000}"
        val made = me.raddatz.veyq.mobile.session.FolderCreate.create(tenant, personalScope, null, folderName)
        val folderId = assertIs<me.raddatz.veyq.mobile.session.NodeCreate.Result.Done>(made, "$made").created.nodeId
        println("made $folderName")
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge file test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        try {
            val jpeg = java.io.ByteArrayOutputStream().also { out ->
                val image = java.awt.image.BufferedImage(1200, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(30, 90, 200), 1200f, 800f, java.awt.Color(240, 200, 40))
                g.fillRect(0, 0, 1200, 800)
                g.dispose()
                javax.imageio.ImageIO.write(image, "jpg", out)
            }.toByteArray()
            val text = ByteArray(me.raddatz.veyq.mobile.depot.FileView.TEXT_CAP + 200_000) { i -> if (i % 80 == 79) '\n'.code.toByte() else ('a' + (i / 80) % 26).code.toByte() }
            val pdf = pdfOf(30)
            val bigSize = 300L shl 20
            /** The large file's byte at [i]: drawn, never held whole. */
            fun big(i: Long): Byte = ((i * 2654435761L) ushr 13).toByte()
            fun bigRead(from: Long, length: Int) = ByteArray(minOf(length.toLong(), bigSize - from).toInt()) { big(from + it) }
            val bigSha = java.security.MessageDigest.getInstance("SHA-256").let { d ->
                var at = 0L
                while (at < bigSize) { val chunk = bigRead(at, 1 shl 20); d.update(chunk); at += chunk.size }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            suspend fun upload(name: String, size: Long, type: String, read: (Long, Int) -> ByteArray) {
                val up = me.raddatz.veyq.mobile.depot.DepotFiles.upload(
                    tenant, personalScope, folderId, name, size, type, null, me.raddatz.veyq.mobile.AppKinds::tokenInput, { from, len -> read(from, len) },
                )
                assertEquals(me.raddatz.veyq.mobile.depot.DepotFiles.Outcome.DONE, up.outcome, name)
                println("uploaded $name ($size B)")
            }
            fun whole(bytes: ByteArray): (Long, Int) -> ByteArray = { from, len -> bytes.copyOfRange(from.toInt(), minOf(bytes.size, from.toInt() + len)) }
            upload("photo.jpg", jpeg.size.toLong(), "image/jpeg", whole(jpeg))
            upload("long.txt", text.size.toLong(), "text/plain", whole(text))
            upload("thirty.pdf", pdf.size.toLong(), "application/pdf", whole(pdf))
            upload("big.bin", bigSize, "application/octet-stream", ::bigRead)

            val gate = b.watching(Watched.Session)
            assertEquals(Answer.Done, b.ask(Call.SessionStart))
            gate.until { (it as Update.Session).gate as? Gate.SignIn }
            assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
            gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
            b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.spaces?.isNotEmpty() == true } }

            val nodes = fresh().state!!.lists.nodes.filter { it.parentId == folderId }
            fun at(name: String) = nodes.first { it.name == name }.let { me.raddatz.veyq.mobile.navigation.NodeAt(it.scopeId, it.nodeId, it.kind, it.name) }
            suspend fun open(name: String): Pair<Long, FileShown> {
                val id = (b.ask(Call.FileOpen(at(name))) as Answer.FileOpened).file
                return id to b.watching(Watched.File(id)).until(2.minutes) { u -> (u as Update.File).shown?.takeIf { it.view != FileShown.View.LOADING && it.headerFacts != null } }
            }
            val (photo, photoShown) = open("photo.jpg")
            assertEquals(FileShown.View.IMAGE to "image/jpeg", photoShown.view to photoShown.mediaType)
            val (photoAnswer, photoBytes) = b.askWithPayload(Call.FileBytes(photo), 1.minutes)
            assertEquals(Answer.Bytes("image/jpeg"), photoAnswer)
            assertEquals(sha256(jpeg), sha256(photoBytes!!), "the JPEG shown is not the one uploaded")
            println("photo.jpg shown: ${photoBytes.size} B at ${sha256(photoBytes)}")
            val (_, longShown) = open("long.txt")
            assertEquals(FileShown.View.TEXT, longShown.view)
            assertEquals(me.raddatz.veyq.mobile.words.Msg.FILE_TEXT_TRUNCATED, longShown.truncated?.msg)
            assertEquals(sha256(text.copyOf(me.raddatz.veyq.mobile.depot.FileView.TEXT_CAP)), sha256(longShown.text!!.encodeToByteArray()), "the text shown is not the file's first megabyte")
            println("long.txt shown: its first ${longShown.text.length} characters, cut said")
            val (thirty, thirtyShown) = open("thirty.pdf")
            assertEquals(FileShown.View.PDF, thirtyShown.view)
            val (pdfAnswer, pdfBytes) = b.askWithPayload(Call.FileBytes(thirty), 1.minutes)
            assertEquals(Answer.Bytes("application/pdf"), pdfAnswer)
            assertEquals(sha256(pdf), sha256(pdfBytes!!))
            assertEquals(Answer.ReceiptPages(30, null), b.ask(Call.FilePages(30)))
            println("thirty.pdf shown: ${pdfBytes.size} B at ${sha256(pdfBytes)}")

            // the large file: nothing to show, saved whole to a path at its SHA-256, a second save cancelled midway
            val (bigId, bigShown) = open("big.bin")
            assertEquals(FileShown.View.MESSAGE to me.raddatz.veyq.mobile.words.Msg.FILE_UNSUPPORTED, bigShown.view to bigShown.words)
            val target = File.createTempFile("veyq-big", ".bin").apply { delete() }
            val started = System.currentTimeMillis()
            val save = (b.ask(Call.FileSave(bigId, target.path)) as Answer.Saving).save
            val saved = b.watching(Watched.Save(save)).until(15.minutes) { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
            assertEquals(me.raddatz.veyq.mobile.depot.FileSave.End.SAVED, saved.end, "${saved.notice}")
            val savedSha = java.security.MessageDigest.getInstance("SHA-256").let { d ->
                target.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            println("big.bin saved in ${System.currentTimeMillis() - started} ms: ${target.length()} B at $savedSha (uploaded $bigSha)")
            assertEquals(bigSha, savedSha, "the copy saved is not the file")
            target.delete()
            val cancelled = File.createTempFile("veyq-cancel", ".bin").apply { delete() }
            val second = (b.ask(Call.FileSave(bigId, cancelled.path)) as Answer.Saving).save
            val midway = b.watching(Watched.Save(second)).until(5.minutes) { u -> (u as Update.Save).shown?.takeIf { it.written >= (20L shl 20) || it.end != null } }
            assertNull(midway.end, "the second save ended before it was cancelled")
            assertEquals(Answer.Done, b.ask(Call.SaveCancel(second), timeout = 1.minutes))
            assertTrue(!cancelled.exists(), "a cancelled save left its file")
            println("the second save cancelled at ${midway.written} B: nothing at ${cancelled.path}")
        } finally {
            // trashed and purged through the bridge's calls where it signed in, else through the core's session
            val signed = runCatching { fresh().state?.lists?.nodes?.firstOrNull { it.nodeId == folderId } }.getOrNull()
            if (signed != null) {
                val h = (b.ask(Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt(signed.scopeId, signed.nodeId, signed.kind, signed.name), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
                println("trash $folderName: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
                val t = (b.ask(Call.TrashOpen(personalScope)) as Answer.TrashOpened).trash
                val tw = b.watching(Watched.Trash(t))
                tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }.firstOrNull { it.id == folderId }?.let { row ->
                    val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 2.minutes)
                    println("purge ${row.name}: $purged")
                    if (purged == Answer.Done) tw.until(2.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
                }
                b.ask(Call.TrashClose(t))
                assertTrue(fresh().state!!.lists.nodes.none { it.nodeId == folderId }, "the folder was left")
            } else {
                // the bridge did not sign in: the core's own session trashes and purges it
                println("trash $folderName (core): ${me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, personalScope, folderId)}")
                println("purge $folderName (core): ${me.raddatz.veyq.mobile.session.NodeTrash.purge(tenant, personalScope, folderId)}")
            }
            b.shutdown()
        }
    }

    /**
     * Uploads by path through calls, over the real core (ADR 0137 decision 3 and its Consequences), with
     * `VEYQ_LIVE_UPLOAD=1` (it moves more than 2 GiB): a folder "App depot <n>" made at the personal space's top level
     * (`Shell.CreateFolder`); three files by path (`Upload.Files`) and a nested folder with a hidden file and a non-ASCII name
     * (`Upload.Folder`, walked by the core, a link said and never followed), each read back to its SHA-256 by the header's
     * download (a file downloads as itself) and its sealed type claimed by its name; a same-named file offered and replaced
     * (`Upload.TakeOffer`); a replace chosen on a file's page and raced by a second session's replace, answered stale
     * ("Replace anyway" said, nothing overwritten); a large upload cancelled (nothing left) and a file that went missing
     * after it was picked failed, then retried once it is back; a 2 GiB + 1 byte file drawn on disk uploaded by path and
     * saved back (`File.Save`) at its SHA-256; with the tenant's quota held at one byte (put back however it ends) a folder
     * upload said not to fit before anything is sent. The folder is trashed and purged however it ends.
     */
    @Test
    fun filesAndAFolderAreUploadedByPathThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        if (System.getenv("VEYQ_LIVE_UPLOAD") == null) return@runBlocking
        val (b, first) = signedInLive(origin, "bridge upload test")
        val personal = first.state!!.lists.spaces.first { it.kind == "PERSONAL" }.scopeId
        val name = "App depot ${System.currentTimeMillis() % 100_000}"
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        assertEquals(Answer.Done, b.ask(Call.ShellChoose(null)))
        assertEquals(Answer.Done, b.ask(Call.ShellSwitchSpace(personal)))
        assertEquals(Answer.Said(null), b.ask(Call.ShellCreateFolder(name), timeout = 1.minutes))
        val folder = assertNotNull(fresh().state!!.lists.nodes.firstOrNull { it.scopeId == personal && it.name == name && it.parentId == null })
        val folderId = folder.nodeId
        println("made $name")
        val dir = java.nio.file.Files.createTempDirectory("veyq-upload").toFile()
        val uploads = b.watching(Watched.Uploads)
        fun UploadsShown.items() = batches.flatMap { it.items }
        // what the uploads say next: one said after the last taken (an update still carrying an earlier one passes)
        var lastSaid = 0L
        suspend fun nextSaid(pick: (UploadsShown.Said) -> Boolean = { true }) = uploads.until(2.minutes) { u ->
            (u as Update.Uploads).shown?.said?.takeIf { it.id > lastSaid && pick(it) }
        }.also { lastSaid = it.id }
        fun digest(f: File) = java.security.MessageDigest.getInstance("SHA-256").let { d ->
            f.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        suspend fun settled(names: Collection<String>, timeout: kotlin.time.Duration = 5.minutes) = uploads.until(timeout) { u ->
            val s = (u as Update.Uploads).shown
            // a file of these that failed ends the wait with its line, never a timeout
            s?.items()?.firstOrNull { it.name in names && it.failed }?.let { error("${it.name} failed: ${it.line}") }
            s?.items()?.filter { it.name in names && it.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.UPLOADING }?.forEach { i ->
                if ((i.progress * 100).toInt() % 10 == 0) println("  ${i.name} at ${(i.progress * 100).toInt()} %")
            }
            s?.takeIf { names.all { n -> s.items().any { it.name == n && it.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.DONE } } }
        }
        suspend fun under(parent: String) = fresh().state!!.lists.nodes.filter { it.parentId == parent }
        fun at(n: me.raddatz.veyq.mobile.navigation.NodeRef) = me.raddatz.veyq.mobile.navigation.NodeAt(n.scopeId, n.nodeId, n.kind, n.name)
        /** A file read back whole through its header's download (a FILE downloads as itself), its SHA-256. */
        suspend fun readBack(n: me.raddatz.veyq.mobile.navigation.NodeRef): String {
            val h = (b.ask(Call.HeaderOpen(at(n), me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = true, hasContent = true), 2)) as Answer.HeaderOpened).header
            val out = File(dir, "back-${n.nodeId}")
            assertTrue((b.ask(Call.HeaderDownload(h, out.path, "deleted"), timeout = 5.minutes) as Answer.Downloaded).written, n.name)
            b.ask(Call.HeaderClose(h))
            return digest(out).also { out.delete() }
        }
        suspend fun typeOf(n: me.raddatz.veyq.mobile.navigation.NodeRef): List<String> {
            val id = (b.ask(Call.FileOpen(at(n))) as Answer.FileOpened).file
            val shown = b.watching(Watched.File(id)).until(2.minutes) { u -> (u as Update.File).shown?.takeIf { it.view != FileShown.View.LOADING && it.headerFacts != null } }
            b.ask(Call.FileClose(id))
            return shown.facts.mapNotNull { (it as? me.raddatz.veyq.mobile.words.Piece.Plain)?.text }
        }
        try {
            // three files by path
            val text = File(dir, "a.txt").apply { writeBytes(ByteArray(3 * me.raddatz.veyq.mobile.crypto.V1.FRAME_PLAINTEXT + 17) { i -> ('a' + i % 26).code.toByte() }) }
            val jpeg = File(dir, "b.jpg").apply {
                outputStream().use { out ->
                    val image = java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(30, 90, 200), 640f, 480f, java.awt.Color(240, 200, 40))
                    g.fillRect(0, 0, 640, 480)
                    g.dispose()
                    javax.imageio.ImageIO.write(image, "jpg", out)
                }
            }
            val pdf = File(dir, "c.pdf").apply { writeBytes(pdfOf(3)) }
            assertEquals(Answer.Queued(3), b.ask(Call.UploadFiles(personal, folderId, name, listOf(text.path, jpeg.path, pdf.path))))
            settled(listOf("a.txt", "b.jpg", "c.pdf"))
            val listed = under(folderId).associateBy { it.name }
            for (f in listOf(text, jpeg, pdf)) assertEquals(digest(f), readBack(listed.getValue(f.name)), "${f.name} read back")
            assertTrue("image/jpeg" in typeOf(listed.getValue("b.jpg")), "a picked JPEG is claimed as one")
            assertTrue("application/pdf" in typeOf(listed.getValue("c.pdf")))
            println("three files uploaded by path and read back at their SHA-256, typed by their names")

            // a nested folder with a hidden file and a non-ASCII name; a link in it said, never followed
            val trip = File(dir, "Trip ü").apply { mkdirs() }
            File(trip, ".hidden").writeText("hidden")
            File(trip, "Büro 中文").mkdirs()
            val nested = File(trip, "Büro 中文/notes.txt").apply { writeText("nested ".repeat(1000)) }
            val outside = File(dir, "outside").apply { mkdirs(); File(this, "secret.txt").writeText("never") }
            java.nio.file.Files.createSymbolicLink(File(trip, "link").toPath(), outside.toPath())
            assertEquals(Answer.Queued(2), b.ask(Call.UploadFolder(personal, folderId, trip.path), timeout = 2.minutes))
            val leftOut = nextSaid()
            assertEquals(listOf(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DEPOT_FOLDER_LINK_SKIPPED, "link")), leftOut.notices)
            assertEquals(Answer.Done, b.ask(Call.UploadSeen(leftOut.id)))
            settled(listOf(".hidden", "notes.txt"))
            val tripNode = assertNotNull(under(folderId).firstOrNull { it.name == "Trip ü" }, "the folder under its own name")
            assertNotNull(under(tripNode.nodeId).firstOrNull { it.name == ".hidden" }, "the hidden file")
            val buro = assertNotNull(under(tripNode.nodeId).firstOrNull { it.name == "Büro 中文" })
            assertEquals(digest(nested), readBack(under(buro.nodeId).single { it.name == "notes.txt" }))
            assertTrue(fresh().state!!.lists.nodes.none { it.name == "secret.txt" })
            println("a nested folder uploaded under its name with its hidden file; the link said and left out")

            // a same-named file offered and replaced
            val other = File(dir, "again").apply { mkdirs() }
            val replacing = File(other, "a.txt").apply { writeText("the replaced content") }
            assertEquals(Answer.Queued(1), b.ask(Call.UploadFiles(personal, folderId, name, listOf(replacing.path))))
            val offer = nextSaid()
            assertEquals(listOf(me.raddatz.veyq.mobile.words.Notices.replaceOffered("a.txt")), offer.notices)
            assertEquals(me.raddatz.veyq.mobile.words.Msg.DEPOT_REPLACE_TITLE, offer.action)
            assertEquals(Answer.Done, b.ask(Call.UploadTakeOffer(offer.id)))
            val aNode = under(folderId).single { it.name == "a.txt" }
            val replacedSha = digest(replacing)
            kotlinx.coroutines.withTimeout(2.minutes.inWholeMilliseconds) { while (runCatching { readBack(aNode) }.getOrNull() != replacedSha) kotlinx.coroutines.delay(1_000) }
            println("a same-named a.txt offered and replaced")

            // a replace chosen on b.jpg's page, then a second session replaces it first: answered stale, nothing overwritten
            val bNode = under(folderId).single { it.name == "b.jpg" }
            val page = (b.ask(Call.FileOpen(at(bNode))) as Answer.FileOpened).file
            b.watching(Watched.File(page)).until(2.minutes) { u -> (u as Update.File).shown?.takeIf { it.headerFacts != null } }
            assertEquals(Answer.Done, b.ask(Call.FileChooseReplace(page)))
            val peer = secondSession(origin, "bridge upload test, the peer")
            val peerFile = assertNotNull(me.raddatz.veyq.mobile.depot.DepotFiles.one(peer, personal, bNode.nodeId))
            val peerBytes = "the peer's content".encodeToByteArray()
            val raced = me.raddatz.veyq.mobile.depot.DepotFiles.replace(peer, peerFile, peerBytes.size.toLong(), "text/plain", "peer.txt", null, peerFile.blobId, { from, len -> peerBytes.copyOfRange(from.toInt(), minOf(peerBytes.size, from.toInt() + len)) })
            assertEquals(me.raddatz.veyq.mobile.depot.DepotFiles.Outcome.DONE, raced.outcome)
            assertEquals(Answer.Queued(1), b.ask(Call.UploadReplace(page, pdf.path)))
            val stale = nextSaid()
            assertEquals(me.raddatz.veyq.mobile.words.Msg.DEPOT_REPLACE_ANYWAY, stale.action, "${stale.notices}")
            assertEquals(Answer.Done, b.ask(Call.UploadSeen(stale.id)))
            assertEquals(sha256(peerBytes), readBack(bNode), "the peer's replace was overwritten")
            b.ask(Call.FileClose(page))
            println("a replace raced by a second session answered stale, the peer's content kept")

            // a large upload cancelled (nothing left); a file gone after it was picked fails, and is retried once back
            val slow = File(dir, "slow.bin")
            java.io.RandomAccessFile(slow, "rw").use { it.setLength(400L shl 20) }
            val late = File(dir, "late.txt").apply { writeText("late") }
            assertEquals(Answer.Queued(1), b.ask(Call.UploadFiles(personal, folderId, name, listOf(slow.path))))
            assertEquals(Answer.Queued(1), b.ask(Call.UploadFiles(personal, folderId, name, listOf(late.path))))
            late.renameTo(File(dir, "late.away"))
            val running = uploads.until(5.minutes) { u -> (u as Update.Uploads).shown?.items()?.firstOrNull { it.name == "slow.bin" && it.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.UPLOADING && it.progress > 0.05 } }
            assertEquals(Answer.Done, b.ask(Call.UploadCancel(running.id)))
            uploads.until(2.minutes) { u -> (u as Update.Uploads).shown?.items()?.firstOrNull { it.id == running.id && it.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.CANCELLED } }
            val failed = uploads.until(3.minutes) { u -> (u as Update.Uploads).shown?.items()?.firstOrNull { it.name == "late.txt" && it.failed } }
            assertTrue(failed.canRetry, "${failed.line}")
            File(dir, "late.away").renameTo(late)
            assertEquals(Answer.Done, b.ask(Call.UploadRetry(failed.id)))
            uploads.until(2.minutes) { u -> (u as Update.Uploads).shown?.items()?.firstOrNull { it.id == failed.id && it.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.DONE } }
            val names = under(folderId).map { it.name }
            assertTrue("slow.bin" !in names, "a cancelled upload left its file: $names")
            assertEquals(1, names.count { it == "late.txt" }, "a retry made a second file: $names")
            slow.delete()
            println("slow.bin cancelled at ${(running.progress * 100).toInt()} %, nothing left; late.txt failed while gone, retried")

            // a read that fails once mid-upload: the queue's own retry fills the file the first run made (no second file)
            val flaky = File(dir, "flaky.bin").apply { writeBytes(ByteArray(3 * me.raddatz.veyq.mobile.crypto.V1.FRAME_PLAINTEXT) { (it % 251).toByte() }) }
            JvmFiles.failOnce += "flaky.bin"
            assertEquals(Answer.Queued(1), b.ask(Call.UploadFiles(personal, folderId, name, listOf(flaky.path))))
            settled(listOf("flaky.bin"))
            assertTrue("flaky.bin" !in JvmFiles.failOnce, "the read did not fail")
            val flakyNodes = under(folderId).filter { it.name.startsWith("flaky") }
            assertEquals(listOf("flaky.bin"), flakyNodes.map { it.name }, "the retry made another file")
            assertEquals(digest(flaky), readBack(flakyNodes.single()))
            println("flaky.bin failed once mid-upload; the retry filled the file its first run made")

            // 2 GiB + 1 byte, drawn on disk, uploaded by path and saved back at its SHA-256
            val bigSize = (2L shl 30) + 1
            fun big(i: Long): Byte = ((i * 2654435761L) ushr 13).toByte()
            val bigFile = File(dir, "big.bin")
            java.io.BufferedOutputStream(bigFile.outputStream(), 1 shl 20).use { out ->
                val buf = ByteArray(1 shl 20)
                var at = 0L
                while (at < bigSize) {
                    val n = minOf(buf.size.toLong(), bigSize - at).toInt()
                    for (k in 0 until n) buf[k] = big(at + k)
                    out.write(buf, 0, n)
                    at += n
                }
            }
            val bigSha = digest(bigFile)
            val upStarted = System.currentTimeMillis()
            assertEquals(Answer.Queued(1), b.ask(Call.UploadFiles(personal, folderId, name, listOf(bigFile.path))))
            settled(listOf("big.bin"), 60.minutes)
            println("big.bin (${bigFile.length()} B) uploaded by path in ${System.currentTimeMillis() - upStarted} ms")
            bigFile.delete()
            val bigNode = under(folderId).single { it.name == "big.bin" }
            val bigPage = (b.ask(Call.FileOpen(at(bigNode))) as Answer.FileOpened).file
            b.watching(Watched.File(bigPage)).until(2.minutes) { u -> (u as Update.File).shown?.takeIf { it.headerFacts != null } }
            val back = File(dir, "big.back")
            val saveStarted = System.currentTimeMillis()
            val save = (b.ask(Call.FileSave(bigPage, back.path)) as Answer.Saving).save
            val saved = b.watching(Watched.Save(save)).until(60.minutes) { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
            assertEquals(me.raddatz.veyq.mobile.depot.FileSave.End.SAVED, saved.end, "${saved.notice}")
            assertEquals(bigSize, back.length())
            assertEquals(bigSha, digest(back), "the 2 GiB + 1 copy is not the file")
            println("big.bin saved back in ${System.currentTimeMillis() - saveStarted} ms at $bigSha")
            back.delete()
            b.ask(Call.FileClose(bigPage))

            // the one-byte quota: a folder upload said not to fit, nothing sent
            val before = under(folderId).size
            withOneByteQuota(origin, first.shown!!.tenant.tenantId) {
                assertEquals(Answer.Queued(0), b.ask(Call.UploadFolder(personal, folderId, trip.path), timeout = 2.minutes))
                val noRoom = nextSaid { s -> s.notices.any { it.msg == me.raddatz.veyq.mobile.words.Msg.DEPOT_FOLDER_NO_ROOM } }
                assertEquals(Answer.Done, b.ask(Call.UploadSeen(noRoom.id)))
                println("with one byte of room: ${noRoom.notices.map { it.msg }}")
            }
            assertEquals(before, under(folderId).size, "a folder that does not fit made something")
        } finally {
            dir.deleteRecursively()
            val h = (b.ask(Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt(folder.scopeId, folderId, folder.kind, folder.name), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            println("trash $name: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
            val t = (b.ask(Call.TrashOpen(personal)) as Answer.TrashOpened).trash
            val tw = b.watching(Watched.Trash(t))
            tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }.firstOrNull { it.id == folderId }?.let { row ->
                val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 5.minutes)
                println("purge ${row.name}: $purged")
                if (purged == Answer.Done) tw.until(5.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
            }
            b.ask(Call.TrashClose(t))
            assertTrue(fresh().state!!.lists.nodes.none { it.nodeId == folderId }, "the folder was left")
            b.shutdown()
        }
    }

    /** A second session of the seeded principal through the core alone (a peer device), its tenant opened. */
    private suspend fun secondSession(origin: String, device: String): me.raddatz.veyq.mobile.session.TenantSession {
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val store = liveRun.store()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, device, BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        return account.open(account.tenants.first { origin.contains(it.domain) })
    }

    /**
     * A medium read in ranges through calls, over the real core (ADR 0137 decision 2 and its Consequences): with
     * `VEYQ_LIVE_MEDIUM=<a video file>` (a 600 MiB WebM, made by `ffmpeg` on this machine) a folder "App depot <n>" is made at
     * the personal space's top level and the file uploaded there by the core's own upload in a second session, read from
     * its path at offsets (never held whole); the bridge opens it (`File.Open`: a medium), opens its reader (`File.Reader`:
     * its size and sniffed type) and reads ranges at the start, across a frame's edge, two frames at once in the middle and
     * at the end — each the file's own bytes at that offset —, then closes the page, its reader with it. The folder is
     * trashed and purged however it ends.
     */
    @Test
    fun aMediumIsReadInRangesThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val medium = System.getenv("VEYQ_LIVE_MEDIUM")?.let(::File) ?: return@runBlocking
        assertTrue(medium.isFile, "no $medium")
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val store = liveRun.store()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, "bridge live medium test, the uploader", BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.tenants.first { origin.contains(it.domain) })
        val personalScope = tenant.spaces().first { it.kind == "PERSONAL" }.scopeId
        val folderName = "App depot ${System.currentTimeMillis() % 100_000}"
        val made = me.raddatz.veyq.mobile.session.FolderCreate.create(tenant, personalScope, null, folderName)
        val folderId = assertIs<me.raddatz.veyq.mobile.session.NodeCreate.Result.Done>(made, "$made").created.nodeId
        println("made $folderName")
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge medium test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        val file = java.io.RandomAccessFile(medium, "r")
        fun at(from: Long, length: Int): ByteArray {
            val out = ByteArray(minOf(length.toLong(), file.length() - from).coerceAtLeast(0).toInt())
            synchronized(file) { file.seek(from); file.readFully(out) }
            return out
        }
        try {
            val name = medium.name
            val started = System.currentTimeMillis()
            val up = me.raddatz.veyq.mobile.depot.DepotFiles.upload(
                tenant, personalScope, folderId, name, file.length(), "video/webm", null, me.raddatz.veyq.mobile.AppKinds::tokenInput, { from, len -> at(from, len) },
            )
            assertEquals(me.raddatz.veyq.mobile.depot.DepotFiles.Outcome.DONE, up.outcome, name)
            println("uploaded $name (${file.length()} B) in ${System.currentTimeMillis() - started} ms")

            val gate = b.watching(Watched.Session)
            assertEquals(Answer.Done, b.ask(Call.SessionStart))
            gate.until { (it as Update.Session).gate as? Gate.SignIn }
            assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
            gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
            b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.spaces?.isNotEmpty() == true } }
            val node = fresh().state!!.lists.nodes.first { it.parentId == folderId && it.name == name }
            val page = (b.ask(Call.FileOpen(me.raddatz.veyq.mobile.navigation.NodeAt(node.scopeId, node.nodeId, node.kind, node.name))) as Answer.FileOpened).file
            val shown = b.watching(Watched.File(page)).until(2.minutes) { u -> (u as Update.File).shown?.takeIf { it.view != FileShown.View.LOADING } }
            assertEquals(FileShown.View.MEDIA to file.length(), shown.view to shown.size)
            val reader = b.ask(Call.FileReader(page), timeout = 1.minutes) as Answer.Reader
            assertEquals(file.length() to "video/webm", reader.size to reader.mediaType)
            val frame = me.raddatz.veyq.mobile.crypto.V1.FRAME_PLAINTEXT.toLong()
            val middle = (file.length() / 2 / frame) * frame + 12_345
            for ((from, length) in listOf(0L to 65_536, frame - 100 to 200, middle to 2 * me.raddatz.veyq.mobile.crypto.V1.FRAME_PLAINTEXT, file.length() - 1000 to 65_536)) {
                val t = System.currentTimeMillis()
                val (answer, bytes) = b.askWithPayload(Call.ReaderRead(reader.reader, from, length), 1.minutes)
                assertEquals(Answer.Bytes(null), answer)
                assertEquals(sha256(at(from, length)), sha256(bytes!!), "the range at $from is not the file's")
                println("range $from+$length: ${bytes.size} B at ${sha256(bytes)} in ${System.currentTimeMillis() - t} ms")
            }
            assertEquals(0, b.askWithPayload(Call.ReaderRead(reader.reader, file.length(), 4096)).second!!.size, "past the end")
            assertEquals(Answer.Done, b.ask(Call.FileClose(page)))
            assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(reader.reader, 0, 4)), "the reader outlived its page")
        } finally {
            file.close()
            // trashed and purged through the uploader's session (the bridge's tree would not see it gone: cached)
            println("trash $folderName (core): ${me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, personalScope, folderId)}")
            println("purge $folderName (core): ${me.raddatz.veyq.mobile.session.NodeTrash.purge(tenant, personalScope, folderId)}")
            b.shutdown()
        }
    }

    /**
     * A desktop pass's folder (ADR 0123's Consequences, the bridge's Depot whole), not a check: with `VEYQ_LIVE_DEPOT_PASS=<name>` it makes a folder of
     * that name at the personal space's top level and fills it through the core's own upload with what the pass opens —
     * a JPEG, a text past `TEXT_CAP`, a 30-page PDF, a ZIP, an empty file and a 300 MiB file of drawn bytes —, printing each
     * one's SHA-256. It stays, for the pass; with `VEYQ_LIVE_DEPOT_PASS_PURGE=1` the folder of that name is trashed and purged
     * instead, the pass's end.
     */
    @Test
    fun aDesktopPassFolderIsFilled() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val folderName = System.getenv("VEYQ_LIVE_DEPOT_PASS") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val store = liveRun.store()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, "bridge live desktop pass, the uploader", BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.tenants.first { origin.contains(it.domain) })
        val scope = tenant.spaces().first { it.kind == "PERSONAL" }.scopeId
        if (System.getenv("VEYQ_LIVE_DEPOT_PASS_PURGE") == "1") {
            // a pass's node at any space's top level (a folder, a drop box a pass made there), its whole subtree
            val folder = assertNotNull(tenant.nodes().firstOrNull { it.parentId == null && it.name == folderName }, "no $folderName")
            println("trash $folderName: ${me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, folder.scopeId, folder.nodeId)}")
            println("purge $folderName: ${me.raddatz.veyq.mobile.session.NodeTrash.purge(tenant, folder.scopeId, folder.nodeId)}")
            return@runBlocking
        }
        val made = me.raddatz.veyq.mobile.session.FolderCreate.create(tenant, scope, null, folderName)
        val folderId = assertIs<me.raddatz.veyq.mobile.session.NodeCreate.Result.Done>(made, "$made").created.nodeId
        val jpeg = java.io.ByteArrayOutputStream().also { out ->
            val image = java.awt.image.BufferedImage(3000, 2000, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(30, 90, 200), 3000f, 2000f, java.awt.Color(240, 200, 40))
            g.fillRect(0, 0, 3000, 2000)
            g.color = java.awt.Color.WHITE
            g.fillOval(1100, 600, 800, 800)
            g.dispose()
            javax.imageio.ImageIO.write(image, "jpg", out)
        }.toByteArray()
        val text = ByteArray(me.raddatz.veyq.mobile.depot.FileView.TEXT_CAP + 200_000) { i -> if (i % 80 == 79) '\n'.code.toByte() else ('a' + (i / 80) % 26).code.toByte() }
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("inside.txt")); z.write("inside\n".encodeToByteArray()); z.closeEntry() }
        }.toByteArray()
        val bigSize = 300L shl 20
        fun big(i: Long): Byte = ((i * 2654435761L) ushr 13).toByte()
        fun bigRead(from: Long, length: Int) = ByteArray(minOf(length.toLong(), bigSize - from).toInt()) { big(from + it) }
        val bigSha = java.security.MessageDigest.getInstance("SHA-256").let { d ->
            var at = 0L
            while (at < bigSize) { val chunk = bigRead(at, 1 shl 20); d.update(chunk); at += chunk.size }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        fun whole(bytes: ByteArray): (Long, Int) -> ByteArray = { from, len -> bytes.copyOfRange(from.toInt(), minOf(bytes.size, from.toInt() + len)) }
        for ((name, sized) in listOf(
            "photo.jpg" to Triple(jpeg.size.toLong(), "image/jpeg", whole(jpeg)),
            "long.txt" to Triple(text.size.toLong(), "text/plain", whole(text)),
            "thirty.pdf" to Triple(pdfOf(30).size.toLong(), "application/pdf", whole(pdfOf(30))),
            "archive.zip" to Triple(zip.size.toLong(), "application/zip", whole(zip)),
            "empty.txt" to Triple(0L, "text/plain", whole(ByteArray(0))),
            "big.bin" to Triple(bigSize, "application/octet-stream", ::bigRead),
        )) {
            val (size, type, read) = sized
            val up = me.raddatz.veyq.mobile.depot.DepotFiles.upload(tenant, scope, folderId, name, size, type, null, me.raddatz.veyq.mobile.AppKinds::tokenInput, { from, len -> read(from, len) })
            println("uploaded $name ($size B): ${up.outcome}")
        }
        // with VEYQ_LIVE_DEPOT_PASS_MEDIA=<file>,<file>…: media made on this machine (ffmpeg), each read from its path at
        // offsets, never held whole (ADR 0137's Consequences)
        for (path in System.getenv("VEYQ_LIVE_DEPOT_PASS_MEDIA").orEmpty().split(',').filter { it.isNotBlank() }) {
            val medium = File(path)
            java.io.RandomAccessFile(medium, "r").use { raf ->
                val read = { from: Long, len: Int ->
                    ByteArray(minOf(len.toLong(), raf.length() - from).toInt()).also { out -> synchronized(raf) { raf.seek(from); raf.readFully(out) } }
                }
                val up = me.raddatz.veyq.mobile.depot.DepotFiles.upload(tenant, scope, folderId, medium.name, raf.length(), "application/octet-stream", null, me.raddatz.veyq.mobile.AppKinds::tokenInput, read)
                println("uploaded ${medium.name} (${raf.length()} B): ${up.outcome}")
            }
        }
        println("$folderName filled; big.bin's SHA-256 $bigSha, photo.jpg's ${sha256(jpeg)}")
    }

    /**
     * Amsterdam copied through the move sheet beside itself and renamed `App pot <n>`; [body] gets its ref and a reopen
     * (closes the Pot id given, opens the copy afresh and answers its id); the copy is trashed and purged however it ends.
     */
    private suspend fun withAmsterdamCopy(b: Bridge<*, *>, first: Update.Shell, body: suspend (me.raddatz.veyq.mobile.navigation.ObjectRef, suspend (Long?) -> Long) -> Unit) {
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val scope = amsterdam.scopeId
        val home = first.state.lists.nodes.first { it.nodeId == amsterdam.nodeId }.parentId
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun nodes(u: Update.Shell) = u.state!!.lists.nodes.filter { it.scopeId == scope }
        fun at(n: me.raddatz.veyq.mobile.navigation.NodeRef) = me.raddatz.veyq.mobile.navigation.NodeAt(n.scopeId, n.nodeId, n.kind, n.name)
        val source = (b.ask(Call.PotOpen(amsterdam)) as Answer.PotOpened).pot
        b.watching(Watched.Pot(source)).untilJson(1.minutes) { (it["state"] as? JsonObject)?.get("view") as? JsonObject }
        val sheet = (b.ask(Call.MoveOpen(at(nodes(first).first { it.nodeId == amsterdam.nodeId }), true, "deleted person")) as Answer.MoveOpened).move
        b.watching(Watched.Move(sheet)).until(2.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded && it.losses.size > 1 } }
        val copied = b.ask(Call.MoveHere(sheet), timeout = 3.minutes) as Answer.MoveSent
        assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_DONE, copied.done?.msg, "${copied.problem}")
        b.ask(Call.MoveClose(sheet))
        b.ask(Call.PotClose(source))
        val before = first.state.lists.nodes.map { it.nodeId }.toSet()
        val copy = assertNotNull(nodes(fresh()).firstOrNull { it.kind == amsterdam.kind && it.parentId == home && it.nodeId !in before }, "the Pot's copy is not listed")
        val name = "App pot ${System.currentTimeMillis() % 100_000}"
        try {
            val header = (b.ask(Call.HeaderOpen(at(copy), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            b.watching(Watched.Header(header)).until { u -> (u as Update.Header).shown?.takeIf { it.plan != null } }
            assertEquals(null, (b.ask(Call.HeaderRename(header, name), timeout = 1.minutes) as Answer.Renamed).problem)
            b.ask(Call.HeaderClose(header))
            println("copied Amsterdam as $name")
            val ref = me.raddatz.veyq.mobile.navigation.ObjectRef(copy.scopeId, copy.nodeId, copy.kind, amsterdam.application, name)
            body(ref) { open ->
                open?.let { b.ask(Call.PotClose(it)) }
                val pot = (b.ask(Call.PotOpen(ref)) as Answer.PotOpened).pot
                b.watching(Watched.Pot(pot)).untilJson(1.minutes) { (it["state"] as? JsonObject)?.get("view") as? JsonObject }
                pot
            }
        } finally {
            val h = (b.ask(Call.HeaderOpen(at(copy), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            println("trash $name: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
            b.ask(Call.HeaderClose(h))
            val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
            val tw = b.watching(Watched.Trash(t))
            val trashed = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
            trashed.firstOrNull { it.id == copy.nodeId }?.let { row ->
                val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                println("purge ${row.name}: $purged")
                if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
            }
            b.ask(Call.TrashClose(t))
            assertTrue(nodes(fresh()).none { it.nodeId == copy.nodeId }, "the copy was left")
        }
    }

    /** A PDF of [pages] pages, 200 × 100 pt each, the left half of each filled black (as the Windows app's `PdfPagesTest`). */
    private fun pdfOf(pages: Int): ByteArray {
        val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [${(0 until pages).joinToString(" ") { "${3 + 2 * it} 0 R" }}] /Count $pages >>")
        val draw = "0 0 0 rg 0 0 100 100 re f"
        repeat(pages) { i ->
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents ${4 + 2 * i} 0 R >>"
            objects += "<< /Length ${draw.length} >>\nstream\n$draw\nendstream"
        }
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, o -> pdf.length.also { pdf.append("${i + 1} 0 obj\n$o\nendobj\n") } }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append("%010d 00000 n \n".format(it)) }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return pdf.toString().encodeToByteArray()
    }

    /** Signs a live bridge in as the seeded principal and returns it with its first shell (Amsterdam listed). */
    private suspend fun signedInLive(origin: String, device: String): Pair<Bridge<*, *>, Update.Shell> {
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config(device), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
        gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        return b to b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.objects?.any { o -> o.name == "Amsterdam" } == true } }
    }

    /** A folder named [name] made beside Amsterdam through calls; [body] runs on it, then it is trashed (and purged where [purge]). */
    private suspend fun withFolder(b: Bridge<*, *>, first: Update.Shell, name: String, purge: Boolean, body: suspend (me.raddatz.veyq.mobile.navigation.NodeAt) -> Unit) {
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val scope = amsterdam.scopeId
        val home = first.state.lists.nodes.first { it.nodeId == amsterdam.nodeId }.parentId
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        fun nodes(u: Update.Shell) = u.state!!.lists.nodes.filter { it.scopeId == scope }
        assertEquals(Answer.Done, b.ask(Call.ShellChoose(null)))
        assertEquals(Answer.Done, b.ask(Call.ShellSwitchSpace(scope)))
        home?.let { h -> nodes(first).first { it.nodeId == h }.asFolder()?.let { assertEquals(Answer.Done, b.ask(Call.ShellEnter(it))) } }
        assertEquals(Answer.Said(null), b.ask(Call.ShellCreateFolder(name), timeout = 1.minutes))
        val folder = assertNotNull(nodes(fresh()).firstOrNull { it.name == name && it.parentId == home }, "the new folder is not listed")
        val at = me.raddatz.veyq.mobile.navigation.NodeAt(folder.scopeId, folder.nodeId, folder.kind, folder.name)
        println("made $name")
        try {
            body(at)
        } finally {
            val h = (b.ask(Call.HeaderOpen(at, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            println("trash $name: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
            b.ask(Call.HeaderClose(h))
            if (purge) {
                val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
                val tw = b.watching(Watched.Trash(t))
                val rows = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
                rows.firstOrNull { it.id == folder.nodeId }?.let { row ->
                    val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                    println("purge ${row.name}: $purged")
                    if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
                }
                b.ask(Call.TrashClose(t))
                assertTrue(nodes(fresh()).none { it.nodeId == folder.nodeId }, "the folder made was left")
            }
        }
    }

    /**
     * A cover through calls, as a desktop sets one: a drawn 16:9 JPEG (CoverCropLiveTest's: left half red, right half blue
     * with a white disc) written to a file as the host's encoder would, its frame posed by the core (`Cover.Pose`: zoomed to
     * 2 and panned to the left edge, the left half), set from that file (`Header.CoverSave`), read back by a fresh header
     * (the crop) and `Header.CoverPicture` (the bytes); framed anew on its right half with its picture kept, then removed.
     * The folder ("App cover <n>") is trashed and purged.
     */
    @Test
    fun aCoverIsSetFromAFileFramedAnewReadBackAndRemovedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val (b, first) = signedInLive(origin, "bridge cover test")
        val image = java.awt.image.BufferedImage(640, 360, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = java.awt.Color(0xdc, 0x26, 0x26); fillRect(0, 0, 320, 360)
                color = java.awt.Color(0x25, 0x63, 0xeb); fillRect(320, 0, 320, 360)
                color = java.awt.Color.WHITE; fillOval(420, 100, 120, 120)
                dispose()
            }
        }
        val jpeg = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
        val file = File.createTempFile("cover", ".jpg").apply { writeBytes(jpeg); deleteOnExit() }
        assertEquals(Answer.CoverFit(640, 360, 0.85, 85), b.ask(Call.CoverFit(640, 360)))
        val centred = b.ask(Call.CoverPose(640.0, 360.0, 320.0, 180.0, null, null, 0.0, 0.0, 1.0)) as Answer.CoverPosed
        val zoomed = b.ask(Call.CoverPose(640.0, 360.0, 320.0, 180.0, centred.pose, null, 0.0, 0.0, 2.0)) as Answer.CoverPosed
        val left = (b.ask(Call.CoverPose(640.0, 360.0, 320.0, 180.0, zoomed.pose, null, 10_000.0, 0.0, 1.0)) as Answer.CoverPosed).crop
        assertEquals(me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.25, 0.5, 0.5), left)
        withFolder(b, first, "App cover ${System.currentTimeMillis() % 100_000}", purge = true) { at ->
            /** A fresh header's frame, read when it opens: red (by the timeout) unless it reads [expected]. */
            suspend fun freshHeader(expected: me.raddatz.veyq.mobile.session.NodeCover.Crop): HeaderShown {
                val h = (b.ask(Call.HeaderOpen(at, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
                return b.watching(Watched.Header(h)).until(1.minutes) { u -> (u as Update.Header).shown?.takeIf { it.cover == expected } }
                    .also { b.ask(Call.HeaderClose(h)) }
            }
            val h = (b.ask(Call.HeaderOpen(at, me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
            assertEquals(Answer.Said(null), b.ask(Call.HeaderCoverSave(h, file.path, left), timeout = 2.minutes))
            val set = freshHeader(left)
            assertEquals(me.raddatz.veyq.mobile.words.Msg.COVER_CHANGE, set.plan!!.let { it.buttons + it.menu + it.last }.first { it.id == "cover" }.word)
            val (answer, bytes) = b.askWithPayload(Call.HeaderCoverPicture(h), timeout = 1.minutes)
            assertEquals(Answer.Bytes("image/jpeg"), answer)
            assertTrue(bytes!!.contentEquals(jpeg), "the cover does not open to the bytes set")
            println("set: framed ${set.cover}, ${bytes.size} bytes back")
            val right = me.raddatz.veyq.mobile.session.NodeCover.Crop(0.5, 0.25, 0.5, 0.5)
            assertEquals(Answer.Said(null), b.ask(Call.HeaderCoverSave(h, null, right), timeout = 2.minutes))
            freshHeader(right)
            assertTrue((b.askWithPayload(Call.HeaderCoverPicture(h), timeout = 1.minutes).second)!!.contentEquals(jpeg), "a recrop must keep the picture")
            assertEquals(Answer.Said(null), b.ask(Call.HeaderCoverRemove(h), timeout = 2.minutes))
            assertEquals(Answer.Refused(Answer.Why.NO_COVER), b.ask(Call.HeaderCoverPicture(h), timeout = 1.minutes))
            println("re-framed on its right half, picture kept, removed")
            b.ask(Call.HeaderClose(h))
        }
        b.shutdown()
    }

    /**
     * A report through calls, with `VEYQ_LIVE_REPORT_BRIDGE=1`: a fresh folder ("App report <n>") reported by its name in
     * "Breach of the terms" with a reason and good faith, the server's receipt read back, the privacy link the core's from
     * the tenant's origin; the folder trashed. Each run leaves an open report for the dev moderators: run it once.
     */
    @Test
    fun aReportIsSentOnceThroughCallsAndItsReceiptReadBack() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        System.getenv("VEYQ_LIVE_REPORT_BRIDGE") ?: return@runBlocking
        val (b, first) = signedInLive(origin, "bridge report test")
        withFolder(b, first, "App report ${System.currentTimeMillis() % 100_000}", purge = false) { at ->
            val id = (b.ask(Call.ReportOpen(at)) as Answer.ReportOpened).report
            val w = b.watching(Watched.Report(id))
            val fresh = (w.next() as Update.Report).shown!!
            assertEquals("${origin.trimEnd('/')}/privacy#reporter-contact", fresh.privacyUrl)
            assertEquals(Answer.Done, b.ask(Call.ReportWhole(id, false)))
            assertEquals(Answer.Done, b.ask(Call.ReportCategory(id, "TERMS_BREACH")))
            assertEquals(Answer.Done, b.ask(Call.ReportReason(id, "A check of the bridge's report (LiveBridgeTest)")))
            assertEquals(Answer.Done, b.ask(Call.ReportGoodFaith(id, true)))
            w.until { u -> (u as Update.Report).shown?.takeIf { it.canSend } }
            assertEquals(Answer.Said(null), b.ask(Call.ReportSend(id), timeout = 2.minutes))
            val receipt = w.until(1.minutes) { u -> (u as Update.Report).shown?.takeIf { it.sent } }
            assertEquals(8, receipt.reference?.length)
            assertNotNull(receipt.receivedAt)
            println("reported ${at.name}: reference ${receipt.reference}, received ${receipt.receivedAt}")
            b.ask(Call.ReportClose(id))
        }
        b.shutdown()
    }

    /**
     * A drop box made, filled by guests and emptied through calls (ADR 0123's Consequences, the bridge's Depot whole): with `VEYQ_LIVE_DROPBOX_GUESTS=<file>`
     * a folder "App depot <n>" is made beside Amsterdam, the space's top level is said to offer its share
     * (`ShellShown.topLevelShare`) and "Add files only" there (`Share.OpenTopLevel`) makes "App drop <n>" with its first link,
     * whose address is written to that file. Two guests then drop through the web's guest page (a script reading the file
     * drives it: "App guest A" and "App guest B", one file each) — after this bridge signed in, so they show only because
     * `Shell.Refresh` reads the tree anew (a session caches it): the drop box's page (`DropBox.Open`) lists both under their
     * names with their files, read anew each time the lists are (a refresh every 5 s, `VEYQ_LIVE_DROPBOX_WAIT_S`, default
     * 300 s). A's folder is moved out into "App depot <n>" (`DropBox.MoveOut`, the move out asked first), B's deleted only
     * after asking (`DropBox.Delete` refused, then asked); a READ link minted on A's file and read back listed. The drop box,
     * B's folder and "App depot <n>" are trashed and purged however it ends.
     */
    @Test
    fun aDropBoxIsMadeFilledAndEmptiedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val out = File(System.getenv("VEYQ_LIVE_DROPBOX_GUESTS") ?: return@runBlocking)
        val wait = (System.getenv("VEYQ_LIVE_DROPBOX_WAIT_S")?.toLong() ?: 300L).seconds
        out.delete()
        val (b, first) = signedInLive(origin, "bridge drop box test")
        suspend fun fresh(): Update.Shell {
            assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
            return b.watching(Watched.Shell).next() as Update.Shell
        }
        val n = System.currentTimeMillis() % 100_000
        val dropName = "App drop $n"
        val purge = java.util.concurrent.CopyOnWriteArrayList<String>()
        withFolder(b, first, "App depot $n", purge = true) { depot ->
            val scope = depot.scopeId
            try {
                assertEquals(Answer.Done, b.ask(Call.ShellSwitchSpace(scope)))
                val top = b.watching(Watched.Shell).until { u -> (u as Update.Shell).shown?.takeIf { it.headed == null } }
                assertTrue(top.topLevelShare, "the space's top level offers no share")
                val sheet = (b.ask(Call.ShareOpenTopLevel(scope)) as Answer.ShareOpened).share
                val sw = b.watching(Watched.Share(sheet))
                assertTrue(sw.until { u -> (u as Update.Share).shown?.takeIf { it.form != null } }.atTopLevel)
                assertEquals(Answer.Done, b.ask(Call.ShareLabel(sheet, dropName)))
                sw.until { u -> (u as Update.Share).shown?.form?.takeIf { it.label == dropName && it.canSave } }
                assertEquals(Answer.Said(null), b.ask(Call.ShareSave(sheet), timeout = 1.minutes))
                val address = sw.until { u -> (u as Update.Share).shown?.freshAddress }
                b.ask(Call.ShareFreshSeen(sheet))
                b.ask(Call.ShareClose(sheet))
                val box = assertNotNull(fresh().state!!.lists.nodes.firstOrNull { it.name == dropName && it.parentId == null && it.kind == "DEPOT_DROPBOX" }, "the drop box is not listed")
                purge += box.nodeId
                out.writeText(address)
                println("made $dropName, its link written to $out; waiting for two guests")

                val page = (b.ask(Call.DropBoxOpen(me.raddatz.veyq.mobile.navigation.NodeAt(box.scopeId, box.nodeId, box.kind, box.name))) as Answer.DropBoxOpened).box
                val pw = b.watching(Watched.DropBox(page))
                assertEquals(emptyList(), pw.until { u -> (u as Update.DropBox).shown?.folders })
                val guests = listOf("App guest A", "App guest B")
                val deadline = System.currentTimeMillis() + wait.inWholeMilliseconds
                var listed: List<DropBoxShown.ShownGuestFolder> = emptyList()
                while (listed.map { it.label }.toSet() != guests.toSet() || listed.any { it.files.isEmpty() }) {
                    assertTrue(System.currentTimeMillis() < deadline, "the guests' folders never showed: ${listed.map { it.label }} (a refresh that reads the cached tree shows none)")
                    kotlinx.coroutines.delay(5_000)
                    fresh()
                    listed = b.watching(Watched.DropBox(page)).next().let { (it as Update.DropBox).shown?.folders.orEmpty() }
                }
                println("listed: ${listed.joinToString { "${it.label} (${it.files.size} files)" }}")
                val a = listed.first { it.label == guests[0] }
                val bFolder = listed.first { it.label == guests[1] }

                // A moved out into the folder: the move out of the drop box asked first
                val m = (b.ask(Call.DropBoxMoveOut(page, a.id)) as Answer.MoveOpened).move
                b.watching(Watched.Move(m)).until(1.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded } }
                // the browser goes a crumb or a listed place at a time: the root, then down to the folder
                val nodes = fresh().state!!.lists.nodes
                val way = generateSequence(nodes.first { it.nodeId == depot.nodeId }) { n -> n.parentId?.let { p -> nodes.first { it.nodeId == p } } }.toList().reversed()
                for (at in listOf(null) + way.map { it.nodeId }) {
                    assertEquals(Answer.Done, b.ask(Call.MoveGo(m, at)))
                    b.watching(Watched.Move(m)).until(1.minutes) { u -> (u as Update.Move).shown?.takeIf { s -> s.trail.last().nodeId == at } }
                }
                b.watching(Watched.Move(m)).until(1.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded && it.canChoose } }
                val asked = b.ask(Call.MoveHere(m), timeout = 1.minutes) as Answer.MoveSent
                val moved = if (asked.confirming) b.ask(Call.MoveConfirm(m), timeout = 2.minutes) as Answer.MoveSent else asked
                assertNotNull(moved.done, "the move out did not go through: ${moved.problem}")
                b.ask(Call.MoveClose(m))
                assertEquals(depot.nodeId, fresh().state!!.lists.nodes.first { it.nodeId == a.id }.parentId, "A's folder is not in the folder")

                // B deleted only after asking
                assertEquals(Answer.Refused(Answer.Why.NOT_ASKED), b.ask(Call.DropBoxDelete(page, bFolder.id)))
                assertTrue(fresh().state!!.lists.nodes.any { it.nodeId == bFolder.id }, "a delete not asked trashed B's folder")
                assertEquals(Answer.Done, b.ask(Call.DropBoxAskDelete(page, bFolder.id)))
                b.watching(Watched.DropBox(page)).until { u -> (u as Update.DropBox).shown?.takeIf { it.deleting == bFolder.id } }
                val trashed = b.ask(Call.DropBoxDelete(page, bFolder.id), timeout = 1.minutes) as Answer.Trashed
                println("B: ${trashed.notice.msg}")
                purge += bFolder.id
                val after = b.watching(Watched.DropBox(page)).until(1.minutes) { u -> (u as Update.DropBox).shown?.folders?.takeIf { f -> f.none { it.id == bFolder.id } } }
                assertTrue(after.none { it.id == a.id }, "A's folder still listed after its move out")
                b.ask(Call.DropBoxClose(page))

                // a READ link on A's file, minted and read back listed
                val file = a.files.first()
                val share = (b.ask(Call.ShareOpen(me.raddatz.veyq.mobile.navigation.NodeAt(file.scopeId, file.nodeId, file.kind, file.name))) as Answer.ShareOpened).share
                val fw = b.watching(Watched.Share(share))
                fw.until(1.minutes) { u -> (u as Update.Share).shown?.links }
                assertEquals(Answer.Done, b.ask(Call.ShareAdd(share)))
                fw.until { u -> (u as Update.Share).shown?.form }
                assertEquals(Answer.Done, b.ask(Call.ShareRole(share, "READ")))
                fw.until { u -> (u as Update.Share).shown?.form?.takeIf { it.role == "READ" && it.canSave } }
                assertEquals(Answer.Said(null), b.ask(Call.ShareSave(share), timeout = 1.minutes))
                val minted = fw.until(1.minutes) { u -> (u as Update.Share).shown?.takeIf { it.freshAddress != null && it.links?.isNotEmpty() == true } }
                assertEquals(minted.freshAddress, minted.links!!.single().address)
                println("${file.name}: a READ link minted and listed")
                b.ask(Call.ShareClose(share))
            } finally {
                out.delete()
                // the drop box trashed; it and B's folder purged (the folder made is withFolder's)
                fresh().state!!.lists.nodes.firstOrNull { it.name == dropName && it.parentId == null }?.let { box ->
                    if (box.nodeId !in purge) purge += box.nodeId
                    val h = (b.ask(Call.HeaderOpen(me.raddatz.veyq.mobile.navigation.NodeAt(box.scopeId, box.nodeId, box.kind, box.name), me.raddatz.veyq.mobile.AppKinds.Facts(), 2)) as Answer.HeaderOpened).header
                    println("trash $dropName: ${(b.ask(Call.HeaderTrash(h), timeout = 1.minutes) as? Answer.Trashed)?.notice?.msg}")
                    b.ask(Call.HeaderClose(h))
                }
                val t = (b.ask(Call.TrashOpen(scope)) as Answer.TrashOpened).trash
                val tw = b.watching(Watched.Trash(t))
                val rows = tw.until(1.minutes) { u -> (u as Update.Trash).shown?.rows }
                println("in the trash of ours: ${rows.filter { it.id in purge }.map { it.name }}")
                for (row in rows.filter { it.id in purge }) {
                    val purged = b.ask(Call.TrashPurge(t, row.id), timeout = 1.minutes)
                    println("purge ${row.name}: $purged")
                    if (purged == Answer.Done) tw.until(1.minutes) { u -> (u as Update.Trash).shown?.takeIf { s -> s.busy == null && s.rows?.none { it.id == row.id } == true } }
                }
                b.ask(Call.TrashClose(t))
            }
        }
        assertTrue(fresh().state!!.lists.nodes.none { it.name == dropName }, "the drop box was left")
        b.shutdown()
    }

    /**
     * A desktop pass's read-back for the web's guest page: with `VEYQ_LIVE_SHARE_LIST=<folder name>` (a folder at a space's
     * top) and `VEYQ_LIVE_LINKS_OUT=<file>`, that folder's share sheet is opened through calls and each link's id and
     * address — where it may be copied — written to the file, one line each (a dev link names the backend's port, 8080:
     * open it on Vite's). Writes nothing on the server.
     */
    @Test
    fun theLinksOfAFolderAreWrittenOutForTheWebsGuestPage() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val name = System.getenv("VEYQ_LIVE_SHARE_LIST") ?: return@runBlocking
        val out = File(System.getenv("VEYQ_LIVE_LINKS_OUT") ?: error("VEYQ_LIVE_LINKS_OUT names no file"))
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge share list"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
        gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        val shell = b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.nodes?.any { n -> n.name == name && n.parentId == null } == true } }
        val folder = shell.state!!.lists.nodes.first { it.name == name && it.parentId == null }
        val id = (b.ask(Call.ShareOpen(me.raddatz.veyq.mobile.navigation.NodeAt(folder.scopeId, folder.nodeId, folder.kind, folder.name))) as Answer.ShareOpened).share
        val links = b.watching(Watched.Share(id)).until(1.minutes) { u -> (u as Update.Share).shown?.links }
        out.writeText(links.joinToString("") { "${it.shareId} ${it.address ?: "-"}\n" })
        println("$name: ${links.size} links written to $out")
        b.ask(Call.ShareClose(id))
        b.shutdown()
    }

    /**
     * A copy the tenant has no room for, through calls: run with `VEYQ_LIVE_NO_ROOM=1` while the core's
     * `DepotLiveTest.aCopyOrAFolder*` holds the tenant's quota at one byte (`VEYQ_LIVE_STORAGE_FIT`,
     * `VEYQ_LIVE_STORAGE_FIT_HOLD_S`; it puts the quota back however it ends). Amsterdam's copy sheet says so with the
     * copy's size, "Copy here" cannot be chosen, and a call to it sends nothing.
     */
    @Test
    fun aCopyWithoutRoomIsSaidFirstAndNothingIsSent() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        System.getenv("VEYQ_LIVE_NO_ROOM") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge no-room test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        gate.until { (it as Update.Session).gate as? Gate.SignIn }
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
        gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        val first = b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.objects?.any { o -> o.name == "Amsterdam" } == true } }
        val amsterdam = first.state!!.lists.objects.first { it.name == "Amsterdam" }
        val potId = (b.ask(Call.PotOpen(amsterdam)) as Answer.PotOpened).pot
        b.watching(Watched.Pot(potId)).untilJson(1.minutes) { u -> (u["state"] as? JsonObject)?.get("view") as? JsonObject }
        val node = me.raddatz.veyq.mobile.navigation.NodeAt(amsterdam.scopeId, amsterdam.nodeId, amsterdam.kind, amsterdam.name)
        val id = (b.ask(Call.MoveOpen(node, true, "deleted person")) as Answer.MoveOpened).move
        val full = b.watching(Watched.Move(id)).until(2.minutes) { u -> (u as Update.Move).shown?.takeIf { it.loaded && it.noRoom != null } }
        assertEquals(me.raddatz.veyq.mobile.words.Msg.COPY_NO_ROOM, full.noRoom!!.msg)
        assertTrue(!full.canChoose)
        val sent = b.ask(Call.MoveHere(id)) as Answer.MoveSent
        assertNull(sent.done)
        assertEquals(Answer.Done, b.ask(Call.ShellRefresh, timeout = 1.minutes))
        val after = (b.watching(Watched.Shell).next() as Update.Shell).state!!.lists.nodes
        assertEquals(first.state.lists.nodes.map { it.nodeId }.toSet(), after.map { it.nodeId }.toSet(), "something was written without room")
        println("Amsterdam's copy is said not to fit: ${full.noRoom}; nothing sent")
        b.ask(Call.MoveClose(id))
        b.ask(Call.PotClose(potId))
        b.shutdown()
    }

    /** A folder's download read back as `VEYQ_LIVE_FOLDER` reads it: a folder without content, the Pot in it, every payload. */
    private fun readBackFolder(file: File, potKind: String): Int = java.util.zip.ZipFile(file).use { zip ->
        val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(".veyq/manifest.json")).readBytes().decodeToString()).jsonObject
        assertEquals(2, manifest.getValue("version").jsonPrimitive.int)
        val root = manifest.getValue("root").jsonObject
        assertEquals("FOLDER", root.getValue("kind").jsonPrimitive.content)
        assertTrue("content" !in root)
        assertTrue(root["children"]?.jsonArray.orEmpty().any { it.jsonObject.getValue("kind").jsonPrimitive.content == potKind }, "the Pot is missing from the archive")
        val refs = Regex("\"path\":\\s*\"([^\"]+)\",\\s*\"size\"[^}]*\"sha256\":\\s*\"([0-9a-f]{64})\"").findAll(manifest.toString()).toList()
        assertTrue(refs.isNotEmpty(), "the archive names no payload")
        for (r in refs) {
            val bytes = zip.getInputStream(zip.getEntry(r.groupValues[1])).readBytes()
            assertEquals(r.groupValues[2], sha256(bytes), "${r.groupValues[1]} is not what the manifest names")
        }
        refs.size
    }

    /**
     * An Emergency Kit by path (`Session.SignInKit`, the kit CR's phase 8): a kit PDF of the principal (`{v: 3, email, sk,
     * srv}` as the web writes it, the token in `Keywords` only) written under this module's `build/` and removed however
     * the case ends — never printed —, read by the core, the form filled (email, server from the token, the key held), and
     * a sign-in with an empty Secret Key reaching the shell. Signed out after; this host keeps no keys, so nothing is
     * trusted.
     */
    @Test
    fun aKitSignsInByPathWithAnEmptySecretKey() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val email = env.getValue("VEYQ_EMAIL")
        val key = assertNotNull(me.raddatz.veyq.mobile.crypto.SecretKeyText.read(BouncyCastlePrimitives, env.getValue("VEYQ_SECRET_KEY")))
        val json = kotlinx.serialization.json.buildJsonObject {
            put("v", kotlinx.serialization.json.JsonPrimitive(3))
            put("email", kotlinx.serialization.json.JsonPrimitive(email))
            put("sk", kotlinx.serialization.json.JsonPrimitive(me.raddatz.veyq.mobile.crypto.SecretKeyText.format(BouncyCastlePrimitives, key)))
            put("srv", kotlinx.serialization.json.JsonPrimitive(origin))
        }.toString()
        key.fill(0)
        val token = "veyqkit:" + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
        val dir = File(System.getProperty("user.dir"), "build/live-kit").apply { mkdirs() }
        val kit = File(dir, "kit-${System.nanoTime()}.pdf")
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge kit test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
        try {
            kit.writeBytes("%PDF-1.4\n1 0 obj\n<< /Keywords ($token) >>\nendobj\ntrailer\n<< /Info 1 0 R >>\n%%EOF\n".encodeToByteArray())
            val gate = b.watching(Watched.Session)
            assertEquals(Answer.Done, b.ask(Call.SessionStart))
            gate.until { (it as Update.Session).gate as? Gate.SignIn }
            assertEquals(Answer.Done, b.ask(Call.SessionSignInKit(kit.path, "")))
            val form = gate.until { (it as Update.Session).gate.let { g -> (g as? Gate.SignIn)?.takeIf { s -> s.kit != null } } }
            assertEquals(SessionModel.KitShown(email, origin, me.raddatz.veyq.mobile.account.KitImport.ServerFrom.TOKEN), form.kit)
            assertEquals(Answer.Done, b.ask(Call.SessionSignIn(form.server!!, form.email!!, env.getValue("VEYQ_PASSWORD"), ""), timeout = 2.minutes))
            val signed = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
            assertEquals(Answer.Done, b.ask(Call.SessionSignOut(signed.connectionKey)))
        } finally {
            kit.delete()
            b.shutdown()
        }
    }

    /**
     * The account's Settings through the bridge over the real core (`cr-app-account-settings` phase 3): a fresh login of
     * the seeded principal opens its page, reads its address and its own family first and marked, renames this device
     * through the register (the name read back on the page and on its own family's row), and signs out from the page —
     * the page closes and the sign-in shows. Only the session this test signs in is touched. Without `VEYQ_LIVE` it is
     * reported as skipped (an assumption), never as passed.
     */
    @Test
    fun anAccountsSettingsAreReadRenamedAndSignedOutThroughCalls() {
        val origin = System.getenv("VEYQ_LIVE")
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        runBlocking {
            val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
            val env = envFile.readLines()
                .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
                .toMap()
            val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge settings test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles)
            try {
                val gate = b.watching(Watched.Session)
                val shell = b.watching(Watched.Shell)
                assertEquals(Answer.Done, b.ask(Call.SessionStart))
                gate.until { (it as Update.Session).gate as? Gate.SignIn }
                val signIn = Call.SessionSignIn(origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"))
                assertEquals(Answer.Done, b.ask(signIn, timeout = 2.minutes))
                val signed = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
                shell.until(1.minutes) { (it as Update.Shell).shown }

                val id = assertIs<Answer.SettingsOpened>(b.ask(Call.SettingsOpen(signed.connectionKey))).settings
                val page = b.watching(Watched.Settings(id))
                val read = page.until(1.minutes) { (it as Update.Settings).shown?.takeIf { s -> s.families != null && !s.working } }
                println("settings: ${read.families!!.size} families, ${read.keptDevices.size} kept devices, addDevice=${read.addDevice}, refusal=${read.refusal}")
                assertNull(read.refusal)
                assertEquals(env.getValue("VEYQ_EMAIL").trim().lowercase(), read.email.lowercase())
                assertNotNull(read.emailConfirmed)
                assertTrue(read.families!!.first().current, "this login's family is not first and marked")

                val name = "App settings bridge ${System.currentTimeMillis()}"
                assertEquals(Answer.Done, b.ask(Call.SettingsRename(id, name), timeout = 1.minutes))
                val renamed = page.until(1.minutes) { (it as Update.Settings).shown?.takeIf { s -> s.device == name && !s.working } }
                assertNull(renamed.refusal)
                assertEquals(name, renamed.families!!.first().label, "the rename did not reach this family's row")

                assertEquals(Answer.Done, b.ask(Call.SettingsSignOutHere(id), timeout = 1.minutes))
                assertIs<Gate.SignIn>(gate.until(1.minutes) { (it as Update.Session).gate.takeIf { g -> g is Gate.SignIn } })
                page.until { (it as Update.Settings).takeIf { u -> u.shown == null } }
            } finally {
                b.shutdown()
            }
        }
    }

    /**
     * Memories through the bridge's calls (the CR "Memories on every app", phase 3), on a running instance: an `App memory
     * <n>` made by `Shell.CreateMemory` at the personal space's top, three JPEGs drawn here and the WebM at
     * `VEYQ_LIVE_MEMORY_WEBM` (600 MiB, made by `ffmpeg`) added by path through `Memory.Upload`, their tiers made by a host
     * this test plays (`Media.Duration`, `Media.Tiers`: ImageIO's JPEGs to the core's spec, a drawn poster for the video), read
     * back as tiles at the thumbnails' SHA-256; a range of the video across a frame's edge at the file's; the original saved
     * through `Medium.Save` at its SHA-256 and a second save cancelled with nothing left; one photo trashed and undone; a
     * chapter made. The Memory is trashed and purged however it ends. Reported skipped without `VEYQ_LIVE` and
     * `VEYQ_LIVE_MEMORY_WEBM`.
     */
    @Test
    fun aMemoryIsMadeFilledByPathPlayedSavedAndEmptiedThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE")
        val webm = System.getenv("VEYQ_LIVE_MEMORY_WEBM")?.let(::File)
        org.junit.Assume.assumeTrue("VEYQ_LIVE and VEYQ_LIVE_MEMORY_WEBM are not set: a live case", origin != null && webm != null)
        origin!!
        assertTrue(webm!!.isFile, "no $webm")
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        // the purge goes through a session of its own: the bridge's tree is cached
        val store = object : DeviceStore {
            val values = ConcurrentHashMap<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        }
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, "bridge live memory test, the purge", BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.tenants.first { origin.contains(it.domain) })
        val personalScope = tenant.spaces().first { it.kind == "PERSONAL" }.scopeId

        // the host's tiers, made here as a platform makes them: a photo through ImageIO to the requested edges, a video's poster drawn
        val thumbnails = ConcurrentHashMap<String, String>() // file name -> its thumbnail's SHA-256
        fun jpeg(image: java.awt.image.BufferedImage, edge: Int): ByteArray {
            val scale = minOf(1.0, edge.toDouble() / maxOf(image.width, image.height))
            val w = maxOf(1, (image.width * scale).toInt())
            val h = maxOf(1, (image.height * scale).toInt())
            val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply { drawImage(image, 0, 0, w, h, null); dispose() }
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "jpg", it) }.toByteArray()
        }
        lateinit var tiersHost: Host
        tiersHost = Host(timeout = 2.minutes) { token, json ->
            thread {
                val r = wire.decodeFromString(HostRequest.serializer(), json)
                val (reply, payload) = when (r) {
                    is HostRequest.MediaDuration -> HostReply.Value("10000") to null
                    is HostRequest.MediaTiers -> {
                        val image = if (r.mediaType.startsWith("video/")) {
                            java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB).also { i ->
                                i.createGraphics().apply { paint = java.awt.Color(20, 120, 60); fillRect(0, 0, 1280, 720); dispose() }
                            }
                        } else {
                            javax.imageio.ImageIO.read(File(r.path))
                        }
                        val preview = jpeg(image, r.previewEdge)
                        val thumbnail = jpeg(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(preview)), r.thumbnailEdge)
                        thumbnails[File(r.path).name] = sha256(thumbnail)
                        HostReply.Tiers(image.width, image.height, preview.size) to preview + thumbnail
                    }
                    is HostRequest.StoreRead -> HostReply.Value(null) to null
                    else -> HostReply.Done to null
                }
                tiersHost.reply(token, wire.encodeToString(HostReply.serializer(), reply), payload)
            }
        }
        val b = Bridge.live(
            BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge memory test"), hostStore(ConcurrentHashMap()), { NoDeviceKeys }, JvmFiles,
            tiers = HostTiers(tiersHost),
        )
        val dir = java.nio.file.Files.createTempDirectory("veyq-live-memory").toFile()
        val name = "App memory ${System.currentTimeMillis() % 100_000}"
        var made: me.raddatz.veyq.mobile.navigation.NodeAt? = null
        try {
            val photos = (1..3).map { n ->
                File(dir, "photo-$n.jpg").also { f ->
                    val image = java.awt.image.BufferedImage(3000 + n * 100, 2000, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    image.createGraphics().apply {
                        paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(30 * n, 90, 200), 3000f, 2000f, java.awt.Color(240, 200, 40 * n))
                        fillRect(0, 0, image.width, image.height)
                        dispose()
                    }
                    javax.imageio.ImageIO.write(image, "jpg", f)
                }
            }
            val gate = b.watching(Watched.Session)
            assertEquals(Answer.Done, b.ask(Call.SessionStart))
            gate.until { (it as Update.Session).gate as? Gate.SignIn }
            assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), timeout = 2.minutes))
            gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
            b.watching(Watched.Shell).until(1.minutes) { (it as Update.Shell).takeIf { u -> u.state?.lists?.spaces?.isNotEmpty() == true } }

            val created = b.ask(Call.ShellCreateMemory(personalScope, null, name), timeout = 1.minutes) as Answer.Created
            val memory = assertNotNull(created.node, "${created.notice}")
            made = memory
            println("made $name")
            assertEquals(Answer.Created(null, me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.NODE_NAME_TAKEN_BY,
                me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MEMORY_A_MEMORY), name)),
                b.ask(Call.ShellCreateMemory(personalScope, null, name), timeout = 1.minutes), "its name is taken now")
            val g = (b.ask(Call.GalleryOpen(memory)) as Answer.GalleryOpened).gallery
            val page = b.watching(Watched.Gallery(g))
            page.until(1.minutes) { u -> (u as Update.Gallery).shown?.takeIf { it.rows?.isEmpty() == true } }

            val started = System.currentTimeMillis()
            assertEquals(Answer.Queued(4, null), b.ask(Call.MemoryUpload(g, photos.map { it.path } + webm.path), timeout = 1.minutes))
            val rows = page.until(30.minutes) { u -> (u as Update.Gallery).shown?.rows?.takeIf { it.size == 4 } }
            println("four media in ${System.currentTimeMillis() - started} ms")
            for (row in rows) {
                val (answer, tile) = b.askWithPayload(Call.GalleryTile(g, row.mediaId), 1.minutes)
                assertEquals(Answer.Bytes("image/jpeg"), answer, row.name)
                assertEquals(thumbnails.getValue(row.name!!), sha256(tile!!), "${row.name}'s tile is not the thumbnail its host made")
            }

            // the video through its reader, a range across a frame's edge at the file's
            val video = rows.indexOfFirst { it.video }
            val l = (b.ask(Call.LightboxOpen(g, video)) as Answer.LightboxOpened).lightbox
            val reader = b.ask(Call.MediumReader(l), timeout = 1.minutes) as Answer.Reader
            assertEquals(webm.length(), reader.size)
            val frame = me.raddatz.veyq.mobile.crypto.V1.FRAME_PLAINTEXT.toLong()
            val range = java.io.RandomAccessFile(webm, "r").use { f -> ByteArray(200).also { f.seek(frame - 100); f.readFully(it) } }
            assertEquals(sha256(range), sha256(b.askWithPayload(Call.ReaderRead(reader.reader, frame - 100, 200), 1.minutes).second!!), "a range across a frame's edge")
            assertEquals(Answer.Done, b.ask(Call.LightboxStop(l)))

            // the original saved at its SHA-256; a second save cancelled, nothing left
            val saved = File(dir, "saved.webm")
            val save = (b.ask(Call.MediumSave(l, saved.path)) as Answer.Saving).save
            val end = b.watching(Watched.Save(save)).until(20.minutes) { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
            assertEquals(me.raddatz.veyq.mobile.depot.FileSave.End.SAVED, end.end)
            val fileSha = java.security.MessageDigest.getInstance("SHA-256").let { d ->
                webm.inputStream().use { s -> val buf = ByteArray(1 shl 20); while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            val savedSha = java.security.MessageDigest.getInstance("SHA-256").let { d ->
                saved.inputStream().use { s -> val buf = ByteArray(1 shl 20); while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            assertEquals(fileSha, savedSha, "the saved original is not the file")
            val cancelled = File(dir, "cancelled.webm")
            val second = (b.ask(Call.MediumSave(l, cancelled.path)) as Answer.Saving).save
            b.watching(Watched.Save(second)).until(5.minutes) { u -> (u as Update.Save).shown?.takeIf { it.written > 0 } }
            assertEquals(Answer.Done, b.ask(Call.SaveCancel(second), timeout = 1.minutes))
            assertTrue(!cancelled.exists(), "a cancelled save left its file")

            // one photo trashed and undone; a chapter made
            val photo = rows.first { !it.video }.mediaId
            assertEquals(Answer.Trashed(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MEMORY_MOVED_TO_TRASH), undoable = true),
                b.ask(Call.GalleryTrash(g, photo), timeout = 1.minutes))
            assertEquals(Answer.Said(null), b.ask(Call.GalleryUndo(g), timeout = 1.minutes))
            page.until(1.minutes) { u -> (u as Update.Gallery).shown?.rows?.takeIf { r -> r.size == 4 && r.any { it.mediaId == photo } } }
            val chapter = b.ask(Call.GalleryCreateChapter(g, "Day one"), timeout = 1.minutes) as Answer.Created
            assertEquals("CHAPTER", assertNotNull(chapter.node, "${chapter.notice}").kind)
        } finally {
            made?.let { m ->
                tenant.forgetTree() // made through the bridge's session: this one reads its tree anew
                println("trash $name (core): ${me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, personalScope, m.nodeId)}")
                println("purge $name (core): ${me.raddatz.veyq.mobile.session.NodeTrash.purge(tenant, personalScope, m.nodeId)}")
            }
            dir.deleteRecursively()
            b.shutdown()
        }
    }

    /** A device store the test's host keeps in [kept], answering from its own thread as a UI loop does. */
    private fun hostStore(kept: ConcurrentHashMap<String, String>): HostStore {
        liveRun.track(kept)
        lateinit var host: Host
        host = Host { token, json ->
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
                    else -> HostReply.Failed("this host draws nothing")
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply))
            }
        }
        return HostStore(host)
    }

    /** Member [userId]'s picture as the core reads it in a session of its own: a sign-in, the tenant, its sync. */
    private suspend fun coreOwnPicture(origin: String, env: Map<String, String>, tenantId: String, userId: String): ByteArray {
        val store = liveRun.store()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            ThisDevice(store, NoDeviceKeys, "bridge test, the core's own read", BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.tenants.first { it.tenantId == tenantId })
        tenant.avatars.sync(tenant.people.values)
        return assertNotNull(tenant.avatars.pictureOf(userId), "the core's own read has no picture of $userId")
    }

    /** The download read back as `VEYQ_LIVE_EXPORT` reads it: every payload the manifest names, to its size and SHA-256. */
    private fun readBack(file: File): Int = java.util.zip.ZipFile(file).use { zip ->
        val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(".veyq/manifest.json")).readBytes().decodeToString()).jsonObject
        assertEquals(2, manifest.getValue("version").jsonPrimitive.int)
        val pot = manifest.getValue("root").jsonObject.getValue("content").jsonObject
        val refs = pot.getValue("entries").jsonArray.flatMap { e -> e.jsonObject["receipts"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("file").jsonObject } } +
            pot.getValue("table").jsonObject
        assertTrue(refs.isNotEmpty())
        for (r in refs) {
            val bytes = zip.getInputStream(zip.getEntry(r.getValue("path").jsonPrimitive.content)).readBytes()
            assertEquals(r.getValue("size").jsonPrimitive.long, bytes.size.toLong())
            assertEquals(r.getValue("sha256").jsonPrimitive.content, sha256(bytes), r.getValue("path").jsonPrimitive.content)
        }
        refs.size
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
