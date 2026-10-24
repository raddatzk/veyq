// The node kind the Expenses application brings (ADR 0108 decision 1; docs/apps/expenses.md). The contract's `NodeKind`
// is an open token, so this is the one definition of the value in the web: every Expenses file that tests a node's kind
// compares against it. Core never imports it (`validate_web_core_imports_no_applications.py`), nor does another
// application; the backend's counterpart is `ExpensesNodeKinds.POT`.

/** A Pot: a node whose participants and entries are sealed revisions in Expenses' own table. A share root with `READ`
 *  and `READ_WRITE`, never `WRITE_ONLY`; only a member creates one. */
export const POT_KIND = 'EXPENSES_POT'
