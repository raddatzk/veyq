// A scope's RE-KEY after a rotation (ADR 0109, uc.spaces.rekey-after-rotation): every node of the scope, live and
// trashed, gets a fresh key minted at the scope's current epoch, top-down, one node per transaction. Run by the device
// that committed the epoch (the remover's, the rotator's for the personal scope, an owner's scope-rotation worker for a
// queued rotation) and by any owner's worker that finds the scope still re-keying (resumed from the server's derived
// state: a node below the current epoch still carries its key from before).
//
// Per node (`rekeyNode`), everything sealed under its OLD key moves to the NEW one, and nothing else changes:
//   - its own entry under its parent's current key (`K_SC` at the current epoch at the root; a guest's `K_up` for an
//     identity folder), sealed at the current epoch and the next placement revision;
//   - each child's entry under the new key: its own key re-wrapped, its name re-sealed, its slug token recomputed, bound
//     to the child's epoch and next placement revision (a guest's identity folder excepted: its entry is under `K_up`);
//   - its meta (core's cover wrap inside it, and the kind's own references through `rekeyContent`);
//   - every `K_blob` wrap in its attachment slots (`K_blob` itself is kept: no byte is read from or written to the store);
//   - each link rooted on it: the new key under the link's `K_share`, recovered from its recovery copy, which stays at its
//     epoch so the link stays marked `reissueRequired` (ADR 0109 point 4);
//   - its drop key, replaced: the new private half under the new key, the public half under each WRITE_ONLY link's
//     `K_share`, each guest's `K_up` re-sealed to it (point 5); a READ_WRITE guest's name re-sealed under the new key;
//   - the kind's own rows (Memories' media, buckets), staged by its `rekeyContent` under the same re-key id.
// The server swaps everything in at the commit, or nothing (`rekey_parts_missing` / `rekey_parts_stale`).
//
// Resumable: nothing is kept between runs. Each round re-lists the scope and re-keys the frontier — the lagging nodes
// whose parent is current — so an interrupted run leaves a tree that opens as it stands, and the next run continues
// where the rows say (E1). A conflict (a writer won the node's lock, another owner committed it) re-lists.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { rekeyApi } from '../../../api/client'
import type { RekeyNode } from '../../../api/generated/content'
import { withRefresh } from '../../../app/sessionRefresh'
import { openScopeById } from '../../../features/spaces/scopes'
import { keyAt, writeKey, zeroEpochKeys, type EpochKeys } from '../../../features/spaces/epochs'
import { HttpStatus, isCodedError } from '../../../shared/httpError'
import { registry } from '../../applications/registry'
import { blobKeyContext, unwrapBlobKey } from '../../blob/keys'
import { recoverShareKey } from '../../sharing/manageShares'
import { SHARE_KEY } from '../../sharing/shareTree'
import { identityNameContext } from '../../sharing/guestIdentity'
import { replaceDropKey } from '../../sharing/dropKey'
import { COVER_ROLE } from '../cover/coverModel'
import { openNodeMetaForWrite, sealNodeMeta } from '../nodeMeta'
import { childEntryKey, openContentKey, openName, sealName, slugTokenFor, wrapContentKey } from '../tree'
import type { RekeyNodeContext } from './hooks'

/** The contracts' cap per staged list (`NodeRekeyStage`). */
export const STAGE_BATCH = 256
/** Conflicts a run absorbs (each re-lists) before it gives up; the next run resumes. */
const MAX_CONFLICTS = 8

/** How a run ended: every node at the scope's current epoch, and how many this run committed. */
export type RekeyScopeResult = { done: boolean; committed: number }

/** The codes after which the scope is re-listed and the run goes on: the rows moved under it. */
const CONFLICTS = ['stale_epoch', 'node_rekey_pending', 'rekey_parts_stale', 'rekey_parts_missing', 'stale_placement', 'stale_revision']

async function isConflict(e: unknown): Promise<boolean> {
  for (const code of CONFLICTS) if (await isCodedError(e, HttpStatus.conflict, code)) return true
  return false
}

