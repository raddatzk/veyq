// Creating a Pot (uc.expenses.create-pot, ADR 0108 decision 2): core's node create with Expenses' kind, its settings
// sealed into the node's meta in the same request (`pot`, contracts/expenses/pot-meta.v1.schema.json), then its first
// records — the people, and for an import or a copy its entries — appended as batches of the server's cap (ADR 0108
// decision 1). The node exists before its records do: a records append that fails leaves an empty Pot, which the caller
// says ({@link PotPeopleNotAdded}); nothing is rolled back behind the person's back.
//
// The two steps are apart ({@link createPotNode}, {@link appendInBatches}) for the import: a new entry's receipts are
// completed before its first revision, so the import uploads each batch's receipts between the node and that batch.
import { createContentNode, type CreateNodeOptions, type NodeParent } from '../../core/node/tree'
import { memberNodeWriter, type MemberNodeWriter } from '../../core/node/rereadNode'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import type { EpochKeys } from '../spaces/epochs'
import { appendPotWrites, heldOfSent, type PotWrite } from './domain/potApi'
import type { HeldRevision } from './domain/revisions'
import type { BaseCurrency } from './domain/ledger'
import { POT_META_KEY, potMetaObject, type OwnCategory } from './domain/potMeta'
import { POT_KIND } from './kinds'

/** The append's batch cap (`PotAppendRequest.revisions` maxItems). */
export const APPEND_BATCH = 256

/** The Pot was created, its first records were not. */
export class PotPeopleNotAdded extends Error {
  constructor(readonly nodeId: string, readonly cause: unknown) {
    super('pot_people_not_added')
    this.name = 'PotPeopleNotAdded'
  }
}

/** Every participant record for [names], in the order given (each joins a millisecond after the one before, so the join
 *  order — the rounding's tie-break — is the order they were typed in). */
export function participantWrites(names: readonly string[], now = Date.now()): PotWrite[] {
  return names.map((name, i) => ({ entryId: crypto.randomUUID(), content: { kind: 'participant', name, active: true, joined: now + i } }))
}

/**
 * Append [writes] to a new Pot in batches, each batch on what the ones before wrote (its heads name them): the sealed
 * revisions of one batch are opened back into what is held, so the next batch's revision numbers and heads follow them.
 * Each batch is one write through the Pot's [MemberNodeWriter] (inbox #670): a rotation that lands while a large import
 * is under way has the batch meet `node_rekey_pending` (or `stale_epoch`), this member's device pull the Pot forward, and
 * the batch sealed again under its key now - the batches before it were re-keyed with the Pot.
 */
export async function appendInBatches(
  pot: MemberNodeWriter, base: BaseCurrency, writes: readonly PotWrite[],
  beforeBatch?: (batch: readonly PotWrite[]) => Promise<void>,
): Promise<void> {
  const { scopeId, nodeId } = pot
  const seal = (batch: readonly PotWrite[], held: readonly HeldRevision[]) =>
    pot.write(async (at) => heldOfSent(scopeId, nodeId, at.key, await appendPotWrites({ kind: 'member', scopeId, nodeId }, at, held, batch, base)))
  let held: HeldRevision[] = []
  for (let i = 0; i < writes.length; i += APPEND_BATCH) {
    const batch = writes.slice(i, i + APPEND_BATCH)
    await beforeBatch?.(batch)
    held = [...held, ...(await seal(batch, held))]
  }
}

/** A created Pot: its node id, its content key and the epoch that key was minted at (what a write states). */
export type CreatedPot = { nodeId: string; nodeKey: Uint8Array; epoch: number }

/** Create the Pot's node named [name] under [parent], its settings ([base], [categories]) sealed into its meta — no
 *  records yet. */
export async function createPotNode(
  scopeId: string, epochs: EpochKeys, parent: NodeParent, name: string, base: BaseCurrency,
  opts: CreateNodeOptions = {}, categories: readonly OwnCategory[] = [],
): Promise<CreatedPot> {
  const meta = { v: 1, [POT_META_KEY]: potMetaObject({ base, categories: [...categories] }) }
  const created = await createContentNode(scopeId, epochs, parent, POT_KIND, name, (key, node) => sealNodeMeta(key, { scopeId: node.scopeId, nodeId: node.nodeId }, meta), opts)
  return { nodeId: created.nodeId, nodeKey: created.contentKey, epoch: created.epoch }
}

/** Create a Pot named [name] under [parent] with [base] and [categories], and its people [names]. Returns its id and
 *  key. Throws {@link PotPeopleNotAdded} when the node exists but its people were not appended. */
export async function createPot(
  scopeId: string, epochs: EpochKeys, parent: NodeParent, name: string, base: BaseCurrency, names: readonly string[],
  opts: CreateNodeOptions = {}, categories: readonly OwnCategory[] = [],
): Promise<CreatedPot> {
  const created = await createPotNode(scopeId, epochs, parent, name, base, opts, categories)
  const writes = participantWrites(names)
  if (writes.length) {
    try {
      await appendInBatches(memberNodeWriter(scopeId, created.nodeId, { key: created.nodeKey, epoch: created.epoch }), base, writes)
    } catch (e) {
      throw new PotPeopleNotAdded(created.nodeId, e)
    }
  }
  return created
}
