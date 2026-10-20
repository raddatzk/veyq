// Reporting (uc.moderation.report, ADR 0093 §§ 1-2): the one dialog (`ReportDialog.vue`, mounted once in App.vue beside
// the confirmation dialog, `shared/state/useConfirm.ts`'s shape) is opened from wherever something can be reported —
// a level's selection bar, a row's or a header's actions, Memories' lightbox, an upload link's page — with the items and
// a way to their keys. Sending builds the bundle here, on the client: a fresh `K_report`; per item its keys sealed under
// it (`v1.sealReportShare`); the reason and the context sealed to the published epoch's public half (`v1.sealReport`),
// and the address apart (`v1.sealReportContact`), so the server can drop it alone six months after the decision was
// told (ADR 0093 § 11). In the clear go only the category, the epoch and the items' kinds and ids — and the address
// once, for the receipt (owner decision Q6), which the server mails and does not keep.
import { ref, shallowRef } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { publicSharesApi, reportsApi } from '../../api/client'
import { readModerationKey } from './moderationKey'
import { ReportCategory, ResponseError, type ReportReceipt } from '../../api/generated/moderation'
import type { ReportSubmission } from '../../api/generated/sharing'
import { withRefresh } from '../../app/sessionRefresh'
import type { Access } from '../access'
import { decodeHttpError } from '../../shared/httpError'

/** The most items one report holds (ADR 0093 § 1); above it the dialog offers the enclosing node. */
export const MAX_REPORT_ITEMS = 100

/** Keys computed when the report is sent, never before; null when this session cannot reach them. */
export type KeysOf = () => Promise<v1.ReportKey[] | null> | v1.ReportKey[] | null

/** One thing to report: what the wire calls it (a `NAME`'s id is its node's), and a way to its keys. */
export type ReportTarget = { kind: 'NODE' | 'ITEM' | 'NAME'; id: string; keys: KeysOf } | { kind: 'LINK'; keys: KeysOf }

/** What a call site opens the dialog with. */
export interface ReportRequest {
  /** Who reports: a member through their space, a guest through the link. */
  readonly access: Access
  /** The space the items lie in (a member's report route names it). */
  readonly scopeId: string
  readonly targets: readonly ReportTarget[]
  /** Above {@link MAX_REPORT_ITEMS}: the node that holds them all, offered instead (none at a scope's top level). */
  readonly enclosing?: ReportTarget
  /**
   * One node reported: its name on its own (`NAME`), offered as the dialog's other choice — a name that is the offence
   * while what the node holds is not (ADR 0093 § 9). Absent where the name is no field this session can hand over.
   */
  readonly nameOnly?: ReportTarget
}

/** The categories the dialog offers, in the contract's order (most urgent first). */
export const REPORT_CATEGORIES: readonly ReportCategory[] = Object.values(ReportCategory)

/** The open request, or null — read by the one dialog. */
export const reportRequest = shallowRef<ReportRequest | null>(null)

/** Open the report dialog for `request`; a second open while one is shown replaces nothing. */
export function openReport(request: ReportRequest): void {
  if (!reportRequest.value) reportRequest.value = request
}

export function closeReport(): void {
  reportRequest.value = null
}

/** The epoch a report seals to and its X-Wing public half, as the server publishes it. */
export type PublishedKey = { epoch: number; kem: Uint8Array }

let published: Promise<PublishedKey | null> | null = null

/**
 * The published moderation key (`getModerationKey`, unauthenticated), read once per page and afresh on demand: null while
 * no epoch exists (`404 moderation_off`) — then nothing can be reported, and the action is not shown (ADR 0099 § 3).
 */
export function publishedKey(fresh = false): Promise<PublishedKey | null> {
  if (!published || fresh) {
    // Through a resolved promise, so a client that throws as it is built is a rejection like any other failure.
    published = Promise.resolve()
      .then(() => readModerationKey())
      .then((k) => ({ epoch: k.epoch, kem: fromB64std(k.kemPublicKey) }))
      .catch((e: unknown) => {
        published = null
        if (e instanceof ResponseError && e.response.status === 404) return null
        throw e
      })
  }
  return published
}

