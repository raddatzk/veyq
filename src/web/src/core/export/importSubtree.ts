// Import a download (ADR 0082 decisions 6-10): a member flow, and everything lands under the importer. Nothing of the source
// is carried but names, order, content and plaintext metadata: ids, keys and wraps are made fresh under the target's keys, the
// importer becomes the owner, and a name that is taken among the target's siblings gets `Name (2)`.
//
// The input is a ZIP or the same download unpacked into a directory (`importSource.ts`); either is read by path, never held
// whole. Before anything is written, {@link openExport} and {@link planImport} - the DRY RUN - refuse an archive that is not
// sound (decision 10: the manifest against the contract, the caps, the ZIP's structure, every payload present at its declared
// size, each kind's own content check), and list per application what this build cannot import (decision 9: an application
// it does not have, or content newer than it reads). The member then imports the rest - exactly those subtrees left out and
// named in the result - or cancels, and a cancel writes nothing.
//
// Each payload is verified (its SHA-256 against the manifest, and a ZIP entry's own CRC) by one pass over its bytes BEFORE a
// kind's hook uploads it, then handed to the hook as a lazy `File` - seekable, as the upload pump needs - whose digest that
// pass computed is remembered for it (`sharedBlob.ts` `rememberDigest`), so an upload's `blob-meta` `sha256` is not read
// again (inbox #555). The server offers no
// multi-node atomicity, so an import is a sequence of ordinary writes; a failure trashes the top-level node it created (its
// subtree goes with it, ADR 0061) and reports how far it got.
import * as v1 from '@veyq/crypto/v1'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { createContentNode, deleteNode, type NodeParent, type TreeNode } from '../node/tree'
import { heldScopeEpochs, memberCreateOptions } from '../node/rereadNode'
import type { EpochKeys } from '../../features/spaces/epochs'
import { ZipError } from './zip'
import { isArchivePath } from './archiveName'
import { sourceOf, type ImportInput, type ImportSource } from './importSource'
import {
  ExportError, MANIFEST_MAX_BYTES, MANIFEST_PATH, MANIFEST_PATH_V1, applicationOf, collectPayloads, isSafePayloadPathV1, parseManifest,
  type Manifest, type ManifestNode, type ManifestPayload,
} from './manifest'
import type { PayloadFile } from './hooks'
import { rememberDigest } from '../blob/sharedBlob'

export type OpenedExport = { source: ImportSource; manifest: Manifest }

/** Any refusal of the ZIP layer as the import names it: a structure the reader does not accept is a damaged archive. */
function asExportError(e: unknown, detail?: string): unknown {
  if (!(e instanceof ZipError)) return e
  if (e.code === 'crc_mismatch') return new ExportError('checksum', e.message, detail)
  if (e.code === 'missing') return new ExportError('payload_missing', e.message, detail)
  return new ExportError('damaged_archive', e.message, detail)
}

async function readAll(stream: AsyncGenerator<Uint8Array>, max: number): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  let length = 0
  for await (const chunk of stream) {
    length += chunk.length
    if (length > max) throw new ExportError('too_large', 'the manifest is too large')
    parts.push(chunk)
  }
  const out = new Uint8Array(length)
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

/** Whether a ZIP holds a veyq manifest, by its content: the cheap test an upload asks before it offers the import. Nothing
 *  but the archive's end records and directory is read; a file that is not a ZIP is answered without parsing anything. */
export async function isDownloadArchive(file: Blob): Promise<boolean> {
  if (file.size < 22) return false
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer())
  if (head[0] !== 0x50 || head[1] !== 0x4b || head[2] !== 0x03 || head[3] !== 0x04) return false
  try {
    const source = await sourceOf({ kind: 'zip', file: file as File })
    return source.has(MANIFEST_PATH) || source.has(MANIFEST_PATH_V1)
  } catch {
    return false // damaged: the dialog would refuse it, and for an upload it is just a file
  }
}

