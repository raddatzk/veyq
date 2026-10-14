// Import a subtree export (ADR 0082 decisions 6-8): a member flow, and everything lands under the importer. Nothing of the
// source is carried but names, order, content and plaintext metadata: ids, keys and wraps are made fresh under the target's
// keys, the importer becomes the owner, and a name that is taken among the target's siblings gets `Name (2)`.
//
// The archive is never held: it is a `Blob` read by slice. Each payload is verified (its SHA-256 and size against the
// manifest, and the ZIP's own CRC) by one pass over its bytes BEFORE a kind's hook uploads it, then handed to the hook as a
// lazy `File` over the archive - seekable, as the upload pump needs. The server offers no multi-node atomicity, so an import
// is a sequence of ordinary writes; a failure trashes the top-level node it created (its subtree goes with it, ADR 0061) and
// reports how far it got. A dry run ({@link planImport}) reads only the manifest.
import * as v1 from '@veyq/crypto/v1'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { createContentNode, deleteNode, type NodeParent, type TreeNode } from '../node/tree'
import type { EpochKeys } from '../../features/spaces/epochs'
import { ZipError, openZip, type ZipArchive } from './zip'
import {
  ExportError, MANIFEST_MAX_BYTES, MANIFEST_PATH, MANIFEST_PATH_V1, collectPayloads, isSafePayloadPathV1, parseManifest, type Manifest,
  type ManifestNode, type ManifestPayload,
} from './manifest'
import { isArchivePath } from './archiveName'
import type { SkippedNode } from './exportSubtree'

export type OpenedExport = { zip: ZipArchive; manifest: Manifest }

/**
 * Recognise and open an export by its CONTENT: a ZIP whose `manifest.json` says `veyq-subtree` (never by the file name).
 * @throws ExportError `not_an_export` for anything else, `newer_version` / `invalid_manifest` for a manifest this build cannot read.
 */
export async function openExport(file: Blob): Promise<OpenedExport> {
  let zip: ZipArchive
  try {
    zip = await openZip(file)
  } catch (e) {
    if (e instanceof ZipError) throw new ExportError('not_an_export', 'not a ZIP archive')
    throw e
  }
  // version 2 keeps it beside the tree, version 1 at the root; each version only where it belongs
  const at = zip.has(MANIFEST_PATH) ? MANIFEST_PATH : zip.has(MANIFEST_PATH_V1) ? MANIFEST_PATH_V1 : null
  if (!at) throw new ExportError('not_an_export', 'no manifest')
  let json: unknown
  try {
    json = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(await zip.bytes(at, MANIFEST_MAX_BYTES)))
  } catch (e) {
    if (e instanceof ZipError && e.code === 'unsupported') throw new ExportError('too_large', 'the manifest is too large')
    throw new ExportError('not_an_export', 'the manifest is not JSON')
  }
  const manifest = parseManifest(json)
  if ((at === MANIFEST_PATH) !== manifest.version >= 2) throw new ExportError('invalid_manifest', `a version ${manifest.version} manifest at ${at}`)
  return { zip, manifest }
}

const importable = (kind: string): boolean => {
  if (kind === FOLDER_KIND) return true
  const d = registry.kind(kind)
  return d.supported && !!d.importContent
}

export type ImportPlan = { nodes: number; bytes: number; skipped: SkippedNode[] }

/** The dry run: what an import would create and how many payload bytes it would upload, from the manifest alone. A node
 *  this build cannot import is skipped with its subtree. */
export function planImport(manifest: Manifest): ImportPlan {
  const plan: ImportPlan = { nodes: 0, bytes: 0, skipped: [] }
  const walk = (n: ManifestNode) => {
    if (!importable(n.kind)) {
      plan.skipped.push({ name: n.name, kind: n.kind, reason: 'unsupported_kind' })
      return
    }
    plan.nodes++
    plan.bytes += collectPayloads(n).reduce((s, p) => s + p.size, 0)
    n.children?.forEach(walk)
  }
  walk(manifest.root)
  return plan
}

export type ImportProgress = { nodesDone: number; nodesTotal: number; bytesDone: number; bytesTotal: number }

export type ImportResult = { nodeId: string; nodes: number; skipped: SkippedNode[] }

/** An import that stopped part-way: `done` of `total` nodes were created, and what it had created was trashed
 *  (`rolledBack`; false when even that failed - the top-level node is then still in the target, named in `leftover`). */
export class ImportFailed extends Error {
  constructor(readonly done: number, readonly total: number, readonly rolledBack: boolean, readonly leftover: string | null, readonly cause: unknown) {
    super(`import stopped after ${done} of ${total}`)
    this.name = 'ImportFailed'
  }
}

export type ImportSubtreeParams = {
  scopeId: string
  epochs: EpochKeys
  /** The decrypted tree of the scope: the target's existing siblings, for the name collisions. */
  tree: TreeNode[]
  parent: NodeParent
  opened: OpenedExport
  signal?: AbortSignal
  onProgress?: (p: ImportProgress) => void
}

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const MAX_NAME_TRIES = 50

