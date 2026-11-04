// Depot's data source (inbox #401 S3): the one object through which a Depot view (Depot's rows `levelRows.ts`, `DropBoxDetail`) reads
// and writes, so ONE view serves a member and a share guest. The member's wraps the unlocked scope's doors (`filesApi`,
// core's node operations and `treeStore`); the guest's the link's (`publicShareFilesApi`, core's guest transport and
// guest create, the shell's naming flow and re-resolve). Neither reads the other's world.
//
// **A write exists only where the session has its door.** The guest's source has no `deleteFile` and no `rename` (a
// guest deletes nothing but its own empty placeholder, ADR 0080 decision 6 — and no guest page offers a rename yet), and a
// `move` only for a `READ_WRITE` link (core's move dialog, the host's, inbox #358); a view offers a write where `depotRights` grants it AND the source carries it (`sourceRights`), so
// a forgotten guard cannot fall through to a member call — there is no member call to fall to.
//
// Operator-blind as the views: every name, size and date is opened by the view under keys the tree holds; the sources
// move ids, sealed sizes and ciphertext.
import type { UploadJob } from '../../core/blob/queue'
import type { BlobChunkProvider } from '../../core/blob/transport'
import type { GuestAccess } from '../../core/access'
import { createContentNode, deleteNode, restoreNode, type NodeParent, type TreeNode } from '../../core/node/tree'
import { memberCreateOptions } from '../../core/node/rereadNode'
import { FOLDER_KIND } from '../../core/node/kind'
import { loadTree, treeStore } from '../../core/node/treeStore'
import { abandonShareNodeAsGuest, createShareNodeAsGuest, guestTreeContext, guestTreeErrorOf, GuestTreeError, type GuestTreeContext } from '../../core/sharing/guestTree'
import { decodeHttpError, HttpStatus } from '../../shared/httpError'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import { withRefresh } from '../../app/sessionRefresh'
import type { ScopeKey } from '../spaces/epochs'
import type { FileBlobRef, FileUploaderName } from '../../api/generated/depot'
import { ShareRole } from '../../api/generated/sharing'
import { caseFilesApi, filesApi, publicShareFilesApi } from './api'
import { activeReview } from '../../core/sharing/review'
import { downloadFile, fileChunkProvider } from './download'
import { FileUploadError, fileUploadJob, type FileJobExtra, type FileReplace, type FileUploadResult } from './uploadFile'
import { guestFileUploadJob } from './shareUpload'
import type { DepotRights } from './rights'

/** One file to upload: into a new FILE node under the level's folder, or into `replace` (its key from the tree). */
export type FileUploadEntry = { file: File; replace?: FileReplace }

/** A level's uploads, begun by {@link FilesUpload.begin} once nothing on the session's side is missing. */
export interface FilesUploadBatch {
  /** The queue jobs for `entries` — empty when the visitor declined to name itself (a guest), so nothing is sent. */
  jobs(entries: readonly FileUploadEntry[]): Promise<UploadJob<FileUploadResult, FileJobExtra>[]>
}

/** A folder upload's writes (inbox #424), begun by {@link FilesUpload.beginTree}: the folders of the tree, then the jobs of
 *  its files. A folder is addressed by the id {@link folder} returned, `null` being the level's folder the upload began at. */
export interface FilesTreeUpload {
  /** Create a `FOLDER` named `name` under `parent`; its id. Throws `Error('slug_taken')` when a sibling holds the name. */
  folder(parent: string | null, name: string): Promise<string>
  /** The queue jobs that upload `files` into new `FILE` nodes under `parent`, each named by its `File`'s name. */
  jobs(parent: string | null, files: readonly File[]): UploadJob<FileUploadResult, FileJobExtra>[]
  /** Take back the folder `id` this upload created, once it holds nothing (a guest's: core's `abandonShareNode`, erased at
   *  once). Throws when the server keeps it. Absent for a member, who trashes what it no longer wants. */
  abandon?(id: string): Promise<void>
}

