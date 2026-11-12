package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
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

/**
 * The dispatch over the real core (ADR 0123's Consequences, the bridge), as a host drives it, on the JVM's suite and
 * HTTP client: signs in through a call as the seeded principal (`.run/.env`, or `VEYQ_ENV_FILE`), watches the shell
 * open, reads Home's groups, searches a name and opens the hit, and opens a Pot and reads its view to balances that sum
 * to zero. Then ADR 0128's channels: the principal's own picture through `Avatar.Picture`, the same bytes as the core's
 * own read in a session of its own (it needs a picture set: Settings, your profile, in the web); and Amsterdam
 * downloaded through `Pot.Download` into a file, every payload of its ZIP to the SHA-256 its manifest names. The device
 * store is a host this test plays, answering from its own thread, and the files a JVM stand-in for core's `FileSink`.
 * Runs only when `VEYQ_LIVE` names an origin of a running dev instance (`./gradlew backend:seed`).
 */
class LiveBridgeTest {
    @Test
    fun theSeededPrincipalSignsInAndReadsTheShellSearchAndAPotThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()

        val kept = ConcurrentHashMap<String, String>()
        lateinit var host: Host
        host = Host { token, json ->
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
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
            val store = object : DeviceStore {
                val values = ConcurrentHashMap<String, String>()
                override fun read(key: String) = values[key]
                override fun write(key: String, value: String) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
            }
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

    /** A device store the test's host keeps in [kept], answering from its own thread as a UI loop does. */
    private fun hostStore(kept: ConcurrentHashMap<String, String>): HostStore {
        lateinit var host: Host
        host = Host { token, json ->
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply))
            }
        }
        return HostStore(host)
    }

    /** Member [userId]'s picture as the core reads it in a session of its own: a sign-in, the tenant, its sync. */
    private suspend fun coreOwnPicture(origin: String, env: Map<String, String>, tenantId: String, userId: String): ByteArray {
        val store = object : DeviceStore {
            val values = ConcurrentHashMap<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        }
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
