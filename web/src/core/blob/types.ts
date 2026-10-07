// The platform's blob model (ADR 0079; cr-files-application B1): what a sealed, chunked blob IS to the transport —
// its id, its shape, its K_blob wrap and the (owner, role) slot that wrap is bound to (ADR 0072). Nothing here knows
// what an owner is (a media, a node, a file): an application builds the ref from the row it addressed and hands it in.

/** The role a blob plays for its owner — the `K_blob` wrap is sealed bound to it (ADR 0072). An open token the owning
 *  application defines (the contract's and the crypto suite's `BlobRole`, cr-files-application P3), so core names none. */
export type BlobRole = string

/**
 * Everything needed to open one blob: its id + the metadata + the K_blob wrap (under the owner's key) + where that
 * wrap is sealed — its owner and its role there. The owner and role are set by whoever built the ref from the row it
 * addressed, never read back from the blob, so a ref the server moved to another row carries that row's owner and
 * refuses.
 */
export type BlobRef = {
  blobId: string
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
  ownerId: string
  role: BlobRole
}

/**
 * A blob about to be uploaded: its plaintext source, a fresh `K_blob` (with its wrap under the owner's key) and the
 * shape the frame size fixes. Nothing is sealed yet — the pump seals frame `i` from `source.slice` when it is about
 * to send it, so the client never holds the whole file, nor its sealed copy (ADR 0013 Consequences).
 */
export type BlobPlan = {
  source: Blob
  blobKey: Uint8Array
  wrappedBlobKey: string
  chunkCount: number
  byteSize: number
  blobId: string
  ownerId: string
  role: BlobRole
}

/** One blob to reserve: its declared shape + the K_blob wrap + the ids that wrap is bound to. */
export type BlobDeclare = {
  blobId: string
  ownerId: string
  role: BlobRole
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
}
/** One presigned PUT: the chunk it is for + its URL. */
export type ChunkPut = { index: number; url: string }
/** One reserved upload: the session id + the PUTs of its first window (ADR 0013 §2 — never the whole blob). */
export type ReservedUpload = { uploadId: string; puts: ChunkPut[] }
/** Sign the window of an open upload that starts at `fromChunk` — each next window, or one whose URLs expired. */
export type WindowFn = (uploadId: string, fromChunk: number) => Promise<ChunkPut[]>