/** The upload door of a session that has one. A picked file whose name a FILE sibling already holds goes into that file
 *  (an entry with `replace`, after the view's question), for a member and a contributor guest alike (owner decision
 *  2026-10-14, reversing inbox #392): the view decides, the door only carries it. */
export interface FilesUpload {
  /** The session can write now (a member's epoch keys are unlocked); the controls stay, disabled, until it can. */
  ready(): boolean
  /** Start the level `folder`'s uploads (null: a member's scope root), or null when this session cannot create there
   *  (no scope, no epoch keys, the folder's key not open) — nothing is asked then. Nothing is sent before `jobs`. */
  begin(folder: TreeNode | null): FilesUploadBatch | null
  /** Start a replace of an opened file's content (`FileDetail`, the root of a single-file link included): there is no
   *  folder to create under, so none is asked for — every entry handed to `jobs` names the file it replaces. Null as
   *  `begin`: this session cannot write now. */
  beginReplace(): FilesUploadBatch | null
  /** Start a folder upload under the level `folder` (null: a member's scope root): a guest is named first through the page's
   *  one flow. Null: this session cannot create there now, or the visitor declined to name itself - nothing is sent. */
  beginTree(folder: TreeNode | null): Promise<FilesTreeUpload | null>
}

/** One file of a member's listing: its blob (null: none yet) and who uploaded that blob — `user:<id>` | `share:<identity>`,
 *  null with no blob (ADR 0083 Decision 8) — with a guest uploader's sealed name and its share (inbox #431; null otherwise). */
export interface ListedFile {
  blob: FileBlobRef | null
  uploaderKey: string | null
  uploaderName: FileUploaderName | null
}

/** Depot's data source; see the header. */
export interface DepotSource {
  /** The scope a blob's seals bind (its id, never a key): what `fileFacts` opens a row's facts under. */
  scopeId(): string | null
  /** What a level's blob listing depends on beyond its folder and files: re-read when it changes (a member's scope; a
   *  guest's resolved head — a re-resolve after a write). */
  listKey(): unknown
  /** The blob of each file of the level `folderId` (null: a member's scope root): `null` for a file known to hold none.
   *  May list more files than the level's (a guest's listing is the whole subtree). Throws what the call throws. */
  listBlobs(folderId: string | null): Promise<Map<string, FileBlobRef | null>>
  /** The same one listing as {@link listBlobs}, with who uploaded each file's current content (the file page's header, ADR
   *  0083 Decision 8). Absent: this session is shown no uploader — a guest's link listing carries none (inbox #190). */
  readonly listWithUploaders?: (folderId: string | null) => Promise<Map<string, ListedFile>>
  /** Save one file: decrypted under `key`, saved as `name`. Starts its save picker before anything else is awaited (the
   *  click's activation). */
  download(key: Uint8Array, nodeId: string, blob: FileBlobRef, name: string): Promise<void>
  /** The reader the file page's viewer opens a file with (inbox #412): the file's chunks decrypted under `key`, presigned
   *  through the session's own door. Nothing is fetched before the first read; throws `OpenRefused` for a ref that is not
   *  this node's. */
  chunks(key: Uint8Array, nodeId: string, blob: FileBlobRef): BlobChunkProvider
  /** The upload-queue batch of a level's uploads (one per level and session). */
  batchKey(folderId: string | null): string
  /** After a write: re-read what the write changed (the tree; a guest's link), then `reloadBlobs` where the listing does
   *  not follow on its own. */
  refresh(reloadBlobs: () => Promise<void>): void
  /** Upload and replace. Absent: this session uploads nothing. */
  readonly upload?: FilesUpload
  /** Delete a file — or a guest's folder of a drop box — into the scope's trash, then re-read the tree (`reload: false`:
   *  not — a selection's delete of several re-reads once, after the last, inbox #421). Absent: this session deletes
   *  nothing. */
  readonly deleteFile?: (nodeId: string, options?: { reload?: boolean }) => Promise<void>
  /** Bring back a node this session just deleted (the undo after a delete, inbox #385 (c)): core's trash restore, then
   *  re-read the tree. Absent: no undo (a guest deletes nothing). */
  readonly restore?: (node: TreeNode) => Promise<void>
  /** Rename a file (the host's name dialog: a row's rename). Absent: this session renames nothing. */
  readonly rename?: (node: TreeNode) => void
  /** Rename a file to `name` in place (its page's header, the host's `renameTo` door); present only beside `rename`. */
  readonly renameTo?: (node: TreeNode, name: string) => Promise<void>
  /** Move a file, a drop box or a guest's folder out of one (the host's `MoveNodeDialog`, inbox #358). Absent: this
   *  session moves nothing. */
  readonly move?: (node: TreeNode) => void
}

