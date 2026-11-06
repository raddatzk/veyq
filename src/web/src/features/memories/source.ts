// Memories' data source (inbox #401 S4): the one object through which a Memory's detail (`MemoryDetail`, its composables,
// the lightbox it opens, the organizer `MemoryBuckets`) reads and writes, so ONE view serves a member and a share guest.
// The member's wraps the unlocked scope's doors exactly as the view called them before (`media.ts`, the node-meta
// writers, the item marks, the buckets, `treeStore` + the active scope's epochs for the bucket names); the guest's the
// link's (`listShareMedia` + `openSharedMemory`, the gated share presign, `addSharePhoto` with the target node, the
// contributor's delete-own with the device's record of its own uploads, `ownShareMedia.ts`). Neither reads the other's
// world, and which one a view holds is fixed at mount (the host picks it by `access.mode`).
//
// **A door exists only where the session has it.** Every write and every member-only read is an OPTIONAL member of the
// source: the guest's has no media trash, no node meta (date, place, cover), no buckets, no item marks, no heavy EXIF, no
// media-meta writes, and its delete reaches only its own media (an irreversible purge, never the member's trash with
// undo). The live poll both have (inbox #497): a guest's gallery learns of a second uploader's photo as a member's does,
// on a tag that is a digest of what its link serves (ADR 0081 decision 4). A view offers an operation where
// `memoryRights` grants it AND the source carries it (`sourceRights`), so a forgotten guard cannot fall through to a
// member call — there is no member call to fall to.
// A `WRITE_ONLY` guest holds no key to read anything: its page is the drop (`MemoryShareBody`), never this source's, so
// the guest source uploads and deletes for a `READ_WRITE` link only.
//
// Operator-blind as the views: every byte is fetched as ciphertext and opened on the device under the key the caller
// hands in (the node's `K_node` from the tree's chain); the sources move ids, wraps and ciphertext.
import { computed, inject, provide, ref, type ComputedRef, type InjectionKey } from 'vue'
import type { GuestAccess } from '../../core/access'
import { makeChunkProvider, openBlobVia, presignAllChunks } from '../../core/blob/transport'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import type { ShareAt } from '../../core/sharing/shareAt'
import { contentKeyChain, type TreeNode } from '../../core/node/tree'
import { treeStore } from '../../core/node/treeStore'
import { useGuestSession, type GuestIdentity } from '../../core/sharing/guestIdentity'
import type { OpenedBlobMeta } from '../../core/blob/meta'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import { activeEpochKeys } from '../../app/activeSpace'
import { loadScopePeople, type Person } from '../../core/people/directory'
import { ShareRole } from '../../api/generated/sharing'
import type { ShareMediaListing } from '../../api/generated/share-media'
import type { ChunkProvider } from '../../sw/protocol'
import {
  openGrid,
  openLightbox,
  openOriginal,
  openBlob,
  chunkProvider,
  loadMediaExif,
  listMediaPages,
  deleteMedia,
  restoreMedia,
  listTrash,
  purgeMedia,
  setMediaMeta,
  backfillPlaceName,
  type MediaMetaWriteOptions,
  downloadRef,
  listBuckets,
  createBucket,
  renameBucket,
  deleteBucket,
  moveBucketMedia,
  type BlobRef,
  type BucketItem,
  type MediaExif,
  type MediaItem,
  type MediaMeta,
  type MediaMetaTarget,
  type MediaMetaWrite,
} from './media'
import { gridFrameOpener, openedBlobMeta, openedMeta } from './openedListing'
import { setMemoryDate, reconcileMemoryDate, type MemoryDate } from './memoryDate'
import { setMemoryLocation, type MemoryLocation } from './memoryLocation'
import { setMemoryParticipants } from './memoryParticipants'
import { writeNodeMeta } from '../../core/node/nodeMetaWrite'
import type { NodeMetaState } from '../../core/node/nodeMeta'
import { assertActiveScopeFloor, memberNodeMetaOptions, rereadMemberKey, writeUnderMemberNode } from '../../core/node/rereadNode'
import { pullRekeyPath } from '../../core/node/rekey/rekeyScope'
import { loadItemFavoritesFor, toggleItemFavorite } from './itemFavoritesStore'
import {
  addSharePhoto,
  deleteSharePhoto,
  readShareMedia,
  openSharedMemory,
  openShareGrid,
  openShareLightbox,
  openShareOriginal,
  shareChunkProvider,
  type ShareItem,
} from './share'
import { forgetOwnShareMedia, loadOwnShareMedia, rememberOwnShareMedia } from './ownShareMedia'
import type { UploadTarget } from './uploadQueue'
import type { MemoryRights } from './rights'
import type { ScopeKey } from '../spaces/epochs'
import { uploadBanner, writeMemoryBanner, type PreparedBanner, type UploadedBanner, type WriteMemoryBannerArgs } from './banner/bannerWrite'

