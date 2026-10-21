// uc.admin.suspend-tenant A5–A6, uc.admin.delete-tenant step 4, uc.moderation.object-to-a-measure A7 (admin-web, ADR 0093
// § 7) — what a Server Owner's step over content answered about its statement's mail. The server mails the facts once
// and keeps no clear copy, so a recipient the mail did not reach is the Server Owner's to tell through the instance
// contact — with the words this client still holds, which is why the report keeps the statement as it was sent beside
// the answer. The console and `veyq-admin` read the outcome as served; neither derives one.
import { StatementDeliveryOutcomeEnum, type StatementDelivery } from '../../api/generated/instance'
import type { ServerOwnerStatementInput } from './stepStatement'

export type { StatementDelivery } from '../../api/generated/instance'
export { StatementDeliveryOutcomeEnum as StatementOutcome } from '../../api/generated/instance'

/** What the console keeps in view after a step whose statement did not plainly reach everyone. */
export interface StatementReport {
  delivery: StatementDelivery
  statement: ServerOwnerStatementInput
}

/**
 * The report to keep in view, or null: when the step carried no statement, or its mail reached everyone (a toast says
 * the step was taken, as before).
 */
export function reportOf(delivery: StatementDelivery | undefined, statement: ServerOwnerStatementInput | null): StatementReport | null {
  if (!delivery || !statement || delivery.outcome === StatementDeliveryOutcomeEnum.Reached) return null
  return { delivery, statement }
}
