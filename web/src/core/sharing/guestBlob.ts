// A guest's blob transport on a public link (cr-files-application F2): core's guest calls `startShareBlobUploads` /
// `presignShareBlobUploadWindows` (reserve and window, the link's whole gate again on every window — a revoked link stops
// an upload at its next window) and `resolveShareBlob` (the presigned GETs of a blob under the shared subtree), shaped as
// the reserve/window/presign functions core's `core/blob/transport.ts` pumps and reads with. Kind-neutral: the target
// node and the role come from the caller; which kinds take a guest's upload is the server's `ShareGuestWrites`.
// The one copy: Depot's guest upload and Memories' guest media transport (`features/memory/share.ts`) both reserve and
// window here (inbox #376) — Memories' wire is pinned byte for byte by its characterisation test
// (`features/memory/uploadWire.characterisation.test.ts`), which held unchanged across the merge.
import { publicSharesApi } from '../../api/client'
import { windowPuts } from '../blob/transport'
import { readScheduler, type Presigned, type ReadOpts } from '../blob/readScheduler'
import type { BlobDeclare, ReservedUpload, WindowFn } from '../blob/types'
import { activeReview } from './review'

/** Who uploads where: the link, the guest's token, the password token when the link has one, and the node below the
 *  root the uploads are for (named on every start and window, as the server checks it on each). `nodeId` absent: the
 *  share root itself, and the body carries no `nodeId` at all (Memories' root-level upload, as it always did). */
export type GuestUploadTarget = { shareId: string; contributorToken: string; pwToken?: string; nodeId?: string }

/** Reserve a guest's uploads (`startShareBlobUploads`): one session per declare, its first window of PUTs. A per-item
 *  error (`quota_exceeded`, `id_taken`, …) is thrown as its code. */
export async function guestReserve(t: GuestUploadTarget, declares: BlobDeclare[]): Promise<ReservedUpload[]> {
  const started = await publicSharesApi().startShareBlobUploads({
    shareId: t.shareId,
    shareBlobUploadsRequest: { contributorToken: t.contributorToken, nodeId: t.nodeId, items: declares },
    xSharePassword: t.pwToken,
  })
  if (started.results.length !== declares.length) throw new Error('upload_failed')
  return started.results.map((r) => {
    if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
    if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
    return { uploadId: r.uploadId, puts: r.presignedPuts }
  })
}

/** Sign a guest upload's next window (`presignShareBlobUploadWindows`) — only for the identity that opened it. */
export function guestWindow(t: GuestUploadTarget): WindowFn {
  return async (uploadId, fromChunk) => {
    const signed = await publicSharesApi().presignShareBlobUploadWindows({
      shareId: t.shareId,
      shareBlobUploadWindowsRequest: { contributorToken: t.contributorToken, nodeId: t.nodeId, items: [{ uploadId, fromChunk }] },
      xSharePassword: t.pwToken,
    })
    return windowPuts(signed.results[0])
  }
}

/** The link's presign per (link, password token). `resolveShareBlob` takes one blob per call, so its batches are of one:
 *  the scheduler still bounds how many are in flight, retries a transient failure and puts a lightbox before a grid. */
const sharePresigners = new Map<string, (blobId: string, opts?: ReadOpts) => Promise<Presigned>>()
function sharePresigner(shareId: string, pwToken: string | undefined) {
  const key = `${shareId}\u0000${pwToken ?? ''}`
  let presigner = sharePresigners.get(key)
  if (!presigner) {
    presigner = readScheduler.batcher(async ([blobId]) => {
      const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId, xSharePassword: pwToken })
      return [[...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)]
    }, 1)
    sharePresigners.set(key, presigner)
  }
  return presigner
}

/** The presigned GET of every chunk of `blobId` under the link (`resolveShareBlob`), in chunk order. A blob outside the
 *  live shared subtree — a file in the trash included — is the server's `404`. */
export async function shareChunkUrls(shareId: string, blobId: string, pwToken?: string, opts?: ReadOpts): Promise<string[]> {
  // A moderator's review reads the held, pinned object instead — never the link's own read (`./review.ts`).
  const review = activeReview()
  if (review) return review.chunkUrls(blobId)
  return (await sharePresigner(shareId, pwToken)(blobId, opts)).urls
}
