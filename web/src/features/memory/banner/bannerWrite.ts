// The WRITE side of a Memory's banner (uc.memory.banner, ADR 0102): prepare the bytes on the device, seal and upload them
// as the node's own `BANNER` (and `BANNER_POSTER`) blobs, and write the slots with the sealed meta that names them in ONE
// request (`setMemoryBanner` / `removeMemoryBanner`) - so the slots and the meta can never part, and a failed new banner
// leaves the old one exactly as it was (the server compares both slots and the meta revision first, and writes nothing on
// a miss).
//
// Operator-blind: every byte is sealed here under a fresh `K_blob`, wrapped under the node's own content key bound to
// (blob, node, slot) (ADR 0072, `core/blob/transport.planBlob`). The server sees ciphertext chunks, the opaque attachment
// rows and the sealed meta; never which medium a banner was made from, its frame, its focal point or its description.
// Its residual is the bytes' SIZE: a video banner is the medium's original copied as it is (no transcoder until the
// client worker queue, inbox #23), so it has that original's exact size - ADR 0102 § What the server sees says so.
//
// What a banner is made of:
//  - an image: one JPEG derived on the device (the cover's spec, `COVER_SPEC`), from a picked file or a gallery medium's
//    ORIGINAL - never the gallery's preview bytes re-sealed;
//  - a video: its original as it is, read by frame and re-sealed (a picked file, or a gallery medium's original through
//    its chunk provider, never held whole), and its first frame as the poster (decode only, the gallery poster's derive).
//    A browser that cannot decode a frame of it refuses it as a banner (`banner_unreadable`, Q5).
import { contentApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { HttpStatus, isCodedError } from '../../../shared/httpError'
import { COVER_SPEC, MAX_COVER_SOURCE_BYTES } from '../../../core/node/cover/coverWrite'
import { authedReserve, authedWindow, planBlob, reserveAndPump } from '../../../core/blob/transport'
import type { BlobMeta } from '../../../core/blob/meta'
import type { BlobSource } from '../../../core/blob/types'
import { editField, unsealNodeMeta } from '../../../core/node/nodeMeta'
import { writeNodeMeta } from '../../../core/node/nodeMetaWrite'
import type { SealedMetaState } from '../../../core/node/sealedMetaWrite'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import { memoryBannerApi } from '../api'
import { BANNER_POSTER_ROLE, BANNER_ROLE, bannerBlobIds, type BannerMedium, type BannerRefShape } from './bannerModel'

/** The cover's derive: the long edge of the lightbox's preview, so the banner is as sharp as the large view. */
export const BANNER_IMAGE_SPEC = COVER_SPEC
/** The largest picked image taken as a banner source (decoded whole in the browser): the cover's bound. */
export const MAX_BANNER_IMAGE_BYTES = MAX_COVER_SOURCE_BYTES
/** The medium could not be decoded: never sealed as a banner nothing could draw (uc.memory.banner E4, Q5). */
export const BANNER_UNREADABLE = 'banner_unreadable'
/** An image above [MAX_BANNER_IMAGE_BYTES]. */
export const BANNER_TOO_LARGE = 'banner_too_large'

/** A banner slot holds another blob than the writer read (a peer changed the banner): nothing was written. */
export class BannerStaleError extends Error {
  constructor() {
    super('banner_stale')
    this.name = 'BannerStaleError'
  }
}

/** A banner's bytes, ready to upload: an image's one JPEG, or a video as it is plus its poster JPEG. */
export type PreparedBanner =
  | { medium: 'image'; image: BlobSource }
  | { medium: 'video'; video: BlobSource; type: string; poster: BlobSource }

/** Derive an image banner's JPEG from a picked file or a decrypted original; an undecodable image is refused. */
export async function prepareImage(source: Blob, deriver: MediaDeriver): Promise<PreparedBanner> {
  if (source.size > MAX_BANNER_IMAGE_BYTES) throw new Error(BANNER_TOO_LARGE)
  try {
    return { medium: 'image', image: new Blob([(await deriver.deriveImage(source, BANNER_IMAGE_SPEC)).bytes as BlobPart], { type: 'image/jpeg' }) }
  } catch {
    throw new Error(BANNER_UNREADABLE)
  }
}

/** A decoded poster turned into a prepared video banner; a poster that could not be decoded refuses the video. */
export async function prepareVideo(video: BlobSource, type: string, poster: () => Promise<Uint8Array>): Promise<PreparedBanner> {
  let frame: Uint8Array
  try {
    frame = await poster()
  } catch {
    throw new Error(BANNER_UNREADABLE)
  }
  if (frame.length === 0) throw new Error(BANNER_UNREADABLE)
  return { medium: 'video', video, type: type.startsWith('video/') ? type : 'video/mp4', poster: new Blob([frame as BlobPart], { type: 'image/jpeg' }) }
}

/** What an upload of a banner leaves behind: the open sessions and the refs the meta will name. */
export type UploadedBanner = {
  medium: BannerMedium
  uploadId: string
  posterUploadId?: string
  blob: BannerRefShape
  poster?: BannerRefShape
  type?: string
}

const JPEG: BlobMeta = { type: 'image/jpeg' }
const refOf = (p: ReturnType<typeof planBlob>): BannerRefShape => ({
  blobId: p.blobId, chunkCount: p.chunkCount, byteSize: p.byteSize, wrappedBlobKey: p.wrappedBlobKey,
})

/**
 * Seal and upload a prepared banner as the node's `BANNER` blob (and its `BANNER_POSTER`) direct-to-store, in one start.
 * The uploads are OPEN afterwards, not yet anyone's banner: [writeMemoryBanner] attaches them. Until then (or if that never
 * happens) they are unfinished uploads like any other, which the server's stale-session purge reclaims.
 */
export async function uploadBanner(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  prepared: PreparedBanner,
  opts: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<UploadedBanner> {
  const owner = (role: string) => ({ ownerId: nodeId, role })
  if (prepared.medium === 'image') {
    const plan = planBlob(scopeId, owner(BANNER_ROLE), nodeKey, prepared.image, JPEG)
    const [uploadId] = await reserveAndPump([plan], (d) => authedReserve(scopeId, d), authedWindow(scopeId), opts.onProgress, opts.signal)
    return { medium: 'image', uploadId, blob: refOf(plan) }
  }
  const video = planBlob(scopeId, owner(BANNER_ROLE), nodeKey, prepared.video, { type: prepared.type })
  const poster = planBlob(scopeId, owner(BANNER_POSTER_ROLE), nodeKey, prepared.poster, JPEG)
  // The poster first: it is small, and a banner whose poster is up shows at once on the next open.
  const [posterUploadId, uploadId] = await reserveAndPump(
    [poster, video], (d) => authedReserve(scopeId, d), authedWindow(scopeId), opts.onProgress, opts.signal,
  )
  return { medium: 'video', uploadId, posterUploadId, blob: refOf(video), poster: refOf(poster), type: prepared.type }
}

export type WriteMemoryBannerArgs = {
  scopeId: string
  nodeId: string
  nodeKey: Uint8Array
  /** The node's sealed meta as the writer last saw it (the tree's); a `409 stale_revision` re-bases it. */
  current: SealedMetaState
  /** The node's new `banner` field value (null: none), built by the caller. */
  banner: Record<string, unknown> | null
  /** The open uploads that become the banner (their blobs are the ones `banner` names); absent for a re-frame or a removal. */
  upload?: { uploadId: string; posterUploadId?: string }
  /** Throw `MetaConflictError` when a peer changed the banner itself between the writer's read and the write (the dialog
   *  re-opens on it). Default true; an import, which writes the banner of a node it just made, passes false. */
  surfaceConflict?: boolean
}

/**
 * Write a Memory's `banner` field and, in the same request, the slot step it implies:
 *  - an `upload`: `setMemoryBanner` attaches the open upload(s), replacing what the meta being replaced named (the
 *    compare: both slots), and writes the meta - an image replacing a video frees the poster;
 *  - no `upload` and no `banner`, where the meta being replaced named a banner blob: `removeMemoryBanner` frees both slots
 *    and writes the meta without it;
 *  - otherwise a plain `updateNode` (a re-frame: the focal point, fill or fit, the description).
 * Revision-checked like every meta write (ADR 0077); the other fields of the meta (the date, the place, the cover) are
 * preserved. A banner slot a peer changed meanwhile is a [BannerStaleError]; nothing was written then.
 */
export async function writeMemoryBanner(a: WriteMemoryBannerArgs): Promise<SealedMetaState> {
  const { scopeId, nodeId } = a
  const openedWith = unsealNodeMeta(a.nodeKey, { scopeId, nodeId }, a.current.wrappedMeta).banner
  const edit = editField('banner', a.banner, openedWith, a.surfaceConflict ?? true)
  let replaced: unknown = null
  const send = async (wrappedMeta: string | null, metaRevision: number): Promise<void> => {
    const sealed = wrappedMeta!
    const old = bannerBlobIds(replaced)
    try {
      if (a.upload) {
        await withRefresh(() =>
          memoryBannerApi().setMemoryBanner({
            scopeId, nodeId,
            setMemoryBannerRequest: {
              uploadId: a.upload!.uploadId,
              posterUploadId: a.upload!.posterUploadId ?? null,
              expectedBlobId: old.blobId,
              expectedPosterBlobId: old.posterBlobId,
              wrappedMeta: sealed,
              metaRevision,
            },
          }),
        )
      } else if (!a.banner && old.blobId) {
        await withRefresh(() =>
          memoryBannerApi().removeMemoryBanner({
            scopeId, nodeId,
            removeMemoryBannerRequest: { blobId: old.blobId!, posterBlobId: old.posterBlobId, wrappedMeta: sealed, metaRevision },
          }),
        )
      } else {
        await withRefresh(() => contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta: sealed, metaRevision } }))
      }
    } catch (e) {
      if (await isCodedError(e, HttpStatus.conflict, 'banner_stale')) throw new BannerStaleError()
      throw e
    }
  }
  const { state } = await writeNodeMeta(
    scopeId, nodeId, a.nodeKey, a.current,
    (meta, ctx) => {
      replaced = meta.banner ?? null // before the edit drops or swaps it in place
      return edit(meta, ctx)
    },
    send,
  )
  return state
}