/** The rights a view shows: the matrix's answer, narrowed to the writes `source` carries. */
export function sourceRights(rights: DepotRights, source: DepotSource): DepotRights {
  return {
    ...rights,
    upload: rights.upload && !!source.upload,
    replace: rights.replace && !!source.upload,
    rename: rights.rename && !!source.rename,
    move: rights.move && !!source.move,
    delete: rights.delete && !!source.deleteFile,
    moveGuestFolder: rights.moveGuestFolder && !!source.move,
    deleteGuestFolder: rights.deleteGuestFolder && !!source.deleteFile,
  }
}

/** A member's source over the unlocked scope: `scopeId` the scope the view addresses, `epochs` its keys (null while
 *  locked), `keyOf` the tree's content keys, `rename` the host's name dialog and `renameTo` its rename in place, `move` the
 *  host's move dialog. */
export function memberDepotSource(from: {
  scopeId: () => string | null
  epochs: () => ScopeKey | null
  keyOf: (id: string) => Uint8Array | null
  rename?: (node: TreeNode) => void
  renameTo?: (node: TreeNode, name: string) => Promise<void>
  move?: (node: TreeNode) => void
}): DepotSource {
  async function listWithUploaders(folderId: string | null): Promise<Map<string, ListedFile>> {
    const scopeId = from.scopeId()
    if (!scopeId) return new Map()
    const page = await withRefresh(() => filesApi().listFiles({ scopeId, parentId: folderId ?? undefined }))
    return new Map(page.items.map((i) => [i.nodeId, { blob: i.fileBlob ?? null, uploaderKey: i.uploaderKey ?? null, uploaderName: i.uploaderName ?? null }]))
  }
  return {
    scopeId: from.scopeId,
    listKey: from.scopeId,
    async listBlobs(folderId) {
      const listed = await listWithUploaders(folderId)
      return new Map([...listed].map(([id, f]) => [id, f.blob]))
    },
    listWithUploaders,
    download(key, nodeId, blob, name) {
      const scopeId = from.scopeId()
      return scopeId ? downloadFile(scopeId, key, nodeId, blob, name) : Promise.resolve()
    },
    chunks(key, nodeId, blob) {
      const scopeId = from.scopeId()
      if (!scopeId) throw new Error('scope_locked')
      return fileChunkProvider(scopeId, key, nodeId, blob)
    },
    batchKey: (folderId) => `files:${from.scopeId() ?? ''}:${folderId ?? ''}`,
    refresh(reloadBlobs) {
      void loadTree().then(reloadBlobs)
    },
    upload: {
      ready: () => !!from.epochs(),
      begin(folder) {
        const scopeId = from.scopeId()
        const epochs = from.epochs()
        if (!scopeId || !epochs) return null
        // Under the folder with ITS key (ADR 0080 Decision 1), or the scope root; a folder whose key does not open takes
        // nothing, rather than a node nobody can read.
        const key = folder ? from.keyOf(folder.id) : null
        if (folder && !key) return null
        const parent: NodeParent = folder && key ? { id: folder.id, key, epoch: folder.epoch, scopeEpoch: folder.scopeEpoch } : null
        return memberBatch(scopeId, epochs, parent)
      },
      beginReplace() {
        const scopeId = from.scopeId()
        const epochs = from.epochs()
        // A replace creates nothing, so no parent is read: the file's own key comes with the entry.
        return scopeId && epochs ? memberBatch(scopeId, epochs, null) : null
      },
      async beginTree(folder) {
        const scopeId = from.scopeId()
        const epochs = from.epochs()
        if (!scopeId || !epochs) return null
        const key = folder ? from.keyOf(folder.id) : null
        if (folder && !key) return null
        return memberTree(scopeId, epochs, folder && key ? { id: folder.id, key, epoch: folder.epoch, scopeEpoch: folder.scopeEpoch } : null)
      },
    },
    async deleteFile(nodeId, options) {
      const scopeId = from.scopeId()
      if (!scopeId) return
      await deleteNode(scopeId, nodeId)
      if (options?.reload !== false) await loadTree()
    },
    async restore(node) {
      const scopeId = from.scopeId()
      const epochs = from.epochs()
      if (!scopeId || !epochs) throw new Error('scope_locked')
      await restoreNode(scopeId, epochs, treeStore.nodes, node)
      await loadTree()
    },
    rename: from.rename,
    ...(from.rename && from.renameTo ? { renameTo: from.renameTo } : {}),
    move: from.move,
  }
}

