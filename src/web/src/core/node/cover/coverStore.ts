// The resolved covers of the nodes on screen (inbox #411; Memories' `coverStore` since cr-0072, lifted into core and made
// kind-blind): ONE implementation, two lifetimes. A MEMBER's covers live in a module singleton beside `treeStore`, owned
// by the same lifecycle (`activeSpace` clears it on a scope switch, a rotation or sign-out), so a node's cover is
// decrypted once per scope for every surface that shows it - the level list and its tiles, the node header, the sidebar's
// Favorites, a map pin. A share GUEST's live and die with the page that shows them (`useCovers`), read through the link's
// own gated presign and never through the member's world.
//
// **What it reads.** A node's `cover` field (sealed in its `wrappedMeta`, `coverModel.ts`) under the node's own key. An
// uploaded image core opens itself (the node's `COVER` blob, `openBlob` / the share's presigned chunks); any other source
// is no cover. A kind without a `cover` descriptor has none.
// Best-effort throughout: no cover, a refused read, a key that does not open - all are "no cover", and the surface draws the
// node's icon. Nothing throws into a view.
//
// **The cache key is the sealed meta.** A held entry records the `wrappedMeta` it was resolved from; `patchNodeMeta` writes
// a new blob into the tree after every cover write, so a replaced or removed cover re-resolves (and a removed one DROPS its
// object URL at once) without any surface sending a signal, and no future call site can forget to. A node whose key is not
// openable yet is left un-tried so a later pass retries it, never remembered as "has no cover".
//
// **Key material never enters Vue reactivity** (`activeSpace.ts`'s standing trap): a key is resolved into a local, used,
// and dropped. What is reactive is the URL map, the crops and the set of nodes that name a cover.
import { computed, onBeforeUnmount, getCurrentInstance, reactive, type Ref } from 'vue'
import type { GuestAccess } from '../../access'
import { registry } from '../../applications/registry'
import { openBlob, openBlobVia } from '../../blob/transport'
import { shareChunkUrls } from '../../sharing/guestBlob'
import { useObjectUrls } from '../../../shared/state/useObjectUrls'
import type { NodeKind } from '../kind'
import { unsealNodeMeta } from '../nodeMeta'
import { readCover, type CoverCrop, type DecryptedCover, type LoadedCover } from './coverModel'

/** What the store needs to know of a node: the sealed meta is the cache key, the kind says whether it can carry a cover. */
export interface CoverSubject {
  id: string
  kind: NodeKind
  wrappedMeta: string | null
}

export interface CoverStore {
  /** nodeId -> the decrypted cover + its crop, for every surface that renders one. Read-only to consumers. */
  covers: Ref<Map<string, LoadedCover>>
  /** The nodes whose sealed meta NAMES a cover, whether or not its image has loaded (or can): "Remove cover" is offered
   *  for these, so a cover that fails to open can still be removed. */
  declared: ReadonlySet<string>
  /** Resolve the covers of `nodes` that are missing or stale, best-effort and in parallel. Safe on every render pass. */
  ensure(scopeId: string | null, nodes: readonly CoverSubject[], keyOf: (id: string) => Uint8Array | null): void
  /** Drop every resolved cover, revoking each decrypted URL. */
  reset(): void
}

/** Open an uploaded cover blob: the member's presign, or the link's own gated one for a guest. */
async function openUploaded(scopeId: string, key: Uint8Array, ref: Parameters<typeof openBlob>[2], guest: GuestAccess | null): Promise<Uint8Array> {
  if (!guest) return openBlob(scopeId, key, ref)
  return openBlobVia(scopeId, key, ref, () => shareChunkUrls(guest, ref.blobId, guest.pwToken))
}

/** One node's cover image from its sealed meta, or null. */
async function loadCover(scopeId: string, key: Uint8Array, node: CoverSubject, cover: unknown, guest: GuestAccess | null): Promise<DecryptedCover | null> {
  const read = readCover(cover, node.id)
  if (!read) return null
  try {
    // A source other than the upload (a stored cover naming one of the Memory's photos, from before covers were set only
    // by an upload) has no reader: the icon (uc.platform.node-cover E4).
    if (read.source.kind !== 'upload') return null
    const bytes = await openUploaded(scopeId, key, read.source.ref, guest)
    return { blob: new Blob([bytes as BlobPart]), crop: read.crop }
  } catch {
    return null // a refused read, a key that does not open, a malformed tier: the icon, never a broken tile
  }
}