/**
 * Recognise and open a download by its CONTENT: a ZIP or a directory whose manifest says `veyq-subtree` (never by its name).
 * Checks, before anything is written, the structure of a ZIP, the manifest (`parseManifest`) and that every payload it names
 * is there at its declared size.
 * @throws ExportError - see {@link ExportErrorCode}; the import dialog explains each.
 */
export async function openExport(input: ImportInput): Promise<OpenedExport> {
  let source: ImportSource
  try {
    source = await sourceOf(input)
  } catch (e) {
    if (e instanceof ZipError && e.code === 'not_zip') throw new ExportError('not_an_export', 'not a ZIP archive')
    throw asExportError(e)
  }
  // version 2 keeps it beside the tree, version 1 at the root (a ZIP only: version 1 was never unpacked by veyq)
  const at = source.has(MANIFEST_PATH) ? MANIFEST_PATH : source.kind === 'zip' && source.has(MANIFEST_PATH_V1) ? MANIFEST_PATH_V1 : null
  if (!at) throw new ExportError('not_an_export', 'no manifest')
  if ((source.size(at) ?? 0) > MANIFEST_MAX_BYTES) throw new ExportError('too_large', 'the manifest is too large')
  let bytes: Uint8Array
  try {
    bytes = await readAll(source.stream(at), MANIFEST_MAX_BYTES)
  } catch (e) {
    throw asExportError(e)
  }
  let json: unknown
  try {
    json = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    throw new ExportError('invalid_manifest', 'the manifest is not JSON')
  }
  const manifest = parseManifest(json)
  if ((at === MANIFEST_PATH) !== manifest.version >= 2) throw new ExportError('invalid_manifest', `a version ${manifest.version} manifest at ${at}`)
  // every file the manifest names is there, at the size it says - before the dry run returns
  const walk = (n: ManifestNode): void => {
    for (const p of collectPayloads(n)) {
      const size = source.size(p.path)
      if (size === undefined) throw new ExportError('payload_missing', `the download lacks ${p.path}`, p.path)
      if (size !== p.size) throw new ExportError('size_mismatch', `${p.path} is not the size the manifest says`, p.path)
    }
    n.children?.forEach(walk)
  }
  walk(manifest.root)
  return { source, manifest }
}

/** Why the dry run leaves a node (and its subtree) out: an application this build does not have, content newer than it
 *  reads, or - in a copy - a kind whose import cannot take the original read by frame (`KindDescriptor.importStreams`). */
export type SkipReason = 'unknown_application' | 'newer_content' | 'not_copyable'

/** A subtree the import leaves out: its top node, and how many nodes it holds. */
export type SkippedSubtree = { name: string; kind: string; application: string; reason: SkipReason; nodes: number }

/** Per application and reason, how many nodes the import leaves out (what the dialog lists). */
export type SkippedGroup = { application: string; reason: SkipReason; nodes: number }

export type ImportPlan = {
  /** The nodes it would create. */
  nodes: number
  /** The payload bytes it would upload. */
  bytes: number
  /** The subtrees it leaves out, in tree order. */
  skipped: SkippedSubtree[]
  /** The same, per application and reason. */
  groups: SkippedGroup[]
}

const countNodes = (n: ManifestNode): number => 1 + (n.children ?? []).reduce((s, c) => s + countNodes(c), 0)

/** Why this build cannot import `n` (with its subtree), or null when it can. A node of a kind this build lacks, or whose kind
 *  belongs to another application than its content says, is an unknown application's; content of a schema newer than the
 *  kind reads is newer. */
function skipReason(n: ManifestNode, manifest: Manifest): { application: string; reason: SkipReason } | null {
  if (n.kind === FOLDER_KIND) return null
  const application = n.content ? applicationOf(n.content.type) : n.kind
  const d = registry.kind(n.kind)
  if (!d.supported || !(d.importContent || d.importsAsFolder) || (d.contentSchema && d.contentSchema.application !== application)) {
    return { application, reason: 'unknown_application' }
  }
  const written = n.content?.schema ?? manifest.applications?.find((a) => a.id === application)?.contentSchema ?? 1
  if (written > (d.contentSchema?.version ?? 1)) return { application, reason: 'newer_content' }
  return null
}

