// The Expenses application's API client — the factories over the client generated from its own spec
// (`contracts/api/expenses/expenses.openapi.yaml` → `generated/expenses`). They live here, not in core's `api/client.ts`,
// because core imports no application client (ADR 0079; `validate_web_core_imports_no_applications.py` derives the
// directory from `generate:api`). The credentials are core's: [bearerParams] for a member, [publicParams] (none) for a guest.
import { CaseExpensesApi, Configuration as ExpensesConfiguration, ExpensesApi, ShareExpensesApi } from '../../api/generated/expenses'
import { bearerParams, publicParams } from '../../api/client'

/** A member's Pot operations (the `expenses` tag): read a Pot's revisions, append, erase a participant, the re-key part. */
export function expensesApi(): ExpensesApi {
  return new ExpensesApi(new ExpensesConfiguration(bearerParams()))
}

/** A guest's Pot operations on a share link (the `share-expenses` tag, no auth): read a Pot's revisions without their
 *  writers, and — on a `READ_WRITE` link — append. */
export function publicShareExpensesApi(): ShareExpensesApi {
  return new ShareExpensesApi(new ExpensesConfiguration(publicParams()))
}

/** A moderator's read of a case's held Pot revisions (`listCasePotRevisions`, uc.moderation.review-a-case). */
export function caseExpensesApi(): CaseExpensesApi {
  return new CaseExpensesApi(new ExpensesConfiguration(bearerParams()))
}
