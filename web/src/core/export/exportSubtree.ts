// Export a node and everything under it as a store-only ZIP (ADR 0082), in the client, on decrypted data. The tree is walked
// with the keys the member already holds (the subtree root's key, then each child's opened under its parent's), each kind
// supplies its content through its descriptor's `exportContent` hook, and the bytes stream into a `ByteSink` one chunk at a
// time: what the tab holds is the manifest (one line per node) and a window of chunks, never the subtree.
//
// Order: the manifest is built first (every hook runs, payloads are only DECLARED), then the payloads stream into the
// archive - each hashed on the way - and the manifest, now with its `sha256`s, is written last. A node whose kind has no
// export hook, or whose hook has nothing to export, is left out with its subtree and listed in `skipped`.
import * as v1 from '@veyq/crypto/v1'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { openContentKey, nodeContentKey, type TreeNode } from '../node/tree'
import type { ByteSink } from '../blob/stream'
import type { EpochKeys } from '../../features/spaces/epochs'
import { ZipWriter } from './zip'
import { MANIFEST_FILE, MANIFEST_FORMAT, MANIFEST_VERSION, ExportError, type Manifest, type ManifestNode, type ManifestPayload } from './manifest'
import type { ExportPayloadSource } from './hooks'

export type SkippedNode = { name: string; kind: string; reason: 'unsupported_kind' | 'nothing_to_export' }

export type ExportOptions = { includeLocation: boolean }

export type ExportResult = { nodes: number; payloads: number; bytes: number; skipped: SkippedNode[] }

export type ExportProgress = { bytesDone: number; bytesTotal: number }

type Pending = { ref: ManifestPayload; source: ExportPayloadSource }

/** A file name that is safe inside `data/<id>/`: no separators, no control characters, not `.`/`..`, bounded. */
export function safeFileName(name: string): string {
  let n = name.replace(/[/\\\u0000-\u001f]/g, '_').trim()
  if (n === '' || n === '.' || n === '..') n = 'file'
  if (n.length > 180) {
    const dot = n.lastIndexOf('.')
    const ext = dot > 0 && n.length - dot <= 12 ? n.slice(dot) : ''
    n = n.slice(0, 180 - ext.length) + ext
  }
  return n
}

export type ExportSubtreeParams = {
  scopeId: string
  epochs: EpochKeys
  /** The decrypted tree of the scope (`treeStore.nodes`). */
  tree: TreeNode[]
  rootId: string
  sink: ByteSink
  options: ExportOptions
  appVersion: string
  signal?: AbortSignal
  onProgress?: (p: ExportProgress) => void
  now?: () => Date
}

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const byOrder = (a: TreeNode, b: TreeNode) => a.orderIndex - b.orderIndex || a.name.localeCompare(b.name)

export async function exportSubtree(params: ExportSubtreeParams): Promise<ExportResult> {
  const { scopeId, epochs, tree, rootId, sink, options, appVersion, signal, onProgress } = params
  const root = tree.find((n) => n.id === rootId)
  const rootKey = root ? nodeContentKey(scopeId, epochs, tree, rootId) : null
  if (!root || !rootKey) throw new ExportError('invalid_manifest', 'the node to export is not in the open tree')

  const children = new Map<string | null, TreeNode[]>()
  for (const n of tree) (children.get(n.parentId) ?? children.set(n.parentId, []).get(n.parentId)!).push(n)
  for (const list of children.values()) list.sort(byOrder)

  const pending: Pending[] = []
  const skipped: SkippedNode[] = []
  const cache = new Map<string, unknown>()
  let counter = 0
  let nodes = 0

  async function build(node: TreeNode, key: Uint8Array): Promise<ManifestNode | null> {
    const d = registry.kind(node.kind)
    const id = `n${++counter}`
    const out: ManifestNode = { id, kind: node.kind, name: node.name }
    if (node.kind !== FOLDER_KIND) {
      if (!d.supported || !d.exportContent) {
        skipped.push({ name: node.name, kind: node.kind, reason: 'unsupported_kind' })
        return null
      }
      const names = new Set<string>()
      const content = await d.exportContent({
        scopeId, epochs, node, nodeKey: key, options, signal, cache,
        addPayload(fileName, source) {
          let name = safeFileName(fileName)
          for (let i = 2; names.has(name); i++) name = `${safeFileName(fileName)}.${i}`
          names.add(name)
          const ref: ManifestPayload = { path: `data/${id}/${name}`, size: source.size, mediaType: source.mediaType }
          if (source.modifiedAt !== undefined) ref.modifiedAt = source.modifiedAt
          pending.push({ ref, source })
          return ref
        },
      })
      if (!content) {
        // payloads the hook declared before deciding to bow out are not part of the archive
        for (let i = pending.length - 1; i >= 0 && pending[i].ref.path.startsWith(`data/${id}/`); i--) pending.pop()
        skipped.push({ name: node.name, kind: node.kind, reason: 'nothing_to_export' })
        return null
      }
      out.content = content
    }
    nodes++
    const kids: ManifestNode[] = []
    for (const child of children.get(node.id) ?? []) {
      if (signal?.aborted) throw signal.reason ?? new DOMException('Export cancelled', 'AbortError')
      let childKey: Uint8Array
      try {
        childKey = openContentKey(scopeId, key, child)
      } catch {
        skipped.push({ name: child.name, kind: child.kind, reason: 'nothing_to_export' })
        continue
      }
      const built = await build(child, childKey)
      if (built) kids.push(built)
    }
    if (kids.length > 0) out.children = kids
    return out
  }

  const manifestRoot = await build(root, rootKey)
  if (!manifestRoot) throw new ExportError('invalid_manifest', 'nothing to export under this node')

  const bytesTotal = pending.reduce((sum, p) => sum + p.source.size, 0)
  const writer = new ZipWriter(sink, { modified: (params.now ?? (() => new Date()))() })
  let bytesDone = 0
  try {
    for (const { ref, source } of pending) {
      const hasher = v1.sha256Stream()
      async function* hashed() {
        for await (const chunk of source.chunks()) {
          if (signal?.aborted) throw signal.reason ?? new DOMException('Export cancelled', 'AbortError')
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
      options: { includeLocation: options.includeLocation },
      root: manifestRoot,
    }
    await writer.addBytes(MANIFEST_FILE, new TextEncoder().encode(JSON.stringify(manifest, null, 2)))
    await writer.finish()
  } catch (e) {
    await writer.abort(e)
    throw e
  }
  return { nodes, payloads: pending.length, bytes: bytesTotal, skipped }
}
