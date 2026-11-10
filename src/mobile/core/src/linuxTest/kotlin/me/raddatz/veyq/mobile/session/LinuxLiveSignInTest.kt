@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.readFile
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The Linux path end to end — libsodium's primitives and Ktor's Curl engine — signing in to a running dev instance as
 * the seeded principal (`.run/.env`) and reading every tenant of the account, as `LiveSignInTest` does on the JVM. Runs
 * only when `VEYQ_LIVE` names the tenant's origin (`run.py test` hands it into the container); a plain run skips it.
 */
class LinuxLiveSignInTest {
    @Test
    fun theSeededPrincipalSignsInThroughLibsodiumAndCurl() = runTest(timeout = 2.minutes) {
        val origin = getenv("VEYQ_LIVE")?.toKString() ?: return@runTest
        val envFile = getenv("VEYQ_ENV_FILE")?.toKString() ?: error("VEYQ_ENV_FILE is not set")
        val env = readFile(envFile).lines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            LibsodiumPrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(LibsodiumPrimitives),
        ) { block -> HttpClient(Curl, block) }
        println("linux tenants: " + account.tenants.joinToString { "${it.name}@${it.domain}" })
        for (tenant in account.tenants) {
            val t = account.open(tenant)
            println("linux [${tenant.name}] spaces: " + t.spaces().joinToString { "${it.kind}:${it.name}" })
            println("linux [${tenant.name}] objects: " + t.objects().joinToString { "${it.application.id}:${it.name}" })
            assertTrue(t.spaces().any { it.kind == "PERSONAL" }, "the personal scope of ${tenant.name} did not open")
        }
        account.signOut()
    }
}
