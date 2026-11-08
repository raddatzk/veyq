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
 * Signs in to a running dev instance as the seeded principal (`.run/.env`, `./gradlew backend:seed`). Runs only when
 * `VEYQ_LIVE` names the tenant's origin (e.g. `http://veyq.localhost:8080`); a plain `check` skips it.
 */
class LiveSignInTest {
    @Test
    fun theSeededPrincipalSignsInUnlocksAndReadsItsSpaces() = runTest(timeout = 2.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val session = VeyqSession.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
        ) { block -> HttpClient(OkHttp, block) }
        val spaces = session.spaces()
        println("spaces: " + spaces.joinToString { "${it.kind}:${it.name}" })
        val objects = session.objects()
        println("objects: " + objects.joinToString { "${it.application.id}:${it.name}" })
        println("favorites: " + session.favorites().joinToString { it.name })
        println("applications off: " + session.applicationsOff())
        assertTrue(spaces.any { it.kind == "TENANT" }, "the tenant scope did not open")
        assertTrue(spaces.any { it.kind == "PERSONAL" }, "the personal scope did not open")
        session.signOut()
    }
}
