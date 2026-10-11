// Files' data source (inbox #401 S3): the one object through which a Files view (`FilesSection`, `DropBoxDetail`) reads
// and writes, so ONE view serves a member and a share guest. The member's wraps the unlocked scope's doors (`filesApi`,
// core's node operations and `treeStore`); the guest's the link's (`publicShareFilesApi`, core's guest transport and
// guest create, the shell's naming flow and re-resolve). Neither reads the other's world.
//
// **A write exists only where the session has its door.** The guest's source has no `deleteFile` and no `rename` (a
// guest deletes nothing — nodes store no authorship, ADR 0080 decision 6 — and no guest page offers a rename yet, inbox
// #358); a view offers a write where `filesCapabilities` grants it AND the source carries it (`sourceCapabilities`), so
// a forgotten guard cannot fall through to a member call — there is no member call to fall to.
//
// Operator-blind as the views: every name, size and date is opened by the view under keys the tree holds; the sources
// move ids, sealed sizes and ciphertext.
import type { UploadJob } from '../../core/blob/queue'
import type { GuestAccess } from '../../core/access'
import { deleteNode, restoreNode, type NodeParent, type TreeNode } from '../../core/node/tree'
import { loadTree, treeStore } from '../../core/node/treeStore'
import { guestTreeContext } from '../../core/sharing/guestTree'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import { withRefresh } from '../../app/sessionRefresh'
import type { EpochKeys } from '../spaces/epochs'
import type { FileBlobRef } from '../../api/generated/files'
import { ShareRole } from '../../api/generated/sharing'
import { filesApi, publicShareFilesApi } from './api'
import { downloadFile } from './download'
import { fileUploadJob, type FileJobExtra, type FileReplace, type FileUploadResult } from './uploadFile'
import { guestFileUploadJob } from './shareUpload'
import type { FilesCapabilities } from './capabilities'

/** One file to upload: into a new FILE node under the level's folder, or into `replace` (its key from the tree). */
export type FileUploadEntry = { file: File; replace?: FileReplace }

/** A level's uploads, begun by {@link FilesUpload.begin} once nothing on the session's side is missing. */
export interface FilesUploadBatch {
  /** The queue jobs for `entries` — empty when the visitor declined to name itself (a guest), so nothing is sent. */
  jobs(entries: readonly FileUploadEntry[]): Promise<UploadJob<FileUploadResult, FileJobExtra>[]>
}

/** The upload door of a session that has one. */
export interface FilesUpload {
  /** A picked file whose name a FILE sibling already holds REPLACES that file after a question (a member), or is created
   *  as new and refused by the server's `409 slug_taken` (a guest: the owner kept no replace by upload, inbox #392). */
  readonly sameNameReplaces: boolean
  /** The session can write now (a member's epoch keys are unlocked); the controls stay, disabled, until it can. */
  ready(): boolean
  /** Start the level `folder`'s uploads (null: a member's scope root), or null when this session cannot create there
   *  (no scope, no epoch keys, the folder's key not open) — nothing is asked then. Nothing is sent before `jobs`. */
  begin(folder: TreeNode | null): FilesUploadBatch | null
}

/** Files' data source; see the header. */
export interface FilesSource {
  /** The scope a blob's seals bind (its id, never a key): what `fileFacts` opens a row's facts under. */
  scopeId(): string | null
  /** What a level's blob listing depends on beyond its folder and files: re-read when it changes (a member's scope; a
   *  guest's resolved head — a re-resolve after a write). */
  listKey(): unknown
  /** The blob of each file of the level `folderId` (null: a member's scope root): `null` for a file known to hold none.
   *  May list more files than the level's (a guest's listing is the whole subtree). Throws what the call throws. */
  listBlobs(folderId: string | null): Promise<Map<string, FileBlobRef | null>>
  /** Save one file: decrypted under `key`, saved as `name`. Starts its save picker before anything else is awaited (the
   *  click's activation). */
  download(key: Uint8Array, nodeId: string, blob: FileBlobRef, name: string): Promise<void>
  /** The upload-queue batch of a level's uploads (one per level and session). */
  batchKey(folderId: string | null): string
  /** After a write: re-read what the write changed (the tree; a guest's link), then `reloadBlobs` where the listing does
   *  not follow on its own. */
  refresh(reloadBlobs: () => Promise<void>): void
  /** Upload and replace. Absent: this session uploads nothing. */
  readonly upload?: FilesUpload
  /** Delete a file — or a guest's folder of a drop box — into the scope's trash, then re-read the tree. Absent: this
   *  session deletes nothing. */
  readonly deleteFile?: (nodeId: string) => Promise<void>
  /** Bring back a node this session just deleted (the undo after a delete, inbox #385 (c)): core's trash restore, then
   *  re-read the tree. Absent: no undo (a guest deletes nothing). */
  readonly restore?: (node: TreeNode) => Promise<void>
  /** Rename a file (the host's name dialog). Absent: this session renames nothing. */
  readonly rename?: (node: TreeNode) => void
}