export async function importSubtree(params: ImportSubtreeParams): Promise<ImportResult> {
  const { scopeId, epochs, tree, parent, opened, signal, onProgress } = params
  const { zip, manifest } = opened
  const plan = planImport(manifest)
  if (!importable(manifest.root.kind)) throw new ExportError('nothing_to_import', 'this build cannot import the exported node')

  // slugs taken per parent: the target's siblings from the tree, a fresh folder's from what this run puts into it
  const taken = new Map<string | null, Set<string>>()
  const takenIn = (parentId: string | null): Set<string> => {
    let set = taken.get(parentId)
    if (!set) {
      set = new Set(tree.filter((n) => n.parentId === parentId).map((n) => registry.slugFor(n.kind, n.name)))
      taken.set(parentId, set)
    }
    return set
  }
  function* candidates(name: string): Generator<string> {
    yield name
    for (let i = 2; i <= MAX_NAME_TRIES; i++) yield `${name} (${i})`
  }

  let nodesDone = 0
  let bytesDone = 0
  const report = () => onProgress?.({ nodesDone, nodesTotal: plan.nodes, bytesDone, bytesTotal: plan.bytes })
  const cache = new Map<string, unknown>()
  let top: string | null = null
  const skipped: SkippedNode[] = [...plan.skipped]

  async function payload(ref: ManifestPayload, fileName: string): Promise<File> {
    if (!(manifest.version === 1 ? isSafePayloadPathV1(ref.path) : isArchivePath(ref.path))) throw new ExportError('unsafe_path', 'unsafe payload path', ref.path)
    const entry = zip.get(ref.path)
    if (!entry) throw new ExportError('payload_missing', `the archive lacks ${ref.path}`)
    if (entry.size !== ref.size) throw new ExportError('checksum', `${ref.path} is not the size the manifest says`)
    const hasher = v1.sha256Stream()
    for await (const chunk of zip.stream(ref.path)) {
      if (signal?.aborted) throw signal.reason ?? new DOMException('Import cancelled', 'AbortError')
      hasher.update(chunk)
    }
    if (ref.sha256 && hex(hasher.digest()) !== ref.sha256) throw new ExportError('checksum', `${ref.path} does not match its checksum`)
    const blob = await zip.blob(ref.path, ref.mediaType)
    return new File([blob], fileName, { type: ref.mediaType, lastModified: ref.modifiedAt })
  }

  async function create(n: ManifestNode, at: NodeParent): Promise<{ nodeId: string; nodeKey: Uint8Array }> {
    const parentId = at?.id ?? null
    const used = takenIn(parentId)
    let lastError: unknown = null
    for (const name of candidates(n.name)) {
      const slug = registry.slugFor(n.kind, name)
      if (used.has(slug)) continue
      try {
        if (n.kind === FOLDER_KIND) {
          const { nodeId, contentKey } = await createContentNode(scopeId, epochs, at, FOLDER_KIND, name)
          used.add(slug)
          return { nodeId, nodeKey: contentKey }
        }
        const hook = registry.kind(n.kind)
        if (!hook.supported || !hook.importContent) throw new ExportError('nothing_to_import', `no import for ${n.kind}`)
        const filesBefore = bytesDone
        const made = await hook.importContent({
          scopeId, epochs, parent: at, name, node: n, signal, cache,
          payload: async (ref, fileName) => {
            const f = await payload(ref, fileName)
            return f
          },
          onProgress: (fraction) => {
            bytesDone = filesBefore + Math.round(fraction * collectPayloads(n).reduce((s, p) => s + p.size, 0))
            report()
          },
        })
        bytesDone = filesBefore + collectPayloads(n).reduce((s, p) => s + p.size, 0)
        used.add(slug)
        return made
      } catch (e) {
        // the server's own answer to a name it holds that the local tree did not know: try the next suffix
        if (e instanceof Error && e.message === 'slug_taken') {
          used.add(slug)
          lastError = e
          continue
        }
        throw e
      }
    }
    throw lastError ?? new ExportError('nothing_to_import', `no free name for ${n.name}`)
  }

  async function walk(n: ManifestNode, at: NodeParent): Promise<string> {
    if (signal?.aborted) throw signal.reason ?? new DOMException('Import cancelled', 'AbortError')
    const made = await create(n, at)
    if (top === null) top = made.nodeId
    nodesDone++
    report()
    const here: NodeParent = { id: made.nodeId, key: made.nodeKey }
    for (const c of n.children ?? []) {
      if (!importable(c.kind)) continue // counted in the plan's `skipped`
      await walk(c, here)
    }
    return made.nodeId
  }

  try {
    const nodeId = await walk(manifest.root, parent)
    return { nodeId, nodes: nodesDone, skipped }
  } catch (e) {
    let rolledBack = true
    if (top !== null) await deleteNode(scopeId, top).catch(() => void (rolledBack = false))
    throw new ImportFailed(nodesDone, plan.nodes, rolledBack, rolledBack ? null : top, e)
  }
}
