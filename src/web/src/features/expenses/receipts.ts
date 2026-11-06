// A Pot's receipts (ADR 0108 decision 7, uc.expenses.attach-receipts): images and PDFs of at most 10 MiB kept with an
// entry — each a blob of core's blob service (ADR 0112) in the role [RECEIPT_ROLE], held by its entry as its owner. The
// wire is core's (`core/blob/applicationBlobs.ts`: the upload, the paged and tagged read, the presign, the whole read
// and the streamed download); this is Expenses' view of it — a listed blob as a receipt of an entry, its role and cap,
// and what a view opens.
//
// A new entry's receipts are completed before its first revision; the revision lists them, sealed, and tells the
// service that the entry keeps them (the server's half, `PotRevisionStore`). A receipt a revision dropped is released for
// good: no listing of its entry shows it again ([HeldReceipt.droppedAt]).
import {
  applicationBlobChunks,
  applicationBlobKeyOf,
  applicationBlobMeta,
  applicationBlobPresign,
  applicationBlobSize,
  downloadApplicationBlob,
  isBlockedRead as isBlockedBlobRead,
  readAll,
  readGuestApplicationBlobs,
  readMemberApplicationBlobs,
  releasedForGood,
  uploadApplicationBlob,
  type HeldApplicationBlob,
} from '../../core/blob/applicationBlobs'
import type { BlobChunkProvider } from '../../core/blob/transport'
import { downloadName, type OpenedBlobMeta } from '../../core/blob/meta'
import type { BlobSource } from '../../core/blob/types'
import type { Access } from '../../core/access'
import type { ShareAt } from '../../core/sharing/shareAt'
import type { PotTarget, PotWriteKey } from './domain/potApi'

/** The role a receipt's blob is declared and wrapped in (the backend's `EXPENSES_RECEIPT`, 16 characters). */
export const RECEIPT_ROLE = 'EXPENSES_RECEIPT'

/** The largest receipt, in plaintext bytes (the backend's role cap, `MemberBlobRoles.maxBytes`): 10 MiB. */
export const RECEIPT_MAX_BYTES = 10 * 1024 * 1024

export { readAll }

/** One receipt as the client holds it: the listed blob, as a receipt of its entry. `writer` is a member read's only. */
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
  /** When a revision of its entry dropped it — released by name, for good; null while none did. */
  droppedAt: Date | null
  purgeAfter: Date | null
  blockedByOperator: boolean
}

const receiptOf = (b: HeldApplicationBlob): HeldReceipt => ({
  receiptId: b.id,
  entryId: b.ownerId,
  blobId: b.blobId,
  chunkCount: b.chunkCount,
  byteSize: b.byteSize,
  wrappedBlobKey: b.wrappedBlobKey,
  wrappedBlobMeta: b.wrappedBlobMeta,
  ...(b.writer !== undefined ? { writer: b.writer } : {}),
  createdAt: b.createdAt,
  droppedAt: releasedForGood(b) ? b.releasedAt : null,
  purgeAfter: b.purgeAfter,
  blockedByOperator: b.blockedByOperator,
})

// --- the read ---------------------------------------------------------------------------------------------------------

/** Every receipt of a Pot and the tag of the listing's first page (`null`: none served). */
export type ReceiptsRead = { receipts: HeldReceipt[]; etag: string | null }

/** A Pot's receipts among its blobs: the service's read of the node, its receipts' role only. */
const asReceipts = (read: { blobs: HeldApplicationBlob[]; etag: string | null } | null): ReceiptsRead | null =>
  read && { receipts: read.blobs.filter((b) => b.role === RECEIPT_ROLE).map(receiptOf), etag: read.etag }

/** A member's read of a Pot's receipts. With `ifNoneMatch` (the last first page's `ETag`) an unchanged listing is `null`. */
export async function readMemberReceipts(scopeId: string, nodeId: string, ifNoneMatch?: string): Promise<ReceiptsRead | null> {
  return asReceipts(await readMemberApplicationBlobs(scopeId, nodeId, ifNoneMatch))
}