/** A member's folder upload: each folder created by core's `createContentNode` under its parent's key, its key kept for what
 *  goes below it (ADR 0080 Decision 1). */
function memberTree(scopeId: string, epochs: ScopeKey, level: NodeParent): FilesTreeUpload {
  const made = new Map<string, NodeParent>()
  const at = (parent: string | null): NodeParent => {
    if (parent === null) return level
    const p = made.get(parent)
    if (!p) throw new Error(`no folder ${parent} in this upload`)
    return p
  }
  return {
    async folder(parent, name) {
      try {
        const under = at(parent)
        const { nodeId, contentKey, epoch, scopeEpoch } = await createContentNode(
          scopeId, epochs, under, FOLDER_KIND, name, undefined,
          // the level's folder may be re-read after an epoch refusal; a folder this upload just made is current already
          parent === null ? memberCreateOptions(scopeId, under) : {},
        )
        made.set(nodeId, { id: nodeId, key: contentKey, epoch, scopeEpoch })
        return nodeId
      } catch (e) {
        const { status, code } = await decodeHttpError(e)
        if (status === HttpStatus.conflict && code === FileUploadError.slugTaken) throw new Error(FileUploadError.slugTaken)
        throw e
      }
    },
    jobs: (parent, files) => files.map((file) => fileUploadJob({ scopeId, epochs, parent: at(parent) }, file)),
  }
}

function memberBatch(scopeId: string, epochs: ScopeKey, parent: NodeParent): FilesUploadBatch {
  return {
    jobs: async (entries) =>
      entries.map(({ file, replace }) =>
        replace ? fileUploadJob({ scopeId, epochs, parent, replace }, file) : fileUploadJob({ scopeId, epochs, parent }, file),
      ),
  }
}

/** A share guest's source over its link (`access`): reads for every role; the upload and move doors only for a
 *  `READ_WRITE` link (the rights matrix decides where), naming the guest first through the page's one flow — `move`
 *  is the host's move dialog, which moves through core's guest placement. A `WRITE_ONLY` guest holds no key to read
 *  anything: its page is the drop box's own (`DropBoxGuestForm`), never this source's. */