/** A node's media as a source lists it — every page of it (ADR 0033 decision 6): the live media and, where the session
 *  polls, the revisions a live poll continues from (the FIRST page's; `null`: this source has no live poll here) and the
 *  cursor (the LAST page's; `null`: no keyset to continue, or the node holds nothing yet). `tag` is what the poll echoes
 *  as `If-None-Match`, opaque to the view: a member's is the node's item revision (ADR 0033), a guest's a digest of what
 *  the link serves (ADR 0081 decision 4). */
/** A node as a write under its key names it: its id and the epoch its key was minted at (`TreeNode.epoch`, ADR 0109
 *  point 6). */
export type NodeAt = { id: string; epoch: number }

export type MediaListing = { items: MediaItem[]; revision: { tag: string; delete: number } | null; cursor: string | null }

/** Where a live poll continues from (uc.memory.live-updates, cr-0107). */
export type PollCursor = { tag: string; since?: string; deleteRevision: number }

/** One live poll's answer: nothing changed (`304`), or the change — a member's keyset delta with the node's id set only
 *  after a removal, a guest's whole listing with every id it holds (a guest is told no order of changes to continue
 *  from, ADR 0033 decision 6) — and the revisions the next poll echoes. */
export type PollPage =
  | { notModified: true }
  | { notModified: false; items: MediaItem[]; tag: string; deleteRevision: number; mediaIds: string[] | null; cursor: string | null }

/** How a media's bytes and its listed seals open — every one under `key`, the node's `K_node` from the chain. */
export interface MediaLoaders {
  /** The grid tier (thumbnail): a grid's bulk read, dropped while it still waits once `signal` aborts (inbox #488). */
  grid(item: MediaItem, key: Uint8Array, signal?: AbortSignal): Promise<Uint8Array>
  /** The lightbox tier (preview). */
  preview(item: MediaItem, key: Uint8Array): Promise<Uint8Array>
  /** The whole original (the lightbox's video fallback without a service worker). */
  original(item: MediaItem, key: Uint8Array): Promise<Uint8Array>
  /** The key-free chunk source over the original (service-worker playback and the streamed download, ADR 0047). */
  chunk(item: MediaItem, key: Uint8Array): ChunkProvider
  /** The listed metadata tier (capture time, geometry, GPS). */
  meta(item: MediaItem, key: Uint8Array): MediaMeta | null
  /** The original's sealed blob meta (type, file name, modified time — inbox #397). */
  blobMeta(item: MediaItem, key: Uint8Array): OpenedBlobMeta | null
  /** A blob attached to the node itself, whole: its uploaded cover (`COVER`), its banner's image or poster (`BANNER`,
   *  `BANNER_POSTER`). */
  coverBlob(ref: BlobRef, key: Uint8Array): Promise<Uint8Array>
  /** The key-free chunk source over a blob attached to the node itself - a video banner's playback through the service
   *  worker (ADR 0047) and its streamed export. */
  attachmentChunks(ref: BlobRef, key: Uint8Array): ChunkProvider
  /** The heavy EXIF bundle, fetched lazily per opened item. Absent: no endpoint serves it to this session (a guest). */
  exif?(item: MediaItem, key: Uint8Array): Promise<MediaExif | null>
}

