// Creating a Pot (uc.expenses.create-pot, ADR 0108 decision 2): core's node create with Expenses' kind, its settings
// sealed into the node's meta in the same request (`pot`, contracts/expenses/pot-meta.v1.schema.json), then its first
// records — the people, and for an import or a copy its entries — appended as batches of the server's cap (ADR 0108
// decision 1). The node exists before its records do: a records append that fails leaves an empty Pot, which the caller
// says ({@link PotPeopleNotAdded}); nothing is rolled back behind the person's back.
import { createContentNode, type CreateNodeOptions, type NodeParent } from '../../core/node/tree'
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
 */
export async function appendInBatches(
  scopeId: string, nodeId: string, key: { key: Uint8Array; epoch: number }, base: BaseCurrency, writes: readonly PotWrite[],
): Promise<void> {
  const seal = async (batch: readonly PotWrite[], held: readonly HeldRevision[]) =>
    heldOfSent(scopeId, nodeId, key.key, await appendPotWrites({ kind: 'member', scopeId, nodeId }, key, held, batch, base))
  let held: HeldRevision[] = []
  for (let i = 0; i < writes.length; i += APPEND_BATCH) held = [...held, ...(await seal(writes.slice(i, i + APPEND_BATCH), held))]
}

/** Create a Pot named [name] under [parent] with [base] and [categories], and its people [names]. Returns its id and
 *  key. Throws {@link PotPeopleNotAdded} when the node exists but its people were not appended. */
export async function createPot(
  scopeId: string, epochs: EpochKeys, parent: NodeParent, name: string, base: BaseCurrency, names: readonly string[],
  opts: CreateNodeOptions = {}, categories: readonly OwnCategory[] = [], more: readonly PotWrite[] = [],
): Promise<{ nodeId: string; nodeKey: Uint8Array; epoch: number }> {
  const meta = { v: 1, [POT_META_KEY]: potMetaObject({ base, categories: [...categories] }) }
  const created = await createContentNode(scopeId, epochs, parent, POT_KIND, name, (key, node) => sealNodeMeta(key, { scopeId: node.scopeId, nodeId: node.nodeId }, meta), opts)
  const writes = [...participantWrites(names), ...more]
  if (writes.length) {
    try {
      await appendInBatches(scopeId, created.nodeId, { key: created.contentKey, epoch: created.epoch }, base, writes)
    } catch (e) {
      throw new PotPeopleNotAdded(created.nodeId, e)
    }
  }
  return { nodeId: created.nodeId, nodeKey: created.contentKey, epoch: created.epoch }
}
