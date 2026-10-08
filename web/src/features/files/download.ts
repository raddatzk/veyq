// A file download (cr-files-application F1b; a guest's since F2): the file's blob decrypted in the browser and saved under the
// file's name (the node name the tree opened). The bytes come straight from the store over presigned GETs (core's
// `presignAllChunks`, by blob id: the scope READ gate), and each frame is opened under the `K_blob` the file node's key
// unwraps, bound to (scope, node, blob, `FILE_CONTENT`) — a ref the server moved to another node does not open.
//
// **Memory, stated (not verified for multi-GB files).** The frames are fetched and opened one by one (at most
// [DOWNLOAD_IN_FLIGHT] at a time, as the upload pump bounds its PUTs), but the plaintext chunks are then held until
// the last one arrived and assembled into one `Blob`: a file of N bytes needs about N bytes of memory (more while the
// browser copies the parts into the Blob), so a 2 GB file needs about 2 GB of tab memory and may fail there. A
// streaming save (`showSaveFilePicker` / a `ReadableStream` through the Service Worker) would bound it and is out of
// scope here.
import { makeChunkProvider, presignAllChunks } from '../../core/blob/transport'
import type { BlobRef } from '../../core/blob/types'
import { saveBlob } from '../../shared/saveBytes'
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

/**
 * Decrypt a file's blob into a `Blob`: unwrap `K_blob` under the file node's key, presign every chunk, fetch and open
 * each frame (its position bound into the AAD), in order. Throws `OpenRefused` for a ref that is not this node's.
 */
export async function fetchFileContent(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef,
  presign: PresignFileChunks = (blobId) => presignAllChunks(scopeId, blobId),
): Promise<Blob> {
  const ref = fileBlobRef(nodeId, blob)
  const provider = makeChunkProvider(scopeId, nodeKey, ref, () => presign(ref.blobId))
  const parts: Uint8Array[] = new Array(ref.chunkCount)
  // Chunk 0 alone first: the provider presigns on its first read, and parallel first reads would each presign.
  parts[0] = await provider.getPlaintextChunk(0)
  let next = 1
  const worker = async () => {
    for (let i = next++; i < ref.chunkCount; i = next++) parts[i] = await provider.getPlaintextChunk(i)
  }
  await Promise.all(Array.from({ length: Math.min(DOWNLOAD_IN_FLIGHT, ref.chunkCount) }, worker))
  // `application/octet-stream` whatever the sealed MIME type says: the anchor downloads it, nothing renders it.
  return new Blob(parts as BlobPart[], { type: 'application/octet-stream' })
}

/** Decrypt a file and save it to the member's device under `name` (the decrypted node name). */
export async function downloadFile(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, blob: FileBlobRef, name: string, presign?: PresignFileChunks,
): Promise<void> {
  saveBlob(await fetchFileContent(scopeId, nodeKey, nodeId, blob, presign), name)
}