/** The upload door of a session that has one. */
export interface MemoryUpload {
  /** The session may pick files now. False: `prepare` first (a guest names itself through the page's one flow) — the
   *  pick follows on the next click, because a picker opened after an awaited dialog no longer counts as the click. */
  ready(): boolean
  /** Make the session ready (a guest: the page's naming flow); resolves whether it now is. */
  prepare(): Promise<boolean>
  /** The upload-queue target of a batch into `nodeId` sealed under `key`, or null when the session cannot send now. */
  target(nodeId: string, key: Uint8Array, nodeEpoch: number): UploadTarget | null
  /** The uploads go through a link: a `quota_exceeded` refusal then reads as the link being full — never which limit
   *  was reached — and offers no retry, because none can help (ADR 0073 §6). */
  readonly viaLink: boolean
}

/** Which media a delete reaches, and how: a member's trash (reversible, undo) or a guest's purge of its own
 *  (irreversible — the view confirms first). */
export type MediaDelete =
  | { readonly reach: 'any'; trash(mediaId: string): Promise<void>; restore(mediaId: string): Promise<void> }
  | { readonly reach: 'own'; isOwn(mediaId: string): boolean; purge(mediaId: string): Promise<void> }

/** The node's media trash (cr-0110/0111). */
export interface MediaTrashDoor {
  list(nodeId: string): Promise<MediaItem[]>
  restore(mediaId: string): Promise<void>
  purge(mediaId: string): Promise<void>
}

/** The node's sealed meta writes (date, place; the automatic reconcile of both) — ADR 0077's version-checked PATCH. */
export interface NodeMetaDoor {
  setDate(nodeId: string, key: Uint8Array, date: MemoryDate, current: NodeMetaState): Promise<SealedMetaState>
  reconcileDate(nodeId: string, key: Uint8Array, items: MediaItem[], current: NodeMetaState): Promise<{ state: SealedMetaState; wrote: boolean }>
  setLocation(nodeId: string, key: Uint8Array, location: MemoryLocation | null, current: NodeMetaState): Promise<SealedMetaState>
  /** The Memory's participants (opaque user ids, ADR 0083): a merging read-modify-write, see `memoryParticipants.ts`. */
  setParticipants(nodeId: string, key: Uint8Array, userIds: readonly string[], current: NodeMetaState): Promise<SealedMetaState>
  /** The read-modify-write the location's self-heal runs its own edit through. */
  write: (
    nodeId: string,
    key: Uint8Array,
    current: NodeMetaState,
    edit: Parameters<typeof writeNodeMeta>[4],
  ) => ReturnType<typeof writeNodeMeta>
}

/** A medium's own meta writes from the lightbox (capture-time correction, the resolved place name). */
export interface MediaMetaDoor {
  set(target: MediaMetaTarget, key: Uint8Array, node: NodeAt, overrideMs: number | null): Promise<MediaMetaWrite>
  backfillPlace(target: MediaMetaTarget, key: Uint8Array, node: NodeAt, placeName: string, placeLevel?: string): Promise<MediaMetaWrite>
}

/** A Memory's banner (uc.memory.banner, ADR 0102): the upload of its bytes and the one write of its slots and the meta
 *  that names them. A member's only: no link role has a banner operation. */
