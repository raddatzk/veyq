package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The seeded second member (`VEYQ_USER2_*` in `.run/.env`) against a running dev instance: their TENANT grant is signed
 * by the owner, so it opens only through the key check (the owner's chain anchored by the pin their accept wrote), and
 * a second sign-in on the same device holds what the first one held. Runs only with `VEYQ_LIVE` AND
 * `VEYQ_LIVE_MEMBER=1`: that member's password may be one a person chose, so the run is the owner's to start.
 */
class MemberLiveSignInTest {
    @Test
    fun theSeededMemberOpensTheTenantTheOwnerGranted() = runTest(timeout = 2.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        if (System.getenv("VEYQ_LIVE_MEMBER") != "1") return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val device = MemoryDeviceStore()
        repeat(2) { round ->
            val account = VeyqAccount.signIn(
                BouncyCastlePrimitives, origin, env.getValue("VEYQ_USER2_EMAIL"), env.getValue("VEYQ_USER2_PASSWORD"), env.getValue("VEYQ_USER2_SECRET_KEY"), device,
            ) { block -> HttpClient(OkHttp, block) }
            for (tenant in account.tenants) {
                val t = account.open(tenant)
                println("[$round ${tenant.name}] spaces: " + t.spaces().joinToString { "${it.kind}:${it.name}" } + "; closed: " + t.closed)
                assertTrue(t.spaces().any { it.kind == "TENANT" }, "the owner's TENANT grant of ${tenant.name} did not open: ${t.closed}")
            }
            account.signOut()
        }
    }
}
