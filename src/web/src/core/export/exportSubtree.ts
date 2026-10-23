// Download a node and everything under it as a store-only ZIP (ADR 0082), in the client, on decrypted data. The tree is walked
// as the downloading session reads it (`TreeSource`: a member's scope tree, a READ / READ_WRITE guest's subtree) with the keys
// that session holds, each kind supplies its content through its descriptor's `exportContent` hook, and the bytes stream into a
// `ByteSink` one chunk at a time: what the tab holds is the manifest (one line per node) and a window of chunks, never the
// subtree.
//
// The archive READS AS THE TREE (decision 3, format version 2): a node that holds others is a directory named after it, its
// payloads files in it under their own names, a kind that IS one file (`downloadsAsFile`) that file; every name goes through
// `archiveName` (safe on every file system, numbered on a clash ignoring case). The manifest sits beside the tree at
// `.veyq/manifest.json`, maps every node to its path and names each application the tree carries with its content-schema
// version (decision 9).
//
// Order: the manifest is built first (every hook runs, payloads are only DECLARED), then the directories and payloads stream
// into the archive - each payload hashed on the way - and the manifest, now with its `sha256`s, is written last. A node whose
// kind has no export hook, or whose hook has nothing to export, is left out with its subtree and listed in `skipped`.
import * as v1 from '@veyq/crypto/v1'
import { registry } from '../applications/registry'
import type { Access, TreeSource } from '../access'
import { FOLDER_KIND } from '../node/kind'
import type { TreeNode } from '../node/tree'
import type { ByteSink } from '../blob/stream'
import { ZipWriter, zipLength, type ZipPlanEntry } from './zip'
import { MANIFEST_DIR, archiveName, foldArchiveName } from './archiveName'
import {
  MANIFEST_FORMAT, MANIFEST_PATH, MANIFEST_VERSION, ExportError, applicationOf, type Manifest, type ManifestApplication, type ManifestNode,
  type ManifestPayload,
} from './manifest'
import type { ExportPayloadSource, WalkPurpose } from './hooks'

export type SkippedNode = { name: string; kind: string; reason: 'unsupported_kind' | 'nothing_to_export' }

export type ExportResult = { nodes: number; payloads: number; bytes: number; skipped: SkippedNode[] }

export type ExportProgress = { bytesDone: number; bytesTotal: number }

/** One declared payload: the manifest's reference to it and where its bytes come from. */
export type Pending = { ref: ManifestPayload; source: ExportPayloadSource }

export type PrepareExportParams = {
  /** Who downloads (a member, or a READ / READ_WRITE guest). */
  access: Access
  /** The tree as that session reads it: children and keys. */
  tree: TreeSource<TreeNode>
  rootId: string
  appVersion: string
  signal?: AbortSignal
  now?: () => Date
}

export type ExportSubtreeParams = PrepareExportParams & {
  sink: ByteSink
  onProgress?: (p: ExportProgress) => void
}

/** A download whose tree is walked and whose payloads are declared, with nothing written yet: what the dialog shows
 *  ({@link ExportResult} less the writing) and, once the member has picked where to save, {@link write}s. */
export type PreparedExport = ExportResult & {
  /** The exact length of the ZIP {@link write} produces - what a save target is opened with. Not {@link ExportResult.bytes}
   *  (the payloads alone): the headers, descriptors, directory and the manifest come on top, and the SW download route
   *  serves this number as the `Content-Length` it holds the stream to (F11). */
  archiveBytes: number
  write(sink: ByteSink, onProgress?: (p: ExportProgress) => void): Promise<void>
}

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const byOrder = (a: TreeNode, b: TreeNode) => a.orderIndex - b.orderIndex || a.name.localeCompare(b.name)
const cancelled = (signal?: AbortSignal): never => {
  throw signal?.reason ?? new DOMException('Download cancelled', 'AbortError')
}

/** The scope a session's seals bind: a member's own, a guest's the link's. */
export const scopeOfAccess = (access: Access): string => (access.mode === 'member' ? access.scopeId : access.head.scopeId)

