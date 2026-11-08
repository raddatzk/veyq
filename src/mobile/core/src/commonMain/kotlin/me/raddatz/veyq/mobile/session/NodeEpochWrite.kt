package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.delay

/**
 * A write that states a node's epoch (ADR 0109 point 6), core's for every application, a port of `writeAtNodeEpoch` in
 * `src/web/src/shared/rekeyRetry.ts` for a writer that pulls no re-key forward — this app drives no re-key, so it waits
 * as the web's guest does:
 *  - **below the floor** (layer 2): the node's key was minted under a scope epoch below the one this device holds — a
 *    node not re-keyed since a rotation, or an operator serving its old wrap. Nothing is sealed under it; wait, re-read.
 *  - **`409 node_rekey_pending`**: the scope was rotated and this node is not re-keyed yet. Wait ([waitsMs], shared with
 *    the floor's waits), re-read, try again.
 *  - **`409 stale_epoch`**: the node was re-keyed since it was read. Re-read once and try again.
 * Every attempt after a refusal re-reads first, so it seals under the node's key as it is then: a retry with the same key
 * and epoch can never succeed once the re-key landed.
 */
object NodeEpochWrite {
    /** What one attempt was answered with. */
    enum class Answer { DONE, REKEY_PENDING, STALE_EPOCH, OTHER }

    sealed interface Result<out T> {
        /** The attempt's own answer (done, or a refusal this loop does not handle), with what it produced. */
        data class Answered<T>(val value: T) : Result<T>
        /** Still not re-keyed after every wait. */
        data object Busy : Result<Nothing>
        /** The re-read no longer finds the node. */
        data object Gone : Result<Nothing>
        /** `stale_epoch` after the re-read too. */
        data object StaleAgain : Result<Nothing>
    }

    val REKEY_WAITS_MS: List<Long> = listOf(2_000, 5_000, 10_000)

    suspend fun <T> run(
        waitsMs: List<Long> = REKEY_WAITS_MS,
        belowFloor: () -> Boolean,
        reread: suspend () -> Boolean,
        attempt: suspend () -> Pair<Answer, T>,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): Result<T> {
        var waited = 0
        var staleRetried = false
        var first = true
        while (true) {
            if (!first && !reread()) return Result.Gone
            first = false
            if (belowFloor()) {
                if (waited >= waitsMs.size) return Result.Busy
                sleep(waitsMs[waited++])
                continue
            }
            val (answer, value) = attempt()
            when (answer) {
                Answer.REKEY_PENDING -> {
                    if (waited >= waitsMs.size) return Result.Busy
                    sleep(waitsMs[waited++])
                }
                Answer.STALE_EPOCH -> {
                    if (staleRetried) return Result.StaleAgain
                    staleRetried = true
                }
                Answer.DONE, Answer.OTHER -> return Result.Answered(value)
            }
        }
    }
}
