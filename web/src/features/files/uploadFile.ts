// A member's file upload (cr-files-application F1b; docs/apps/files.md): one picked `File` becomes one job of core's
// upload queue (`core/blob/queue.ts`), which runs it, shows it in the top bar's status menu and retries a transient
// failure once. What the job does, in this order:
//
//  1. the NODE: for a new file, core's `createContentNode` births a `FILE` node — a fresh random node key wrapped under
//     the parent's key (the folder's, `E_current` at the scope root; ADR 0080 Decision 1), its name sealed and its slug
//     token derived under that same key by the kind's own folding (`fileNameSlug`, `registry.slugFor`, inbox #350). A
//     replace targets the existing node the member confirmed, with its own key.
//  2. the BLOB: core's `planBlob` under the file node's OWN key (role and owner `FILE_CONTENT` / the node id, so the
//     `K_blob` wrap opens only there, ADR 0072 / ADR 0080 Decision 13) and `reserveAndPump` through core's authed blob
//     control plane — read, seal and PUT one frame at a time, never the whole file in memory.
//  3. the COMPLETION: Files' `completeFile` attaches the verified upload to the node's slot, a compare-and-set on
//     `expectedBlobId` (absent for a new file; the blob the member saw for a replace): a peer's replace in between
//     answers `409 stale_file`, and nothing is overwritten.
//  4. the METADATA: MIME type, plaintext size and modified time sealed into the node's `wrappedMeta` (`fileMeta.ts`)
//     AFTER the blob is attached, so a failed upload leaves no meta that describes bytes that are not there. A failed
//     meta write does not undo the stored file: the job still succeeds and says `metaWritten: false`.
//
// The queue retries a failure once by running the job again, so the job keeps what it already did: a node it created
// is reused (a second create would collide with its own name), and a refusal no retry can change — a taken name, an
// illegal place, a peer's replace — is remembered and rethrown at once, without a second upload.
//
// Once the job is given up (failed after its retry, or cancelled — the queue's `abandon`), a node it CREATED that still
// holds no content is moved to the trash (inbox #384): otherwise it stays as a content-less "not uploaded yet" row that
// takes the name. Only after `listFiles` confirmed the slot is empty (a completion whose answer was lost may have
// attached it after all), and best effort: a node the trash call misses stays, and the section offers an upload into
// it. A replace never trashes anything — the node was there before the job.
import { createContentNode, deleteNode, type NodeParent } from '../../core/node/tree'
import { planBlob, reserveAndPump, authedReserve, authedWindow, throwIfAborted } from '../../core/blob/transport'
import type { UploadBatch, UploadJob, UploadJobContext } from '../../core/blob/queue'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import { expectMintedId } from '../../core/node/mintedId'
import type { EpochKeys } from '../spaces/epochs'
import { withRefresh } from '../../app/sessionRefresh'
import { HttpStatus, decodeHttpError, httpStatus } from '../../shared/httpError'
import type { FileBlobRef } from '../../api/generated/files'
import { filesApi } from './api'
import { FILE_CONTENT_ROLE, FILE_KIND } from './kinds'
import { fileMetaOf, writeFileMeta } from './fileMeta'

/** The failure codes a file upload ends on beyond the transport's own (`quota_exceeded`, `upload_failed`, …). Each is
 *  final: no retry of the same job can change it. */
export const FileUploadError = {
  /** A sibling already holds the name (a folder or Memory whose slug folds to the same token, or a file the member
   *  picked twice in one go). */
  slugTaken: 'slug_taken',
  /** The server refused a file here (`illegal_structure`). */
  illegalStructure: 'illegal_structure',
  /** A peer replaced the file after the member read it (`409 stale_file`): nothing was overwritten. */
  staleFile: 'stale_file',
} as const

/** The `409 stale_file` answer: the slot holds another blob than the one this upload expected to replace. */
export class StaleFileError extends Error {
  constructor(readonly currentBlobId: string | null) {
    super(FileUploadError.staleFile)
    this.name = 'StaleFileError'
  }
}

/** A replace: the existing file node, its own key, its meta as the tree listed it, and the blob the member saw in it
 *  (null when the node holds none yet — a file whose first upload never completed). */
export type FileReplace = { nodeId: string; nodeKey: Uint8Array; meta: SealedMetaState; expectedBlobId: string | null }

/** Where one upload goes: the scope, its epoch keys (a new node seals under the write epoch) and either the parent a
 *  new file is created under (`null` = the scope root) or the file it replaces. */
export type FileUploadTarget = {
  scopeId: string
  epochs: EpochKeys
  parent: NodeParent
  replace?: FileReplace
}

/** A finished upload: the file node's id, and whether its metadata was written (false: the file is stored, its
 *  type/size/date are not — the section says so). */
export type FileUploadResult = { nodeId: string; metaWritten: boolean }

/** What a Files job keeps on its queue item: the picked file (for a retry by the member) and, for a replace, the node. */
export type FileJobExtra = { file: File; replaceNodeId?: string }

/** Create the FILE node, mapping the two refusals a create can end on to their final codes. */
async function createFileNode(target: FileUploadTarget, name: string): Promise<{ nodeId: string; nodeKey: Uint8Array }> {
  try {
    const { nodeId, contentKey } = await createContentNode(target.scopeId, target.epochs, target.parent, FILE_KIND, name)
    return { nodeId, nodeKey: contentKey }
  } catch (e) {
    const { status, code } = await decodeHttpError(e)
    if (status === HttpStatus.conflict && code === FileUploadError.slugTaken) throw new Error(FileUploadError.slugTaken)
    if (status === HttpStatus.unprocessable) throw new Error(FileUploadError.illegalStructure)
    throw e
  }
}

