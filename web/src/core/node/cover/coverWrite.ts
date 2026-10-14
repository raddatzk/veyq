// The WRITE side of a node's cover (inbox #411): derive the image on device, seal and upload it as the node's own `COVER`
// blob, and write the cover with the sealed meta that names it in ONE request (`setNodeCover` / `removeNodeCover`) - so the
// slot and the meta can never part, and a failed new cover leaves the old one exactly as it was (the server compare-and-sets
// the blob it replaces and the meta version, and rolls both back together).
//
// Operator-blind: the image is downscaled and sealed here under a fresh `K_blob`, wrapped under the NODE's own content key
// bound to (blob, node, `COVER`) (ADR 0072, `core/blob/transport.planBlob`) - the very wrap and blob-meta purposes a Memory's
// uploaded cover has always used, no second cover format. The server sees ciphertext chunks, the opaque attachment row and
// the sealed meta; never the image, the crop, or which source a cover has.
//
// One writer for every kind. A cover is `{ source, crop }` (`coverModel.ts`); an application that has another source
// (Memories: one of its own photos) passes its own `cover` value, and the writer decides the blob step from the meta it is
// replacing: the old cover's uploaded blob, if any, is the compare value (`expectedBlobId` / the removal's `blobId`), read
// again from the blob a `409 stale_version` hands back, so a retry is judged against what the server holds now.
import { contentApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { HttpStatus, isCodedError } from '../../../shared/httpError'
import { createBrowserDeriver, type AddMediaSource, type MediaDeriver } from '../../../shared/image/mediaDeriver'
import { authedReserve, authedWindow, planBlob, reserveAndPump } from '../../blob/transport'
import type { BlobMeta } from '../../blob/meta'
import type { BlobSource } from '../../blob/types'
import { editField, unsealNodeMeta } from '../nodeMeta'
import { writeNodeMeta } from '../nodeMetaWrite'
import type { SealedMetaState } from '../sealedMetaWrite'
import { COVER_ROLE, uploadBlobIdOf } from './coverModel'

/**
 * Cover downscale spec: a cover is only ever shown small-to-medium (a tile, a header, a 34 px pin), so an uploaded cover is
 * downscaled to ONE cover-sized JPEG, no tiers. The long edge matches the preview tier; the normalized crop then renders it
 * identically at any size. The very spec Memories' covers have always used.
 */
export const COVER_SPEC = { maxEdge: 2048, quality: 0.85 }

/** The largest picked image the client takes as a cover source (decoded in the browser, then downscaled): 40 MB. */
export const MAX_COVER_SOURCE_BYTES = 40 * 1024 * 1024

/** The sealed blob-meta of a derived cover: the JPEG it was re-encoded as. */
const DERIVED_META: BlobMeta = { type: 'image/jpeg' }

/** The failure code of an image the browser cannot decode, when the caller does not accept the pristine bytes. */
export const COVER_UNREADABLE = 'cover_unreadable'

/** The cover slot holds another blob than the writer read (a peer changed the cover): nothing was written. */
export class CoverStaleError extends Error {
  constructor() {
    super('cover_stale')
    this.name = 'CoverStaleError'
  }
}

export type UploadCoverOptions = {
  onProgress?: (fraction: number) => void
  signal?: AbortSignal
  /**
   * Refuse an image the browser cannot decode (`cover_unreadable`) instead of sealing its pristine bytes. A folder's or
   * drop box's cover is picked from files of any kind, and a cover nothing can draw is worse than none; a Memory's
   * dialog has always taken the pristine bytes of an undecodable image (uc.memory.upload E4), and still does.
   */
  strict?: boolean
}

/** What an upload of a cover leaves behind: the open upload session and the plan its ids came from. */
export type UploadedCover = {
  uploadId: string
  plan: { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string; ownerId: string }
}

/**
 * Downscale `source` on device, seal it as the node's cover blob under `nodeKey` and upload it direct-to-store. The upload
 * is OPEN afterwards, not yet anyone's cover: [writeNodeCover] attaches it. Until then (or if that never happens) it is
 * an unfinished upload like any other, which the server's stale-session purge reclaims.
 */
export async function uploadCoverImage(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: UploadCoverOptions = {},
): Promise<UploadedCover> {
  let blob = source.blob
  // The cover blob's meta is its type alone (inbox #397): the JPEG it was derived into, else the pristine source's.
  const given = source.mediaType.trim()
  let meta: BlobMeta = { type: given || 'application/octet-stream' }
  if (source.mediaType.startsWith('image/')) {
    try {
      blob = new Blob([(await deriver.deriveImage(source.blob, COVER_SPEC)).bytes as BlobPart])
      meta = DERIVED_META
    } catch {
      if (opts.strict) throw new Error(COVER_UNREADABLE)
      /* undecodable codec -> seal the pristine bytes (still a valid, if larger, cover) */
    }
  } else if (opts.strict) {
    throw new Error(COVER_UNREADABLE)
  }
  return uploadCoverBlob(scopeId, nodeId, nodeKey, blob, meta, opts)
}

/**
 * Seal `source` AS IT IS as the node's cover blob under `nodeKey` and upload it direct-to-store - no decode, no re-encode:
 * [uploadCoverImage]'s last step, and the whole of a copied cover (ADR 0082 Addendum 2), whose source is the original
 * cover read by frame (`core/export/copySource.ts`), never held whole. Open afterwards, as [uploadCoverImage]'s.
 */
export async function uploadCoverBlob(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  source: BlobSource,
  meta: BlobMeta,
  opts: Pick<UploadCoverOptions, 'onProgress' | 'signal'> = {},
): Promise<UploadedCover> {
  const plan = planBlob(scopeId, { ownerId: nodeId, role: COVER_ROLE }, nodeKey, source, meta)
  const [uploadId] = await reserveAndPump([plan], (d) => authedReserve(scopeId, d), authedWindow(scopeId), opts.onProgress, opts.signal)
  const { blobId, chunkCount, byteSize, wrappedBlobKey, ownerId } = plan
  return { uploadId, plan: { blobId, chunkCount, byteSize, wrappedBlobKey, ownerId } }
}

export type WriteNodeCoverArgs = {
  scopeId: string
  nodeId: string
  nodeKey: Uint8Array
  /** The node's sealed meta as the writer last saw it (the tree's); a `409 stale_version` re-bases it. */
  current: SealedMetaState
  /** The node's new `cover` field value (null: none), built by the caller. */
  cover: unknown | null
  /** The open upload that becomes the cover (its blob is the one `cover` names); absent when no new blob is attached. */
  uploadId?: string
  /** Throw `MetaConflictError` when a peer changed the cover field itself between the writer's read and the write
   *  (an editor re-opens on it). False: this write replaces whatever the cover is now (a set from a picker). Default true. */
  surfaceConflict?: boolean
}

/** What the write confirmed: the state of the meta, and the `cover` value it replaced (null: none) - read from the blob
 *  the server accepted the write over, so the caller decides what else to do from the server's state, not its own copy. */
export type WriteNodeCoverResult = SealedMetaState & { replaced: unknown | null }

/**
 * Write a node's `cover` field and, in the same request, the blob step it implies:
 *  - an `uploadId`: `setNodeCover` attaches the open upload as the cover, replacing the blob the meta being replaced named
 *    (the compare), and writes the meta;
 *  - no `uploadId`, and the meta being replaced named an uploaded cover blob the new cover does not keep: `removeNodeCover`
 *    frees that blob and writes the meta (a switch to a photo, or no cover);
 *  - otherwise a plain `updateNode` (a moved crop, a cover without a blob of its own).
 * Version-checked like every meta write (ADR 0077); the other fields of the meta (a Memory's date and place) are preserved.
 * A cover slot a peer changed meanwhile is a [CoverStaleError]; nothing was written then, and the server freed this upload.
 */
export async function writeNodeCover(a: WriteNodeCoverArgs): Promise<WriteNodeCoverResult> {
  const address = { scopeId: a.scopeId, nodeId: a.nodeId }
  const openedWith = unsealNodeMeta(a.nodeKey, address, a.current.wrappedMeta).cover
  const edit = editField('cover', a.cover, openedWith, a.surfaceConflict ?? true)
  let replaced: unknown | null = null
  const send = async (wrappedMeta: string | null, metaVersion: number): Promise<void> => {
    const sealed = wrappedMeta!
    const oldBlob = uploadBlobIdOf(replaced)
    const keptBlob = uploadBlobIdOf(a.cover)
    const { scopeId, nodeId } = a
    try {
      if (a.uploadId) {
        await withRefresh(() =>
          contentApi().setNodeCover({
            scopeId, nodeId, setNodeCoverRequest: { uploadId: a.uploadId!, expectedBlobId: oldBlob, wrappedMeta: sealed, metaVersion },
          }),
        )
      } else if (oldBlob && oldBlob !== keptBlob) {
        await withRefresh(() =>
          contentApi().removeNodeCover({
            scopeId, nodeId, removeNodeCoverRequest: { blobId: oldBlob, wrappedMeta: sealed, metaVersion },
          }),
        )
      } else {
        await withRefresh(() =>
          contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta: sealed, metaVersion } }),
        )
      }
    } catch (e) {
      if (await isCodedError(e, HttpStatus.conflict, 'cover_stale')) throw new CoverStaleError()
      throw e
    }
  }
  const { state } = await writeNodeMeta(
    a.scopeId, a.nodeId, a.nodeKey, a.current,
    (meta, ctx) => {
      replaced = meta.cover ?? null // before the edit drops or swaps it in place
      return edit(meta, ctx)
    },
    send,
  )
  return { ...state, replaced }
}
