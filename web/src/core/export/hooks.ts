// What a kind supplies so core can export and import a subtree (ADR 0082 decision 1). Core walks the tree, writes the
// archive and creates the nodes; a kind only says what its CONTENT is. The kind's descriptor carries these two hooks
// (`KindDescriptor.exportContent` / `importContent`), beside `createEntry`, so core reaches an application only through
// the registry (ADR 0079).
import type { EpochKeys } from '../../features/spaces/epochs'
import type { Access, TreeSource } from '../access'
import type { NodeParent, TreeNode } from '../node/tree'
import type { ManifestContent, ManifestNode, ManifestPayload } from './manifest'

/** The bytes of one payload: its size is declared up front (the ZIP writer needs it), the chunks are produced on demand. */
export type ExportPayloadSource = {
  size: number
  mediaType: string
  modifiedAt?: number
  chunks: () => AsyncIterable<Uint8Array>
}

export type ExportNodeContext = {
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
  scopeId: string
  epochs: EpochKeys
  /** Where to create the node (null: the scope root). */
  parent: NodeParent
  /** The name to create it under: the exported name, made free among the target's siblings. */
  name: string
  node: ManifestNode
  /** A payload as a seekable `File` named `fileName`, its checksum verified; refuses (throws) a payload that fails it. */
  payload(ref: ManifestPayload, fileName: string): Promise<File>
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
