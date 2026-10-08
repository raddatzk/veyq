// A file download (cr-files-application F1b; a guest's since F2): the file's blob decrypted in the browser and saved under the
// file's name (the node name the tree opened). The bytes come straight from the store over presigned GETs (core's
// `presignAllChunks`, by blob id: the scope READ gate), and each frame is opened under the `K_blob` the file node's key
// unwraps, bound to (scope, node, blob, `FILE_CONTENT`) — a ref the server moved to another node does not open.
//
// **Memory (inbox #371).** The frames are streamed: opened at most [DOWNLOAD_IN_FLIGHT] at a time and written in order
// into the save target `shared/saveStream.ts` opens (a picked file, the Service Worker's download route, or — only up
// to its cap — an in-memory Blob), each dropped once written. So the tab holds a few frames, not the file. Measured
// only in unit tests: no multi-GB download has run in a real browser yet (#371 keeps that open).
import { makeChunkProvider, presignAllChunks } from '../../core/blob/transport'
import { streamBlob, type ByteSink } from '../../core/blob/stream'
import type { BlobRef } from '../../core/blob/types'
import { plaintextTotal } from '../../sw/range'
import { openSaveTarget } from '../../shared/saveStream'
import type { FileBlobRef } from '../../api/generated/files'
import { FILE_CONTENT_ROLE } from './kinds'

/** How many frames are fetched and opened at once — the pump's bound, so a download holds no more frames in flight
 *  than an upload does. */
export const DOWNLOAD_IN_FLIGHT = 4

/** The transport's ref for a file's blob: the listed ref, bound to the file node it was listed for and its role. */
export function fileBlobRef(nodeId: string, blob: FileBlobRef): BlobRef {
  return {
    blobId: blob.blobId, chunkCount: blob.chunkCount, byteSize: blob.byteSize, wrappedBlobKey: blob.wrappedBlobKey,
    ownerId: nodeId, role: FILE_CONTENT_ROLE,
  }
}

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
 * Decrypt a file and save it to the user's device under `name` (the decrypted node name), streamed into the target
 * `openSaveTarget` picks. Call it straight from the click handler with nothing awaited before: the save-file picker
 * needs the click's activation, and it is the first thing awaited here. Throws what `openSaveTarget` throws
 * (`SaveCancelled`, `SaveTooLarge`), `OpenRefused` for a ref that is not this node's, or the stream's failure — after
 * which the target is aborted, never left as a truncated file.
 */
export async function downloadFile(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef, name: string, presign?: PresignFileChunks,
): Promise<void> {
  const { ref, provider } = fileChunks(scopeId, nodeKey, nodeId, blob, presign)
  const sink = await openSaveTarget(name, plaintextTotal(ref.byteSize, ref.chunkCount))
  await streamBlob(provider, sink, { inFlight: DOWNLOAD_IN_FLIGHT })
}