export function guestDepotSource(access: () => GuestAccess, host: { move?: (node: TreeNode) => void } = {}): DepotSource {
  const contributes = access().role === ShareRole.ReadWrite
  return {
    scopeId: () => access().head.scopeId,
    listKey: () => access().head,
    async listBlobs() {
      const a = access()
      // A moderator's review lists the files its case's hold copied — never the link's own read (core/sharing/review.ts).
      const review = activeReview()
      const page = review
        ? await caseFilesApi().listCaseFiles({ caseId: review.caseId, ...(await review.proof('listCaseFiles')) })
        : await publicShareFilesApi().listShareFiles({ shareId: a.shareId, xSharePassword: a.pwToken })
      return new Map(page.items.map((i) => [i.nodeId, i.fileBlob ?? null]))
    },
    download(key, nodeId, blob, name) {
      const a = access()
      return downloadFile(a.head.scopeId, key, nodeId, blob, name, (blobId) => shareChunkUrls(a.shareId, blobId, a.pwToken))
    },
    chunks(key, nodeId, blob) {
      const a = access()
      return fileChunkProvider(a.head.scopeId, key, nodeId, blob, (blobId) => shareChunkUrls(a.shareId, blobId, a.pwToken))
    },
    batchKey: (folderId) => `files-share:${access().shareId}:${folderId ?? ''}`,
    refresh() {
      // The new node lists with its key and name; the new head re-reads the blobs (`listKey`).
      void access().reload()
    },
    upload: contributes ? guestUpload(access) : undefined,
    move: contributes ? host.move : undefined,
  }
}

function guestUpload(access: () => GuestAccess): FilesUpload {
  return {
    ready: () => true,
    begin(folder) {
      if (!folder) return null // a guest is never at a scope root
      return guestBatch(access, folder.id)
    },
    // A replace creates nothing, so the parent a create would use is never read (any id does; the job's type wants one).
    beginReplace: () => guestBatch(access, ''),
    async beginTree(folder) {
      if (!folder) return null
      const a = access()
      const guest = await a.requestGuest()
      if (!guest) return null
      return guestTree(guestTreeContext(a.head, { contributorToken: guest.contributorToken, fragmentKey: a.fragmentKey, pwToken: a.pwToken }), folder.id)
    },
  }
}

/** A guest's folder upload: each folder created as the guest (core's `createShareNodeAsGuest`) under its parent's key from the
 *  chain; the new folder joins the context, so what goes below it seals under its key. */
function guestTree(ctx: GuestTreeContext, levelId: string): FilesTreeUpload {
  return {
    async folder(parent, name) {
      const parentId = parent ?? levelId
      try {
        const { nodeId, contentKey, epoch, scopeEpoch } = await createShareNodeAsGuest(ctx, { parentId, kind: FOLDER_KIND, name })
        ctx.keys.set(nodeId, contentKey)
        ctx.names.set(nodeId, name)
        ctx.nodes.set(nodeId, { id: nodeId, parentId, kind: FOLDER_KIND, epoch, scopeEpoch, blockedByOperator: false })
        return nodeId
      } catch (e) {
        const err = await guestTreeErrorOf(e)
        if (err instanceof GuestTreeError && err.code === 'slug_taken') throw new Error(FileUploadError.slugTaken)
        throw err
      }
    },
    jobs: (parent, files) => files.map((file) => guestFileUploadJob({ ctx, parentId: parent ?? levelId }, file)),
    abandon: (id) => abandonShareNodeAsGuest(ctx, id),
  }
}

function guestBatch(access: () => GuestAccess, parentId: string): FilesUploadBatch {
  return {
    async jobs(entries) {
      const a = access()
      const guest = await a.requestGuest()
      if (!guest) return []
      const ctx = guestTreeContext(a.head, { contributorToken: guest.contributorToken, fragmentKey: a.fragmentKey, pwToken: a.pwToken })
      return entries.map(({ file, replace }) =>
        replace ? guestFileUploadJob({ ctx, parentId, replace }, file) : guestFileUploadJob({ ctx, parentId }, file),
      )
    },
  }
}