/** A guest's read of a Pot's receipts under its link (no writer: the guest's schema has none). */
export async function readGuestReceipts(at: ShareAt, nodeId: string, opts: { pwToken?: string; ifNoneMatch?: string } = {}): Promise<ReceiptsRead | null> {
  return asReceipts(await readGuestApplicationBlobs(at, nodeId, opts))
}

// --- the upload -------------------------------------------------------------------------------------------------------

/** The file a receipt is made of — a picked one, or an import's payload (a ZIP's, or a copy's original read by frame):
 *  its bytes are read frame by frame, its type, name and time sealed in its meta. */
export type ReceiptFile = BlobSource & { readonly name: string; readonly lastModified: number }

/**
 * Seal and upload one picked file as the receipt [receiptId] of the entry [entryId] in the Pot [target], under the Pot's
 * key and epoch [key] — core's upload in the receipt role, its type, name and modified time sealed in its meta. Throws
 * what core's upload throws (`budget_exceeded` — over 10 MiB, already at the start —, `quota_exceeded`,
 * `capability_disabled`, `too_many_blobs`, …; `stale_epoch` / `node_rekey_pending` are the caller's re-read,
 * `writeAtNodeEpoch`).
 */
export async function uploadReceipt(
  target: PotTarget,
  key: PotWriteKey,
  entryId: string,
  receiptId: string,
  file: ReceiptFile,
  opts: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<HeldReceipt> {
  const meta = { type: file.type, name: file.name, modifiedAt: file.lastModified }
  return receiptOf(await uploadApplicationBlob(target, key, entryId, receiptId, RECEIPT_ROLE, file, meta, opts))
}

// --- the bytes --------------------------------------------------------------------------------------------------------

type ReceiptBlob = Pick<HeldReceipt, 'blobId' | 'chunkCount' | 'byteSize' | 'wrappedBlobKey' | 'wrappedBlobMeta'>

/** A receipt's plaintext size: its sealed size less every frame's overhead (exact). */
export const receiptSize = (r: Pick<HeldReceipt, 'byteSize' | 'chunkCount'>): number => applicationBlobSize(r)

/** A receipt's sealed meta (type, name, modified time) for display; null when it carries none or it does not open. */
export function receiptMeta(scopeId: string, potKey: Uint8Array, potId: string, r: ReceiptBlob): OpenedBlobMeta | null {
  return applicationBlobMeta(scopeId, potKey, potId, RECEIPT_ROLE, r)
}

/** A receipt's `K_blob` (for a report share): throws `OpenRefused` for a row that is not this Pot's. */
export function receiptKeyOf(scopeId: string, potKey: Uint8Array, potId: string, r: ReceiptBlob): Uint8Array {
  return applicationBlobKeyOf(scopeId, potKey, potId, RECEIPT_ROLE, r)
}

/** Who presigns a receipt's chunks for whoever looks: a member's scope READ gate, or a guest's link. */
export const receiptPresign = applicationBlobPresign

/** The provider over a receipt's chunks: `K_blob` unwrapped at once (`OpenRefused` for a row that is not this Pot's), the
 *  chunks presigned on first read. */
export function receiptChunks(scopeId: string, potKey: Uint8Array, potId: string, r: HeldReceipt, presign: (blobId: string) => Promise<string[]>): BlobChunkProvider {
  return applicationBlobChunks(scopeId, potKey, potId, RECEIPT_ROLE, r, presign)
}

/** The name a receipt is saved under when it sealed none. */
const UNNAMED = 'receipt'

/** A receipt's file name: the one sealed in its meta, the extension its sealed type names when the name has none. */
export const receiptName = (meta: OpenedBlobMeta | null): string => downloadName(null, meta, () => UNNAMED)

/** Decrypt a receipt and save it to the device under [receiptName] — core's streamed download. Call it straight from the
 *  click with nothing awaited before (the save picker needs the click's activation). */
export async function downloadReceipt(provider: BlobChunkProvider, size: number): Promise<void> {
  await downloadApplicationBlob(provider, receiptName(provider.meta()), size)
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

/** A refused read of a blocked receipt: a member's presign item `blocked_by_operator`, a guest's `451`. */
export const isBlockedRead = isBlockedBlobRead

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
