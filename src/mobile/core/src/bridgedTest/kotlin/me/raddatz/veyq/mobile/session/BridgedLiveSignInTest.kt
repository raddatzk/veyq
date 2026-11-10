@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.readFile
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The platform's own HTTP engine, as the bridge links it: Curl on Linux, WinHTTP on Windows. */
internal expect val liveEngineName: String

internal expect fun liveHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * A bridged platform's path end to end — libsodium's primitives and its own Ktor engine — signing in to a running dev
 * instance as the seeded principal (`.run/.env`) and reading every tenant of the account, as `LiveSignInTest` does on
 * the JVM. Runs only when `VEYQ_LIVE` names the tenant's origin (`linux/run.py test` and `windows/run.py test` hand it
 * in); a plain run skips it.
 */
class BridgedLiveSignInTest {
    @Test
    fun theSeededPrincipalSignsInThroughLibsodiumAndThePlatformsEngine() = runTest(timeout = 2.minutes) {
        val origin = getenv("VEYQ_LIVE")?.toKString() ?: return@runTest
        val envFile = getenv("VEYQ_ENV_FILE")?.toKString() ?: error("VEYQ_ENV_FILE is not set")
        val env = readFile(envFile).lines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            LibsodiumPrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(LibsodiumPrimitives),
        ) { block -> liveHttpClient(block) }
        println("$liveEngineName tenants: " + account.tenants.joinToString { "${it.name}@${it.domain}" })
        for (tenant in account.tenants) {
            val t = account.open(tenant)
            println("$liveEngineName [${tenant.name}] spaces: " + t.spaces().joinToString { "${it.kind}:${it.name}" })
            println("$liveEngineName [${tenant.name}] objects: " + t.objects().joinToString { "${it.application.id}:${it.name}" })
            assertTrue(t.spaces().any { it.kind == "PERSONAL" }, "the personal scope of ${tenant.name} did not open")
        }
        account.signOut()
    }
}