export interface BannerDoor {
  upload(
    nodeId: string, key: Uint8Array, prepared: PreparedBanner, opts?: { onProgress?: (fraction: number) => void; signal?: AbortSignal },
  ): Promise<UploadedBanner>
  write(args: Omit<WriteMemoryBannerArgs, 'scopeId'>): Promise<SealedMetaState>
  /**
   * Run one banner save at the node's key `at` (its key and the epoch it was read at): the device floor before each attempt
   * and, on an epoch refusal, the node's path pulled forward on this device and the key re-read (ADR 0111 decision 11) —
   * `save` then runs again from scratch under it, the upload included (the first upload's `K_blob` is wrapped under the key
   * the re-key replaced), handed the node as the re-read tree holds it (null on the first attempt).
   */
  under<T>(nodeId: string, at: { key: Uint8Array; epoch: number }, save: (at: { key: Uint8Array; epoch: number }, reread: TreeNode | null) => Promise<T>): Promise<T>
}

/** The item marks (ADR 0068): sealed under the scope's one key (ADR 0115 decision 1), the scope members' own. */
export interface MarksDoor {
  load(nodeId: string, scopeKey: ScopeKey): Promise<void>
  toggle(mediaId: string, nodeId: string, scopeKey: ScopeKey): Promise<void>
}

/** The contributions organizer (uc.memory.buckets): the buckets, the scope's members that name them, and the moves. */
export interface BucketsDoor {
  list(nodeId: string): Promise<BucketItem[]>
  /** The scope's current members, named by the display rule (ADR 0083 decision 9): core's directory, the tenant's names. */
  people(): Promise<Person[]>
  /** The keys of `nodeId`'s ancestors, nearest first — a link's guest named itself under its root's key. */
  ancestorKeys(nodeId: string): Uint8Array[]
  create(nodeId: string, key: Uint8Array, nodeEpoch: number, name: string, parentBucketId?: string): Promise<BucketItem>
  rename(nodeId: string, key: Uint8Array, nodeEpoch: number, bucketId: string, name: string): Promise<BucketItem>
  remove(nodeId: string, bucketId: string): Promise<void>
  move(nodeId: string, mediaIds: string[], targetBucketId: string | null): Promise<void>
  trash(mediaId: string): Promise<void>
  restore(mediaId: string): Promise<void>
}

/** Memories' data source; see the header. */
export interface MemorySource {
  /** The scope every seal binds (its id, never a key). */
  scopeId(): string
  /** The upload-queue grouping of a node's batches — which in-flight tiles a gallery shows (one per node and session). */
  batchKey(nodeId: string): string
  /** The live media of `nodeId` in full, every page read before it answers. Throws what the read throws. */
  listMedia(nodeId: string): Promise<MediaListing>
  readonly loaders: MediaLoaders
  /** One live poll from `cursor` (a conditional read, every page of it). Absent: no live updates — the view re-reads
   *  after its own writes. */
  readonly poll?: (nodeId: string, cursor: PollCursor) => Promise<PollPage>
  /** Upload into a node. Absent: this session uploads nothing. */
  readonly upload?: MemoryUpload
  /** Delete media. Absent: this session deletes nothing. */
  readonly deleteMedia?: MediaDelete
  /** The node's media trash. Absent: none. */
  readonly trash?: MediaTrashDoor
  /** The node's date and place, their automatic reconcile included. Absent: read-only meta, no reconcile. */
  readonly nodeMeta?: NodeMetaDoor
  /** The node's banner writes. Absent: the banner is shown, never changed. */
  readonly banner?: BannerDoor
  /** A medium's capture-time correction and place-name backfill. Absent: the lightbox is read-only. */
  readonly mediaMeta?: MediaMetaDoor
  /** The item marks. Absent: none shown, none loaded. */
  readonly marks?: MarksDoor
  /** The contributions organizer. Absent: no organizer. */
  readonly buckets?: BucketsDoor
}

/** The rights a view shows: the matrix's answer, narrowed to the doors `source` carries. A delete keeps its reach
 *  only where the source's delete has that same reach (a member's trash for `any`, a guest's purge for `own`). */
