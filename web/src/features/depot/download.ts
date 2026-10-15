// A file download (cr-files-application F1b; a guest's since F2): the file's blob decrypted in the browser and saved under the
// file's name — core's one rule `downloadName`: the node name the tree opened (a rename wins), else the name sealed in
// the blob's meta, with the extension the blob's sealed type names when the name has none (inbox #397). The type goes
// no further: the save target stays `application/octet-stream`, so nothing a peer sealed decides how a browser
// renders the bytes (`shared/saveStream.ts`). The bytes come straight from the store over presigned GETs (core's
// `presignAllChunks`, by blob id: the scope READ gate), and each frame is opened under the `K_blob` the file node's key
// unwraps, bound to (scope, node, blob, `FILE_CONTENT`) — a ref the server moved to another node does not open.
//
// **Memory (inbox #371).** The frames are streamed: opened at most [DOWNLOAD_IN_FLIGHT] at a time and written in order
// into the save target `shared/saveStream.ts` opens (a picked file, the Service Worker's download route, or — only up
// to its cap — an in-memory Blob), each dropped once written. So the tab holds a few frames, not the file. Measured
// only in unit tests: no multi-GB download has run in a real browser yet (#371 keeps that open).
import { makeChunkProvider, presignAllChunks, readBlobMeta, type BlobChunkProvider } from '../../core/blob/transport'
import { streamBlob, type ByteSink } from '../../core/blob/stream'
import { downloadName, type OpenedBlobMeta } from '../../core/blob/meta'
import type { BlobRef } from '../../core/blob/types'
import { plaintextTotal } from '../../sw/range'
import { openSaveTarget } from '../../shared/saveStream'
import type { FileBlobRef } from '../../api/generated/depot'
import { FILE_CONTENT_ROLE } from './kinds'

/** How many frames are fetched and opened at once — the pump's bound, so a download holds no more frames in flight
 *  than an upload does. */
export const DOWNLOAD_IN_FLIGHT = 4

/** The transport's ref for a file's blob: the listed ref, bound to the file node it was listed for and its role. */
export function fileBlobRef(nodeId: string, blob: FileBlobRef): BlobRef {
  return {
    blobId: blob.blobId, chunkCount: blob.chunkCount, byteSize: blob.byteSize, wrappedBlobKey: blob.wrappedBlobKey,
    wrappedBlobMeta: blob.wrappedBlobMeta, ownerId: nodeId, role: FILE_CONTENT_ROLE,
  }
}

/** What a file row shows of its blob: the plaintext size (the sealed size less every frame's overhead — exact) and the
 *  blob's sealed meta (type, uploaded name, modified time), or null when the blob carries none or it does not open
 *  under this node's key — a ref the server moved here from another node shows no meta, never a wrong one. */
export type FileFacts = { size: number; meta: OpenedBlobMeta | null }

/** The [FileFacts] of `blob`, listed for the file `nodeId` whose key is `nodeKey`. Never throws. */
export function fileFacts(scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef): FileFacts {
  return {
    size: Math.max(0, plaintextTotal(blob.byteSize, blob.chunkCount)),
    meta: readBlobMeta(scopeId, nodeKey, fileBlobRef(nodeId, blob)),
  }
}

/** The name a file is saved under when its node name is blank and its blob sealed none (`downloadName`'s fallback). */
const UNNAMED = 'file'

/** Who presigns a file's chunks: the member's scope READ gate (the default), or a guest's link
 *  (`shareChunkUrls(shareId, blobId, pwToken)`, the share's subtree gate — cr-files-application F2). */
export type PresignFileChunks = (blobId: string) => Promise<string[]>

/** The provider over a file's chunks: `K_blob` unwrapped (synchronously — a ref listed for another node throws
 *  `OpenRefused` here, before anything is fetched) and the chunks presigned on first read. */
function fileChunks(scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef, presign?: PresignFileChunks) {
  const ref = fileBlobRef(nodeId, blob)
  const provider = makeChunkProvider(scopeId, nodeKey, ref, () => (presign ?? ((id) => presignAllChunks(scopeId, id)))(ref.blobId))
  return { ref, provider }
}

/** The chunk provider the file page's viewer reads (inbox #412): the same one a download streams from, so the key
 *  unwrap, the presign and the per-frame open are the code the download proves. Throws `OpenRefused` for a ref that is not
 *  this node's. */
export function fileChunkProvider(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef, presign?: PresignFileChunks,
): BlobChunkProvider {
  return fileChunks(scopeId, nodeKey, nodeId, blob, presign).provider
}

/**
 * Decrypt a file's blob into a `Blob` in memory — the whole file, so for reading a small file back (the e2e suites),
 * never for a user's download, which streams ([downloadFile]). Throws `OpenRefused` for a ref that is not this node's.
 */
export async function fetchFileContent(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef, presign?: PresignFileChunks,
): Promise<Blob> {
  const { provider } = fileChunks(scopeId, nodeKey, nodeId, blob, presign)
  const parts: Uint8Array[] = []
  const collect: ByteSink = { write: async (b) => void parts.push(b), close: async () => {}, abort: async () => {} }
  await streamBlob(provider, collect, { inFlight: DOWNLOAD_IN_FLIGHT })
  return new Blob(parts as BlobPart[], { type: 'application/octet-stream' })
}

/**
 * Decrypt a file and save it to the user's device under `downloadName(name, the blob's meta, 'file')` — `name` the
 * decrypted node name — streamed into the target `openSaveTarget` picks. The meta opens under the `K_blob` the chunk
 * provider already unwrapped, synchronously. Call it straight from the click handler with nothing awaited before: the save-file picker
 * needs the click's activation, and it is the first thing awaited here. Throws what `openSaveTarget` throws
 * (`SaveCancelled`, `SaveTooLarge`), `OpenRefused` for a ref that is not this node's, or the stream's failure — after
 * which the target is aborted, never left as a truncated file.
 */
export async function downloadFile(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef, name: string, presign?: PresignFileChunks,
): Promise<void> {
  const { ref, provider } = fileChunks(scopeId, nodeKey, nodeId, blob, presign)
  const sink = await openSaveTarget(downloadName(name, provider.meta(), () => UNNAMED), plaintextTotal(ref.byteSize, ref.chunkCount))
  await streamBlob(provider, sink, { inFlight: DOWNLOAD_IN_FLIGHT })
}
