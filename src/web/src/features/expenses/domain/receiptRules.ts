// The rules of a Pot's receipts that need no network (ADR 0108 decisions 6 and 7, uc.expenses.attach-receipts,
// uc.expenses.entry-history E2): which file may be added, how an opened one is shown, which receipts a revision drops, and
// whether a deleted entry may still be restored. Pure, so each rule is tested against its control.
import { sniffImage, sniffPdf, type ImageType } from '../../../core/blob/sniff'
import type { HeldRevision } from './revisions'

/** What an added file may claim to be: an image or a PDF. Only the client can keep a receipt to these — its type is
 *  sealed — so the claim (the browser's type, else the name's extension) is checked when it is added; what an opened one
 *  is, is decided by its bytes ({@link receiptView}). */
const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'bmp', 'heic', 'heif'])
export function claimsReceipt(file: { type: string; name: string }): boolean {
  const type = file.type.toLowerCase()
  if (type) return type.startsWith('image/') || type === 'application/pdf'
  const ext = /\.([a-z0-9]+)$/i.exec(file.name)?.[1]?.toLowerCase() ?? ''
  return ext === 'pdf' || IMAGE_EXTENSIONS.has(ext)
}

/** How an opened receipt is shown — decided by its decrypted BYTES, never by its sealed type or name: a raster image in
 *  the client (an `<img>` on an object URL typed with the sniffed type), a PDF through core's page renderer, and anything
 *  else — an SVG, a text, a file whose claim was wrong — only offered for download, never rendered. */
export type ReceiptView = { kind: 'image'; type: ImageType } | { kind: 'pdf' } | { kind: 'other' }
export function receiptView(bytes: Uint8Array): ReceiptView {
  const head = bytes.subarray(0, 4096)
  const image = sniffImage(head)
  if (image) return { kind: 'image', type: image }
  if (sniffPdf(head)) return { kind: 'pdf' }
  return { kind: 'other' }
}

/** One listed receipt, as far as the drop rule reads it. */
type Listed = { receiptId: string; entryId: string; createdAt: Date; droppedAt: Date | null }

/**
 * The receipts the next revision of [entryId] names in `droppedReceipts`: every receipt of that entry the listing still
 * holds (not dropped) and the revision does not keep ([kept]) that
 * - the revision it is written on ([on], null for a new entry) held — a receipt removed in the editor;
 * - this writer uploaded for it ([own]) — one added, then removed again before the save, or left by a save that failed;
 * - was completed before the revision it is written on — one whose own revision never came (a writer's save that failed
 *   after its upload): no later revision can hold it, so it would stay with a live entry for good.
 * A receipt another writer completed after [on] is left alone: it may be on its way into that writer's revision (whose
 * save is then stale against this one anyway). A dropped receipt is never held again, so none is named twice.
 */
export function receiptsToDrop(
  listing: readonly Listed[],
  entryId: string,
  kept: readonly string[],
  on: { receipts: readonly string[]; createdAt: Date } | null,
  own: ReadonlySet<string>,
): string[] {
  const keep = new Set(kept)
  const before = new Set(on?.receipts ?? [])
  return listing
    .filter((r) => r.entryId === entryId && r.droppedAt === null && !keep.has(r.receiptId))
    .filter((r) => before.has(r.receiptId) || own.has(r.receiptId) || (on !== null && r.createdAt.getTime() < on.createdAt.getTime()))
    .map((r) => r.receiptId)
}

/** When a deleted entry's receipts are erased: its tombstone's `purgeAfter` while the tombstone is the entry's latest
 *  revision, else null (a live entry, or one the server served no time for). */
export function purgeAfterOf(held: readonly HeldRevision[], entryId: string): Date | null {
  let latest: HeldRevision | null = null
  for (const r of held) if (r.entryId === entryId && (!latest || r.revision > latest.revision)) latest = r
  return latest?.body?.kind === 'deleted' ? (latest.purgeAfter ?? null) : null
}

/** Whether a deleted entry is still offered a restore (uc.expenses.entry-history E2): until its tombstone's `purgeAfter`;
 *  past it the receipts are erased and the entry stays a line in the history. No time served: offered. */
export function restoreOffered(purgeAfter: Date | null, now: Date = new Date()): boolean {
  return purgeAfter === null || purgeAfter.getTime() > now.getTime()
}
