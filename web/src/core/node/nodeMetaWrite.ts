// uc.memory.concurrent-curation (web) — the node-meta write: the one PATCH every Memory meta writer goes through
// (ADR 0077). Kept apart from `nodeMeta.ts` on purpose: that module is the pure envelope and sits in the crypto
// mutation scope, which admits only files that do no I/O; this one is the wiring.
import { contentApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { openNodeMetaForWrite, sealNodeMeta, type RawNodeMeta } from './nodeMeta'
import { writeSealedMeta, type MetaEditContext, type SealedMetaResult, type SealedMetaState } from './sealedMetaWrite'

/**
 * Write a node's sealed meta, revision-checked (ADR 0077): open the blob at `base`, apply `edit`, seal, and PATCH
 * it with the `metaRevision` it was built from; on `409 stale_version` re-run `edit` on the blob the conflict
 * handed back. Every node-meta writer — the three editors and the two self-heals — goes through here.
 */
export function writeNodeMeta(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  base: SealedMetaState,
  edit: (meta: RawNodeMeta, ctx: MetaEditContext) => RawNodeMeta | null | Promise<RawNodeMeta | null>,
  send?: (wrappedMeta: string | null, metaRevision: number) => Promise<void>,
): Promise<SealedMetaResult<RawNodeMeta>> {
  const address = { scopeId, nodeId }
  return writeSealedMeta<RawNodeMeta>(
    base,
    {
      open: (wrappedMeta) => openNodeMetaForWrite(nodeKey, address, wrappedMeta),
      seal: (meta) => sealNodeMeta(nodeKey, address, meta),
      // `send` is the plain `updateNode` unless a writer carries more in the same request: a node's cover write sends the
      // blob step with the meta it names (`cover/coverWrite.ts`), so the two can never part.
      send:
        send ??
        (async (wrappedMeta, metaRevision) => {
          await withRefresh(() =>
            contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta: wrappedMeta!, metaRevision } }),
          )
        }),
    },
    edit,
  )
}

