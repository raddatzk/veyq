package com.veyq.app.shared.persistence

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.persistence.PersistenceException
import jakarta.transaction.Status

/**
 * Run the create [create] in its own transaction, and let the loser of a concurrent create answer as a replay does
 * (developer-styleguide §12, the insert half).
 *
 * A row that must exist at most once is guarded by a unique index or primary key, and the site's own "taken?" read
 * runs first as the fast path. Two creates that both read "free" before either commits still both insert, and the
 * index lets exactly one through: the loser's insert waits for the winner's uncommitted row (H2 and InnoDB alike)
 * and fails once the winner commits. Unhandled, that failure is a 500 the client cannot tell apart from a crash.
 * So when the transaction fails with a [PersistenceException] anywhere in its causes, [conflict] — the site's own
 * replay question, run in a fresh transaction that now sees the winner — names the answer. Null means the replay
 * question does not explain the failure: it is re-thrown, and a violation of some other index stays the 500 a bug
 * should look like. A failure that is no persistence failure is never re-asked. A batch that answers per item has no
 * single conflict: there the answer is its replay itself, run inside that fresh transaction (`MediaHandler`'s start).
 *
 * The helper owns the transaction, so it sits where the transaction starts — never inside an open one: a failed flush
 * marks a JTA transaction rollback-only, and nothing inside it could recover. A [create] that marks its own
 * transaction rollback-only (a refusal after a spend) is rolled back and its answer returned, as `@Transactional`
 * does.
 */
fun <T> createOrConflict(conflict: () -> T?, create: () -> T): T {
    check(QuarkusTransaction.getStatus() == Status.STATUS_NO_TRANSACTION) {
        "createOrConflict owns its transaction; call it where the transaction would start, not inside one"
    }
    return try {
        inOwnTransaction(create)
    } catch (failure: RuntimeException) {
        if (!failure.isPersistenceFailure()) throw failure
        QuarkusTransaction.requiringNew().call(conflict) ?: throw failure
    }
}

private fun <T> inOwnTransaction(body: () -> T): T {
    QuarkusTransaction.begin()
    val result = try {
        body()
    } catch (t: Throwable) {
        runCatching { QuarkusTransaction.rollback() }.exceptionOrNull()?.let(t::addSuppressed)
        throw t
    }
    if (QuarkusTransaction.isRollbackOnly()) QuarkusTransaction.rollback() else QuarkusTransaction.commit()
    return result
}

/** A commit failure arrives wrapped (the transaction's `RollbackException`, Quarkus's own exception around it), so
 *  the whole cause chain is searched. */
private fun Throwable.isPersistenceFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is PersistenceException }
