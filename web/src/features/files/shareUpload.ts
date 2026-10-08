// A guest's file upload on a READ_WRITE folder link (cr-files-application F2; docs/apps/files.md § Guests): the member's
// job (`uploadFile.ts`) with the guest's doors. One picked `File` becomes one job of core's upload queue:
//
//  1. the NODE: core's guest tree operation `createShareNodeAsGuest` births the `FILE` under the folder on screen — a
//     fresh node key wrapped under the folder's key from the chain, name and slug sealed under it (the server checks the
//     place: `NodeKindRules`, core's `FolderGuestNodeRules`). A replace targets the existing file with its own key.
//  2. the BLOB: core's `planBlob` under the file node's own key (role `FILE_CONTENT`, owner = the node) and
//     `reserveAndPump` through core's guest transport (`core/sharing/guestBlob.ts`), the upload's target the node.
//  3. the COMPLETION with the METADATA: Files' `completeShareFile` attaches the upload, a compare-and-set on
//     `expectedBlobId`, and writes the file's sealed meta in the SAME transaction — a guest has no other door to a
//     node's payload, and so the meta goes with the bytes it describes (`metaWritten` is always true on success).
//
// As the member's job: a node it created is reused by a retry, a final refusal (taken name, illegal place, a peer's
// replace) is remembered and rethrown without a request.
import { planBlob, reserveAndPump, throwIfAborted } from '../../core/blob/transport'
import type { UploadJob, UploadJobContext } from '../../core/blob/queue'
import { expectMintedId } from '../../core/node/mintedId'
import { createShareNodeAsGuest, guestTreeErrorOf, GuestTreeError, type GuestTreeContext } from '../../core/sharing/guestTree'
import { guestReserve, guestWindow, type GuestUploadTarget } from '../../core/sharing/guestBlob'
import type { FileBlobRef } from '../../api/generated/files'
import { publicShareFilesApi } from './api'
import { FILE_CONTENT_ROLE, FILE_KIND } from './kinds'
import { fileMetaOf, openFileMetaForWrite, sealFileMeta, type RawFileMeta } from './fileMeta'
import { FINAL, FileUploadError, rethrowCompletion, type FileJobExtra, type FileUploadResult } from './uploadFile'

/** A guest's replace: the file node, its key from the chain, its sealed meta as served (kept fields survive) and the
 *  blob the guest saw in it (null: none yet). */
export type GuestFileReplace = { nodeId: string; nodeKey: Uint8Array; wrappedMeta: string | null; expectedBlobId: string | null }

/** Where a guest's upload goes: the link's context (identity, password token, keys) and the folder a new file is
 *  created under, or the file it replaces. */
export type GuestFileTarget = { ctx: GuestTreeContext; parentId: string; replace?: GuestFileReplace }

/** Create the FILE node as the guest, mapping the refusals a create ends on to Files' final codes. */
async function createGuestFileNode(target: GuestFileTarget, name: string): Promise<{ nodeId: string; nodeKey: Uint8Array }> {
  try {
    const { nodeId, contentKey } = await createShareNodeAsGuest(target.ctx, { parentId: target.parentId, kind: FILE_KIND, name })
    return { nodeId, nodeKey: contentKey }
  } catch (e) {
    const err = await guestTreeErrorOf(e)
    if (err instanceof GuestTreeError && err.code === 'slug_taken') throw new Error(FileUploadError.slugTaken)
    if (err instanceof GuestTreeError && (err.code === 'illegal_structure' || err.code === 'illegal_scope')) {
      throw new Error(FileUploadError.illegalStructure)
    }
    throw err
  }
}

/** The file's sealed meta for the swap: the served envelope's unknown fields kept (an unreadable one is not rebuilt
 *  from it — a fresh envelope then, since the guest replaces the bytes it describes), the picked file's facts set. */
function guestMeta(nodeKey: Uint8Array, address: { scopeId: string; nodeId: string }, served: string | null, file: File): string {
  let raw: RawFileMeta
  try {
    raw = openFileMetaForWrite(nodeKey, address, served)
  } catch {
    raw = { v: 1 }
  }
  const meta = fileMetaOf(file)
  return sealFileMeta(nodeKey, address, { ...raw, mime: meta.mime, size: meta.size, modifiedAt: meta.modifiedAt })
}

/** Files' `completeShareFile`: the upload becomes the file's blob, its meta written with it. */
async function completeGuestFile(
  ctx: GuestTreeContext, nodeId: string, uploadId: string, expectedBlobId: string | null, wrappedMeta: string,
): Promise<FileBlobRef> {
  try {
    return await publicShareFilesApi().completeShareFile({
      shareId: ctx.shareId,
      nodeId,
      shareFileCompleteRequest: { contributorToken: ctx.contributorToken, uploadId, expectedBlobId: expectedBlobId ?? undefined, wrappedMeta },
      xSharePassword: ctx.pwToken,
    })
  } catch (e) {
    return rethrowCompletion(e)
  }
}

/** One queue job for one file a guest picked (see the header). */
export function guestFileUploadJob(target: GuestFileTarget, file: File): UploadJob<FileUploadResult, FileJobExtra> {
  const { ctx } = target
  let node: { nodeId: string; nodeKey: Uint8Array; wrappedMeta: string | null; expectedBlobId: string | null } | null =
    target.replace ? { ...target.replace } : null
  let final: Error | null = null

  async function run({ onProgress, signal }: UploadJobContext): Promise<FileUploadResult> {
    if (final) throw final
    throwIfAborted(signal)
    try {
      node ??= { ...(await createGuestFileNode(target, file.name)), wrappedMeta: null, expectedBlobId: null }
      const to: GuestUploadTarget = { shareId: ctx.shareId, contributorToken: ctx.contributorToken, pwToken: ctx.pwToken, nodeId: node.nodeId }
      const plan = planBlob(ctx.scopeId, { ownerId: node.nodeId, role: FILE_CONTENT_ROLE }, node.nodeKey, file)
      const [uploadId] = await reserveAndPump([plan], (d) => guestReserve(to, d), guestWindow(to), onProgress, signal)
      throwIfAborted(signal)
      const meta = guestMeta(node.nodeKey, { scopeId: ctx.scopeId, nodeId: node.nodeId }, node.wrappedMeta, file)
      const attached = await completeGuestFile(ctx, node.nodeId, uploadId, node.expectedBlobId, meta)
      expectMintedId(plan.blobId, attached.blobId)
      return { nodeId: node.nodeId, metaWritten: true }
    } catch (e) {
      if (e instanceof Error && FINAL.has(e.message)) final = e
      throw e
    }
  }

  return {
    name: file.name,
    sizeBytes: file.size,
    run,
    extra: target.replace ? { file, replaceNodeId: target.replace.nodeId } : { file },
  }
}