export function sourceRights(rights: MemoryRights, source: MemorySource): MemoryRights {
  return {
    ...rights,
    upload: rights.upload && !!source.upload,
    deleteMedia: rights.deleteMedia !== 'none' && source.deleteMedia?.reach === rights.deleteMedia ? rights.deleteMedia : 'none',
    editMeta: rights.editMeta && !!source.nodeMeta,
    setBanner: rights.setBanner && !!source.banner,
    // shown only by a source that can read the node's meta as a member: the guest's source carries no `nodeMeta` door
    participants: rights.participants && !!source.nodeMeta,
    mark: rights.mark && !!source.marks,
    mediaTrash: rights.mediaTrash && !!source.trash,
    buckets: rights.buckets && !!source.buckets,
  }
}

/** What `MemoryDetail` hands its deep children (the organizer, …): the source and the rights it shows. */
export interface MemoryView {
  readonly source: MemorySource
  readonly rights: ComputedRef<MemoryRights>
}
/** The injection key of {@link MemoryView} — exported for a test that mounts a child on its own (`global.provide`). */
export const MEMORY_VIEW: InjectionKey<MemoryView> = Symbol('memories.view')

/** Provide the view to the detail's children (`MemoryDetail`). */
export function provideMemoryView(view: MemoryView): void {
  provide(MEMORY_VIEW, view)
}

/** The view a child of a Memory's detail serves. Throws outside one: no child falls back to the member's doors. */
export function useMemoryView(): MemoryView {
  const view = inject(MEMORY_VIEW, null)
  if (!view) throw new Error('useMemoryView outside a Memory detail')
  return view
}

/** How a member's media-meta write under `nodeId` of the active scope `scopeId` meets an epoch refusal: the device floor
 *  before each attempt, the pull of the node's path and the re-read of its key (ADR 0111 decision 11): the lightbox's
 *  edit and the place backfill. */
export function memberMediaMetaOptions(scopeId: string, nodeId: string): MediaMetaWriteOptions {
  return {
    atFloor: (epoch) => assertActiveScopeFloor(scopeId, { id: nodeId, epoch }),
    reread: rereadMemberKey(scopeId, nodeId),
    pull: pullRekeyPath(scopeId, [nodeId]),
  }
}

/** A member's source over the scope `scopeId` (a getter: the host's scope changes under it on a scope switch). Every
 *  door is today's call, unchanged — the wire is `media.ts`'s (`uploadWire.*` snapshots). */
