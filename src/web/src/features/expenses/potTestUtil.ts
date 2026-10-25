// A Pot for the Expenses tests: an in-memory server that takes batches through the REAL write path (`sealPotWrites`:
// the heads read, each revision under its own K_rev) and serves them as the wire would, and the Pot's tree node with its
// settings sealed into its meta. Only tests import it.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import type { TreeNode } from '../../core/node/tree'
import { treeNode } from '../../test/models'
import { sealRevision } from './domain/codec'
import { headsToName } from './domain/heads'
import { buildLedger, type BaseCurrency } from './domain/ledger'
import { sealPotWrites, type PotWrite } from './domain/potApi'
import { openServedRevisions, type HeldRevision, type ServedRevision } from './domain/revisions'
import { POT_META_KEY, potMetaObject, type OwnCategory } from './domain/potMeta'
import { POT_KIND } from './kinds'
import type { ExpenseBody } from './domain/format'
import { planBlob } from '../../core/blob/transport'
import { RECEIPT_ROLE, type HeldReceipt } from './receipts'

export const SCOPE = '22222222-2222-4222-8222-222222222222'
export const POT_ID = '0e000000-0000-4000-b000-000000000001'
export const EUR: BaseCurrency = { currency: 'EUR', minorUnits: 2 }
export const ANNA = '0e000000-0000-4000-8000-000000000001'
export const BEN = '0e000000-0000-4000-8000-000000000002'
export const CLEO = '0e000000-0000-4000-8000-000000000003'

export const person = (name: string, joined: number, active = true): PotWrite['content'] => ({ kind: 'participant', name, active, joined })
export const expense = (title: string, amount: number, payer: string, among: string[]): ExpenseBody => ({
  kind: 'expense', title, date: '2026-07-01', category: 'food', currency: 'EUR', minorUnits: 2, rate: '1', amount,
  payers: [{ participant: payer, amount }], allocation: { mode: 'equal', participants: among }, receipts: [],
})

/** What was appended, in order, as the rows a server serves — with each row's writer (a member's read carries it). */
export class PotServer {
  rows: ServedRevision[] = []
  clock = 0
  constructor(readonly potKey: Uint8Array = v1.randomBytes(32), readonly scopeId = SCOPE, readonly nodeId = POT_ID) {}
  held(rows = this.rows): HeldRevision[] {
    return openServedRevisions(this.scopeId, this.nodeId, this.potKey, rows)
  }
  append(writes: readonly PotWrite[], writer = 'user:u-anna', held: readonly HeldRevision[] = this.held(), base = EUR): void {
    for (const w of sealPotWrites({ scopeId: this.scopeId, nodeId: this.nodeId }, this.potKey, held, writes, base)) {
      this.rows.push({
        entryId: w.entryId, revision: w.revision, createdAt: new Date(1_767_225_600_000 + this.clock++ * 60_000),
        sealedBody: fromB64std(w.sealedBody), wrappedRevisionKey: fromB64std(w.wrappedRevisionKey), writer,
      })
      // the server stamps each dropped receipt's drop (a receipt of this record only)
      for (const r of this.receipts) if (r.entryId === w.entryId && !r.droppedAt && w.droppedReceipts?.includes(r.receiptId)) r.droppedAt = new Date()
    }
  }
  erase(entryId: string, base = EUR): void {
    const held = this.held()
    const latest = Math.max(...held.filter((r) => r.entryId === entryId).map((r) => r.revision))
    const joined = buildLedger(held, base).participants.find((p) => p.id === entryId)!.joined
    const s = sealRevision({ scopeId: this.scopeId, nodeId: this.nodeId, entryId, revision: latest + 1 }, this.potKey, { v: 1, heads: headsToName(held), kind: 'erasure', joined })
    this.rows = this.rows.filter((r) => r.entryId !== entryId)
    this.rows.push({ entryId, revision: latest + 1, createdAt: new Date(1_767_225_600_000 + this.clock++ * 60_000), writer: 'user:u-anna', ...s })
  }
  /** The receipts the Pot holds, as its listing serves them. */
  receipts: HeldReceipt[] = []
  /** A receipt of [entryId] as an upload under the Pot's key leaves it: its `K_blob` wrapped bound to (blob, Pot, the
   *  receipt role), its type and name sealed in its meta. Held in [receipts]; answered with the blob's key. */
  receipt(entryId: string, file: { name: string; type: string; bytes?: Uint8Array }, over: Partial<HeldReceipt> = {}): HeldReceipt & { blobKey: Uint8Array } {
    const bytes = file.bytes ?? new Uint8Array([1, 2, 3])
    const source = new Blob([bytes as BlobPart], { type: file.type })
    const plan = planBlob(this.scopeId, { ownerId: this.nodeId, role: RECEIPT_ROLE }, this.potKey, source, { type: file.type, name: file.name })
    const r: HeldReceipt = {
      receiptId: crypto.randomUUID(), entryId, blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize,
      wrappedBlobKey: plan.wrappedBlobKey, wrappedBlobMeta: plan.wrappedBlobMeta ?? null, writer: 'user:u-anna',
      createdAt: new Date(1_767_225_600_000 + this.clock++ * 60_000), droppedAt: null, purgeAfter: null, blockedByOperator: false, ...over,
    }
    this.receipts.push(r)
    return { ...r, blobKey: plan.blobKey }
  }
  /** The rows a guest is served: the same, with no writer. */
  guestRows(): ServedRevision[] {
    return this.rows.map(({ writer: _writer, ...r }) => r)
  }
}

/** The Pot's tree node, its settings sealed into its meta under [potKey] (none: a Pot no client set up). */
export function potNode(potKey: Uint8Array, base: BaseCurrency | null = EUR, categories: OwnCategory[] = [], over: Partial<TreeNode> = {}): TreeNode {
  const wrappedMeta = base ? sealNodeMeta(potKey, { scopeId: SCOPE, nodeId: POT_ID }, { v: 1, [POT_META_KEY]: potMetaObject({ base, categories }) }) : null
  return treeNode({ id: POT_ID, kind: POT_KIND, name: 'Trip', parentId: null, wrappedMeta, metaRevision: 1, ...over })
}
