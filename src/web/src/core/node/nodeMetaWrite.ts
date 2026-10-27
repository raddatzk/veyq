// uc.memory.concurrent-curation (web) — the node-meta write: the one PATCH every Memory meta writer goes through
// (ADR 0077). Kept apart from `nodeMeta.ts` on purpose: that module is the pure envelope and sits in the crypto
// mutation scope, which admits only files that do no I/O; this one is the wiring.
import { contentApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { writeAtNodeEpoch } from '../../shared/rekeyRetry'
import { openNodeMetaForWrite, sealNodeMeta, type NodeMetaState, type RawNodeMeta } from './nodeMeta'
import { writeSealedMeta, type MetaEditContext, type SealedMetaResult } from './sealedMetaWrite'

/** The node as a re-read finds it: its key now and its meta with the epoch that key was minted at. */
export type NodeMetaReread = () => Promise<{ nodeKey: Uint8Array; state: NodeMetaState }>

export type WriteNodeMetaOptions = {
  /**
   * How to read the node again after one of the epoch refusals (ADR 0109 point 6): `node_rekey_pending` (the node is not
   * re-keyed since the last rotation; retried after a wait) and `stale_epoch` (re-keyed since it was read; once). The
   * write is then rebuilt from scratch under the key the re-read returns. Given only by a writer whose edit names nothing
   * sealed under the OLD key — a writer that carries a blob wrap (a cover, a banner) must not be rebuilt under a new key
   * its wrap is not under, so it passes none and the refusal is its caller's.
   */
  reread?: NodeMetaReread
  /** A MEMBER's write only, with `reread`: on the first epoch refusal, bring the node's path to the current epoch on
   *  this device and retry at once (`shared/rekeyRetry.ts`, ADR 0111 decision 11) — core's `pullRekeyPath`. */
  pull?: () => Promise<boolean>
  /** The device floor (`rekey/floor.ts`, layer 2), run before each attempt with the epoch it states: a member's writer
   *  refuses a node whose key is from before the last rotation it holds (`NodeKeyBelowFloorError`, retried as pending). */
  atFloor?: (nodeEpoch: number) => void
}

/**
 * Write a node's sealed meta, revision-checked (ADR 0077): open the blob at `base`, apply `edit`, seal, and PATCH
 * it with the `metaRevision` it was built from and the node's epoch as read (`nodeEpoch`, ADR 0109 point 6); on
 * `409 stale_revision` re-run `edit` on the blob the conflict handed back. Every node-meta writer — the three editors and
 * the two self-heals — goes through here. With `opts.reread`, the two epoch refusals re-read the node and write again.
 */
export function writeNodeMeta(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  base: NodeMetaState,
  edit: (meta: RawNodeMeta, ctx: MetaEditContext) => RawNodeMeta | null | Promise<RawNodeMeta | null>,
  send?: (wrappedMeta: string | null, metaRevision: number, nodeEpoch: number) => Promise<void>,
  opts: WriteNodeMetaOptions = {},
): Promise<SealedMetaResult<RawNodeMeta>> {
  const address = { scopeId, nodeId }
  const once = (key: Uint8Array, at: NodeMetaState) => {
    opts.atFloor?.(at.nodeEpoch)
    return writeSealedMeta<RawNodeMeta>(
      at,
      {
        open: (wrappedMeta) => openNodeMetaForWrite(key, address, wrappedMeta),
        seal: (meta) => sealNodeMeta(key, address, meta),
        // `send` is the plain `updateNode` unless a writer carries more in the same request: a node's cover write sends the
        // blob step with the meta it names (`cover/coverWrite.ts`), so the two can never part.
        send: (wrappedMeta, metaRevision) =>
          send
            ? send(wrappedMeta, metaRevision, at.nodeEpoch)
            : withRefresh(() =>
              contentApi().updateNode({
                scopeId, nodeId, updateNodeRequest: { wrappedMeta: wrappedMeta!, metaRevision, nodeEpoch: at.nodeEpoch },
              }),
            ).then(() => undefined),
      },
      edit,
    )
  }
  const reread = opts.reread
  if (!reread) return Promise.resolve().then(() => once(nodeKey, base))
  return writeAtNodeEpoch(async (again) => {
    if (!again) return once(nodeKey, base)
    const fresh = await reread()
    return once(fresh.nodeKey, fresh.state)
  }, { pull: opts.pull })
}