/** The capabilities a view shows: the matrix's answer, narrowed to the writes `source` carries. */
export function sourceCapabilities(caps: FilesCapabilities, source: FilesSource): FilesCapabilities {
  return {
    ...caps,
    upload: caps.upload && !!source.upload,
    replace: caps.replace && !!source.upload,
    rename: caps.rename && !!source.rename,
    deleteFile: caps.deleteFile && !!source.deleteFile,
  }
}

/** A member's source over the unlocked scope: `scopeId` the scope the view addresses, `epochs` its keys (null while
 *  locked), `keyOf` the tree's content keys, `rename` the host's name dialog. */
export function memberFilesSource(from: {
  scopeId: () => string | null
  epochs: () => EpochKeys | null
  keyOf: (id: string) => Uint8Array | null
  rename?: (node: TreeNode) => void
}): FilesSource {
  return {
    scopeId: from.scopeId,
    listKey: from.scopeId,
    async listBlobs(folderId) {
      const scopeId = from.scopeId()
      if (!scopeId) return new Map()
      const page = await withRefresh(() => filesApi().listFiles({ scopeId, parentId: folderId ?? undefined }))
      return new Map(page.items.map((i) => [i.nodeId, i.fileBlob ?? null]))
    },
    download(key, nodeId, blob, name) {
      const scopeId = from.scopeId()
      return scopeId ? downloadFile(scopeId, key, nodeId, blob, name) : Promise.resolve()
    },
    batchKey: (folderId) => `files:${from.scopeId() ?? ''}:${folderId ?? ''}`,
    refresh(reloadBlobs) {
      void loadTree().then(reloadBlobs)
    },
    upload: {
      sameNameReplaces: true,
      ready: () => !!from.epochs(),
      begin(folder) {
        const scopeId = from.scopeId()
        const epochs = from.epochs()
        if (!scopeId || !epochs) return null
        // Under the folder with ITS key (ADR 0080 Decision 1), or the scope root; a folder whose key does not open takes
        // nothing, rather than a node nobody can read.
        const key = folder ? from.keyOf(folder.id) : null
        if (folder && !key) return null
        const parent: NodeParent = folder && key ? { id: folder.id, key } : null
        return {
          jobs: async (entries) =>
            entries.map(({ file, replace }) =>
              replace ? fileUploadJob({ scopeId, epochs, parent, replace }, file) : fileUploadJob({ scopeId, epochs, parent }, file),
            ),
        }
      },
    },
    async deleteFile(nodeId) {
      const scopeId = from.scopeId()
      if (!scopeId) return
      await deleteNode(scopeId, nodeId)
      await loadTree()
    },
    async restore(node) {
      const scopeId = from.scopeId()
      const epochs = from.epochs()
      if (!scopeId || !epochs) throw new Error('scope_locked')
      await restoreNode(scopeId, epochs, treeStore.nodes, node)
      await loadTree()
    },
    rename: from.rename,
  }
}

/** A share guest's source over its link (`access`): reads for every role; the upload door only for a `READ_WRITE` link
 *  (the capability matrix decides where), naming the guest first through the page's one flow. A `WRITE_ONLY` guest holds
 *  no key to read anything: its page is the drop box's own (`DropBoxGuestForm`), never this source's. */
export function guestFilesSource(access: () => GuestAccess): FilesSource {
  const contributes = access().role === ShareRole.ReadWrite
  return {
    scopeId: () => access().head.scopeId,
    listKey: () => access().head,
    async listBlobs() {
      const a = access()
      const page = await publicShareFilesApi().listShareFiles({ shareId: a.shareId, xSharePassword: a.pwToken })
      return new Map(page.items.map((i) => [i.nodeId, i.fileBlob ?? null]))
    },
    download(key, nodeId, blob, name) {
      const a = access()
      return downloadFile(a.head.scopeId, key, nodeId, blob, name, (blobId) => shareChunkUrls(a.shareId, blobId, a.pwToken))
    },
    batchKey: (folderId) => `files-share:${access().shareId}:${folderId ?? ''}`,
    refresh() {
      // The new node lists with its key and name; the new head re-reads the blobs (`listKey`).
      void access().reload()
    },
    upload: contributes ? guestUpload(access) : undefined,
  }
}

function guestUpload(access: () => GuestAccess): FilesUpload {
  return {
    sameNameReplaces: false,
    ready: () => true,
    begin(folder) {
      if (!folder) return null // a guest is never at a scope root
      return {
        async jobs(entries) {
          const a = access()
          const guest = await a.requestGuest()
          if (!guest) return []
          const ctx = guestTreeContext(a.head, { contributorToken: guest.contributorToken, fragmentKey: a.fragmentKey, pwToken: a.pwToken })
          return entries.map(({ file, replace }) =>
            replace
              ? guestFileUploadJob({ ctx, parentId: folder.id, replace }, file)
              : guestFileUploadJob({ ctx, parentId: folder.id }, file),
          )
        },
      }
    },
  }
}