export function memberMemorySource(from: { scopeId: () => string }): MemorySource {
  const scope = from.scopeId
  const mediaMetaOptions = (nodeId: string): MediaMetaWriteOptions => memberMediaMetaOptions(scope(), nodeId)
  return {
    scopeId: scope,
    batchKey: (nodeId) => nodeId,
    async listMedia(nodeId) {
      const page = await listMediaPages(scope(), nodeId) // every page — no ETag, so never a 304
      if (page.notModified) return { items: [], revision: null, cursor: null } // unreachable without an ETag: exhaustiveness
      return { items: page.items, revision: { tag: String(page.mediaRevision), delete: page.deleteRevision }, cursor: page.cursor }
    },
    loaders: {
      grid: (item, key, signal) => openGrid(scope(), key, item, signal, gridFrameOpener(scope(), key)),
      preview: (item, key) => openLightbox(scope(), key, item),
      original: (item, key) => openOriginal(scope(), key, item),
      chunk: (item, key) => chunkProvider(scope(), key, item),
      meta: (item, key) => openedMeta(scope(), key, item),
      blobMeta: (item, key) => openedBlobMeta(scope(), key, item),
      coverBlob: (ref, key) => openBlob(scope(), key, ref),
      attachmentChunks: (ref, key) => makeChunkProvider(scope(), key, ref, () => presignAllChunks(scope(), ref.blobId)),
      exif: (item, key) => loadMediaExif(scope(), item, key),
    },
    async poll(nodeId, c) {
      const page = await listMediaPages(scope(), nodeId, { ifNoneMatch: c.tag, since: c.since, sinceDelete: c.deleteRevision })
      if (page.notModified) return page
      return { notModified: false, items: page.items, tag: String(page.mediaRevision), deleteRevision: page.deleteRevision, mediaIds: page.mediaIds, cursor: page.cursor }
    },
    upload: {
      ready: () => true,
      prepare: async () => true,
      target: (nodeId, key, nodeEpoch) => ({
        scopeId: scope(), nodeId, memoryKey: key, nodeEpoch,
        // the device floor before anything is sealed under the node's key (ADR 0109 point 6, layer 2)
        atFloor: (epoch) => assertActiveScopeFloor(scope(), { id: nodeId, epoch }),
        // a member's upload pulls the node's path forward on an epoch refusal and seals again (ADR 0111 decision 11)
        pull: pullRekeyPath(scope(), [nodeId]),
        reread: rereadMemberKey(scope(), nodeId),
      }),
      viaLink: false,
    },
    deleteMedia: {
      reach: 'any',
      trash: (mediaId) => deleteMedia(scope(), mediaId),
      restore: (mediaId) => restoreMedia(scope(), mediaId),
    },
    trash: {
      list: (nodeId) => listTrash(scope(), nodeId),
      restore: (mediaId) => restoreMedia(scope(), mediaId),
      purge: (mediaId) => purgeMedia(scope(), mediaId),
    },
    nodeMeta: {
      // A member's meta writes carry plain values only, so after an epoch refusal they are rebuilt under the node's key
      // as a re-read finds it; the floor runs before each attempt (ADR 0109 point 6).
      setDate: (nodeId, key, date, current) => setMemoryDate(scope(), nodeId, key, date, current, memberNodeMetaOptions(scope(), nodeId)),
      reconcileDate: (nodeId, key, items, current) =>
        reconcileMemoryDate(scope(), nodeId, key, items, current, memberNodeMetaOptions(scope(), nodeId)),
      setLocation: (nodeId, key, location, current) =>
        setMemoryLocation(scope(), nodeId, key, location, current, memberNodeMetaOptions(scope(), nodeId)),
      setParticipants: (nodeId, key, userIds, current) =>
        setMemoryParticipants(scope(), nodeId, key, userIds, current, memberNodeMetaOptions(scope(), nodeId)),
      write: (nodeId, key, current, edit) => writeNodeMeta(scope(), nodeId, key, current, edit, undefined, memberNodeMetaOptions(scope(), nodeId)),
    },
    banner: {
      upload: (nodeId, key, prepared, opts) => uploadBanner(scope(), nodeId, key, prepared, opts),
      write: (args) => writeMemoryBanner({ ...args, scopeId: scope() }),
      under: (nodeId, at, save) => writeUnderMemberNode(scope(), nodeId, at, save),
    },
    // A medium's meta is re-sealed under its node's key: the floor before each attempt, and after an epoch refusal the
    // node's path pulled forward on this device and the key re-read for a rebuild (ADR 0111 decision 11, `persistMeta`).
    mediaMeta: {
      set: (target, key, node, overrideMs) => setMediaMeta(scope(), target, key, node.epoch, overrideMs, mediaMetaOptions(node.id)),
      backfillPlace: (target, key, node, name, level) =>
        backfillPlaceName(scope(), target, key, node.epoch, name, level, mediaMetaOptions(node.id)),
    },
    marks: {
      load: (nodeId, scopeKey) => loadItemFavoritesFor(scope(), nodeId, scopeKey),
      toggle: (mediaId, nodeId, scopeKey) => toggleItemFavorite(mediaId, scope(), nodeId, scopeKey),
    },
    buckets: {
      list: (nodeId) => listBuckets(scope(), nodeId),
      people: () => loadScopePeople(scope()),
      ancestorKeys(nodeId) {
        const epochs = activeEpochKeys()
        if (!epochs) return []
        try {
          return contentKeyChain(scope(), epochs, treeStore.nodes, nodeId).slice(1)
        } catch {
          return [] // a chain that does not open: only the node's own key
        }
      },
      // a bucket's name is sealed afresh under the node's key: after an epoch refusal the node's path is pulled forward
      // and the name sealed again under its key now (ADR 0111 decision 11); the floor runs before each attempt
      create: (nodeId, key, nodeEpoch, name, parent) =>
        writeUnderMemberNode(scope(), nodeId, { key, epoch: nodeEpoch }, (at) => createBucket(scope(), nodeId, at.key, at.epoch, name, parent)),
      rename: (nodeId, key, nodeEpoch, bucketId, name) =>
        writeUnderMemberNode(scope(), nodeId, { key, epoch: nodeEpoch }, (at) => renameBucket(scope(), nodeId, at.key, at.epoch, bucketId, name)),
      remove: (nodeId, bucketId) => deleteBucket(scope(), nodeId, bucketId),
      move: (nodeId, mediaIds, target) => moveBucketMedia(scope(), nodeId, mediaIds, target),
      trash: (mediaId) => deleteMedia(scope(), mediaId),
      restore: (mediaId) => restoreMedia(scope(), mediaId),
    },
  }
}

