// What a kind supplies so core can export and import a subtree (ADR 0082 decision 1). Core walks the tree, writes the
// archive and creates the nodes; a kind only says what its CONTENT is. The kind's descriptor carries these two hooks
// (`KindDescriptor.exportContent` / `importContent`), beside `createEntry`, so core reaches an application only through
// the registry (ADR 0079).
import type { EpochKeys } from '../../features/spaces/epochs'
import type { BlobSource } from '../blob/types'
import type { Access, TreeSource } from '../access'
import type { CreateNodeOptions, NodeParent, TreeNode } from '../node/tree'
import type { ManifestContent, ManifestNode, ManifestPayload } from './manifest'

/** A payload's plaintext by frame, in any order: frame `i` holds bytes `[i * frameSize, (i + 1) * frameSize)` of the payload, the
 *  last one the rest (an empty payload is one empty frame). A stored blob's frames are the chunks its upload sealed (the
 *  download's chunk provider, `CHUNK_SIZE` each); bytes already in memory are one frame. What a copy reads its original
 *  through (`copySource.ts`), one frame at a time. */
export type PlaintextFrames = {
  readonly frameSize: number
  readonly chunkCount: number
  getPlaintextChunk(index: number): Promise<Uint8Array>
}

/** The bytes of one payload: its size is declared up front (the ZIP writer needs it), the chunks are produced on demand. */
export type ExportPayloadSource = {
  size: number
  mediaType: string
  modifiedAt?: number
  chunks: () => AsyncIterable<Uint8Array>
  /** The same bytes by frame, for a reader that seeks (a copy, ADR 0082 decision 11). A payload without them cannot be
   *  copied: the copy leaves its node out and names it before anything is written. */
  frames?: PlaintextFrames
}

/** What an import hook is handed for a payload: a `File` (from a ZIP or a directory), or - in a copy - a source that reads
 *  the original by frame on demand (`copySource.ts`). Either is read only by `slice(...).arrayBuffer()`, as the upload pump
 *  reads; a hook that needs the bytes as a real `Blob` (to decode them) must check `instanceof Blob`, and its kind declares
 *  `importStreams` only where a copy never needs that (Memories: a copy hands over the tiers it would otherwise derive). */
export type PayloadFile = BlobSource & { readonly name: string; readonly lastModified: number }

/** Why the walk runs: a `download` (an archive, ADR 0082 decision 3) or a `copy` (ADR 0082 decision 11), whose import is this
 *  build's own and in the same space - a kind may then hand over what it keeps beside its payloads (Memories: each medium's
 *  derived tiers and sealed facts), which an archive never carries. */
export type WalkPurpose = 'download' | 'copy'

export type ExportNodeContext = {
  purpose: WalkPurpose
  /** Who downloads: a member, or a `READ`/`READ_WRITE` share guest (ADR 0082 decision 5). A hook reads through that
   *  session's own doors - a guest's never touch a member's API. */
  access: Access
  /** The tree as that session reads it (the member's scope tree, the guest's subtree). */
  tree: TreeSource<TreeNode>
  /** The scope every seal binds (a guest's: the link's scope). */
  scopeId: string
  node: TreeNode
  /** The node's own content key. */
  nodeKey: Uint8Array
  signal?: AbortSignal
  /** Scratch that lives for one export run: a hook caches what it reads once per folder here. */
  cache: Map<string, unknown>
  /** Register a payload: a file named `fileName` in the node's directory (made safe and unique by core,
   *  `archiveName`), or - for a kind that `downloadsAsFile` - the node's own path, once. Returns the reference to put into
   *  the returned content. */
  addPayload(fileName: string, source: ExportPayloadSource): ManifestPayload
}

export type ImportNodeContext = {
  /** What is replayed: a download (`import`, untrusted - only what an archive carries is read) or a `copy`, whose content
   *  is what this build's own export hook wrote for a copy (`ExportNodeContext.purpose`). A hook reads a copy's extra
   *  content only here, never from an archive. */
  purpose: 'import' | 'copy'
  scopeId: string
  epochs: EpochKeys
  /** Where to create the node (null: the scope root). */
  parent: NodeParent
  /** What the create under `parent` passes `createContentNode` (`CreateNodeOptions`): an import or a copy is a member's
   *  flow, so a parent not re-keyed since a rotation is pulled forward on this device and re-read, and the create retried
   *  (ADR 0111 decision 11) - core's `memberCreateOptions`; an owner-only parent (a drop box) waits for an owner. */
  createOptions: CreateNodeOptions
  /** The name to create it under: the exported name, made free among the target's siblings. */
  name: string
  node: ManifestNode
  /** A payload, seekable, named `fileName`: from a download its checksum verified first (refuses - throws - a payload that
   *  fails it), and the SHA-256 that check computed remembered for the returned object, so `contentDigest` answers it
   *  without a read while the hook hands that very object on (a re-wrapped copy is read again); in a copy the original
   *  read by frame, each frame checked against the size the walk declared. */
  payload(ref: ManifestPayload, fileName: string): Promise<PayloadFile>
  signal?: AbortSignal
  /** Fraction of THIS node's bytes uploaded. */
  onProgress?: (fraction: number) => void
  cache: Map<string, unknown>
}

/** What `exportContent` returns: the kind's content for the manifest, or null when there is nothing to export (the node,
 *  with its subtree, is then left out and reported). */
export type ExportContentHook = (ctx: ExportNodeContext) => Promise<ManifestContent | null>

/** Creates the node (with its content) under `ctx.parent` and returns its id and key, so core can create its children
 *  under it. On a failure it leaves behind nothing it can clean up itself; core trashes the top-level node it created. */
export type ImportContentHook = (ctx: ImportNodeContext) => Promise<{ nodeId: string; nodeKey: Uint8Array }>