export function createCoverStore(guest: GuestAccess | null): CoverStore {
  // The one owner of the decrypted covers' object-URL lifecycle. A member's store has no component to unmount with, which
  // is why `useObjectUrls` guards its hook - `reset` is that store's unmount.
  const urls = useObjectUrls()
  const crops = reactive(new Map<string, CoverCrop>())
  const declared = reactive(new Set<string>())
  // nodeId -> the `wrappedMeta` the held entry was resolved from (null: it had none). Absent: un-tried.
  const resolvedFrom = new Map<string, string | null>()

  const covers = computed(() => {
    const m = new Map<string, LoadedCover>()
    for (const [id, url] of urls.urls.value) {
      const crop = crops.get(id)
      if (crop) m.set(id, { url, crop })
    }
    return m
  }) as Ref<Map<string, LoadedCover>>

  function drop(id: string): void {
    urls.drop(id) // a cover that was REMOVED must stop showing, not linger from the previous resolve
    crops.delete(id)
  }

  function ensure(scopeId: string | null, nodes: readonly CoverSubject[], keyOf: (id: string) => Uint8Array | null): void {
    if (!scopeId) return
    for (const node of nodes) {
      const info = registry.kind(node.kind)
      if (!info.supported || !info.cover) continue
      if (resolvedFrom.has(node.id) && resolvedFrom.get(node.id) === node.wrappedMeta) continue // current, nothing to do
      const meta = node.wrappedMeta
      if (!meta) {
        resolvedFrom.set(node.id, null)
        declared.delete(node.id)
        drop(node.id)
        continue // no sealed meta, no cover - and nothing to read for it
      }
      const key = keyOf(node.id)
      if (!key) continue // the node's key is not openable yet - leave un-tried so a later pass retries
      resolvedFrom.set(node.id, meta)
      const raw = unsealNodeMeta(key, { scopeId, nodeId: node.id }, meta).cover
      if (raw) declared.add(node.id)
      else declared.delete(node.id)
      void loadCover(scopeId, key, node, raw, guest).then((cover) => {
        if (resolvedFrom.get(node.id) !== meta) return // a newer meta won the race; its own resolve owns the entry
        if (!cover) {
          drop(node.id)
          return
        }
        crops.set(node.id, cover.crop)
        urls.set(node.id, cover.blob)
      })
    }
  }

  function reset(): void {
    urls.clear()
    crops.clear()
    declared.clear()
    resolvedFrom.clear()
  }

  return { covers, declared, ensure, reset }
}

// --- the member's store --------------------------------------------------------------------------------------------

const member = createCoverStore(null)

/** The member's store itself, for the one surface that is no level view and holds no tree source of its own: the sidebar's
 *  Favorites, which resolves the pinned nodes against the active scope's tree and epoch keys. */
export const memberCoverStore: CoverStore = member

/** nodeId -> the member's decrypted cover + crop, for every surface that renders one. */
export const covers: Ref<Map<string, LoadedCover>> = member.covers

/** The member's nodes whose meta names a cover. */
export const declaredCovers: ReadonlySet<string> = member.declared

/** Drop every resolved cover of the member, revoking each decrypted URL - a scope switch, a rotation, or sign-out. */
export function resetCovers(): void {
  member.reset()
}

// --- one door for a view, member or guest ------------------------------------------------------------------------

export interface UseCovers {
  covers: Ref<Map<string, LoadedCover>>
  declared: ReadonlySet<string>
  /** Resolve the covers of `nodes` not yet resolved from their current `wrappedMeta`; `keyOf` is the session's tree source's. */
  ensure(nodes: readonly CoverSubject[], keyOf: (id: string) => Uint8Array | null): void
}

/**
 * The covers a view shows, for a member (the shared singleton) or a share guest (this page's own store, its object URLs
 * revoked on unmount). The view reads `covers` / `declared` and calls `ensure` with the nodes it draws; it holds no key.
 */
export function useCovers(access: { mode: 'member'; scopeId: string } | GuestAccess): UseCovers {
  if (access.mode === 'member') {
    const scopeId = access.scopeId
    return { covers: member.covers, declared: member.declared, ensure: (nodes, keyOf) => member.ensure(scopeId, nodes, keyOf) }
  }
  const store = createCoverStore(access)
  if (getCurrentInstance()) onBeforeUnmount(store.reset)
  const scopeId = access.head.scopeId
  return { covers: store.covers, declared: store.declared, ensure: (nodes, keyOf) => store.ensure(scopeId, nodes, keyOf) }
}