/**
 * A share guest's source over its link (`access`, a getter: a re-resolve replaces the head). `rootId` is the share root
 * (the tree's): an upload into it names no target node, as every upload of a link always did; one into a Memory or
 * chapter below names that node (ADR 0080 decision 6), sealed under ITS key from the chain — the server then creates
 * the guest's own bucket there (`ensurePrimaryBucket` for `share:<identity>`).
 *
 * Reads for `READ` and `READ_WRITE`; the upload and the delete of its own media for `READ_WRITE` only (see the header).
 */
export function guestMemorySource(access: () => GuestAccess, tree: { readonly rootId: string | null }): MemorySource {
  const a0 = access()
  const shareId = a0.shareId
  // The link as every guest read and write addresses it: its id and the instance that serves it (ADR 0121).
  const at: ShareAt = { shareId, apiBase: a0.apiBase }
  const contributes = a0.role === ShareRole.ReadWrite
  const scope = () => access().head.scopeId
  // A served media as the share helpers take it: opened under `key` (the node's, from the chain), its origin on the item.
  const shareItem = (item: MediaItem, key: Uint8Array): ShareItem => ({ item, memoryKey: key, scopeId: scope(), nodeId: '' })
  // The listing's media of `nodeId`, opened: the walk keys every media by its node; one whose node's key did not open is
  // not served (`openSharedMemory`), and the filter keeps a moderator's review — the whole held subtree — to this node.
  const openedIn = (a: GuestAccess, nodeId: string, listing: ShareMediaListing): MediaItem[] =>
    openSharedMemory(a.head, listing).items.filter((si) => si.nodeId === nodeId).map((si) => si.item)

  return {
    scopeId: scope,
    batchKey: (nodeId) => `share:${shareId}:${nodeId}`,
    async listMedia(nodeId) {
      const a = access()
      if (a.role === ShareRole.WriteOnly) return { items: [], revision: null, cursor: null } // served nothing, holds no key (ADR 0031)
      // The guest lists the node it has open, every page of it, as a member does (`listShareMedia` with its node, ADR
      // 0033 decision 6), and keeps the listing's tag for the live poll — none for a moderator's review, a snapshot.
      const read = await readShareMedia(at, a.role, a.pwToken, nodeId)
      const items = read ? openedIn(a, nodeId, read.listing) : []
      return { items, revision: read?.etag ? { tag: read.etag, delete: 0 } : null, cursor: null }
    },
    // The guest's live poll (inbox #497): the listing again, conditional on the tag of what the gallery holds — a `304`
    // while the link serves the same, else the whole listing with every id in it, so the view adds and drops by the one
    // merge a member's poll goes through. No keyset: a guest pages by id and is told no order of the Memory's changes.
    async poll(nodeId, c) {
      const a = access()
      const read = await readShareMedia(at, a.role, a.pwToken, nodeId, c.tag)
      if (!read) return { notModified: true }
      const items = openedIn(a, nodeId, read.listing)
      return { notModified: false, items, tag: read.etag ?? c.tag, deleteRevision: 0, mediaIds: items.map((m) => m.mediaId), cursor: null }
    },
    loaders: {
      grid: (item, key, signal) => openShareGrid(at, shareItem(item, key), access().pwToken, signal, gridFrameOpener(scope(), key)),
      preview: (item, key) => openShareLightbox(at, shareItem(item, key), access().pwToken),
      original: (item, key) => openShareOriginal(at, shareItem(item, key), access().pwToken),
      chunk: (item, key) => shareChunkProvider(at, shareItem(item, key), downloadRef(item), access().pwToken),
      meta: (item, key) => openedMeta(scope(), key, item),
      blobMeta: (item, key) => openedBlobMeta(scope(), key, item),
      // The node's uploaded cover through the gated share presign, which serves a cover blob below the share.
      coverBlob: (ref, key) => openBlobVia(scope(), key, ref, () => shareChunkUrls(at, ref.blobId, access().pwToken)),
      // A video banner's chunks through the same gated presign: the share serves every attachment below its root.
      attachmentChunks: (ref, key) => makeChunkProvider(scope(), key, ref, () => shareChunkUrls(at, ref.blobId, access().pwToken)),
    },
    ...(contributes ? guestWrites(access, tree, at, scope) : {}),
  }
}

