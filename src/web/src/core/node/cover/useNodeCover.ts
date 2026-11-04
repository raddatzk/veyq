// A node's cover as a CORE feature for a view (inbox #411): the resolved covers of the nodes it draws (every kind that
// opts in; a share guest's through the link's own reads) AND the core action that sets, changes and removes one, plugged
// into a node's row through `core/node/coreNodeActions.ts` - the way the favorite heart is (`core/favorites/favoriteActions.ts`),
// with no change to any application. A node's header has no cover action: its leading visual is the way in (`canSet`, `open`;
// ADR 0110 decision 11).
//
// Called in a view's SETUP (it uses the i18n, toast and watch contexts) with the view's access, tree source and the nodes it
// draws. ONE action, `cover`, labelled `Set cover` / `Change cover` by whether the node has one, gated by `coverRights`
// (member, writable, keys loaded, a kind that opted in; a guest none); it sits in the row's "more" menu, not as a `first`
// button. It OPENS THE DIALOG (`components/CoverDialog.vue`, mounted by the view through `CoverEditor.vue` from `editor`): the
// one place a cover is chosen, cropped, re-cropped and REMOVED - there is no separate remove action.
//
// The dialog's save is ONE write through the atomic core writer (`coverWrite.ts`: `setNodeCover` / `removeNodeCover` / a plain
// `updateNode`, the blob step and the meta in one request); a new image is a job of the core upload queue, so progress and a
// failure show in the existing tray. A cover is set only by an upload in the dialog; where the kind finds the picked image
// already stored for the node (`KindCover.findStored`: Memories' PREVIEW of an equal photo), the cover is a new row on that
// stored object instead of a second upload (ADR 0013 § 5).
import { ref, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { targetOf, type Access, type TreeSource } from '../../access'
import { registry } from '../../applications/registry'
import { enqueue, QUOTA_EXCEEDED } from '../../blob/queue'
import { shareBlob, type SharedBlob } from '../../blob/sharedBlob'
import type { CoreRowAction } from '../levelRow'
import { nodeMetaState, unsealNodeMeta } from '../nodeMeta'
import { writeUnderMemberNode } from '../rereadNode'
import type { TreeNode } from '../tree'
import { patchNodeMeta, treeStore } from '../treeStore'
import { useToast } from '../../../shared/state/useToast'
import { MetaConflictError, MetaUnreadableError, staleMetaOf } from '../sealedMetaWrite'
import { coverRights } from './rights'
import { COVER_ROLE, normalizeCoverCrop, uploadedCover, type CoverCrop, type CoverSaveIntent, type LoadedCover } from './coverModel'
import { useCovers } from './coverStore'
import { COVER_UNREADABLE, CoverStaleError, DERIVED_META, uploadCoverImage, writeNodeCover } from './coverWrite'
import { coverFileProblem } from './pickImage'

type Translate = (key: string, params?: Record<string, unknown>) => string

/** What the dialog is open on: the node, and its current cover. */
export interface CoverEditorState {
  node: TreeNode
  /** The node's current cover, if its sealed meta names one: the crop to restore and the remove button. */
  cover: { crop: CoverCrop } | null
  /** The current cover's decrypted image (the crop source when re-cropping it); null while it is not loaded. */
  existingSrc: string | null
}

export interface UseNodeCover {
  /** nodeId -> the decrypted cover + crop of the nodes this view draws (member or guest). */
  covers: Ref<Map<string, LoadedCover>>
  /** Whether the node's sealed meta names a cover (shown or not). */
  has(nodeId: string): boolean
  /** Resolve the covers of further nodes this view draws that `nodes` does not list. */
  ensure(nodes: readonly TreeNode[]): void
  /** A row's core action for `node` (a header has none: its leading visual opens the dialog, ADR 0110 decision 11). */
  rowActions(node: TreeNode | null): CoreRowAction[]
  /** Whether whoever looks may set `node`'s cover (`coverRights`): what a kind's own cover control reads (Memories' cover
   *  tile), so it offers exactly what core's action does. */
  canSet(node: TreeNode): boolean
  /** The dialog's state while it is open (mounted by `CoverEditor.vue`), else null. */
  editor: Ref<CoverEditorState | null>
  /** Open the dialog on `node` (a no-op where the right says no). */
  open(node: TreeNode): void
  /** Close the dialog without saving. */
  close(): void
  /** Apply what the dialog emitted for `node` (a no-op where the right says no). */
  save(node: TreeNode, intent: CoverSaveIntent): void
}

/** The reason an upload's failure is shown with: the i18n key of its message. */
function failureKey(code: string): string {
  if (code === QUOTA_EXCEEDED) return 'node.cover.noSpace'
  if (code === 'cover_stale') return 'node.cover.stale'
  if (code === COVER_UNREADABLE) return 'node.cover.unreadable'
  if (code === 'meta_unreadable') return 'node.cover.metaUnreadable'
  return 'node.cover.failed'
}

export function useNodeCover(access: () => Access, tree: () => TreeSource, nodes: () => readonly TreeNode[]): UseNodeCover {
  const { t } = useI18n()
  const toast = useToast()
  const translate: Translate = (key, params) => (params ? t(key, params) : t(key))

  // The view's covers: a member's from the shared store, a guest's this page's own. Resolved for the nodes drawn, watched on
  // the sealed meta too - a cover write patches `wrappedMeta` in place, so the array alone would leave a stale cover.
  const a0 = access()
  const store = useCovers(a0.mode === 'member' ? { mode: 'member', scopeId: a0.scopeId } : a0)
  const sync = (): void => store.ensure(nodes(), (id) => tree().keyOf(id))
  watch(() => nodes().map((n) => `${n.id}:${n.kind}:${n.wrappedMeta ?? ''}`).join('|'), sync, { immediate: true })

  const canSet = (node: TreeNode): boolean => coverRights(access(), targetOf(tree(), node)).setCover

  /** The freshest copy of the node: the tree's, which every cover write patches. */
  const latest = (node: TreeNode): TreeNode => treeStore.nodes.find((n) => n.id === node.id) ?? node
  const findStoredOf = (node: TreeNode) => {
    const info = registry.kind(node.kind)
    return info.supported ? info.cover?.findStored : undefined
  }

  function report(code: string): void {
    toast.error(translate(failureKey(code)))
  }

  // --- the dialog -------------------------------------------------------------------------------------------------------
  const editor = ref<CoverEditorState | null>(null)

  /** The node's stored `cover` field as sealed (opaque to core beyond its crop): null for none - or for a meta that does not
   *  open, which has no cover to show (the writer refuses such a meta itself, `MetaUnreadableError`). */
  function storedCover(node: TreeNode): unknown | null {
    const key = tree().keyOf(node.id)
    const a = access()
    if (!key || a.mode !== 'member') return null
    return unsealNodeMeta(key, { scopeId: a.scopeId, nodeId: node.id }, latest(node).wrappedMeta).cover ?? null
  }

  function open(node: TreeNode): void {
    if (access().mode !== 'member' || !canSet(node)) return
    const fresh = latest(node)
    const loaded = store.covers.value.get(node.id) ?? null
    const declared = store.declared.has(node.id)
    const stored = declared ? storedCover(fresh) : null
    const crop = stored && typeof stored === 'object' ? normalizeCoverCrop((stored as { crop?: unknown }).crop) : (loaded?.crop ?? null)
    editor.value = {
      node: fresh,
      cover: declared ? { crop: crop ?? normalizeCoverCrop(null) } : null,
      existingSrc: loaded?.url ?? null,
    }
  }

  function close(): void {
    editor.value = null
  }

  // --- the write --------------------------------------------------------------------------------------------------------
  async function writeFailed(node: TreeNode, e: unknown): Promise<void> {
    if (e instanceof MetaConflictError) {
      // A peer changed the cover field itself: take what the server holds, re-open the dialog on it and say so.
      patchNodeMeta(node.id, e.current.wrappedMeta, e.current.metaRevision)
      open(node)
      report('cover_stale')
      return
    }
    if (e instanceof CoverStaleError) report('cover_stale')
    else if (e instanceof MetaUnreadableError) report('meta_unreadable')
    else {
      const fresh = await staleMetaOf(e)
      if (fresh) patchNodeMeta(node.id, fresh.wrappedMeta, fresh.metaRevision)
      report('failed')
    }
  }

  function upload(node: TreeNode, key: Uint8Array, scopeId: string, file: File, crop: CoverCrop): void {
    enqueue<void>(
      [
        {
          name: file.name,
          sizeBytes: file.size,
          // A cover another writer changed meanwhile, or an image the browser cannot decode: a retry changes nothing.
          isFinal: (e) => e === 'cover_stale' || e === COVER_UNREADABLE || e === 'meta_unreadable',
          async run({ onProgress, signal }) {
            // The cover is sealed under the node's key, so an epoch refusal - the device floor before anything is sealed
            // (ADR 0109 point 6, layer 2), or the server's `node_rekey_pending` after the upload - runs the WHOLE cover again:
            // this member's device pulls the node's path forward, re-reads its key, and looks up, uploads and writes under it;
            // the first upload's `K_blob` is wrapped under the key the re-key replaced (ADR 0111 decision 11). A node only an
            // owner re-keys (a drop box) is not this member's to pull: the cover waits for an owner's device then.
            await writeUnderMemberNode(scopeId, node.id, { key, epoch: node.epoch }, async (at) => {
              // An equal picture the node already stores is shared, not uploaded again (ADR 0013 § 5); a failure to look is
              // no match. Its row is minted per attempt: a retry never reuses an id the server may have taken.
              const stored = await findStoredOf(node)?.({ scopeId, nodeId: node.id, nodeKey: at.key, file, signal }).catch(() => null)
              const shared: SharedBlob | null = stored
                ? shareBlob(scopeId, stored, at.key, { ownerId: node.id, role: COVER_ROLE }, DERIVED_META)
                : null
              const up = shared
                ? null
                : await uploadCoverImage(
                  scopeId, node.id, at.key,
                  { blob: file, mediaType: file.type, lastModified: file.lastModified, name: file.name },
                  undefined, { strict: true, onProgress, signal },
                )
              // The freshest meta and version, read AFTER the upload: it may have taken a while, and a stale base only costs a
              // retry. The epoch stated is the one `at.key` was read at, never a fresher tree's: the blob is wrapped under it.
              const state = await writeNodeCover({
                scopeId, nodeId: node.id, nodeKey: at.key, current: { ...nodeMetaState(latest(node)), nodeEpoch: at.epoch },
                cover: uploadedCover(shared ? shared.ref : up!.plan, crop),
                uploadId: up?.uploadId, shared: shared?.request, surfaceConflict: false,
              })
              patchNodeMeta(node.id, state.wrappedMeta, state.metaRevision)
            })
            onProgress(1)
          },
        },
      ],
      {
        label: translate('node.cover.batchLabel', { name: node.name }),
        onComplete: (batch) => {
          const failed = batch.files.find((f) => f.status === 'failed')
          if (failed) report(failed.error ?? 'upload_failed')
        },
      },
    )
  }

  /** A cover write that uploads nothing (a removal, a moved crop): `cover` builds the value from the key the attempt holds
   *  and - in a rebuild after an epoch refusal - the node as the re-read tree holds it (`undefined`: nothing to write). The
   *  same pull and rebuild as an upload's (`writeUnderMemberNode`): a kept cover's wraps are re-read from the meta the
   *  re-key re-sealed, never carried over from under the old key. */
  async function writeSealed(
    node: TreeNode, key: Uint8Array, scopeId: string,
    cover: (key: Uint8Array, reread: TreeNode | null) => unknown | null | undefined, surfaceConflict: boolean,
  ): Promise<void> {
    try {
      await writeUnderMemberNode(scopeId, node.id, { key, epoch: node.epoch }, async (at, reread) => {
        const value = cover(at.key, reread)
        if (value === undefined) return
        // the epoch `at.key` was read at, whatever a fresher tree says: the cover's wraps are under `at.key`
        const current = { ...nodeMetaState(latest(node)), nodeEpoch: at.epoch }
        const state = await writeNodeCover({ scopeId, nodeId: node.id, nodeKey: at.key, current, cover: value, surfaceConflict })
        patchNodeMeta(node.id, state.wrappedMeta, state.metaRevision)
      })
    } catch (e) {
      await writeFailed(node, e)
    }
  }

  function save(node: TreeNode, intent: CoverSaveIntent): void {
    const a = access()
    if (a.mode !== 'member' || !canSet(node)) return
    const key = tree().keyOf(node.id)
    if (!key) {
      toast.error(translate('node.cover.locked'))
      return
    }
    if (intent.kind === 'upload') {
      const why = coverFileProblem(intent.file)
      if (why) toast.error(translate(why, { max: '40 MB' }))
      else upload(node, key, a.scopeId, intent.file, intent.crop)
      return
    }
    if (intent.kind === 'remove') {
      void writeSealed(node, key, a.scopeId, () => null, false)
      return
    }
    // keep: the current cover's own value with only the crop moved (its source stays) - in a rebuild the one the re-read
    // meta holds, its wrap moved to the new key by the re-key.
    const stored = storedCover(node)
    if (!stored || typeof stored !== 'object') return
    const scopeId = a.scopeId
    const kept = (k: Uint8Array, reread: TreeNode | null) => {
      const now = reread ? (unsealNodeMeta(k, { scopeId, nodeId: node.id }, reread.wrappedMeta).cover ?? null) : stored
      return now && typeof now === 'object' ? { ...(now as object), crop: intent.crop } : undefined
    }
    void writeSealed(node, key, scopeId, kept, true)
  }

  function build(node: TreeNode | null): CoreRowAction[] {
    const shown = !!node && canSet(node)
    if (!shown) return []
    return [
      {
        id: 'cover',
        shown,
        label: translate(store.declared.has(node.id) ? 'node.cover.change' : 'node.cover.set'),
        icon: 'image',
        placement: 'after',
        onClick: () => open(node),
      },
    ]
  }

  return {
    covers: store.covers,
    has: (id) => store.declared.has(id),
    ensure: (extra) => store.ensure(extra, (id) => tree().keyOf(id)),
    rowActions: (node) => build(node),
    canSet,
    editor,
    open,
    close,
    save,
  }
}