/** How a dry run reads its manifest. */
export type PlanOptions = {
  /** The manifest came from outside (a download, decision 10): each kind checks its content. Default true; a copy's
   *  manifest is the member's own tree as the walk read it, so nothing is checked (cr-copy-node Decision 2). */
  untrusted?: boolean
  /** A further reason to leave a node out (a copy's: a kind that cannot take a payload read by frame). */
  leaveOut?: (n: ManifestNode) => SkipReason | null
}

/** Why `n` is left out (with its subtree), by this build and by the caller's own rule; null where it is created. */
export const leftOutWhy = (n: ManifestNode, manifest: Manifest, options: PlanOptions): { application: string; reason: SkipReason } | null => {
  const skip = skipReason(n, manifest)
  if (skip) return skip
  const reason = options.leaveOut?.(n) ?? null
  return reason ? { application: n.content ? applicationOf(n.content.type) : n.kind, reason } : null
}

/**
 * The dry run: what an import would create and how many payload bytes it would upload, from the manifest alone, with every
 * subtree this build cannot import listed (decision 9). Each importable node's content is checked by its kind
 * (`validateContent`), so a manifest whose content is not its kind's shape is refused here, before anything is written.
 * @throws ExportError `invalid_manifest` / `too_large` (from a kind's check)
 */
export function planImport(manifest: Manifest, options: PlanOptions = {}): ImportPlan {
  const untrusted = options.untrusted ?? true
  const plan: ImportPlan = { nodes: 0, bytes: 0, skipped: [], groups: [] }
  const walk = (n: ManifestNode) => {
    const skip = leftOutWhy(n, manifest, options)
    if (skip) {
      const nodes = countNodes(n)
      plan.skipped.push({ name: n.name, kind: n.kind, ...skip, nodes })
      const group = plan.groups.find((g) => g.application === skip.application && g.reason === skip.reason)
      if (group) group.nodes += nodes
      else plan.groups.push({ ...skip, nodes })
      return
    }
    if (untrusted && n.kind !== FOLDER_KIND) {
      const d = registry.kind(n.kind)
      const problem = d.supported && d.validateContent && n.content ? d.validateContent(n.content) : null
      if (problem) throw new ExportError(problem.code, `${n.id}: ${problem.message}`, n.name)
    }
    plan.nodes++
    plan.bytes += collectPayloads(n).reduce((s, p) => s + p.size, 0)
    n.children?.forEach(walk)
  }
  walk(manifest.root)
  return plan
}

export type ImportProgress = { nodesDone: number; nodesTotal: number; bytesDone: number; bytesTotal: number }

export type ImportResult = { nodeId: string; nodes: number; skipped: SkippedSubtree[] }

/** An import that stopped part-way: `done` of `total` nodes were created, and what it had created was trashed
 *  (`rolledBack`; false when even that failed - a top-level node is then still in the target, named in `leftover`). */
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

/** Hands an import hook one payload of its manifest (`ImportNodeContext.payload`). */
export type PayloadResolver = (ref: ManifestPayload, fileName: string) => Promise<PayloadFile>

/** One subtree to replay: its manifest, where its payloads come from, how its dry run reads it, and what it is - a
 *  download's (`import`, the default) or a copy's walk (`ImportNodeContext.purpose`). */
export type ReplayItem = { manifest: Manifest; payload: PayloadResolver; plan?: PlanOptions; purpose?: 'import' | 'copy' }

export type ReplayParams = Omit<ImportSubtreeParams, 'opened'> & { items: readonly ReplayItem[] }

