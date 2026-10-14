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
import { ZipWriter } from './zip'
import { MANIFEST_DIR, archiveName, foldArchiveName } from './archiveName'
import {
  MANIFEST_FORMAT, MANIFEST_PATH, MANIFEST_VERSION, ExportError, applicationOf, type Manifest, type ManifestApplication, type ManifestNode,
  type ManifestPayload,
} from './manifest'
import type { ExportPayloadSource } from './hooks'

export type SkippedNode = { name: string; kind: string; reason: 'unsupported_kind' | 'nothing_to_export' }

export type ExportResult = { nodes: number; payloads: number; bytes: number; skipped: SkippedNode[] }

export type ExportProgress = { bytesDone: number; bytesTotal: number }

type Pending = { ref: ManifestPayload; source: ExportPayloadSource }

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
  write(sink: ByteSink, onProgress?: (p: ExportProgress) => void): Promise<void>
}

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const byOrder = (a: TreeNode, b: TreeNode) => a.orderIndex - b.orderIndex || a.name.localeCompare(b.name)
const cancelled = (signal?: AbortSignal): never => {
  throw signal?.reason ?? new DOMException('Download cancelled', 'AbortError')
}

/** The scope a session's seals bind: a member's own, a guest's the link's. */
export const scopeOfAccess = (access: Access): string => (access.mode === 'member' ? access.scopeId : access.head.scopeId)

/** Walk the subtree and run every kind's hook, so the size and the report are known BEFORE the member picks a place to save
 *  (the save picker needs the click's user activation, which a long walk would have used up). */
export async function prepareExport(params: PrepareExportParams): Promise<PreparedExport> {
  const { access, tree, rootId, appVersion, signal } = params
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
    const segment = archiveName(node.name, taken)
    const path = dir === null ? segment : `${dir}/${segment}`
    const out: ManifestNode = { id, kind: node.kind, name: node.name, path }
    const asFile = node.kind !== FOLDER_KIND && d.supported && !!d.downloadsAsFile
    if (node.kind !== FOLDER_KIND && d.supported && d.exportContent) {
      const mark = pending.length
      const inside = new Set<string>()
      const content = await d.exportContent({
        access, tree, scopeId, node, nodeKey: key, signal, cache,
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
    for (const child of [...tree.children(node.id)].sort(byOrder)) {
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
  const exportedRoot: ManifestNode = built

  const bytesTotal = pending.reduce((sum, p) => sum + p.source.size, 0)
  async function write(sink: ByteSink, onProgress?: (p: ExportProgress) => void): Promise<void> {
    const writer = new ZipWriter(sink, { modified: (params.now ?? (() => new Date()))() })
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
      const manifest: Manifest = {
        format: MANIFEST_FORMAT,
        version: MANIFEST_VERSION,
        exportedAt: (params.now ?? (() => new Date()))().toISOString(),
        appVersion,
        applications: [...applications].map(([id, contentSchema]): ManifestApplication => ({ id, contentSchema })),
        root: exportedRoot,
      }
      await writer.addBytes(MANIFEST_PATH, new TextEncoder().encode(JSON.stringify(manifest, null, 2)))
      await writer.finish()
    } catch (e) {
      await writer.abort(e)
      throw e
    }
  }
  return { nodes, payloads: pending.length, bytes: bytesTotal, skipped, write }
}

export async function exportSubtree(params: ExportSubtreeParams): Promise<ExportResult> {
  const { write, ...result } = await prepareExport(params)
  await write(params.sink, params.onProgress)
  return result
}