/** Files' `completeFile`: attach the upload to the node's slot, replacing exactly `expectedBlobId` (absent: an empty
 *  slot). A `409 stale_file` becomes [StaleFileError] with the blob the slot holds now. */
export async function completeFileUpload(
  scopeId: string, nodeId: string, uploadId: string, expectedBlobId: string | null,
): Promise<FileBlobRef> {
  try {
    return await withRefresh(() =>
      filesApi().completeFile({ scopeId, nodeId, fileCompleteRequest: { uploadId, expectedBlobId: expectedBlobId ?? undefined } }),
    )
  } catch (e) {
    return rethrowCompletion(e)
  }
}

/** A refused completion, member's or guest's: a `409 stale_file` becomes [StaleFileError] with the blob the slot holds
 *  now; anything else is rethrown as it came. */
export async function rethrowCompletion(e: unknown): Promise<never> {
  if (httpStatus(e) === HttpStatus.conflict) {
    const body = await (e as { response: Response }).response.clone().json().catch(() => null) as
      { code?: unknown; currentBlobId?: unknown } | null
    if (body?.code === FileUploadError.staleFile) {
      throw new StaleFileError(typeof body.currentBlobId === 'string' ? body.currentBlobId : null)
    }
  }
  throw e
}

/** The codes no retry of the same job can change: a job remembers one and rethrows it without a request. */
export const FINAL = new Set<string>(Object.values(FileUploadError))

/** The failed items of one view's `batches` that a LATER item of the same name has since delivered (`done`): the
 *  section stops explaining a failure the member or guest already made good by uploading the file again (inbox #395).
 *  "Later" is queue order — the batches by `seq`, each batch's items as queued; an earlier success fixes nothing. */
export function deliveredSince(batches: readonly UploadBatch[]): Set<string> {
  const items = [...batches].sort((a, b) => a.seq - b.seq).flatMap((b) => b.files)
  const out = new Set<string>()
  items.forEach((item, i) => {
    if (item.status === 'failed' && items.slice(i + 1).some((later) => later.name === item.name && later.status === 'done')) out.add(item.id)
  })
  return out
}

/**
 * One queue job for one picked file into `target` (see the header for its four steps). The job's `run` may be called
 * again by the queue's automatic retry and by the member's retry: it reuses a node it created and rethrows a final
 * refusal without touching the network.
 */
export function fileUploadJob(target: FileUploadTarget, file: File): UploadJob<FileUploadResult, FileJobExtra> {
  const { scopeId } = target
  let node: { nodeId: string; nodeKey: Uint8Array; meta: SealedMetaState; expectedBlobId: string | null } | null =
    target.replace ? { ...target.replace } : null
  let final: Error | null = null
  /** The node this job created and that holds nothing of it yet — what {@link abandon} trashes. */
  let created: string | null = null

  async function run({ onProgress, signal }: UploadJobContext): Promise<FileUploadResult> {
    if (final) throw final
    throwIfAborted(signal)
    let attached: FileBlobRef
    try {
      if (!node) {
        // A new node's meta is empty at version 0: the first meta write is built on it.
        node = { ...(await createFileNode(target, file.name)), meta: { wrappedMeta: null, metaVersion: 0 }, expectedBlobId: null }
        created = node.nodeId
      }
      const plan = planBlob(scopeId, { ownerId: node.nodeId, role: FILE_CONTENT_ROLE }, node.nodeKey, file)
      const [uploadId] = await reserveAndPump([plan], (d) => authedReserve(scopeId, d), authedWindow(scopeId), onProgress, signal)
      throwIfAborted(signal)
      attached = await completeFileUpload(scopeId, node.nodeId, uploadId, node.expectedBlobId)
      created = null // the node holds the file now: nothing to undo, whatever fails after
      expectMintedId(plan.blobId, attached.blobId)
    } catch (e) {
      if (e instanceof Error && FINAL.has(e.message)) final = e
      throw e
    }
    // The blob is stored: from here on nothing fails the job. The meta describes the bytes just attached.
    let metaWritten = true
    try {
      await writeFileMeta(scopeId, node.nodeId, node.nodeKey, node.meta, fileMetaOf(file))
    } catch {
      metaWritten = false
    }
    return { nodeId: node.nodeId, metaWritten }
  }

  /** Given up: trash the node this job created if it is still empty, and forget it, so the member's retry creates the
   *  file afresh. A node that could not be trashed is kept: the retry then fills it rather than colliding with it. */
  async function abandon(): Promise<void> {
    const nodeId = created
    if (!nodeId) return
    const page = await withRefresh(() => filesApi().listFiles({ scopeId, parentId: target.parent?.id }))
    const listed = page.items.find((i) => i.nodeId === nodeId)
    if (listed?.fileBlob) {
      created = null // attached after all (a lost answer): the file is there, nothing to undo
      return
    }
    if (listed) await deleteNode(scopeId, nodeId)
    created = null
    node = null
  }

  return {
    name: file.name,
    sizeBytes: file.size,
    run,
    abandon,
    extra: target.replace ? { file, replaceNodeId: target.replace.nodeId } : { file },
  }
}
