// A guest's file upload on a READ_WRITE folder link (cr-files-application F2; docs/apps/files.md § Guests): the member's
// job (`uploadFile.ts`) with the guest's doors. One picked `File` becomes one job of core's upload queue:
//
//  1. the NODE: core's guest tree operation `createShareNodeAsGuest` births the `FILE` under the folder on screen — a
//     fresh node key wrapped under the folder's key from the chain, name and slug sealed under it (the server checks the
//     place: `NodeKindRules`, core's `FolderGuestNodeRules`). A replace targets the existing file with its own key.
//  2. the BLOB with its METADATA: core's `planBlob` under the file node's own key (role `FILE_CONTENT`, owner = the
//     node), the picked file's type, name and modified time sealed under its `K_blob` (`fileBlobMeta`, inbox #397), and
//     `reserveAndPump` through core's guest transport (`core/sharing/guestBlob.ts`), the upload's target the node.
//  3. the COMPLETION: Depot's `completeShareFile` attaches the upload, a compare-and-set on `expectedBlobId` — the meta
//     is the blob's, so it goes with the bytes it describes, and the guest writes no node payload at all.
//
// As the member's job: a node it created is reused by a retry, a final refusal (taken name, illegal place, a peer's
// replace) is remembered and rethrown without a request.
import { planBlob, reserveAndPump, throwIfAborted } from '../../core/blob/transport'
import type { UploadJob, UploadJobContext } from '../../core/blob/queue'
import { expectMintedId } from '../../core/node/mintedId'
import { createShareNodeAsGuest, guestTreeErrorOf, GuestTreeError, type GuestTreeContext } from '../../core/sharing/guestTree'
import { guestReserve, guestWindow, type GuestUploadTarget } from '../../core/sharing/guestBlob'
import type { FileBlobRef } from '../../api/generated/depot'
import { publicShareFilesApi } from './api'
import { FILE_CONTENT_ROLE, FILE_KIND } from './kinds'
import { FINAL, FileUploadError, fileBlobMeta, rethrowCompletion, type FileJobExtra, type FileUploadResult } from './uploadFile'

/** A guest's replace: the file node, its key from the chain and the blob the guest saw in it (null: none yet). */
export type GuestFileReplace = { nodeId: string; nodeKey: Uint8Array; expectedBlobId: string | null }

/** Where a guest's upload goes: the link's context (identity, password token, keys) and the folder a new file is
 *  created under, or the file it replaces. */
export type GuestFileTarget = { ctx: GuestTreeContext; parentId: string; replace?: GuestFileReplace }

/** Create the FILE node as the guest, mapping the refusals a create ends on to Depot's final codes. */
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

/** Depot's `completeShareFile`: the upload becomes the file's blob (its meta is the blob's own). */
async function completeGuestFile(
  ctx: GuestTreeContext, nodeId: string, uploadId: string, expectedBlobId: string | null,
): Promise<FileBlobRef> {
  try {
    return await publicShareFilesApi().completeShareFile({
      shareId: ctx.shareId,
      nodeId,
      shareFileCompleteRequest: { contributorToken: ctx.contributorToken, uploadId, expectedBlobId: expectedBlobId ?? undefined },
      xSharePassword: ctx.pwToken,
    })
  } catch (e) {
    return rethrowCompletion(e)
  }
}

/** One queue job for one file a guest picked (see the header). */
export function guestFileUploadJob(target: GuestFileTarget, file: File): UploadJob<FileUploadResult, FileJobExtra> {
  const { ctx } = target
  let node: { nodeId: string; nodeKey: Uint8Array; expectedBlobId: string | null } | null =
    target.replace ? { ...target.replace } : null
  let final: Error | null = null

  async function run({ onProgress, signal }: UploadJobContext): Promise<FileUploadResult> {
    if (final) throw final
    throwIfAborted(signal)
    try {
      node ??= { ...(await createGuestFileNode(target, file.name)), expectedBlobId: null }
      const to: GuestUploadTarget = { shareId: ctx.shareId, contributorToken: ctx.contributorToken, pwToken: ctx.pwToken, nodeId: node.nodeId }
      const plan = planBlob(ctx.scopeId, { ownerId: node.nodeId, role: FILE_CONTENT_ROLE }, node.nodeKey, file, fileBlobMeta(file))
      const [uploadId] = await reserveAndPump([plan], (d) => guestReserve(to, d), guestWindow(to), onProgress, signal)
      throwIfAborted(signal)
      const attached = await completeGuestFile(ctx, node.nodeId, uploadId, node.expectedBlobId)
      expectMintedId(plan.blobId, attached.blobId)
      return { nodeId: node.nodeId }
    } catch (e) {
      if (e instanceof Error && FINAL.has(e.message)) final = e
      throw e
    }
  }

  return {
    name: file.name,
    sizeBytes: file.size,
    run,
    isFinal: (error) => FINAL.has(error),
    extra: target.replace ? { file, replaceNodeId: target.replace.nodeId } : { file },
  }
}
