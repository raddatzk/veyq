package me.raddatz.veyq.mobile.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What a sign-in's guard lets through (`VeyqAccount.asSignInFailure`): a wrong PIN reaches `SessionModel.unlockWithPin` as
 * itself, so the TV says the attempts left (ADR 0160 decision 3) — wrapped, it read as a failed sign-in (tvOS pass
 * 2026-11-26; `TvSessionModelTest` plays the door and never passed the guard).
 */
class SignInGuardTest {
    @Test
    fun aWrongPinPassesTheGuardWithItsAttemptsLeft() {
        val refused = PinRefused(9)
        val out = VeyqAccount.asSignInFailure(refused)
        assertSame(refused, out)
        assertEquals(9, assertIs<PinRefused>(out).attemptsLeft)
    }

    @Test
    fun aSignInFailurePassesAsItIsAndAnythingElseIsAProtocolFailure() {
        val failure = SignInFailure(SignInFailure.Reason.UNREACHABLE, "down")
        assertSame(failure, VeyqAccount.asSignInFailure(failure))
        assertEquals(SignInFailure.Reason.PROTOCOL, assertIs<SignInFailure>(VeyqAccount.asSignInFailure(IllegalStateException("x"))).reason)
    }
}