/**
 * Re-key every node of [scopeId] that lags its current epoch. Returns `done` once none lags. Throws when this device
 * cannot (its grant lags, a node's key does not open, a kind registers no re-key) — the scope then stays *re-keying*
 * for an owner's worker to resume.
 */
export async function rekeyScope(scopeId: string, opts: { signal?: AbortSignal } = {}): Promise<RekeyScopeResult> {
  const opened = await openScopeById(scopeId)
  if (!opened) throw new Error('rekey: cannot open the scope')
  const epochs = opened.epochs
  // The old and new key of each link root this run re-keyed, for a copy a link's guest sealed under its root.
  const shareRoots = new Map<string, { oldKey: Uint8Array; newKey: Uint8Array }>()
  let committed = 0
  let conflicts = 0
  try {
    for (;;) {
      opts.signal?.throwIfAborted()
      const list = await withRefresh(() => rekeyApi().listRekeyNodes({ scopeId }))
      const current = list.currentEpoch
      if (epochs.current < current) throw new Error('rekey: this device does not hold the scope\'s current epoch')
      const rows = list.nodes.map((n) => ({ ...n, parentId: n.parentId ?? null }))
      if (!rows.some((n) => n.epoch < current)) return { done: true, committed }
      const byId = new Map(rows.map((r) => [r.id, r]))
      const keys = openKeys(scopeId, epochs, rows)
      const frontier = rows.filter((n) => n.epoch < current && (n.parentId === null || (byId.get(n.parentId)?.epoch ?? -1) >= current))
      if (!frontier.length) throw new Error('rekey: no node of the scope can be re-keyed (a parent lags that is not listed)')
      try {
        for (const node of frontier) {
          opts.signal?.throwIfAborted()
          await rekeyNode({ scopeId, epochs, current, rows, byId, keys, shareRoots, signal: opts.signal }, node)
          committed++
        }
      } catch (e) {
        if (!(await isConflict(e)) || ++conflicts > MAX_CONFLICTS) throw e
      } finally {
        for (const k of keys.values()) k.fill(0)
      }
    }
  } finally {
    zeroEpochKeys(epochs)
    for (const { oldKey, newKey } of shareRoots.values()) { oldKey.fill(0); newKey.fill(0) }
  }
}

type Row = RekeyNode & { parentId: string | null }

/** Every node's CURRENT key, opened top-down from the rows as they stand: a root under `K_SC` at its own epoch, every
 *  other under its parent's key (a guest's identity folder through the parent's drop key). A node whose chain does not
 *  open is absent; re-keying it then refuses. */
function openKeys(scopeId: string, epochs: EpochKeys, rows: Row[]): Map<string, Uint8Array> {
  const children = new Map<string | null, Row[]>()
  for (const r of rows) children.set(r.parentId, [...(children.get(r.parentId) ?? []), r])
  const keys = new Map<string, Uint8Array>()
  const queue: { row: Row; parent: Row | null }[] = (children.get(null) ?? []).map((row) => ({ row, parent: null }))
  while (queue.length) {
    const { row, parent } = queue.shift()!
    try {
      const entryKey = parent ? childEntryKey(scopeId, parent, keys.get(parent.id)!, row) : keyAt(epochs, row.epoch)
      keys.set(row.id, openContentKey(scopeId, entryKey, row))
    } catch {
      continue // its subtree stays unopened: re-keying any of it refuses
    }
    for (const child of children.get(row.id) ?? []) queue.push({ row: child, parent: row })
  }
  return keys
}

type Run = {
  scopeId: string
  epochs: EpochKeys
  current: number
  rows: Row[]
  byId: Map<string, Row>
  keys: Map<string, Uint8Array>
  shareRoots: Map<string, { oldKey: Uint8Array; newKey: Uint8Array }>
  signal?: AbortSignal
}

