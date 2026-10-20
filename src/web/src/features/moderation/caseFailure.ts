// Which message a failed case read or decision shows (uc.moderation.review-a-case, uc.moderation.take-a-measure): every
// code the moderation spec names for those operations, mapped to an i18n key under `moderation.errors`. A refused proof
// and a changed epoch say the same thing — this page's key no longer answers — so both send the moderator to reload and
// unlock again; so does a key this page no longer holds.
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'
import { ModerationKeyMissing } from './proof'

const byCode: Record<string, string> = {
  moderation_proof_invalid: 'moderation.errors.reload',
  moderation_epoch_changed: 'moderation.errors.reload',
  not_moderator: 'moderation.errors.notModerator',
  case_not_found: 'moderation.errors.caseNotFound',
  case_not_open: 'moderation.errors.caseNotOpen',
  case_closed: 'moderation.errors.caseClosed',
  moderation_target_unknown: 'moderation.errors.targetUnknown',
  moderation_measure_exists: 'moderation.errors.measureExists',
  measure_not_found: 'moderation.errors.measureNotFound',
  moderation_proposal_exists: 'moderation.errors.proposalExists',
  extension_out_of_bounds: 'moderation.errors.extensionOutOfBounds',
  nothing_held: 'moderation.errors.nothingHeld',
  report_not_found: 'moderation.errors.reportNotFound',
  invalid_request: 'moderation.errors.invalidRequest',
  statement_facts_required: 'moderation.errors.factsRequired',
  statement_provision_required: 'moderation.errors.provisionRequired',
  extension_reason_not_allowed: 'moderation.errors.extendTypeNotAllowed',
  statement_recipients_changed: 'moderation.errors.recipientsChanged',
  statement_recipients_too_many: 'moderation.errors.recipientsTooMany',
  objection_not_found: 'moderation.errors.objectionNotFound',
  objection_decided: 'moderation.errors.objectionDecided',
  objection_needs_another_moderator: 'moderation.errors.needsAnotherModerator',
  // an authority's order and the deferral (uc.moderation.authority-order)
  deferral_out_of_bounds: 'moderation.errors.deferralOutOfBounds',
  statement_not_found: 'moderation.errors.statementNotFound',
  statement_not_deferred: 'moderation.errors.statementNotDeferred',
  deferral_renewed: 'moderation.errors.deferralRenewed',
  order_exists: 'moderation.errors.orderExists',
}

/** This surface's own refusals, before anything is sent: a text the moderator must give. */
const byMessage: Record<string, string> = {
  extension_reason_required: 'moderation.errors.reasonRequired',
  deferral_reason_required: 'moderation.errors.reasonRequired',
  handover_reference_required: 'moderation.errors.orderReference',
  'order_invalid:share': 'moderation.errors.orderShare',
  'order_invalid:reference': 'moderation.errors.orderReference',
}

/** The i18n key for [e], or [fallback] when the failure names no code this surface knows. */
export async function caseFailureKey(e: unknown, fallback = 'moderation.errors.failed'): Promise<string> {
  if (e instanceof ModerationKeyMissing) return 'moderation.errors.reload'
  if (e instanceof Error && byMessage[e.message]) return byMessage[e.message]
  if (isUnauthorized(e)) return 'moderation.errors.signedOut'
  const { code } = await decodeHttpError(e)
  return (code && byCode[code]) || fallback
}
