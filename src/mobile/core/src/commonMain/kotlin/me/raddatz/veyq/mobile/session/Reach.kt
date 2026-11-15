package me.raddatz.veyq.mobile.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether an account reaches its server (ADR 0140 decision 5), the core's alone, from what its requests came to: a
 * transport failure turns it [Offline], a probe of the server (`GET /api/setup`, [LivePoll]'s backoff from 5 s to 60 s)
 * and the resume steps after it turn it [Online] again — or [Ended], where the resume found this device's trust gone (a
 * refused refresh, a wrap no longer served, ADR 0140 decision 4): the account's store is wiped then, and the person
 * signs in again. No platform monitor is asked (the CR's R7).
 */
sealed class Reach {
    object Online : Reach()

    /** Unreachable; [asOf] is when the server last answered (epoch ms), null where it never did in this run. */
    data class Offline(val asOf: Long?) : Reach()

    /** The resume at reconnect found this device's trust gone, [reason] why: the account signs out. */
    data class Ended(val reason: SignInFailure.Reason) : Reach()
}

/** A request that did not reach the server (or an account opened offline, which sends none until it reconnects). */
class Unreachable(message: String? = null, cause: Throwable? = null) : Exception(message ?: "unreachable", cause)

/**
 * What a screen shows offline (ADR 0140 decision 5): read-only, and [asOf] — the local time (epoch ms) its content was
 * last confirmed by the server; null where it is not known. Serializable: the bridge carries it as it is (a time, never
 * a key or a name).
 */
@Serializable
@SerialName("OfflineAsOf")
data class OfflineAsOf(val asOf: Long?)
