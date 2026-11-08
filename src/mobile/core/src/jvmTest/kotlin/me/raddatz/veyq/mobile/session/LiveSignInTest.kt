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
 * Signs in to a running dev instance as the seeded principal (`.run/.env`, `./gradlew backend:seed`) and opens every
 * tenant of the account. Runs only when `VEYQ_LIVE` names an origin of the server (e.g. `http://veyq.localhost:8080`);
 * a plain `check` skips it.
 */
class LiveSignInTest {
    @Test
    fun theSeededPrincipalSignsInUnlocksAndReadsEveryTenant() = runTest(timeout = 2.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), MemoryDeviceStore(),
        ) { block -> HttpClient(OkHttp, block) }
        println("tenants: " + account.tenants.joinToString { "${it.name}@${it.domain}" })
        for (tenant in account.tenants) {
            val t = account.open(tenant)
            println("[${tenant.name}] spaces: " + t.spaces().joinToString { "${it.kind}:${it.name}" })
            println("[${tenant.name}] nodes: " + t.nodes().joinToString { "${it.kind}:${it.name}" })
            println("[${tenant.name}] favorites: " + t.favorites().joinToString { it.name })
            assertTrue(t.spaces().any { it.kind == "PERSONAL" }, "the personal scope of ${tenant.name} did not open")
        }
        account.signOut()
    }
}
