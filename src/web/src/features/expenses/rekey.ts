// Expenses' part of a Pot's re-key (ADR 0109 point 2, ADR 0108 decisions 4 and 7; core's driver
// `core/node/rekey/rekeyScope.ts` calls it through the kind's `rekeyContent`): each revision's `K_rev` is opened under the
// Pot's old key and wrapped under the new one, bound to the same (scope, Pot, entry, revision) — the body is no input, so
// no body and no digest moves, and the server refuses a staged part that names one (the backend participant's `STALE`).
// Each receipt's `K_blob` is moved the same way, bound as before to (scope, Pot, blob, `EXPENSES_RECEIPT`) — core's
// `rewrapBlobKey`, the cover's and Memories' move; its bytes are not touched. The re-key listing names a receipt by id and
// wrap only, while the wrap is bound to its blob id, so the blob ids come from the Pot's receipt listing (read once, and
// once more for a receipt completed since). Live and trashed Pots alike: both listings serve every revision and receipt.
// The Pot's meta (`pot`: base currency, own categories) holds nothing sealed under the key of its own beyond the envelope
// core re-seals. Staged under the commit's re-key id at the stage's cap; fails closed — a wrap that does not open, or a
// receipt whose blob is unknown, throws, and core does not commit the node (a receipt left out is the server's
// `rekey_parts_missing`).
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { withRefresh } from '../../app/sessionRefresh'
import type { RekeyNodeContext } from '../../core/node/rekey/hooks'
import { STAGE_BATCH, rewrapBlobKey } from '../../core/node/rekey/rekeyScope'
import { expensesApi } from './api'
import { rewrapRevisionKey } from './domain/codec'
import { RECEIPT_ROLE, readMemberReceipts } from './receipts'

/** The listing's page size: the stage's cap, so one page is one batch. */
const PAGE = STAGE_BATCH

/** The kind's `rekeyContent` for EXPENSES_POT (see the header). */
export async function rekeyPotContent(ctx: RekeyNodeContext): Promise<void> {
  const { scopeId, node, oldKey, newKey } = ctx
  let blobs: Map<string, string> | null = null
  const readBlobs = async () => new Map(((await readMemberReceipts(scopeId, node.id))?.receipts ?? []).map((r) => [r.receiptId, r.blobId] as const))
  async function blobOf(receiptId: string): Promise<string> {
    blobs ??= await readBlobs()
    if (!blobs.has(receiptId)) blobs = await readBlobs() // completed after the first read
    const blobId = blobs.get(receiptId)
    if (!blobId) throw new Error('rekey_receipt_unknown')
    return blobId
  }
  let since: string | undefined
  for (;;) {
    ctx.signal?.throwIfAborted()
    const page = await withRefresh(() => expensesApi().listPotRekey({ scopeId, nodeId: node.id, since, limit: PAGE }))
    const revisions = page.revisions.map((r) => ({
      entryId: r.entryId,
      revision: r.revision,
      wrappedRevisionKey: b64std(rewrapRevisionKey({ scopeId, nodeId: node.id, entryId: r.entryId, revision: r.revision }, oldKey, newKey, fromB64std(r.wrappedRevisionKey))),
    }))
    const receipts: { receiptId: string; wrappedBlobKey: string }[] = []
    for (const r of page.receipts) {
      const blobId = await blobOf(r.receiptId)
      receipts.push({ receiptId: r.receiptId, wrappedBlobKey: rewrapBlobKey(scopeId, oldKey, newKey, { blobId, ownerId: node.id, role: RECEIPT_ROLE, wrappedBlobKey: r.wrappedBlobKey }) })
    }
    // One stage per batch: the revisions first, then the receipts, at most the stage's cap of each.
    for (let i = 0; i < Math.max(revisions.length, receipts.length); i += STAGE_BATCH) {
      const rev = revisions.slice(i, i + STAGE_BATCH)
      const rec = receipts.slice(i, i + STAGE_BATCH)
      await withRefresh(() =>
        expensesApi().stagePotRekey({
          scopeId,
          nodeId: node.id,
          rekeyId: ctx.rekeyId,
          potRekeyStage: { fromEpoch: node.epoch, revisions: rev, ...(rec.length ? { receipts: rec } : {}) },
        }),
      )
    }
    if (!page.more) return
    since = page.cursor ?? undefined
  }
}