/** What a replay created: the top node of each subtree, in order, how many nodes, and what it left out. */
export type ReplayResult = { nodeIds: string[]; nodes: number; skipped: SkippedSubtree[] }

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const MAX_NAME_TRIES = 50

/** The kind an import creates for `n`: its own, or a plain `FOLDER` for a kind that imports as one (a drop box). */
export const createdKind = (n: { kind: string }): string => {
  if (n.kind === FOLDER_KIND) return FOLDER_KIND
  const d = registry.kind(n.kind)
  return d.supported && d.importsAsFolder ? FOLDER_KIND : n.kind
}

/** The payload resolver of a download (decision 10): the path checked, the size the manifest says, and its SHA-256 (and a
 *  ZIP entry's CRC) verified by one pass over its bytes BEFORE a hook uploads it, then handed out as a lazy `File` with the
 *  digest that pass computed remembered for it (`rememberDigest`) - the hasher's own bytes, also where the manifest names
 *  no `sha256`, and never the manifest's hex, which is only compared (inbox #555). */
function archivePayload(opened: OpenedExport, signal?: AbortSignal): PayloadResolver {
  const { source, manifest } = opened
  return async (ref, fileName) => {
    if (!(manifest.version === 1 ? isSafePayloadPathV1(ref.path) : isArchivePath(ref.path))) throw new ExportError('unsafe_path', 'unsafe payload path', ref.path)
    const size = source.size(ref.path)
    if (size === undefined) throw new ExportError('payload_missing', `the download lacks ${ref.path}`, ref.path)
    if (size !== ref.size) throw new ExportError('size_mismatch', `${ref.path} is not the size the manifest says`, ref.path)
    const hasher = v1.sha256Stream()
    try {
      for await (const chunk of source.stream(ref.path)) {
        if (signal?.aborted) throw signal.reason ?? new DOMException('Import cancelled', 'AbortError')
        hasher.update(chunk)
      }
    } catch (e) {
      throw asExportError(e, ref.path)
    }
    const digest = hasher.digest()
    if (ref.sha256 && hex(digest) !== ref.sha256) throw new ExportError('checksum', `${ref.path} does not match its checksum`, ref.path)
    const file = new File([source.blob(ref.path, ref.mediaType)], fileName, { type: ref.mediaType, lastModified: ref.modifiedAt })
    rememberDigest(file, digest)
    return file
  }
}

/** Import the rest: everything {@link planImport} does not leave out. Nothing is written when the plan holds nothing. */
export async function importSubtree(params: ImportSubtreeParams): Promise<ImportResult> {
  const { opened, ...rest } = params
  const result = await replaySubtrees({ ...rest, items: [{ manifest: opened.manifest, payload: archivePayload(opened, params.signal) }] })
  return { nodeId: result.nodeIds[0], nodes: result.nodes, skipped: result.skipped }
}

/**
 * Re-create each item's subtree under `parent`, one after the other, through the kinds' import hooks: what an import does
 * with a download, and what a copy does with the subtrees it walked (ADR 0082 decision 11). A name taken among the target's
 * siblings - the tree's, and those this run created - gets `Name (2)`. A failure trashes every top-level node the run
 * created (their subtrees go with them, ADR 0061) and reports how far it got.
 * @throws ExportError `nothing_to_import` when the plans hold nothing (nothing is written); {@link ImportFailed} part-way.
 */
