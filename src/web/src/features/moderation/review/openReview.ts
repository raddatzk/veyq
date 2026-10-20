// The share viewer's moderation mode, its data half (uc.moderation.review-a-case steps 4-5; ADR 0093 § 3, ADR 0099 § 6):
// fetch the case's review with a key proof, open each report with its epoch's key and each report share with that
// report's `K_report`, and turn what they hand over into what the guest viewer reads — a head whose root key came from
// the report share (no `K_share`, no wrap), and the review's own data source (`core/sharing/review.ts`), whose byte reads
// go to `presignCaseBlobs` in batches (each call spends a key proof).
//
// Nothing here ever calls a guest endpoint: the review's reads are the moderation spec's and the review tags'.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { caseReviewApi } from '../../../api/client'
import { ShareRole, type CaseReview } from '../../../api/generated/sharing'
import { withRefresh } from '../../../app/sessionRefresh'
import { SHARE_MEMORY_NAME, SHARE_NOTE, type PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import type { ShareReview } from '../../../core/sharing/review'
import { openReportOf, type OpenedReport } from '../cases'
import { proofFor } from '../proof'

/** One report of the case, opened (null: this account holds no key of its epoch, or it does not open). */
export type ReviewedReport = { reportId: string; receivedAt: Date; body: OpenedReport | null }

/** A case's review, opened in the browser. */
export type OpenedReview = {
  review: CaseReview
  reports: ReviewedReport[]
  /** The keys the report shares handed over — every report share of the case names the same item; the first that opens
   *  carries them all. Empty when none opened. Key material. */
  keys: v1.ReportKey[]
}

/** Fetch a case's review: the report shares and the held snapshot (the server logs the opening). */
export async function fetchReview(caseId: string): Promise<CaseReview> {
  const proof = await proofFor('getCaseReview', caseId)
  return withRefresh(() => caseReviewApi().getCaseReview({ caseId, ...proof }))
}

/** The item a report share is sealed addressed by (`report-share` [itemKind, itemId] or `report-link` [shareId]). */
function addressOf(review: CaseReview): v1.ReportItemAddress {
  return review.itemKind === 'LINK' ? { kind: 'LINK', shareId: review.itemId } : { kind: review.itemKind, id: review.itemId }
}

/** Open every report and the keys its report share hands over. */
export function openReview(review: CaseReview): OpenedReview {
  const address = addressOf(review)
  let keys: v1.ReportKey[] = []
  const reports = review.reports.map((r) => {
    const body = openReportOf(r.epoch, r.sealedReport, r.sealedContact)
    if (body && keys.length === 0) {
      try {
        keys = v1.openReportShare(body.reportKey, address, fromB64std(r.sealedShare))
      } catch {
        /* a report share that does not open hands over nothing; the next report's may */
      }
    }
    return { reportId: r.reportId, receivedAt: r.receivedAt, body }
  })
  return { review, reports, keys }
}

/** The key of [role] (and [ref]) the report shares handed over, or null. */
export function keyOf(opened: OpenedReview, role: string, ref: string | null = null): Uint8Array | null {
  return opened.keys.find((k) => k.role === role && (ref === null || k.ref === ref))?.key ?? null
}

const td = new TextDecoder()

/** A field opened with the `K_field` a report handed over, or null. */
function openWith(context: v1.FieldContext, key: Uint8Array | null, envelope: string | null | undefined): string | null {
  if (!key || !envelope) return null
  try {
    return td.decode(v1.openFieldWith(context, key, fromB64std(envelope)))
  } catch {
    return null
  }
}

/**
 * The reported node's name: its own held name with the report's `NODE_NAME` field key, or — a guest's report of a link's
 * root — the name of the held link its `LINK_NAME` field key opens. Null when neither opens.
 */
export function rootNameOf(opened: OpenedReview): string | null {
  const { review } = opened
  const root = review.share.nodes.find((n) => n.parentId == null)
  if (root?.encryptedName != null && root.placementRevision != null && review.share.scopeId) {
    const ctx = {
      purpose: 'node-name', scopeId: review.share.scopeId, nodeId: root.id, epoch: root.epoch, placementRevision: root.placementRevision,
    } as const
    const name = openWith(ctx, keyOf(opened, 'NODE_NAME'), root.encryptedName)
    if (name !== null) return name
  }
  const linkName = keyOf(opened, 'LINK_NAME')
  const links = [review.share.sealedName, ...review.heldLinks.map((l) => l.sealedName)]
  for (const sealed of links) {
    const name = openWith(SHARE_MEMORY_NAME, linkName, sealed)
    if (name !== null) return name
  }
  return null
}

/** A reported upload link's note, opened with the report's `LINK_NOTE` field key (JSON per locale), or null. */
export function linkNoteOf(opened: OpenedReview): string | null {
  return openWith(SHARE_NOTE, keyOf(opened, 'LINK_NOTE'), opened.review.share.sealedNote)
}

/**
 * The head the guest viewer renders a reported node's snapshot from: the held share, READ, the root key from the
 * report share in place of a wrap. Null when no report share opened a node key (a medium or a link has none).
 */
export function reviewHead(caseId: string, opened: OpenedReview, unnamed: string): PublicShareHead | null {
  const rootKey = keyOf(opened, 'NODE')
  const share = opened.review.share
  if (!rootKey || !share.scopeId) return null
  return {
    shareId: caseId, share, kShare: new Uint8Array(32), scopeId: share.scopeId, name: rootNameOf(opened) ?? unnamed,
    noteByLocale: {}, role: ShareRole.Read, rootKind: share.rootKind ?? null, rootKey,
  }
}

/** The page's data source while it reviews [caseId]: byte reads batched into `presignCaseBlobs`, listings key-proved. */
export function reviewSource(caseId: string, batchDelayMs = 25): ShareReview {
  type Waiter = { resolve: (urls: string[]) => void; reject: (e: unknown) => void }
  let pending = new Map<string, Waiter[]>()
  let timer: ReturnType<typeof setTimeout> | null = null

  async function flush() {
    const batch = pending
    pending = new Map()
    timer = null
    const ids = [...batch.keys()]
    for (let i = 0; i < ids.length; i += 500) {
      const slice = ids.slice(i, i + 500)
      try {
        const proof = await proofFor('presignCaseBlobs', caseId)
        const res = await withRefresh(() => caseReviewApi().presignCaseBlobs({ caseId, caseBlobsRequest: { blobIds: slice }, ...proof }))
        const urls = new Map(res.blobs.map((b) => [b.blobId, [...b.presignedGets].sort((x, y) => x.index - y.index).map((u) => u.url)]))
        for (const id of slice) {
          const got = urls.get(id)
          for (const w of batch.get(id) ?? []) {
            if (got) w.resolve(got)
            else w.reject(new Error('not_held'))
          }
        }
      } catch (e) {
        for (const id of slice) for (const w of batch.get(id) ?? []) w.reject(e)
      }
    }
  }

  return {
    caseId,
    chunkUrls: (blobId) =>
      new Promise<string[]>((resolve, reject) => {
        pending.set(blobId, [...(pending.get(blobId) ?? []), { resolve, reject }])
        if (timer === null) timer = setTimeout(() => void flush(), batchDelayMs)
      }),
    proof: (operationId) => proofFor(operationId, caseId),
  }
}
