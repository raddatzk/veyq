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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import me.raddatz.veyq.mobile.session.DeviceStore
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
        val signIn = Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), remember = false)
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
     * The device trust through calls (CR device-trust-on-linux-and-windows, Phase 3), over core's [SoftwareDeviceKeys]
     * whose prompt the test plays and a device store the host keeps across bridges — each new bridge the app started
     * again. Staying signed in: the sign-in carries what it uses, a fresh bridge resumes, a cancelled prompt stays on the
     * unlock, the unlock by the device key opens, Manage servers says how, and the sign-out ends it. The password trust:
     * a fresh bridge unlocks with the password, and "Stop trusting" ends it. Everything enrolled is removed again.
     */
    @Test
    fun staySignedInResumesAFreshBridgeAndTheUnlockOpensByTheDeviceKeyAndByThePassword() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val kept = ConcurrentHashMap<String, String>()
        val keys = SoftwareDeviceKeys(BouncyCastlePrimitives)
        fun started() = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge trust test"), hostStore(kept), { keys }, JvmFiles)
        fun signIn(remember: Boolean) = Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), remember)

        // signed in staying signed in; beside the sign-in, what it uses (the fake's: the person's confirmation)
        val first = started()
        val gate = first.watching(Watched.Session)
        assertEquals(Answer.Done, first.ask(Call.SessionStart))
        assertEquals(StaySignedIn.PRESENCE, gate.until { (it as Update.Session).takeIf { u -> u.gate is Gate.SignIn }?.staySignedIn })
        assertEquals(Answer.Done, first.ask(signIn(remember = true), timeout = 2.minutes))
        val connection = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }.connectionKey
        assertEquals(1, keys.prompts, "staying signed in did not ask the person")
        first.shutdown() // the app closed, not signed out

        // started again: resumed, nothing typed
        started().apply {
            assertEquals(Answer.Done, ask(Call.SessionStart, timeout = 2.minutes))
            assertIs<Gate.Signed>(gateNow(), "a remembered account did not resume")
        }.shutdown()
        assertEquals(2, keys.prompts)

        // started again with the prompt cancelled: the unlock, which a cancel keeps and the device key opens
        keys.authenticates = false
        val third = started()
        assertEquals(Answer.Done, third.ask(Call.SessionStart, timeout = 2.minutes))
        val unlock = assertIs<Gate.Unlock>(third.gateNow())
        assertEquals(TrustedDevice.Method.DEVICE_KEY, unlock.record.method)
        assertEquals(Answer.Done, third.ask(Call.SessionUnlock(""), timeout = 2.minutes))
        assertEquals(Gate.Unlock(unlock.record), third.gateNow(), "a cancelled prompt left the unlock")
        keys.authenticates = true
        assertEquals(Answer.Done, third.ask(Call.SessionUnlock(""), timeout = 2.minutes))
        assertIs<Gate.Signed>(third.gateNow(), "the device key did not unlock")

        // Manage servers says how this device trusts the server; the sign-out ends staying signed in
        val manage = third.watching(Watched.Manage)
        assertEquals(Answer.Done, third.ask(Call.ManageOpen))
        assertEquals(TrustedDevice.Method.DEVICE_KEY, manage.until { (it as Update.Manage).servers?.single { s -> s.connection == connection } }.trusted)
        assertEquals(Answer.Done, third.ask(Call.ManageClose))
        assertEquals(Answer.Done, third.ask(Call.SessionSignOut(connection)))
        assertIs<Gate.SignIn>(third.gateNow(), "the sign-out left a remembered account")

        // the password trust: started again, the unlock asks for the password alone
        assertEquals(Answer.Done, third.ask(signIn(remember = false), timeout = 2.minutes))
        assertIs<Gate.Signed>(third.gateNow())
        third.shutdown()
        val fourth = started()
        assertEquals(Answer.Done, fourth.ask(Call.SessionStart, timeout = 2.minutes))
        val byPassword = assertIs<Gate.Unlock>(fourth.gateNow())
        assertEquals(TrustedDevice.Method.DEVICE, byPassword.record.method)
        assertEquals(Answer.Done, fourth.ask(Call.SessionUnlock(env.getValue("VEYQ_PASSWORD")), timeout = 2.minutes))
        assertIs<Gate.Signed>(fourth.gateNow(), "the password did not unlock")
        val prompts = keys.prompts

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

    /** core's `FileSink` as the JVM plays it: the file stays only when the producer answered true. */
    private object JvmFiles : DeviceFiles {
        override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean {
            val file = File(path)
            var keep = false
            try {
                file.outputStream().buffered().use { out -> keep = produce { out.write(it) } }
                return keep
            } finally {
                if (!keep) file.delete()
            }
        }
    }
}
