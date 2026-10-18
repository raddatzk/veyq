// A moderator's cases (uc.moderation.review-a-case, uc.moderation.take-a-measure): the queue, one case, and every
// decision — each a key-proved call over its own operation and its case (`proofFor`), as the server's gate rebuilds the
// statement from what it matched. The texts the server must never read stay sealed in the browser: a report opens with
// its epoch's key (`moderation-report` [epoch]), a note seals to the current epoch bound to its case
// (`moderation-note` [epoch, caseId]) and opens with the epoch it was sealed to. A measure's statement of reasons
// (uc.moderation.object-to-a-measure) seals its facts twice: to the moderation key, and to each affected member's identity
// the server lists for that step (`moderation-facts` [caseId, accountId]); an objection opens with its epoch's key.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { moderationApi } from '../../api/client'
import type {
  ModerationCase,
  ModerationCaseExport,
  ModerationCaseState,
  ModerationCaseSummary,
  ModerationGround,
  ModerationHandoverKindEnum,
  ModerationStatementDeferral,
  ModerationProposalStep,
  ReporterNotice,
  SealedFactsFor,
  SealedModerationNote,
} from '../../api/generated/moderation'
import { withRefresh } from '../../app/sessionRefresh'
import { currentModerationKey, moderationKeyOf } from './moderationKeys'
import { ModerationKeyMissing, proofFor } from './proof'

/** The queue — the open and decided cases, or one state's — urgent first (the server's order). */
export async function listCases(state?: ModerationCaseState): Promise<ModerationCaseSummary[]> {
  const proof = await proofFor('listModerationCases')
  return (await withRefresh(() => moderationApi().listModerationCases({ state, ...proof }))).cases
}

/** One case: its sealed reports, measures, proposals, hold, targets and log. */
export async function getCase(caseId: string): Promise<ModerationCase> {
  const proof = await proofFor('getModerationCase', caseId)
  return withRefresh(() => moderationApi().getModerationCase({ caseId, ...proof }))
}

/** Seal [text] to the current epoch, bound to [caseId] — a decision's note or an extension's reason; null for no text. */
export function sealNote(caseId: string, text: string): SealedModerationNote | null {
  if (text.trim() === '') return null
  const held = currentModerationKey()
  if (!held) throw new ModerationKeyMissing()
  try {
    const kem = v1.moderationBundle(held.key).kem
    return { epoch: held.epoch, sealedNote: b64std(v1.sealModerationNote(held.epoch, caseId, kem, text)) }
  } finally {
    held.key.fill(0)
  }
}

/** Open a note sealed to [epoch] for [caseId]; null when this account holds no key of that epoch or it does not open. */
export function openNote(epoch: number, caseId: string, sealed: string): string | null {
  const m = moderationKeyOf(epoch)
  if (!m) return null
  try {
    return v1.openModerationNote(epoch, caseId, v1.moderationKemSecret(m), fromB64std(sealed))
  } catch {
    return null
  } finally {
    m.fill(0)
  }
}

/** What a reporter wrote, opened: the reason, the contact and the context — and `K_report`, which opens the report shares. */
export type OpenedReport = v1.ReportBody

/** Open a report's sealed part with its epoch's key; null when not held or it does not open. */
export function openReportOf(epoch: number, sealedReport: string): OpenedReport | null {
  const m = moderationKeyOf(epoch)
  if (!m) return null
  try {
    return v1.openReport(epoch, v1.moderationKemSecret(m), fromB64std(sealedReport))
  } catch {
    return null
  } finally {
    m.fill(0)
  }
}

/** An address the server can mail: a contact a reporter left is free text, and only an address is sent on. */
const MAILABLE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/**
 * The reporters of [c] to tell a decision (ADR 0093 § 2): each report this page opens whose contact is an address. The
 * address leaves the browser once, beside the decision; the server mails it from memory and keeps it nowhere.
 */
