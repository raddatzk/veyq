// Copy a node and everything under it to another place in the same space (uc.platform.copy-node, ADR 0082 decision 11): the
// download's walk handed straight to the import, without the archive. `prepareCopy` walks each copied subtree as the member
// reads it (`walkSubtree`, every kind's `exportContent`) into an in-memory manifest whose payloads stay DECLARED - nothing is
// read yet - and says what a copy would create, how many bytes, what it leaves out and which kinds it copies (the dialog's
// losses). `run` then replays the manifests under the destination through the kinds' `importContent` hooks
// (`replaySubtrees`): every node created fresh under a fresh key, every file read from the original by frame, decrypted on
// the device and uploaded again under a fresh `K_blob` (`copySource.ts`). The server sees ordinary creates and uploads.
// Both hooks are told it is a copy (`purpose: 'copy'`), so a kind may hand over what an archive never carries and its import
// then needs no decode (Memories: each medium's existing tiers and sealed facts, ADR 0082 decision 11).
//
// What the archive path checks and this one does not (uc.platform.copy-node step 4): the input is the member's own decrypted tree,
// not untrusted bytes, so no manifest schema, no caps, no checksums and no kind's content check. What it does check: each
// payload's frames against the size the walk declared (`framedPayload`).
//
// A node is left out, with its subtree, and named before anything is written where the walk has nothing of it (a kind
// without an export hook, a file whose upload never finished), where this build cannot create its kind (no import hook),
// and where its kind's import cannot take a payload read by frame (`KindDescriptor.importStreams` absent) or one of its
// payloads cannot be read by frame (`ExportPayloadSource.frames` absent): the copy never buffers a file to feed a hook.
import { registry } from '../applications/registry'
import type { MemberAccess, TreeSource } from '../access'
import { FOLDER_KIND } from '../node/kind'
import type { NodeParent, TreeNode } from '../node/tree'
import type { ScopeKey } from '../../features/spaces/epochs'
import { walkSubtree, type Pending, type SkippedNode } from './exportSubtree'
import { framedPayload } from './copySource'
import { ExportError, MANIFEST_FORMAT, MANIFEST_VERSION, collectPayloads, type Manifest, type ManifestNode } from './manifest'
import {
  createdKind, leftOutWhy, planImport, replaySubtrees, type ImportProgress, type PlanOptions, type ReplayItem, type ReplayResult, type SkipReason,
} from './importSubtree'

/** A node a copy leaves out (with its subtree): its name, its kind and why. */
export type CopyLeftOut = { name: string; kind: string; reason: SkippedNode['reason'] | SkipReason }

export type CopyTarget = {
  scopeId: string
  epochs: ScopeKey
  /** The decrypted tree of the scope: the destination's existing children, for `Name (2)`. */
  tree: TreeNode[]
  /** Where the copies go (null: the scope root). */
  parent: NodeParent
  signal?: AbortSignal
  onProgress?: (p: ImportProgress) => void
}

/** A copy walked and planned, nothing written yet. */
export type PreparedCopy = {
  /** The nodes it would create. */
  nodes: number
  /** The payload bytes it would upload (and read). */
  bytes: number
  /** What it leaves out, in tree order. */
  leftOut: CopyLeftOut[]
  /** The kinds of the nodes it copies, as they are now (a drop box is still a drop box here): the dialog's losses. */
  kinds: string[]
  /** Re-create the copies under `target.parent`; resolves the top node of each copy. */
  run(target: CopyTarget): Promise<ReplayResult>
}

export type PrepareCopyParams = {
  access: MemberAccess
  tree: TreeSource<TreeNode>
  /** The nodes to copy, in order (one node, or a level's selection). */
  rootIds: readonly string[]
  appVersion: string
  signal?: AbortSignal
}

/** Why a copy cannot re-create `n` from what the walk gave it, or null where it can (see the header). */
function notCopyable(n: ManifestNode, payloads: Map<string, Pending>): SkipReason | null {
  if (createdKind(n) === FOLDER_KIND) return null
  const d = registry.kind(n.kind)
  if (!d.supported || !d.importStreams) return 'not_copyable'
  return collectPayloads(n).every((ref) => payloads.get(ref.path)?.source.frames) ? null : 'not_copyable'
}

/** Walk every copied subtree and plan its copy (see the header). Reads listings, never a payload. */
export async function prepareCopy(params: PrepareCopyParams): Promise<PreparedCopy> {
  const { access, tree, rootIds, appVersion, signal } = params
  const items: ReplayItem[] = []
  const leftOut: CopyLeftOut[] = []
  const kinds = new Set<string>()
  let nodes = 0
  let bytes = 0
  for (const rootId of rootIds) {
    let walked: Awaited<ReturnType<typeof walkSubtree>>
    try {
      walked = await walkSubtree({ access, tree, rootId, signal, purpose: 'copy' })
    } catch (e) {
      if (!(e instanceof ExportError && e.code === 'nothing_to_import')) throw e
      const node = tree.node(rootId)
      if (node) leftOut.push({ name: tree.nameOf(rootId) || node.name, kind: node.kind, reason: 'nothing_to_export' })
      continue
    }
    leftOut.push(...walked.skipped)
    const payloads = new Map(walked.payloads.map((p) => [p.ref.path, p]))
    const manifest: Manifest = {
      format: MANIFEST_FORMAT,
      version: MANIFEST_VERSION,
      exportedAt: new Date().toISOString(),
      appVersion,
      applications: [...walked.applications].map(([id, contentSchema]) => ({ id, contentSchema })),
      root: walked.root,
    }
    const plan: PlanOptions = { untrusted: false, leaveOut: (n) => notCopyable(n, payloads) }
    const planned = planImport(manifest, plan)
    leftOut.push(...planned.skipped.map((s) => ({ name: s.name, kind: s.kind, reason: s.reason })))
    nodes += planned.nodes
    bytes += planned.bytes
    const collect = (n: ManifestNode) => {
      if (leftOutWhy(n, manifest, plan)) return
      kinds.add(n.kind)
      n.children?.forEach(collect)
    }
    collect(walked.root)
    items.push({
      manifest,
      plan,
      purpose: 'copy',
      payload: async (ref, fileName) => {
        const frames = payloads.get(ref.path)?.source.frames
        if (!frames) throw new ExportError('payload_missing', `${ref.path} cannot be read by frame`, ref.path)
        return framedPayload(frames, { size: ref.size, name: fileName, type: ref.mediaType, lastModified: ref.modifiedAt ?? Date.now(), path: ref.path }, signal)
      },
    })
  }
  return {
    nodes,
    bytes,
    leftOut,
    kinds: [...kinds],
    run: (target) => replaySubtrees({ ...target, signal: target.signal ?? signal, items }),
  }
}
