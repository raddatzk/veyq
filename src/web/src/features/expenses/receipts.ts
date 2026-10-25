// A Pot's receipts on the wire (ADR 0108 decision 7, uc.expenses.attach-receipts): images and PDFs of at most 10 MiB kept
// with an entry, each one blob in Expenses' own table. What a view does with them is the editor's; this is the wire, once.
//
// - **Upload** through core's transport, member and guest alike: `planBlob` under the Pot's key with owner = the Pot and
//   role [RECEIPT_ROLE] (the wrap is bound to blob, Pot and role, ADR 0072: no blob purpose of its own), the file's type,
//   name and modified time sealed under its `K_blob` (`wrappedBlobMeta`, the server never learns what a receipt is), the
//   pump through core's authed or guest reserve and window, then Expenses' completion against the entry. A new entry's
//   receipts are completed before its first revision; the revision lists them, sealed.
// - **Read**: the Pot's receipt listing, paged by receipt id, its first page tagged (`ETag`, `If-None-Match` → `304`).
//   Every receipt change moves the Pot's `ledgerRevision`, so the session re-reads this when its revision read changed.
// - **Bytes**: core's presign by blob id — a member's scope READ gate (`presignBlobDownloads`, a block is the item's
//   `blocked_by_operator`), a guest's link (`resolveShareBlob`, a block is `451`; in a moderator's review the case's hold,
//   `core/sharing/guestBlob.ts`) — each frame opened under the `K_blob` the Pot's key unwraps.
import { readBlobMeta, makeChunkProvider, planBlob, presignAllChunks, reserveAndPump, authedReserve, authedWindow, throwIfAborted, unwrapBlobKey, type BlobChunkProvider } from '../../core/blob/transport'
import { guestReserve, guestWindow, shareChunkUrls } from '../../core/sharing/guestBlob'
import { streamBlob, type ByteSink } from '../../core/blob/stream'
import { downloadName, type OpenedBlobMeta } from '../../core/blob/meta'
import type { BlobRef } from '../../core/blob/types'
import { expectMintedId } from '../../core/node/mintedId'
import { plaintextTotal } from '../../sw/range'
import { openSaveTarget } from '../../shared/saveStream'
import { withRefresh } from '../../app/sessionRefresh'
import { HttpStatus, isHttpStatus } from '../../shared/httpError'
import type { PotReceipt, SharePotReceipt } from '../../api/generated/expenses'
import type { Access } from '../../core/access'
import { expensesApi, publicShareExpensesApi } from './api'
import { POT_PAGE_LIMIT, type PotTarget, type PotWriteKey } from './domain/potApi'

/** The role a receipt's blob is declared and wrapped in (the backend's `EXPENSES_RECEIPT`, 16 characters). */
export const RECEIPT_ROLE = 'EXPENSES_RECEIPT'

/** The largest receipt, in plaintext bytes (the backend's role cap, `MemberBlobRoles.maxBytes`): 10 MiB. */
export const RECEIPT_MAX_BYTES = 10 * 1024 * 1024

/** How many frames a receipt's read fetches and opens at once (Depot's download bound). */
const READ_IN_FLIGHT = 4

/** One receipt as the client holds it: the listed row, decoded. `writer` is a member read's only. */
export type HeldReceipt = {
  receiptId: string
  entryId: string
  blobId: string
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
  wrappedBlobMeta: string | null
  writer?: string
  createdAt: Date
  droppedAt: Date | null
  purgeAfter: Date | null
  blockedByOperator: boolean
}

const held = (r: PotReceipt | SharePotReceipt): HeldReceipt => ({
  receiptId: r.receiptId,
  entryId: r.entryId,
  blobId: r.blobId,
  chunkCount: r.chunkCount,
  byteSize: r.byteSize,
  wrappedBlobKey: r.wrappedBlobKey,
  wrappedBlobMeta: r.wrappedBlobMeta ?? null,
  ...('writer' in r ? { writer: r.writer } : {}),
  createdAt: r.createdAt,
  droppedAt: r.droppedAt ?? null,
  purgeAfter: r.purgeAfter ?? null,
  blockedByOperator: r.blockedByOperator,
})

// --- the read ---------------------------------------------------------------------------------------------------------

/** Every receipt of a Pot and the tag of the listing's first page (`null`: none served). */
export type ReceiptsRead = { receipts: HeldReceipt[]; etag: string | null }

type ReceiptPage = { receipts: (PotReceipt | SharePotReceipt)[]; more: boolean; cursor?: string | null }

/** The page loop both reads share: a conditional first page (an unchanged listing answers `null`), later pages `after`
 *  the page before's cursor, never conditional. */
