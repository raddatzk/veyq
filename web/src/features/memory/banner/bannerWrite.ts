// The WRITE side of a Memory's banner (uc.memory.banner, ADR 0102): prepare the bytes on the device, seal and upload them
// as the node's own `BANNER` (and `BANNER_POSTER`) blobs, and write the slots with the sealed meta that names them in ONE
// request (`setMemoryBanner` / `removeMemoryBanner`) - so the slots and the meta can never part, and a failed new banner
// leaves the old one exactly as it was (the server compares both slots and the meta revision first, and writes nothing on
// a miss).
//
// Operator-blind: every byte is sealed here under a fresh `K_blob`, wrapped under the node's own content key bound to
// (blob, node, slot) (ADR 0072, `core/blob/transport.planBlob`) - or, where the Memory already stores the very bytes a
// slot would hold, the slot is a new row on that stored object (`core/blob/sharedBlob.ts`, ADR 0013 § 5), its `K_blob`
// wrapped again for the slot. The server sees ciphertext chunks, the opaque attachment rows and the sealed meta; never the
// frame or the focal point. It does see which medium's object a shared slot names - the accepted residual of ADR 0102
// § What the server sees.
//
// What a banner is made of, always from a file picked in its dialog:
//  - an image: one JPEG derived on the device (the cover's spec, `COVER_SPEC`) - or the PREVIEW of a gallery medium whose
//    original is the same file (the same spec of the same picture), shared;
//  - a video: the file as it is, read by frame (never held whole), with its SHA-256 in its `blob-meta` - or a gallery
//    medium's ORIGINAL of the same file, shared - and its first frame as the poster (the medium's PREVIEW when shared, else
//    decoded here). A browser that cannot decode a frame of an unshared video refuses it (`banner_unreadable`, Q5).
import { contentApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { HttpStatus, isCodedError } from '../../../shared/httpError'
import { COVER_SPEC, MAX_COVER_SOURCE_BYTES } from '../../../core/node/cover/coverWrite'
import { authedReserve, authedWindow, planBlob, reserveAndPump } from '../../../core/blob/transport'
import type { BlobMeta } from '../../../core/blob/meta'
import { shareBlob, type SharedBlobRequest, type StoredBlob } from '../../../core/blob/sharedBlob'
import type { BlobPlan, BlobSource } from '../../../core/blob/types'
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

/** One slot's bytes, ready: a source to upload, or a stored blob of the Memory the slot shares (ADR 0013 § 5). */
export type BannerPart = { upload: BlobSource } | { share: StoredBlob }

/** A banner's bytes, ready to write: an image's one JPEG, or a video as it is (with its file's digest) plus its poster. */
export type PreparedBanner =
  | { medium: 'image'; image: BannerPart }
  | { medium: 'video'; video: BannerPart; type: string; sha256: string; poster: BannerPart }

/** Derive an image banner's JPEG from a picked file; an undecodable image is refused. */
export async function prepareImage(source: Blob, deriver: MediaDeriver): Promise<BannerPart> {
  if (source.size > MAX_BANNER_IMAGE_BYTES) throw new Error(BANNER_TOO_LARGE)
  try {
    return { upload: new Blob([(await deriver.deriveImage(source, BANNER_IMAGE_SPEC)).bytes as BlobPart], { type: 'image/jpeg' }) }
  } catch {
    throw new Error(BANNER_UNREADABLE)
  }
}

/** A video's poster as a part: the decoded first frame; a poster that could not be decoded refuses the video. */
export async function preparePoster(poster: () => Promise<Uint8Array>): Promise<BannerPart> {
  let frame: Uint8Array
  try {
    frame = await poster()
  } catch {
    throw new Error(BANNER_UNREADABLE)
  }
  if (frame.length === 0) throw new Error(BANNER_UNREADABLE)
  return { upload: new Blob([frame as BlobPart], { type: 'image/jpeg' }) }
}

/** A video's media type as the banner stores it: the file's, else mp4. */
export const bannerVideoType = (type: string): string => (type.startsWith('video/') ? type : 'video/mp4')

/** What a banner's preparation leaves behind: per slot an open upload or a shared row, and the refs the meta will name. */
export type UploadedBanner = {
  medium: BannerMedium
  uploadId?: string
  posterUploadId?: string
  shared?: SharedBlobRequest
  posterShared?: SharedBlobRequest
  blob: BannerRefShape
  poster?: BannerRefShape
  type?: string
  sha256?: string
}

const JPEG: BlobMeta = { type: 'image/jpeg' }
const refOf = (p: { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }): BannerRefShape => ({
  blobId: p.blobId, chunkCount: p.chunkCount, byteSize: p.byteSize, wrappedBlobKey: p.wrappedBlobKey,
})

/**
 * Seal and upload a prepared banner as the node's `BANNER` blob (and its `BANNER_POSTER`) direct-to-store, in one start -
 * each part that is a stored blob becomes a shared row instead, nothing uploaded for it. The uploads are OPEN afterwards,
 * not yet anyone's banner: [writeMemoryBanner] attaches them (and creates the shared rows). Until then (or if that never
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
  // Each part: a plan to upload, or a shared row. Order of the plans: the poster first - it is small, and a banner whose
  // poster is up shows at once on the next open.
  const part = (p: BannerPart, role: string, meta: BlobMeta) =>
    'share' in p
      ? { shared: shareBlob(scopeId, p.share, nodeKey, owner(role), meta) }
      : { plan: planBlob(scopeId, owner(role), nodeKey, p.upload, meta) }
  const pump = async (plans: BlobPlan[]): Promise<string[]> =>
    plans.length === 0 ? [] : reserveAndPump(plans, (d) => authedReserve(scopeId, d), authedWindow(scopeId), opts.onProgress, opts.signal)
  if (prepared.medium === 'image') {
    const image = part(prepared.image, BANNER_ROLE, JPEG)
    if (image.shared) return { medium: 'image', shared: image.shared.request, blob: refOf(image.shared.ref) }
    const [uploadId] = await pump([image.plan!])
    return { medium: 'image', uploadId, blob: refOf(image.plan!) }
  }
  const video = part(prepared.video, BANNER_ROLE, { type: prepared.type, sha256: prepared.sha256 })
  const poster = part(prepared.poster, BANNER_POSTER_ROLE, JPEG)
  const plans = [poster.plan, video.plan].filter((p): p is BlobPlan => !!p)
  const ids = await pump(plans)
  const idOf = (p: BlobPlan | undefined) => (p ? ids[plans.indexOf(p)] : undefined)
  return {
    medium: 'video',
    uploadId: idOf(video.plan),
    posterUploadId: idOf(poster.plan),
    shared: video.shared?.request,
    posterShared: poster.shared?.request,
    blob: refOf(video.shared?.ref ?? video.plan!),
    poster: refOf(poster.shared?.ref ?? poster.plan!),
    type: prepared.type,
    sha256: prepared.sha256,
  }
}

export type WriteMemoryBannerArgs = {
  scopeId: string
  nodeId: string
  nodeKey: Uint8Array
  /** The node's sealed meta as the writer last saw it (the tree's); a `409 stale_revision` re-bases it. */
  current: SealedMetaState
  /** The node's new `banner` field value (null: none), built by the caller. */
  banner: Record<string, unknown> | null
  /** The open uploads - or shared rows - that become the banner (their blobs are the ones `banner` names); absent for a
   *  re-frame or a removal. Per slot exactly one of the upload and the shared row. */
  upload?: { uploadId?: string; posterUploadId?: string; shared?: SharedBlobRequest; posterShared?: SharedBlobRequest }
  /** Throw `MetaConflictError` when a peer changed the banner itself between the writer's read and the write (the dialog
   *  re-opens on it). Default true; an import, which writes the banner of a node it just made, passes false. */
  surfaceConflict?: boolean
}

/**
 * Write a Memory's `banner` field and, in the same request, the slot step it implies:
 *  - an `upload`: `setMemoryBanner` attaches the open upload(s) or creates the shared row(s), replacing what the meta
 *    being replaced named (the compare: both slots), and writes the meta - an image replacing a video frees the poster;
 *  - no `upload` and no `banner`, where the meta being replaced named a banner blob: `removeMemoryBanner` frees both slots
 *    and writes the meta without it;
 *  - otherwise a plain `updateNode` (a re-frame: the focal point, fill or fit).
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
              uploadId: a.upload!.uploadId ?? null,
              shared: a.upload!.shared ?? null,
              posterUploadId: a.upload!.posterUploadId ?? null,
              posterShared: a.upload!.posterShared ?? null,
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
