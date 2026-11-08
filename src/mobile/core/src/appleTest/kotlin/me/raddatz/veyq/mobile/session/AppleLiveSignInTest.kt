@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The Apple path end to end — libsodium's primitives and URLSession — signing in to a running dev instance as the
 * seeded principal. Runs only when `VEYQ_LIVE` names the tenant's origin; a plain test run skips it.
 */
class AppleLiveSignInTest {
    @Test
    fun theSeededPrincipalSignsInThroughLibsodium() = runTest(timeout = 2.minutes) {
        val origin = getenv("VEYQ_LIVE")?.toKString() ?: return@runTest
        val envFile = getenv("VEYQ_ENV_FILE")?.toKString() ?: error("VEYQ_ENV_FILE is not set")
        val env = (NSString.stringWithContentsOfFile(envFile, NSUTF8StringEncoding, null) ?: error("cannot read $envFile"))
            .lines().mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = AppleSessions.signIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), testDevice(me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives))
        val session = account.open(account.defaultTenant ?: error("no tenant"))
        val spaces = session.spaces()
        val objects = session.objects()
        println("apple spaces: " + spaces.joinToString { "${it.kind}:${it.name}" })
        println("apple objects: " + objects.joinToString { "${it.application.id}:${it.name}" })
        println("apple favorites: " + session.favorites().joinToString { it.name })
        assertTrue(spaces.any { it.kind == "TENANT" }, "the tenant scope did not open")
        assertTrue(objects.isNotEmpty(), "no object opened")
        account.signOut()
    }
}