async function readPages(
  first: () => Promise<{ raw: Response; value(): Promise<ReceiptPage> }>,
  next: (after: string) => Promise<ReceiptPage>,
  conditional: boolean,
): Promise<ReceiptsRead | null> {
  let res
  try {
    res = await first()
  } catch (e) {
    if (conditional && isHttpStatus(e, HttpStatus.notModified)) return null
    throw e
  }
  const etag = res.raw.headers.get('ETag')
  let page = await res.value()
  const receipts = page.receipts.map(held)
  while (page.more) {
    const after = page.cursor
    if (!after) throw new Error('receipt_page_without_cursor') // a page that says more must say where it ends
    page = await next(after)
    if (page.more && page.cursor === after) throw new Error('receipt_page_did_not_advance')
    receipts.push(...page.receipts.map(held))
  }
  return { receipts, etag }
}

/** A member's read of a Pot's receipts. With `ifNoneMatch` (the last first page's `ETag`) an unchanged listing is `null`. */
export function readMemberReceipts(scopeId: string, nodeId: string, ifNoneMatch?: string): Promise<ReceiptsRead | null> {
  return readPages(
    () => withRefresh(() => expensesApi().listPotReceiptsRaw({ scopeId, nodeId, limit: POT_PAGE_LIMIT, ifNoneMatch })),
    (after) => withRefresh(() => expensesApi().listPotReceipts({ scopeId, nodeId, after, limit: POT_PAGE_LIMIT })),
    ifNoneMatch !== undefined,
  )
}

/** A guest's read of a Pot's receipts under its link (no writer: the guest's schema has none). */
export function readGuestReceipts(shareId: string, nodeId: string, opts: { pwToken?: string; ifNoneMatch?: string } = {}): Promise<ReceiptsRead | null> {
  const api = publicShareExpensesApi()
  return readPages(
    () => api.listSharePotReceiptsRaw({ shareId, nodeId, xSharePassword: opts.pwToken, limit: POT_PAGE_LIMIT, ifNoneMatch: opts.ifNoneMatch }),
    (after) => api.listSharePotReceipts({ shareId, nodeId, xSharePassword: opts.pwToken, after, limit: POT_PAGE_LIMIT }),
    opts.ifNoneMatch !== undefined,
  )
}

// --- the upload -------------------------------------------------------------------------------------------------------

/** The picked file a receipt is made of: its bytes are read frame by frame, its type, name and time sealed in its meta. */
export type ReceiptFile = Pick<File, 'size' | 'type' | 'name' | 'lastModified' | 'slice'>

/**
 * Seal and upload one picked file as the receipt [receiptId] of the entry [entryId] in the Pot [target] (see the header),
 * under the Pot's key and epoch [key]. Throws the transport's codes (`budget_exceeded` — over 10 MiB, already at the
 * start —, `quota_exceeded`, `capability_disabled`, …) and the completion's refusals as the generated client throws them
 * (`stale_epoch` / `node_rekey_pending` are the caller's re-read, `writeAtNodeEpoch`).
 */