export async function replaySubtrees(params: ReplayParams): Promise<ReplayResult> {
  const { scopeId, epochs, tree, parent, items, signal, onProgress } = params
  const plans = items.map((item) => planImport(item.manifest, item.plan))
  const nodesTotal = plans.reduce((s, p) => s + p.nodes, 0)
  const bytesTotal = plans.reduce((s, p) => s + p.bytes, 0)
  if (nodesTotal === 0) throw new ExportError('nothing_to_import', 'this build can import nothing in this download')

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
  const report = () => onProgress?.({ nodesDone, nodesTotal, bytesDone, bytesTotal })
  const cache = new Map<string, unknown>()
  const tops: string[] = []

  // The scope's epochs as this device holds them now: a rotation another member makes while the run is under way is met by
  // a pull, which re-opens the scope here (and zeroes the epochs the run was started with, `refreshActiveScope`) — every
  // node created and every write from then on is at the new epoch (inbox #670).
  const held = (): EpochKeys => heldScopeEpochs(scopeId) ?? epochs

  async function create(n: ManifestNode, at: NodeParent, item: ReplayItem): Promise<{ nodeId: string; nodeKey: Uint8Array; epoch?: number }> {
    const parentId = at?.id ?? null
    const used = takenIn(parentId)
    let lastError: unknown = null
    const nodeBytes = collectPayloads(n).reduce((s, p) => s + p.size, 0)
    const kind = createdKind(n)
    // A member's create under `at`: a parent not re-keyed since a rotation is pulled forward on this device and re-read, and
    // the create retried, never refused (ADR 0111 decision 11). The import's target (and its ancestors) can lag, and so can
    // what this run created itself, once a rotation landed after it: the next create under it pulls it the same way.
    const createOptions = memberCreateOptions(scopeId, at)
    for (const name of candidates(n.name)) {
      const slug = registry.slugFor(kind, name)
      if (used.has(slug)) continue
      try {
        if (kind === FOLDER_KIND) {
          const { nodeId, contentKey, epoch } = await createContentNode(scopeId, held(), at, FOLDER_KIND, name, undefined, createOptions)
          used.add(slug)
          return { nodeId, nodeKey: contentKey, epoch }
        }
        const hook = registry.kind(n.kind)
        if (!hook.supported || !hook.importContent) throw new ExportError('nothing_to_import', `no import for ${n.kind}`)
        const filesBefore = bytesDone
        const made = await hook.importContent({
          purpose: item.purpose ?? 'import', scopeId, epochs: held(), parent: at, createOptions, name, node: n, signal, cache, payload: item.payload,
          onProgress: (fraction) => {
            bytesDone = filesBefore + Math.round(fraction * nodeBytes)
            report()
          },
        })
        bytesDone = filesBefore + nodeBytes
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

  async function walk(n: ManifestNode, at: NodeParent, item: ReplayItem, leftOut: Set<ManifestNode>, top: boolean): Promise<string> {
    if (signal?.aborted) throw signal.reason ?? new DOMException('Import cancelled', 'AbortError')
    const made = await create(n, at, item)
    if (top) tops.push(made.nodeId)
    nodesDone++
    report()
    // the node as its last write left it: minted at the epoch the device held, or re-keyed since by its kind's own content
    // writes pulling it forward (a hook that names no epoch: the epoch held now); its children's creates state that one
    const here: NodeParent = { id: made.nodeId, key: made.nodeKey, epoch: made.epoch ?? held().current }
    for (const c of n.children ?? []) {
      if (leftOut.has(c)) continue // listed in the plan, named in the result
      await walk(c, here, item, leftOut, false)
    }
    return made.nodeId
  }

  const nodeIds: string[] = []
  const skipped = plans.flatMap((p) => p.skipped)
  try {
    for (const [i, item] of items.entries()) {
      if (plans[i].nodes === 0) continue // its top itself is left out: listed in the plan
      const leftOut = new Set<ManifestNode>()
      const mark = (n: ManifestNode) => {
        if (leftOutWhy(n, item.manifest, item.plan ?? {})) leftOut.add(n)
        else n.children?.forEach(mark)
      }
      mark(item.manifest.root)
      nodeIds.push(await walk(item.manifest.root, parent, item, leftOut, true))
    }
    return { nodeIds, nodes: nodesDone, skipped }
  } catch (e) {
    let leftover: string | null = null
    for (const top of tops) await deleteNode(scopeId, top).catch(() => void (leftover ??= top))
    throw new ImportFailed(nodesDone, nodesTotal, leftover === null, leftover, e)
  }
}