export function reporterNoticesOf(c: ModerationCase): ReporterNotice[] {
  const out: ReporterNotice[] = []
  for (const r of c.reports) {
    const body = openReportOf(r.epoch, r.sealedReport)
    if (!body) continue
    body.reportKey.fill(0)
    const address = body.contact.trim()
    if (MAILABLE.test(address) && address.length <= 254) out.push({ reportId: r.id, address })
  }
  return out
}

export async function dismissCase(caseId: string, note: string, reporterNotices: ReporterNotice[] = []): Promise<ModerationCase> {
  const body = { note: sealNote(caseId, note), reporterNotices }
  const proof = await proofFor('dismissModerationCase', caseId)
  return withRefresh(() => moderationApi().dismissModerationCase({ caseId, moderationDismissal: body, ...proof }))
}

export type MeasureKind = 'SHARE' | 'CONTENT' | 'GUEST_IDENTITY'

/**
 * What a step's statement carries: the ground, the facts the moderator wrote (empty for none), and — only when an
 * authority asked for it (ADR 0093 § 7) — its deferral: the end, and the authority's request, sealed as its reason.
 */
export type StatementDraft = { ground: ModerationGround; facts: string; deferral?: { until: Date; reason: string } | null }

/**
 * The facts sealed for every member [kind] on [target] would tell — the server's list, read with a proof over the case.
 * Sealed to the identity it serves, unpinned: the moderation surface holds no Master Key to read a pin store (ADR 0099
 * § 6), and a substituted key would win the operator nothing the moderation key's copy of the same facts does not.
 */
async function factsFor(caseId: string, kind: MeasureKind, target: { shareId?: string; targetId?: string }, facts: string): Promise<SealedFactsFor[]> {
  const proof = await proofFor('getModerationStatementRecipients', caseId)
  const recipients = await withRefresh(() =>
    moderationApi().getModerationStatementRecipients({ caseId, kind, shareId: target.shareId, targetId: target.targetId, ...proof }),
  )
  return recipients.accounts.map((a) => {
    const identity = v1.deserializeIdentityPublic(fromB64std(a.identityPublicKey))
    return { accountId: a.accountId, sealedFacts: b64std(v1.sealModerationFacts(caseId, a.accountId, identity.kem, facts)) }
  })
}

/**
 * A moderator's step: `SHARE` names a link ([shareId]); `CONTENT` the reported item and `GUEST_IDENTITY` a guest
 * ([targetId]) — with its statement of reasons, and the reporters to tell the decision.
 */
export async function takeMeasure(
  caseId: string,
  kind: MeasureKind,
  target: { shareId?: string; targetId?: string },
  note: string,
  statement: StatementDraft,
  reporterNotices: ReporterNotice[] = [],
): Promise<ModerationCase> {
  const facts = sealNote(caseId, statement.facts)
  const sealedFor = facts ? await factsFor(caseId, kind, target, statement.facts) : []
  const deferral = statement.deferral ? deferralOf(caseId, statement.deferral.until, statement.deferral.reason) : null
  const body = {
    kind,
    shareId: target.shareId ?? null,
    targetId: target.targetId ?? null,
    note: sealNote(caseId, note),
    statement: { ground: statement.ground, facts, factsFor: sealedFor, deferral },
    reporterNotices,
  }
  const proof = await proofFor('takeModerationMeasure', caseId)
  return withRefresh(() => moderationApi().takeModerationMeasure({ caseId, moderationMeasureRequest: body, ...proof }))
}

/** Decide an objection (ADR 0099 § 5): `UPHOLD` keeps the measure, `LIFT` lifts it. */
export async function decideObjection(caseId: string, objectionId: string, outcome: 'UPHOLD' | 'LIFT', note: string): Promise<ModerationCase> {
  const body = { outcome, note: sealNote(caseId, note) }
  const proof = await proofFor('decideModerationObjection', caseId)
  return withRefresh(() => moderationApi().decideModerationObjection({ caseId, objectionId, moderationObjectionDecision: body, ...proof }))
}