export async function uploadReceipt(
  target: PotTarget,
  key: PotWriteKey,
  entryId: string,
  receiptId: string,
  file: ReceiptFile,
  opts: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<HeldReceipt> {
  const { scopeId, nodeId } = target
  const plan = planBlob(scopeId, { ownerId: nodeId, role: RECEIPT_ROLE }, key.key, file, { type: file.type, name: file.name, modifiedAt: file.lastModified })
  let receipt: PotReceipt | SharePotReceipt
  if (target.kind === 'member') {
    const [uploadId] = await reserveAndPump([plan], (d) => authedReserve(scopeId, d), authedWindow(scopeId), opts.onProgress, opts.signal)
    throwIfAborted(opts.signal)
    receipt = await withRefresh(() =>
      expensesApi().completePotReceipt({ scopeId, nodeId, potReceiptCompleteRequest: { nodeEpoch: key.epoch, entryId, receiptId, uploadId } }),
    )
  } else {
    const to = { shareId: target.shareId, contributorToken: target.contributorToken, pwToken: target.pwToken, nodeId }
    const [uploadId] = await reserveAndPump([plan], (d) => guestReserve(to, d), guestWindow(to), opts.onProgress, opts.signal)
    throwIfAborted(opts.signal)
    receipt = await publicShareExpensesApi().completeSharePotReceipt({
      shareId: target.shareId,
      nodeId,
      xSharePassword: target.pwToken,
      sharePotReceiptCompleteRequest: { nodeEpoch: key.epoch, contributorToken: target.contributorToken, entryId, receiptId, uploadId },
    })
  }
  expectMintedId(plan.blobId, receipt.blobId)
  expectMintedId(receiptId, receipt.receiptId)
  return held(receipt)
}

// --- the bytes --------------------------------------------------------------------------------------------------------

/** The transport's ref for a receipt's blob: the listed row, bound to the Pot it was listed for and the receipt role —
 *  a row the server moved here from another Pot does not open. */
export function receiptBlobRef(potId: string, r: Pick<HeldReceipt, 'blobId' | 'chunkCount' | 'byteSize' | 'wrappedBlobKey' | 'wrappedBlobMeta'>): BlobRef {
  return { blobId: r.blobId, chunkCount: r.chunkCount, byteSize: r.byteSize, wrappedBlobKey: r.wrappedBlobKey, wrappedBlobMeta: r.wrappedBlobMeta, ownerId: potId, role: RECEIPT_ROLE }
}

/** A receipt's plaintext size: its sealed size less every frame's overhead (exact). */
export const receiptSize = (r: Pick<HeldReceipt, 'byteSize' | 'chunkCount'>): number => Math.max(0, plaintextTotal(r.byteSize, r.chunkCount))

/** A receipt's sealed meta (type, name, modified time) for display; null when it carries none or it does not open. */
export function receiptMeta(scopeId: string, potKey: Uint8Array, potId: string, r: HeldReceipt): OpenedBlobMeta | null {
  return readBlobMeta(scopeId, potKey, receiptBlobRef(potId, r))
}

/** A receipt's `K_blob` (for a report share): throws `OpenRefused` for a row that is not this Pot's. */
export function receiptKeyOf(scopeId: string, potKey: Uint8Array, potId: string, r: HeldReceipt): Uint8Array {
  return unwrapBlobKey(scopeId, potKey, receiptBlobRef(potId, r))
}

/** Who presigns a receipt's chunks for whoever looks: a member's scope READ gate, or a guest's link. */
export function receiptPresign(access: Access, scopeId: string): (blobId: string) => Promise<string[]> {
  if (access.mode === 'guest') return (blobId) => shareChunkUrls(access.shareId, blobId, access.pwToken)
  return (blobId) => presignAllChunks(scopeId, blobId)
}

/** The provider over a receipt's chunks: `K_blob` unwrapped at once (`OpenRefused` for a row that is not this Pot's), the
 *  chunks presigned on first read. */
export function receiptChunks(scopeId: string, potKey: Uint8Array, potId: string, r: HeldReceipt, presign: (blobId: string) => Promise<string[]>): BlobChunkProvider {
  return makeChunkProvider(scopeId, potKey, receiptBlobRef(potId, r), () => presign(r.blobId))
}

/** Read a provider's whole plaintext: a receipt is at most 10 MiB, so its view holds it (never a download, which streams). */
export async function readAll(provider: Pick<BlobChunkProvider, 'chunkCount' | 'getPlaintextChunk'>, signal?: AbortSignal): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  const collect: ByteSink = { write: async (b) => void parts.push(b), close: async () => {}, abort: async () => {} }
  await streamBlob(provider, collect, { inFlight: READ_IN_FLIGHT, signal })
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

/** The name a receipt is saved under when it sealed none. */
const UNNAMED = 'receipt'

/** A receipt's file name: the one sealed in its meta, the extension its sealed type names when the name has none. */
export const receiptName = (meta: OpenedBlobMeta | null): string => downloadName(null, meta, () => UNNAMED)

/**
 * Decrypt a receipt and save it to the device under [receiptName], streamed into the target `openSaveTarget` picks. Call
 * it straight from the click with nothing awaited before (the save picker needs the click's activation). The type goes no
 * further than the name: the save target stays `application/octet-stream`.
 */
export async function downloadReceipt(provider: BlobChunkProvider, size: number): Promise<void> {
  const sink = await openSaveTarget(receiptName(provider.meta()), size)
  await streamBlob(provider, sink, { inFlight: READ_IN_FLIGHT })
}

// --- what a view opens ------------------------------------------------------------------------------------------------

/** One receipt as a view opens it, whoever looks (a member, a guest, a moderator): its name and size, a whole read of its
 *  plaintext for the view, and a streamed download. Built once per receipt row; nothing is fetched until asked. */
export type ReceiptSource = {
  id: string
  name: string
  size: number
  blocked: boolean
  read(signal?: AbortSignal): Promise<Uint8Array>
  download(): Promise<void>
}

/** A refused read of a blocked receipt: a member's presign item `blocked_by_operator`, a guest's `451`. The listing marks
 *  a block, so a view rarely meets this — only a block placed after its last read. */
export function isBlockedRead(e: unknown): boolean {
  if (e instanceof Error && e.message === 'blocked_by_operator') return true
  return (e as { response?: { status?: unknown } } | null)?.response?.status === 451
}

/** The source of a receipt of the Pot [potId] under its key [potKey], presigned for [access] ([receiptPresign]). */
export function potReceiptSource(access: Access, scopeId: string, potId: string, potKey: Uint8Array, r: HeldReceipt): ReceiptSource {
  const presign = receiptPresign(access, scopeId)
  const provider = () => receiptChunks(scopeId, potKey, potId, r, presign)
  const size = receiptSize(r)
  return {
    id: r.receiptId,
    name: receiptName(receiptMeta(scopeId, potKey, potId, r)),
    size,
    blocked: r.blockedByOperator,
    read: (signal) => readAll(provider(), signal),
    download: () => downloadReceipt(provider(), size),
  }
}
