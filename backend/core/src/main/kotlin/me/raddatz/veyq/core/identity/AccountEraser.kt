package me.raddatz.veyq.core.identity

/**
 * What goes with an account when `AccountPurge` erases it (ADR 0101 decisions 2-4): the package that owns rows keyed on
 * an account deletes that account's rows here, so the purge names no table of an application (ADR 0079). Called inside
 * the purge's one transaction, after the account's own scopes were erased and before the account row goes; a failure
 * rolls the whole erasure back, so an account is never half erased.
 *
 * [columns] classifies every column of the module that carries an account id **without** a foreign key (ADR 0101
 * decision 3): [ERASED] where this eraser clears it, [kept] with the reason where it stays — content attribution, which
 * belongs to the scope and shows as a former member's, or a moderation record the law keeps. The classification is code,
 * not prose: `scripts/validate_account_erasure_arity.py` reads it and refuses a column it does not find there.
 *
 * An implementation deletes by account id only, takes no lock beyond the purge's account-row lock, and never calls the
 * blob store (a row that names store objects is recorded for reclaim by core, as the scope erasure does).
 */
interface AccountEraser {
    val columns: Map<String, ColumnFate>

    fun eraseAccount(accountId: String)
}

/** What the account erasure does with one column that names an account without a foreign key. */
sealed interface ColumnFate

/** The eraser clears it: the rows are deleted, or the column is set to nothing. */
data object ERASED : ColumnFate

/** The column stays, under the opaque id, for the reason given (ADR 0101 decisions 5 and 7). */
data class Kept(val reason: String) : ColumnFate

fun kept(reason: String): ColumnFate = Kept(reason)
