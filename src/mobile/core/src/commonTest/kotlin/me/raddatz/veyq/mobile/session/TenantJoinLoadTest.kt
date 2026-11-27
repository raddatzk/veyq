package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.Invites
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The join's first read (the web's `TenantJoinView` preview): a server that cannot be reached ends the check as the web's
 * catch-all does, an invalid invite, never "Checking your invitation…" for good (measured 2026-11-27 on Android: a mailed
 * link whose origin nothing served stayed on the spinner).
 */
class TenantJoinLoadTest {
    @Test
    fun anUnreachableServerEndsTheCheck() = runTest {
        val join = TenantJoin(
            testPrimitives,
            "https://nothing-here.example",
            Invites.parseFragment("tok.sealed"),
            { block -> HttpClient(MockEngine { throw RuntimeException("connection refused") }, block) },
            { _, _, _, _ -> error("no sign-in before a preview") },
            null,
        )
        join.load()
        assertEquals(TenantJoin.Phase.INVALID, join.current.phase)
    }
}