/** A walked subtree, nothing written: the manifest's root (its payloads only DECLARED - no `sha256` yet), each payload with
 *  its source in tree order, the directories, what was left out, and the applications it carries. What a download writes
 *  into a ZIP ({@link prepareExport}) and what a copy replays under its destination (`copySubtree.ts`). */
export type WalkedSubtree = {
  root: ManifestNode
  payloads: Pending[]
  directories: string[]
  skipped: SkippedNode[]
  applications: Map<string, number>
  nodes: number
}

/** Walk the subtree under `rootId` as the session reads it, running every kind's export hook (see the header) - for a
 *  download unless `purpose` says it is a copy's walk. */
export async function walkSubtree(params: Omit<PrepareExportParams, 'appVersion' | 'now'> & { purpose?: WalkPurpose }): Promise<WalkedSubtree> {
  const { access, tree, rootId, signal, purpose = 'download' } = params
  const scopeId = scopeOfAccess(access)
  const root = tree.node(rootId)
  const rootKey = root ? tree.keyOf(rootId) : null
  if (!root || !rootKey) throw new ExportError('nothing_to_import', 'the node to download is not in the open tree')

  const pending: Pending[] = []
  const directories: string[] = []
  const skipped: SkippedNode[] = []
  const applications = new Map<string, number>()
  const cache = new Map<string, unknown>()
  let counter = 0
  let nodes = 0

  /** Build `node` (key `key`) inside the directory `dir` (null: the archive's root), whose names so far are `taken`. */
  async function build(node: TreeNode, key: Uint8Array, dir: string | null, taken: Set<string>): Promise<ManifestNode | null> {
    const d = registry.kind(node.kind)
    if (node.kind !== FOLDER_KIND && (!d.supported || !d.exportContent)) {
      skipped.push({ name: node.name, kind: node.kind, reason: 'unsupported_kind' })
      return null
    }
    const id = `n${++counter}`
    // the name as the session's views show it among its siblings (`Anna (2)` for a drop box's second guest named Anna, whose
    // folders share a name the server never compares, inbox #393), made safe; the manifest keeps the node's own name
    const segment = archiveName(tree.nameOf(node.id) || node.name, taken)
    const path = dir === null ? segment : `${dir}/${segment}`
    const out: ManifestNode = { id, kind: node.kind, name: node.name, path }
    const asFile = node.kind !== FOLDER_KIND && d.supported && !!d.downloadsAsFile
    if (node.kind !== FOLDER_KIND && d.supported && d.exportContent) {
      const mark = pending.length
      const inside = new Set<string>()
      const content = await d.exportContent({
        purpose, access, tree, scopeId, node, nodeKey: key, signal, cache,
        addPayload(fileName, source) {
          if (asFile && pending.length > mark) throw new Error(`${node.kind} downloads as one file and declared a second payload`)
          const ref: ManifestPayload = { path: asFile ? path : `${path}/${archiveName(fileName, inside)}`, size: source.size, mediaType: source.mediaType }
          if (source.modifiedAt !== undefined) ref.modifiedAt = source.modifiedAt
          pending.push({ ref, source })
          return ref
        },
      })
      if (!content) {
        // payloads the hook declared before deciding to bow out are not part of the archive, nor is its name
        pending.length = mark
        taken.delete(foldArchiveName(segment))
        skipped.push({ name: node.name, kind: node.kind, reason: 'nothing_to_export' })
        return null
      }
      out.content = content
      const app = applicationOf(content.type)
      applications.set(app, Math.max(applications.get(app) ?? 1, content.schema ?? 1))
    }
    nodes++
    if (asFile) return out
    directories.push(path)
    const kids: ManifestNode[] = []
    const names = new Set<string>()
    // equal names in the order their shown names number them (`Anna`, `Anna (2)`), so an import - which numbers a clash in the
    // order it meets the nodes - gives each the name its directory has
    const shownOrder = (a: TreeNode, b: TreeNode) => byOrder(a, b) || tree.nameOf(a.id).localeCompare(tree.nameOf(b.id), undefined, { numeric: true })
    for (const child of [...tree.children(node.id)].sort(shownOrder)) {
      if (signal?.aborted) cancelled(signal)
      const childKey = tree.keyOf(child.id)
      if (!childKey) {
        skipped.push({ name: child.name, kind: child.kind, reason: 'nothing_to_export' })
        continue
      }
      const built = await build(child, childKey, path, names)
      if (built) kids.push(built)
    }
    if (kids.length > 0) out.children = kids
    return out
  }

  // The archive's root holds the exported node and `.veyq/`; no node takes the manifest directory's name.
  const built = await build(root, rootKey, null, new Set([MANIFEST_DIR]))
  if (!built) throw new ExportError('nothing_to_import', 'nothing to download under this node')
  return { root: built, payloads: pending, directories, skipped, applications, nodes }
}

