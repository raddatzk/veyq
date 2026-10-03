package com.veyq.app.features.account.login

import java.sql.SQLException

/**
 * Retry a rotation transaction that the database rolled back as a **deadlock victim**.
 *
 * `SQLSTATE 40001` (serialization failure; MariaDB error 1213) is the *retryable* class: the transaction
 * committed nothing, so replaying it reads the same rows it would have read the first time. Without this a
 * deadlock reaches the client as a `500` from `/api/token/refresh`, and a client cannot tell that apart from
 * a dead session — it clears its tokens either way.
 *
 * The deadlock this answers is real and measured, and it is **not** a lock-ordering cycle that could be
 * designed away: two refreshes of one token both take an `X` record lock on the same entry of
 * `ux_refresh_token_hash`, and the winner's INSERT of the successor then needs an *insert-intention gap lock*
 * before that very record, which must queue behind the loser's waiting request. Whether it fires at all
 * depends on where the successor's **random** hash sorts, so the rate falls off with the size of the index —
 * measured at ~6 % of requests with the table essentially empty and 0 in 150 at 50 rows and beyond.
 *
 * Since a rotation takes its **family's** row lock first (`SessionService.rotate`), two refreshes of one token
 * queue on the family's primary key before either touches `ux_refresh_token_hash`, and the cycle no longer forms.
 * Measured on MariaDB 11.4 (inbox #262, rotate's statements replayed, two refreshes of one token released together
 * on near-empty tables): token-first, 17 deadlocks in 1 600 requests; family-first, none in 1 600. So the reason this
 * exists is gone. It stays only until rotate's grace path is fixed on MariaDB (that same measurement found it
 * revoking the family on every such race), and goes with that fix — at which point a reversed lock order turns the
 * H2 race tests red instead of being retried into a green run.
 *
 * **Not a general-purpose retry.** It is deliberately local to rotation, bounded, and immediate: rotation is
 * short, it is the only statement pair in this codebase that provokes the cycle, and anything longer would be
 * holding a request open to wait for a lock that is already gone.
 */
internal const val ROTATION_ATTEMPTS = 3

/** MariaDB/InnoDB: serialization failure — the transaction was chosen as the deadlock victim and rolled back. */
private const val SQLSTATE_SERIALIZATION_FAILURE = "40001"

/**
 * Run [body], retrying up to [attempts] times while it fails with a serialization failure. The last failure
 * is rethrown: a deadlock that survives three immediate attempts is not the transient one this exists for.
 */
internal fun <T> withDeadlockRetry(attempts: Int = ROTATION_ATTEMPTS, body: () -> T): T {
    require(attempts >= 1) { "attempts must be at least 1" }
    var remaining = attempts
    while (true) {
        remaining--
        try {
            return body()
        } catch (e: RuntimeException) {
            if (remaining <= 0 || !isSerializationFailure(e)) throw e
        }
    }
}

/**
 * Whether [e] is, anywhere in its cause chain, a serialization failure. The chain matters: the driver's
 * `SQLException` reaches this through a Hibernate `PessimisticLockException` and a JTA rollback exception, and
 * only the innermost one carries the SQL state.
 */
private fun isSerializationFailure(e: Throwable): Boolean {
    var cause: Throwable? = e
    val seen = mutableSetOf<Throwable>()
    while (cause != null && seen.add(cause)) {
        if (cause is SQLException && cause.sqlState == SQLSTATE_SERIALIZATION_FAILURE) return true
        cause = cause.cause
    }
    return false
}