/** Re-key one node whose parent is current (see the header). The new key lives only for the call. */
export async function rekeyNode(run: Run, node: Row): Promise<void> {
  const { scopeId, epochs, current } = run
  const kind = registry.kind(node.kind)
  if (!kind.supported) throw new Error(`rekey: no application re-keys a node of kind ${node.kind}`)
  const oldKey = run.keys.get(node.id)
  if (!oldKey) throw new v1.OpenRefused('content-key')
  const parent = node.parentId ? run.byId.get(node.parentId) ?? null : null
  if (node.parentId && !parent) throw new Error('rekey: the node\'s parent is not listed')
  // The key the entry lives under: the parent's current key (its re-key ran first), `K_up` for an identity folder,
  // `K_SC` — at the node's own epoch to open the name, at the current one to seal it — at the scope root.
  const parentKey = parent ? run.keys.get(parent.id) : undefined
  if (parent && !parentKey) throw new v1.OpenRefused('content-key')
  const entryNow = parent ? childEntryKey(scopeId, parent, parentKey!, node) : keyAt(epochs, node.epoch)
  const entryNew = parent ? entryNow : writeKey(epochs)
  const name = openName(entryNow, address(scopeId, node, node.epoch, node.placementRevision), node.encryptedName)
  const newKey = v1.randomBytes(32)
  const rekeyId = crypto.randomUUID()
  try {
    const sealedAt = address(scopeId, node, current, node.placementRevision + 1)
    const parts = await withRefresh(() => rekeyApi().getNodeRekeyParts({ scopeId, nodeId: node.id }))

    // children: their unchanged keys under the new one, at their own epoch and the next placement revision
    const children = run.rows.filter((r) => r.parentId === node.id && !r.shareIdentityId).map((c) => {
      const childName = openName(oldKey, address(scopeId, c, c.epoch, c.placementRevision), c.encryptedName)
      const childKey = openContentKey(scopeId, oldKey, c)
      try {
        const at = address(scopeId, c, c.epoch, c.placementRevision + 1)
        return {
          nodeId: c.id, placementRevision: c.placementRevision, encryptedName: sealName(newKey, at, childName),
          slugToken: slugTokenFor(newKey, node.id, c.kind, childName), wrappedContentKey: wrapContentKey(newKey, at, childKey),
        }
      } finally {
        childKey.fill(0)
      }
    })

    // attachment blobs: K_blob kept, its wrap moved
    const blobs = parts.blobs.map((b) => ({ blobId: b.blobId, wrappedBlobKey: rewrapBlobKey(scopeId, oldKey, newKey, { blobId: b.blobId, ownerId: node.id, role: b.role, wrappedBlobKey: b.wrappedBlobKey }) }))

    // the drop key, replaced (point 5)
    const drop = node.sealedDropPrivateKey ? replaceDropKey(scopeId, node.id, oldKey, newKey, node.sealedDropPrivateKey) : null

    // links rooted here: the new key under each K_share, recovered from its copy (left at its epoch)
    const shares = parts.shares.map((s) => {
      const kShare = recoverShareKey(epochs, { scopeId, nodeId: node.id, shareId: s.shareId }, {
        sealedShareKey: s.sealedShareKey, sealedShareKeyEpoch: s.sealedShareKeyEpoch ?? null,
      })
      try {
        if (s.hasDropPublicKey && !drop) throw new Error('rekey: a link carries a drop key the node does not')
        run.shareRoots.set(s.shareId, { oldKey: oldKey.slice(), newKey: newKey.slice() })
        return {
          shareId: s.shareId,
          wrap: s.hasWrap ? b64std(v1.seal(SHARE_KEY, kShare, newKey)) : null,
          sealedDropPublicKey: s.hasDropPublicKey ? drop!.sealedPublicFor(kShare) : null,
        }
      } finally {
        kShare.fill(0)
      }
    })

    // guests: a WRITE_ONLY guest's K_up to the new drop key, a READ_WRITE guest's name under the new key
    const identities = parts.identities.map((i) => {
      if (i.wrappedIdentityKey) {
        if (!drop) throw new Error('rekey: a guest identity is wrapped to a drop key the node does not carry')
        return { identityId: i.identityId, wrappedIdentityKey: drop.resealIdentityKey(i.identityId, i.wrappedIdentityKey) }
      }
      if (!i.sealedDisplayName) return { identityId: i.identityId }
      const context = identityNameContext(scopeId, i.shareId, i.identityId)
      const plain = v1.openField(context, oldKey, fromB64std(i.sealedDisplayName))
      return { identityId: i.identityId, sealedDisplayName: b64std(v1.sealField(context, newKey, plain)) }
    })

    // the meta: core's cover, then the kind's own references, all re-wrapped in place, sealed under the new key
    const meta = node.wrappedMeta ? openNodeMetaForWrite(oldKey, { scopeId, nodeId: node.id }, node.wrappedMeta) : null
    if (meta) rewrapCover(scopeId, node.id, oldKey, newKey, meta)
    const ctx: RekeyNodeContext = {
      scopeId, rekeyId, node: { id: node.id, kind: node.kind, epoch: node.epoch, trashed: node.trashed },
      oldKey, newKey, meta, shareRootKeys: (shareId) => run.shareRoots.get(shareId) ?? null, signal: run.signal,
    }
    await kind.rekeyContent(ctx)
    const wrappedMeta = meta ? sealNodeMeta(newKey, { scopeId, nodeId: node.id }, meta) : undefined

    // stage core's lists at the contracts' caps, then commit
    const batches = Math.max(1, ...[children, blobs, shares, identities].map((l) => Math.ceil(l.length / STAGE_BATCH)))
    for (let i = 0; i < batches; i++) {
      const slice = <T>(l: T[]) => l.slice(i * STAGE_BATCH, (i + 1) * STAGE_BATCH)
      const stage = { fromEpoch: node.epoch, children: slice(children), blobs: slice(blobs), shares: slice(shares), identities: slice(identities) }
      if (!stage.children.length && !stage.blobs.length && !stage.shares.length && !stage.identities.length) continue
      await withRefresh(() => rekeyApi().stageNodeRekey({ scopeId, nodeId: node.id, rekeyId, nodeRekeyStage: stage }))
    }
    await withRefresh(() => rekeyApi().commitNodeRekey({
      scopeId, nodeId: node.id, rekeyId,
      commitNodeRekeyRequest: {
        nodeEpoch: node.epoch, epoch: current,
        parentId: node.parentId ?? undefined, parentEpoch: parent?.epoch,
        placementRevision: node.placementRevision, metaRevision: node.metaRevision,
        encryptedName: sealName(entryNew, sealedAt, name),
        slugToken: slugTokenFor(entryNew, node.parentId ?? scopeId, node.kind, name),
        wrappedContentKey: wrapContentKey(entryNew, sealedAt, newKey),
        wrappedMeta,
        sealedDropPrivateKey: drop?.sealedDropPrivateKey,
      },
    }))
  } finally {
    newKey.fill(0)
  }
}

