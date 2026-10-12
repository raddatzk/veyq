// A node's cover as a CORE feature for a view (inbox #411): the resolved covers of the nodes it draws (every kind that
// opts in; a share guest's through the link's own reads) AND the core actions that set, change and remove one, plugged
// into the two places a node shows its actions - `NodeHeader`'s `coreActions` and the level list's `coreRowActions` - the
// way the favorite heart is (`core/favorites/favoriteActions.ts`), with no change to any application.
//
// Called in a view's SETUP (it uses the i18n, toast and watch contexts) with the view's access, tree source and the nodes it
// draws. The actions are gated by `coverCapabilities` (member, writable, keys loaded, a kind that opted in; a guest none), and
// are `Set cover` / `Change cover` (one action `cover`, its label by whether the node has one) and `Remove cover`
// (`cover-remove`, only where there is one). Neither is a `first` row action: both sit in the row's "more" menu.
//
// **The picker is core's** (`pickImage.ts`, a programmatic `image/*` input); the image is validated (type and size, a clear
// message), derived with the existing pipeline into the cover size, uploaded as the node's own `COVER` blob and attached in
// ONE write with the meta that names it (`coverWrite.ts`) - as a job in the core upload queue, so progress and a failure show
// in the existing tray. A kind whose cover has a source UI of its own (Memories: a photo of the Memory, a crop) hands
// `choose` to the header action: core owns the action's id and place, the application supplies the way to choose.
import { watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { targetOf, type Access, type TreeSource } from '../../access'
import { enqueue, QUOTA_EXCEEDED } from '../../blob/queue'
import type { CoreHeaderAction } from '../nodeHeader'
import type { CoreRowAction } from '../levelRow'
import { nodeMetaState } from '../nodeMeta'
import type { TreeNode } from '../tree'
import { patchNodeMeta, treeStore } from '../treeStore'
import { useToast } from '../../../shared/state/useToast'
import { MetaUnreadableError } from '../sealedMetaWrite'
import { coverCapabilities } from './capabilities'
import { uploadedCover, type LoadedCover } from './coverModel'
import { useCovers } from './coverStore'
import { COVER_UNREADABLE, CoverStaleError, uploadCoverImage, writeNodeCover } from './coverWrite'
import { coverFileProblem, pickImageFile } from './pickImage'

type Translate = (key: string, params?: Record<string, unknown>) => string

export interface UseNodeCover {
  /** nodeId -> the decrypted cover + crop of the nodes this view draws (member or guest). */
  covers: Ref<Map<string, LoadedCover>>
  /** Whether the node's sealed meta names a cover (shown or not). */
  has(nodeId: string): boolean
  /** Resolve the covers of further nodes this view draws that `nodes` does not list. */
  ensure(nodes: readonly TreeNode[]): void
  /** The header's core actions for `node`; `choose` replaces the picker with the application's own source UI. */
  headerActions(node: TreeNode | null, opts?: { choose?: () => void }): CoreHeaderAction[]
  /** A row's core actions for `node`. */
  rowActions(node: TreeNode | null): CoreRowAction[]
}

/** The reason an upload's failure is shown with: the i18n key of its message. */
function failureKey(code: string): string {
  if (code === QUOTA_EXCEEDED) return 'node.cover.noSpace'
  if (code === 'cover_stale') return 'node.cover.stale'
  if (code === COVER_UNREADABLE) return 'node.cover.unreadable'
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

  const canSet = (node: TreeNode): boolean => coverCapabilities(access(), targetOf(tree(), node)).setCover

  /** The freshest copy of the node: the tree's, which every cover write patches. */
  const latest = (node: TreeNode): TreeNode => treeStore.nodes.find((n) => n.id === node.id) ?? node

  function report(code: string): void {
    toast.error(translate(failureKey(code)))
  }

  async function change(node: TreeNode, choose?: () => void): Promise<void> {
    const a = access()
    if (a.mode !== 'member' || !canSet(node)) return
    if (choose) {
      choose()
      return
    }
    const file = await pickImageFile()
    if (!file) return
    const problem = coverFileProblem(file)
    if (problem) {
      toast.error(translate(problem, { max: '40 MB' }))
      return
    }
    const key = tree().keyOf(node.id)
    if (!key) {
      toast.error(translate('node.cover.locked'))
      return
    }
    const scopeId = a.scopeId
    enqueue<void>(
      [
        {
          name: file.name,
          sizeBytes: file.size,
          // A cover another writer changed meanwhile, or an image the browser cannot decode: a retry changes nothing.
          isFinal: (e) => e === 'cover_stale' || e === COVER_UNREADABLE || e === 'meta_unreadable',
          async run({ onProgress, signal }) {
            const up = await uploadCoverImage(
              scopeId, node.id, key,
              { blob: file, mediaType: file.type, lastModified: file.lastModified, name: file.name },
              undefined, { strict: true, onProgress, signal },
            )
            // The freshest meta and version, read AFTER the upload: it may have taken a while, and a stale base only costs a retry.
            const state = await writeNodeCover({
              scopeId, nodeId: node.id, nodeKey: key, current: nodeMetaState(latest(node)),
              cover: uploadedCover(up.plan), uploadId: up.uploadId, surfaceConflict: false,
            })
            patchNodeMeta(node.id, state.wrappedMeta, state.metaVersion)
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

  async function remove(node: TreeNode): Promise<void> {
    const a = access()
    if (a.mode !== 'member' || !canSet(node)) return
    const key = tree().keyOf(node.id)
    if (!key) {
      toast.error(translate('node.cover.locked'))
      return
    }
    try {
      const state = await writeNodeCover({
        scopeId: a.scopeId, nodeId: node.id, nodeKey: key, current: nodeMetaState(latest(node)), cover: null, surfaceConflict: false,
      })
      patchNodeMeta(node.id, state.wrappedMeta, state.metaVersion)
    } catch (e) {
      if (e instanceof CoverStaleError) report('cover_stale')
      else if (e instanceof MetaUnreadableError) report('meta_unreadable')
      else report('failed')
    }
  }

  function build(node: TreeNode | null, choose?: () => void): (CoreHeaderAction & CoreRowAction)[] {
    if (!node || !canSet(node)) return []
    const has = store.declared.has(node.id)
    return [
      {
        id: 'cover',
        label: translate(has ? 'node.cover.change' : 'node.cover.set'),
        icon: 'pi pi-image',
        placement: 'after',
        onClick: () => void change(node, choose),
      },
      ...(has
        ? [{ id: 'cover-remove', label: translate('node.cover.remove'), icon: 'pi pi-image', placement: 'after' as const, class: 'is-cover-remove', onClick: () => void remove(node) }]
        : []),
    ]
  }

  return {
    covers: store.covers,
    has: (id) => store.declared.has(id),
    ensure: (extra) => store.ensure(extra, (id) => tree().keyOf(id)),
    headerActions: (node, opts) => build(node, opts?.choose),
    rowActions: (node) => build(node),
  }
}
