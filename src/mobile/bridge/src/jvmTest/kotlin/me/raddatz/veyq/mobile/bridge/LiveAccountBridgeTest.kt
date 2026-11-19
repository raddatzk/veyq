package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.Throwaway
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The account's flows through the bridge over the real core (ADR 0148 decision 11), as a throwaway ([Throwaway]: a file
 * naming the seeded principal or member FAILS): signed in through a call, one's own profile read and a username saved,
 * then the password changed and changed back — every secret typed inbound only, the states watched. The device is taken
 * off the account afterwards ([LiveRun]).
 */
class LiveAccountBridgeTest {
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    @Test
    fun theProfileAndThePasswordChangeRunThroughCalls() = runBlocking {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val run = LiveRun("VEYQ", t.file).also { runs += it }
        val kept = run.track(ConcurrentHashMap<String, String>())
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
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge account test"), HostStore(host), { NoDeviceKeys }, JvmFiles)
        val gate = b.watching(Watched.Session)
        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        val password = t.env.getValue("VEYQ_PASSWORD")
        val secretKey = t.env.getValue("VEYQ_SECRET_KEY")
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn(origin, t.email(), password, secretKey), timeout = 2.minutes))
        val signed = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        b.ask(Call.SessionNotNow)
        val connection = signed.connectionKey

        val profile = (b.ask(Call.ProfileOpen) as Answer.ProfileOpened).profile
        val shown = b.watching(Watched.Profile(profile)).until(1.minutes) { u -> (u as Update.Profile).shown?.takeIf { it.loaded } }
        assertTrue(shown.hasKey, "the profile holds no tenant key")
        val name = "tw${System.currentTimeMillis() % 100000}"
        assertEquals(Answer.Done, b.ask(Call.ProfileUsername(profile, name)))
        assertEquals(Answer.Done, b.ask(Call.ProfileSave(profile), timeout = 1.minutes))
        b.watching(Watched.Profile(profile)).until(1.minutes) { u -> (u as Update.Profile).shown?.takeIf { it.saves == 1 && it.refusal == null } }

        val next = "bridge-changed-${System.currentTimeMillis()}"
        for ((from, to) in listOf(password to next, next to password)) {
            val flow = (b.ask(Call.PasswordChangeOpen(connection)) as Answer.FlowOpened).flow
            assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(flow, from, to, to, secretKey), timeout = 3.minutes))
            val done = b.watching(Watched.PasswordChange(flow)).until(1.minutes) { u -> (u as Update.PasswordChange).shown?.takeIf { !it.working } }
            assertTrue(done.done, "the password change through the bridge was refused: ${done.refusal}")
            assertEquals(Answer.Done, b.ask(Call.PasswordChangeClose(flow)))
        }
        b.shutdown()
    }
}