/** Walk the subtree and run every kind's hook, so the size and the report are known BEFORE the member picks a place to save
 *  (the save picker needs the click's user activation, which a long walk would have used up). */
export async function prepareExport(params: PrepareExportParams): Promise<PreparedExport> {
  const { signal } = params
  const { root: exportedRoot, payloads: pending, directories, skipped, applications, nodes } = await walkSubtree(params)

  const now = params.now ?? (() => new Date())
  const bytesTotal = pending.reduce((sum, p) => sum + p.source.size, 0)
  const manifestBytes = (exportedAt: Date): Uint8Array => {
    const manifest: Manifest = {
      format: MANIFEST_FORMAT,
      version: MANIFEST_VERSION,
      exportedAt: exportedAt.toISOString(),
      appVersion: params.appVersion,
      applications: [...applications].map(([id, contentSchema]): ManifestApplication => ({ id, contentSchema })),
      root: exportedRoot,
    }
    return new TextEncoder().encode(JSON.stringify(manifest, null, 2))
  }
  // The manifest is written last, once every payload's `sha256` is known - but a SHA-256 in hex is always 64 characters and
  // an ISO time stamp always 24, so the manifest's length is known now: serialise it with a stand-in hash on every payload
  // (set and removed again, so the real one later lands at the same place in each object) and the time of now.
  for (const { ref } of pending) ref.sha256 = '0'.repeat(64)
  const manifestLength = manifestBytes(now()).length
  for (const { ref } of pending) delete ref.sha256
  const plan: ZipPlanEntry[] = [
    ...directories.map((path) => ({ path, directory: true as const })),
    ...pending.map(({ ref, source }) => ({ path: ref.path, size: source.size })),
    { path: MANIFEST_PATH, size: manifestLength },
  ]
  const archiveBytes = zipLength(plan)

  async function write(sink: ByteSink, onProgress?: (p: ExportProgress) => void): Promise<void> {
    const writer = new ZipWriter(sink, { modified: now() })
    let bytesDone = 0
    try {
      for (const dir of directories) await writer.addDirectory(dir)
      for (const { ref, source } of pending) {
        const hasher = v1.sha256Stream()
        async function* hashed() {
          for await (const chunk of source.chunks()) {
            if (signal?.aborted) cancelled(signal)
            hasher.update(chunk)
            bytesDone += chunk.length
            onProgress?.({ bytesDone, bytesTotal })
            yield chunk
          }
        }
        await writer.addFile(ref.path, source.size, hashed())
        ref.sha256 = hex(hasher.digest())
      }
      const manifest = manifestBytes(now())
      // the one record whose length was derived rather than known (a five-digit year would do it): a target held to the
      // announced length would fail the download, so it fails here, before anything past the payloads is written
      if (manifest.length !== manifestLength) throw new ExportError('size_mismatch', 'the manifest is not the length announced')
      await writer.addBytes(MANIFEST_PATH, manifest)
      await writer.finish()
    } catch (e) {
      await writer.abort(e)
      throw e
    }
  }
  return { nodes, payloads: pending.length, bytes: bytesTotal, archiveBytes, skipped, write }
}

export async function exportSubtree(params: ExportSubtreeParams): Promise<ExportResult> {
  const { write, ...result } = await prepareExport(params)
  await write(params.sink, params.onProgress)
  return result
}
