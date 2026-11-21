package me.raddatz.veyq.mobile.offline

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.BackgroundRun
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scheduled run without the person (ADR 0153 decision 8), over a server the test plays
 * with real seals: a login-level account is opened as a start opens it, its queue sent, its pins synced, then closed with
 * its keys zeroed; a presence-level and a password-trusted account never run and nobody is asked; a refused refresh ends
 * the record and wipes the store; without the server nothing is opened; the deadline stops the run between writes; an
 * account open in the process is used, never opened twice; the switch is on at the login level, absent elsewhere.
 *
 * Bites (each one edit, seen red, then restored): (a) the level check dropped (`BackgroundRun.runs` without its
 * `unlockLevel == LOGIN`) → [a_presence_level_and_a_password_trusted_account_never_run_and_nobody_is_asked] red; (b) the
 * start's offline twin used (`VeyqAccount.resume` in place of `resumeOnline`) →
 * [without_the_server_nothing_is_opened] red (the store opened, a second request: the reconnect); (c) the opened account
 * left open (no `retire` after the run) → [a_login_level_account_sends_its_queue_syncs_its_pins_and_closes] red; (d) the
 * deadline not asked between writes (`OutboxSender`'s `go`) → [the_deadline_stops_the_run_between_writes] red.
 */
class BackgroundRunTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private fun play(level: StaySignedIn = StaySignedIn.LOGIN, runs: Boolean = true) =
        PlayedTenant(level = level, backgroundRuns = runs).also { played += it }

    private fun HttpRequestData.body(): String = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun isCreate(r: HttpRequestData) = r.method == HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))

    /** Folder creates answered, each node id recorded in [into]; [then] runs after each. */
    private fun PlayedTenant.creates(into: MutableList<String>, then: () -> Unit = {}) {
        route = { r ->
            if (!isCreate(r)) null else {
                into += Json.parseToJsonElement(r.body()).jsonObject["nodeId"]!!.jsonPrimitive.content
                then()
                201 to """{"nodeId":"${into.last()}"}"""
            }
        }
    }

    private fun run(t: PlayedTenant, now: () -> Long = { 0L }) = BackgroundRun(t.p, t.device, t.client(), AppKinds.pins, now)

    private fun PlayedTenant.refreshes() = requests.count { it == "/api/token/refresh" }

    @Test
    fun a_login_level_account_sends_its_queue_syncs_its_pins_and_closes() = runTest {
        val t = play()
        val s = t.scope("s1")
        val m = t.node(s, "memory")
        t.medium(m, "photo")
        t.remember()
        // the app, earlier: a Memory pinned and kept, a folder queued offline, the app left
        val app = t.resume()
        val pins = assertNotNull(app.offlinePins).also { it.use(AppKinds.pins) }
        assertTrue(pins.pin(app.open(app.tenants.first()), s.id, m.id))
        pins.sync()
        val w = assertNotNull(QueuedNodes.folder(app, "t1", s.id, null, "Made on the train", 1))
        app.retire()
        // meanwhile on the web: a photo added to the pinned Memory
        t.medium(m, "later")
        val created = mutableListOf<String>()
        t.creates(created)
        val before = t.refreshes()

        val opened = mutableListOf<VeyqAccount>()
        val r = run(t).also { it.onOpened = { a -> opened += a } }
        val report = r.run(deadline = 60_000)

        assertEquals(mapOf(t.record.key to BackgroundRun.Outcome.DONE), report.accounts)
        assertEquals(1, report.sent)
        assertEquals(listOf(w.id), created, "the queued folder was not sent")
        assertEquals(before + 1, t.refreshes(), "not resumed with one refresh of the kept session, as a start")
        assertTrue(t.requests.none { it.startsWith("/api/login") }, "a run signed in anew: a new session family")
        // the keys zeroed when the run ended (bite (c))
        val a = opened.single()
        assertNull(a.masterKeyCopy(), "the account the run opened is still open")
        assertTrue(a.masterKey.all { it == 0.toByte() }, "the Master Key outlived the run")
        assertTrue(a.openedScopeKeys.isNotEmpty() && a.openedScopeKeys.all { k -> k.all { it == 0.toByte() } }, "a scope key outlived the run")

        // what the run kept: the photo added on the web, the outbox empty
        val after = t.resume()
        val o = assertNotNull(after.offline)
        val kept = o.store.entries().filter { it.key.kind == KeptRead.FRAMES }.map { t.nameOf(it.key.id) }.toSet()
        assertTrue("later-t" in kept, "the photo added on the web was not kept by the run: $kept")
        assertEquals(0, assertNotNull(after.outbox).count())
        after.retire()
    }

    @Test
    fun a_presence_level_and_a_password_trusted_account_never_run_and_nobody_is_asked() = runTest {
        val presence = play(level = StaySignedIn.PRESENCE)
        presence.scope("s1")
        presence.remember()
        presence.resume().retire()
        val prompts = presence.keys.prompts
        presence.requests.clear()
        assertNull(presence.device.backgroundSync(presence.record.key), "a presence-level account has a switch")
        val report = run(presence).run(deadline = 60_000)
        assertEquals(emptyMap(), report.accounts, "a presence-level account ran")
        assertEquals(prompts, presence.keys.prompts, "a run asked the person")
        assertEquals(emptyList(), presence.requests)

        val password = play()
        password.device.remember(TrustedDevice(password.server, "u1@a.example", password.userId, TrustedDevice.Method.DEVICE, "w-2", "veyq.device.2"))
        assertNull(password.device.backgroundSync(password.record.key), "a password-trusted account has a switch")
        assertEquals(emptyMap(), run(password).run(deadline = 60_000).accounts, "a password-trusted account ran")
        assertEquals(0, password.keys.prompts)
        assertEquals(emptyList(), password.requests)
    }

    @Test
    fun a_refused_refresh_ends_the_record_and_wipes_the_store() = runTest {
        val t = play()
        t.scope("s1")
        t.remember()
        t.resume().retire()
        assertTrue(assertNotNull(t.device.offline).has(t.record.key))
        // the device's saved key removed from another device: its session family revoked
        t.refreshStatus = 401
        val report = run(t).run(deadline = 60_000)
        assertEquals(mapOf(t.record.key to BackgroundRun.Outcome.ENDED), report.accounts)
        assertNull(t.device.trusted(t.record.key), "the record outlived a refused refresh")
        assertFalse(assertNotNull(t.device.offline).has(t.record.key), "the store outlived a refused refresh")
        assertFalse(t.device.backgroundWanted.value, "a run is still wanted for an account gone")
    }

    @Test
    fun without_the_server_nothing_is_opened() = runTest {
        val t = play()
        t.scope("s1")
        t.remember()
        val app = t.resume()
        assertNotNull(QueuedNodes.folder(app, "t1", t.scope("s2").id, null, "Waiting", 1))
        app.retire()
        // the store and the kept wrap are there: the start's offline twin would open the account from them
        t.reachable = false
        t.requests.clear()
        val opened = mutableListOf<VeyqAccount>()
        val report = run(t).also { it.onOpened = { a -> opened += a } }.run(deadline = 60_000)
        assertEquals(mapOf(t.record.key to BackgroundRun.Outcome.UNREACHABLE), report.accounts)
        assertEquals(emptyList(), opened, "an account was opened without the server")
        assertEquals(listOf("/api/setup"), t.requests, "more than the start's one attempt to reach the server")
    }

    @Test
    fun the_deadline_stops_the_run_between_writes() = runTest {
        var clock = 0L
        val t = play()
        val s = t.scope("s1")
        t.remember()
        val app = t.resume()
        val first = assertNotNull(QueuedNodes.folder(app, "t1", s.id, null, "First", 1))
        val second = assertNotNull(QueuedNodes.folder(app, "t1", s.id, null, "Second", 2))
        app.retire()
        val created = mutableListOf<String>()
        // the first write takes the run past its time
        t.creates(created) { clock = 10_000 }
        val report = run(t) { clock }.run(deadline = 5_000)
        assertEquals(mapOf(t.record.key to BackgroundRun.Outcome.UNFINISHED), report.accounts)
        assertEquals(listOf(first.id), created, "the run went on past its deadline")
        val after = t.resume()
        assertEquals(listOf(second.id), assertNotNull(after.outbox).writes().map { it.id }, "the write left is not waiting for the next run")
        after.retire()
    }

    @Test
    fun an_account_open_in_the_process_is_used_and_left_open() = runTest {
        val t = play()
        val s = t.scope("s1")
        t.remember()
        val app = t.resume()
        val w = assertNotNull(QueuedNodes.folder(app, "t1", s.id, null, "Made a moment ago", 1))
        val created = mutableListOf<String>()
        t.creates(created)
        val before = t.refreshes()
        val opened = mutableListOf<VeyqAccount>()
        val report = run(t).also { it.onOpened = { a -> opened += a } }.run(deadline = 60_000)
        assertEquals(mapOf(t.record.key to BackgroundRun.Outcome.DONE), report.accounts)
        assertEquals(listOf(w.id), created)
        assertEquals(emptyList(), opened, "the account was opened a second time")
        assertEquals(before, t.refreshes(), "the account open in the process was resumed anew")
        assertNotNull(app.masterKeyCopy()?.also { it.fill(0) }, "the run closed the app's account")
        app.retire()
    }

    @Test
    fun the_switch_is_on_at_the_login_level_turned_off_by_the_person_and_absent_elsewhere() = runTest {
        val t = play()
        t.scope("s1")
        t.remember()
        assertEquals(true, t.device.backgroundSync(t.record.key), "the switch is not on by default at the login level")
        assertTrue(t.device.backgroundWanted.value)
        val a = t.resume()
        val settings = AccountSettings(AccountSettings.Over(a), Here(), a.email) { 0L }
        settings.refresh()
        assertEquals(true, settings.current.backgroundSync)
        assertNull(settings.current.backgroundSyncMissing)
        settings.setBackgroundSync(false)
        assertEquals(false, settings.current.backgroundSync)
        assertFalse(t.device.backgroundWanted.value, "a run is still scheduled with every switch off")
        a.retire()
        t.requests.clear()
        assertEquals(emptyMap(), run(t).run(deadline = 60_000).accounts, "an account whose switch is off ran")
        assertEquals(emptyList(), t.requests)

        // the presence level on a phone: no switch, and why
        val presence = play(level = StaySignedIn.PRESENCE)
        presence.scope("s1")
        presence.remember()
        val p = presence.resume()
        val ps = AccountSettings(AccountSettings.Over(p), Here(), p.email) { 0L }.also { it.refresh() }
        assertNull(ps.current.backgroundSync)
        assertEquals(Msg.ACCOUNT_SETTINGS_BACKGROUND_SYNC_MISSING, ps.current.backgroundSyncMissing)
        p.retire()

        // a desktop: no switch, nothing to say (ADR 0153 decision 8: no scheduled run there)
        val desktop = play(runs = false)
        desktop.scope("s1")
        desktop.remember()
        val d = desktop.resume()
        val ds = AccountSettings(AccountSettings.Over(d), Here(), d.email) { 0L }.also { it.refresh() }
        assertNull(ds.current.backgroundSync)
        assertNull(ds.current.backgroundSyncMissing)
        assertFalse(desktop.device.backgroundWanted.value)
        assertEquals(emptyMap(), run(desktop).run(deadline = 60_000).accounts, "a desktop ran a scheduled sync")
        d.retire()
    }

    /** The trust and sign-out half of the page, unused here. */
    private class Here : AccountSettings.Here<VeyqAccount, TenantSession> {
        override fun trust() = AccountSettings.Trust(TrustedDevice.Method.DEVICE_KEY, StaySignedIn.LOGIN, StaySignedIn.entries)
        override suspend fun stay(level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(password: String) = TrustChange.FAILED
        override suspend fun stopTrusting() = Unit
        override suspend fun signOut(): SessionModel.SignedOut<VeyqAccount, TenantSession> = throw UnsupportedOperationException()
    }
}