/** A contributor's two doors: upload into the node on screen, and the irreversible delete of what this device sent. */
function guestWrites(
  access: () => GuestAccess,
  tree: { readonly rootId: string | null },
  at: { readonly shareId: string; readonly apiBase: string },
  scope: () => string,
): Pick<MemorySource, 'upload' | 'deleteMedia'> {
  const { shareId, apiBase } = at
  // The page's one session (named or forgotten anywhere on it), and the device's record of which listed media it added
  // under that identity (#361) — the server tells no guest whose an upload is (ADR 0030 point 8).
  const session = useGuestSession<GuestIdentity>(at)
  const recorded = ref(0) // bumped on every remember/forget, so `own` re-reads the record
  const own = computed(() => {
    void recorded.value
    const s = session.value
    return s ? loadOwnShareMedia(at, s.identityId) : new Set<string>()
  })
  return {
    upload: {
      ready: () => !!session.value,
      prepare: async () => !!(await access().requestGuest()),
      viaLink: true,
      target(nodeId, key, nodeEpoch) {
        const s = session.value
        if (!s) return null
        return {
          key: `share:${shareId}:${nodeId}`,
          async upload(source, opts) {
            const a = access()
            const added = await addSharePhoto(
              {
                ...s,
                shareId,
                apiBase,
                scopeId: scope(),
                sealKey: key,
                nodeEpoch,
                pwToken: a.pwToken,
                ...(nodeId === tree.rootId ? {} : { nodeId }),
              },
              source,
              undefined,
              opts,
            )
            rememberOwnShareMedia(at, s.identityId, added.mediaId)
            recorded.value++
            return added
          },
        }
      },
    },
    deleteMedia: {
      reach: 'own',
      isOwn: (mediaId) => own.value.has(mediaId),
      async purge(mediaId) {
        const s = session.value
        if (!s) throw new Error('no_guest_session')
        await deleteSharePhoto(at, s.contributorToken, mediaId, access().pwToken)
        forgetOwnShareMedia(at, s.identityId, mediaId)
        recorded.value++
      },
    },
  }
}