const address = (scopeId: string, node: { id: string }, epoch: number, placementRevision: number) =>
  ({ scopeId, nodeId: node.id, epoch, placementRevision })

/** A `K_blob` wrap moved from the old node key to the new one, bound as before (scope, owner, blob, role). */
export function rewrapBlobKey(
  scopeId: string, oldKey: Uint8Array, newKey: Uint8Array, ref: { blobId: string; ownerId: string; role: string; wrappedBlobKey: string },
): string {
  const kBlob = unwrapBlobKey(scopeId, oldKey, ref)
  try {
    return b64std(v1.seal(blobKeyContext(scopeId, ref), newKey, kBlob))
  } finally {
    kBlob.fill(0)
  }
}

/** Core's cover inside the meta (`cover.source.ref`, owner = the node, role `COVER`): its wrap moved in place. Any other
 *  source (an older client's `{ kind: 'media' }`) carries no wrap and is left as it is. */
function rewrapCover(scopeId: string, nodeId: string, oldKey: Uint8Array, newKey: Uint8Array, meta: Record<string, unknown>): void {
  const source = (meta.cover as { source?: { kind?: unknown; ref?: { blobId?: unknown; wrappedBlobKey?: unknown } } } | undefined)?.source
  const ref = source?.kind === 'upload' ? source.ref : undefined
  if (!ref || typeof ref.blobId !== 'string' || typeof ref.wrappedBlobKey !== 'string') return
  ref.wrappedBlobKey = rewrapBlobKey(scopeId, oldKey, newKey, { blobId: ref.blobId, ownerId: nodeId, role: COVER_ROLE, wrappedBlobKey: ref.wrappedBlobKey })
}