/** Whether the report action is shown: a published epoch exists. False until the key was read. */
export const reportOffered = ref(false)
let asked = false

/** Read the published key once, so {@link reportOffered} says whether to show the action. A failure shows none. */
export function useReportOffered() {
  if (!asked) {
    asked = true
    publishedKey().then(
      (k) => { reportOffered.value = k !== null },
      () => {
        reportOffered.value = false
        asked = false
      },
    )
  }
  return reportOffered
}

/** For a test: forget the published key, whether it was asked, and any open request. */
export function resetReporting(): void {
  published = null
  asked = false
  reportOffered.value = false
  reportRequest.value = null
}

/** What the reporter wrote. */
export interface ReportForm {
  category: ReportCategory
  reason: string
  /** An address for the receipt and the decision; '' for none. */
  contact: string
}

/** A report that was not sent or that the server refused, by its code. */
export class ReportRefused extends Error {
  constructor(readonly code: string | null) {
    super(`report_refused: ${code ?? 'unknown'}`)
    this.name = 'ReportRefused'
  }
}

/** Build the report for `targets` and send it; resolves the receipt. A key changed in between is read again once. */
export async function submitReport(request: ReportRequest, targets: readonly ReportTarget[], form: ReportForm): Promise<ReportReceipt> {
  for (let attempt = 0; ; attempt++) {
    const key = await publishedKey(attempt > 0)
    if (!key) throw new ReportRefused('moderation_off')
    const submission = await buildSubmission(request.access, key, targets, form)
    try {
      return await send(request.access, request.scopeId, submission)
    } catch (e) {
      const { code } = await decodeHttpError(e)
      if (code === 'moderation_epoch_changed' && attempt === 0) continue
      throw new ReportRefused(code)
    }
  }
}

/** The address a target's share is sealed for: the item itself, or for a LINK the link the request goes through. */
function addressOf(access: Access, t: ReportTarget): v1.ReportItemAddress {
  if (t.kind !== 'LINK') return { kind: t.kind, id: t.id }
  if (access.mode !== 'guest') throw new ReportRefused('report_keys_unavailable')
  return { kind: 'LINK', shareId: access.shareId }
}

async function buildSubmission(access: Access, key: PublishedKey, targets: readonly ReportTarget[], form: ReportForm): Promise<ReportSubmission> {
  const reportKey = v1.newReportKey()
  try {
    const items = await Promise.all(targets.map(async (t) => {
      const keys = await t.keys()
      if (!keys || keys.length === 0) throw new ReportRefused('report_keys_unavailable')
      return {
        kind: t.kind,
        ...(t.kind === 'LINK' ? {} : { id: t.id }),
        sealedShare: b64std(v1.sealReportShare(reportKey, addressOf(access, t), keys)),
      }
    }))
    const contact = form.contact.trim()
    return {
      category: form.category,
      epoch: key.epoch,
      sealedReport: b64std(v1.sealReport(key.epoch, key.kem, { reportKey, reason: form.reason, context: '' })),
      items,
      ...(contact ? { sealedContact: b64std(v1.sealReportContact(key.epoch, key.kem, contact)), receiptAddress: contact } : {}),
    }
  } finally {
    reportKey.fill(0) // K_report lives only as long as the sealing; the moderators open it from the sealed part
  }
}

async function send(access: Access, scopeId: string, reportSubmission: ReportSubmission): Promise<ReportReceipt> {
  if (access.mode === 'member') {
    return withRefresh(() => reportsApi().reportInScope({ scopeId, reportSubmission }))
  }
  return publicSharesApi().reportInShare({
    shareId: access.shareId,
    xSharePassword: access.pwToken,
    xShareWrite: b64std(v1.shareWriteToken(fromB64url(access.fragmentKey), access.shareId)),
    reportSubmission,
  })
}