/** Open an objection with its epoch's key, at the statement it answers; null when not held or it does not open. */
export function openObjectionOf(epoch: number, statementId: string, sealed: string): string | null {
  const m = moderationKeyOf(epoch)
  if (!m) return null
  try {
    return v1.openObjection(epoch, statementId, v1.moderationKemSecret(m), fromB64std(sealed))
  } catch {
    return null
  } finally {
    m.fill(0)
  }
}

export async function liftMeasure(caseId: string, measureId: string, note: string): Promise<ModerationCase> {
  const body = { note: sealNote(caseId, note) }
  const proof = await proofFor('liftModerationMeasure', caseId)
  return withRefresh(() => moderationApi().liftModerationMeasure({ caseId, measureId, moderationDecisionNote: body, ...proof }))
}

export async function proposeStep(caseId: string, step: ModerationProposalStep, targetId: string | null, note: string): Promise<ModerationCase> {
  const body = { step, targetId, note: sealNote(caseId, note) }
  const proof = await proofFor('proposeModerationStep', caseId)
  return withRefresh(() => moderationApi().proposeModerationStep({ caseId, moderationProposalRequest: body, ...proof }))
}

export async function extendHold(caseId: string, until: Date, reason: string): Promise<ModerationCase> {
  const sealed = sealNote(caseId, reason)
  if (!sealed) throw new Error('extension_reason_required')
  const proof = await proofFor('extendModerationHold', caseId)
  return withRefresh(() => moderationApi().extendModerationHold({ caseId, moderationHoldExtension: { until, reason: sealed }, ...proof }))
}

/** An authority's deferral with its request sealed as the reason; the request is required — deferring is never the moderator's call. */
function deferralOf(caseId: string, until: Date, reason: string): ModerationStatementDeferral {
  const sealed = sealNote(caseId, reason)
  if (!sealed) throw new Error('deferral_reason_required')
  return { until, reason: sealed }
}

/** Renew the authority's deferral of [statementId] once (ADR 0093 § 7), its renewed request sealed as the reason. */
export async function renewDeferral(caseId: string, statementId: string, until: Date, reason: string): Promise<ModerationCase> {
  const body = deferralOf(caseId, until, reason)
  const proof = await proofFor('deferModerationStatement', caseId)
  return withRefresh(() => moderationApi().deferModerationStatement({ caseId, statementId, moderationStatementDeferral: body, ...proof }))
}

/** Record a handover to an authority (ADR 0093 §§ 11-13): `REPORTED` or `INFORMATION_GIVEN`, the reference sealed. */
export async function recordHandover(caseId: string, kind: ModerationHandoverKindEnum, reference: string): Promise<ModerationCase> {
  const note = sealNote(caseId, reference)
  if (!note) throw new Error('handover_reference_required')
  const proof = await proofFor('recordModerationHandover', caseId)
  return withRefresh(() => moderationApi().recordModerationHandover({ caseId, moderationHandover: { kind, note }, ...proof }))
}

/** The clear fields an Art. 18 report asks for (ADR 0093 § 13) — what the server holds, never content. */
export async function getExport(caseId: string): Promise<ModerationCaseExport> {
  const proof = await proofFor('getModerationCaseExport', caseId)
  return withRefresh(() => moderationApi().getModerationCaseExport({ caseId, ...proof }))
}

export async function barReporter(caseId: string, reportId: string, note: string): Promise<ModerationCase> {
  const body = { note: sealNote(caseId, note) }
  const proof = await proofFor('barModerationReporter', caseId)
  return withRefresh(() => moderationApi().barModerationReporter({ caseId, reportId, moderationDecisionNote: body, ...proof }))
}

/**
 * Where a case's review opens (ADR 0099, decided with the owner 3): the reported tenant's host, keeping this page's
 * protocol and port; this host when the server names none (the tenant is gone, or suspended — its host serves nothing).
 * The fragment `#moderation` is what turns the share viewer into its moderation mode; it carries no key.
 */
export function reviewUrl(caseId: string, tenantDomain: string | null, here: Location = window.location): string {
  const url = new URL(here.href)
  if (tenantDomain) url.hostname = tenantDomain
  url.pathname = `/s/${caseId}`
  url.search = ''
  url.hash = 'moderation'
  return url.toString()
}
