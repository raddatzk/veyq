// What a Depot view may offer to whoever looks (inbox #401): one pure, FAIL-CLOSED function over core's `Access` and the
// node on screen, so Depot's rows (`levelRows.ts`) and `DropBoxDetail` show a control only where this says yes — a
// member and a share guest see the same view with functions removed. The matrix is written down once, in
// `docs/apps/depot.md` § Guests (ADR 0080 decision 6); this is its code. Every field defaults to false: an unknown mode,
// role or kind grants nothing.
//
// **The node-generic rights are core's** (`nodeRights`, core/access.ts): share, rename, move and delete of the node on
// screen - a file, a drop box - start from core's answer and Depot only narrows them (a drop box is never renamed, a guest
// moves no drop box); `rights.test.ts` asserts that no answer here is wider than core's. A file row asks about ITS file,
// as the file's own page does, so a row and the page cannot disagree.
import { FOLDER_KIND } from '../../core/node/kind'
import {
  applicationWritable, folderRights, nodeRights, type Access, type NodeRights, type RightsTarget, type MemberAccess,
} from '../../core/access'
import { DROPBOX_KIND, FILE_KIND } from './kinds'

export interface DepotRights extends NodeRights {
  /** Download a file of the level, open a drop box or one of its guests' folders. */
  download: boolean
  /** Upload new files into the level (a member and a contributor: a `FILE` node each; a drop box's own guest: into its
   *  identity folder). */
  upload: boolean
  /** Replace a file's content (or upload into one that holds none): at a level, by uploading a file of the same name. */
  replace: boolean
  /** Create a drop box at this level (needs `core.public-shares.drop-box`: the server refuses the node without it). */
  createDropBox: boolean
  /** On a drop box: move one of its guests' folders out of it (core's `MoveNodeDialog`, inbox #377 (a)). */
  moveGuestFolder: boolean
  /** On a drop box: delete one of its guests' folders (into the scope's trash, inbox #391). */
  deleteGuestFolder: boolean
}

export const NO_DEPOT_RIGHTS: Readonly<DepotRights> = Object.freeze({
  download: false,
  upload: false,
  replace: false,
  rename: false,
  delete: false,
  move: false,
  createDropBox: false,
  share: false,
  shareContribute: false,
  shareUpload: false,
  moveGuestFolder: false,
  deleteGuestFolder: false,
})

/** The Depot matrix (see the header; the table is `docs/apps/depot.md`). `at` is the node on screen: a folder level whose
 *  files and drop boxes the sections show (`FOLDER`; null at a member's scope root), an opened drop box, or a file (its
 *  row, or `FileDetail`: the page of one file, the root of a single-file link included). `share`, `rename`, `move` and
 *  `delete` are about THAT node: none at a level (a folder is core's, `folderRights`). */
export function depotRights(access: Access, at: RightsTarget): DepotRights {
  if (access.mode === 'member') return memberRights(access, at)
  if (access.mode === 'guest') return guestRights(access, at)
  return NO_DEPOT_RIGHTS
}

/** A guest's folder of a drop box as core is asked about it: an identity folder whose parent is the drop box. */
const GUEST_FOLDER: RightsTarget = Object.freeze({ kind: FOLDER_KIND, inIdentityFolder: true, shareable: false, parentKind: DROPBOX_KIND })

function memberRights(access: MemberAccess, at: RightsTarget): DepotRights {
  // Depot switched off is read-only (uc.platform.switch-application-off): no upload, replace, rename, move or new link —
  // but a delete to the trash and its restore stay, as does downloading (the server's exemptions).
  const write = !access.readonly && applicationWritable(access, FILE_KIND)
  // Nothing is placed in a guest's identity folder (a server placement rule), but a member removes what a guest sent,
  // file by file (inbox #391), and moves it out (a move out of a drop box is a member's, inbox #377 (a); the move dialog
  // offers no identity folder as a destination).
  const place = write && !at.inIdentityFolder
  if (at.kind === null || at.kind === FOLDER_KIND) {
    return { ...NO_DEPOT_RIGHTS, download: true, upload: place, replace: place, createDropBox: place && access.publicSharesDropBox }
  }
  const node = nodeRights(access, at)
  if (at.kind === FILE_KIND) {
    // The file: download, replace its content, and core's share, rename, move and delete of it (in a guest's identity
    // folder core leaves a delete and a move out). Download stays during a pending deletion; its links are a read there.
    return {
      ...NO_DEPOT_RIGHTS,
      download: true,
      replace: place,
      share: node.share,
      shareContribute: node.shareContribute,
      rename: node.rename,
      move: node.move,
      delete: node.delete,
    }
  }
  if (at.kind === DROPBOX_KIND) {
    // A drop box's listing and links stay readable during a pending deletion; deleting or moving it, or a guest's folder
    // (out of it: the folder's identity ends, ADR 0080 decision 11), is a write. Its links are WRITE_ONLY ones, so its
    // share is the uploader one (the drop-box leaf, not the READ one). It is never renamed here.
    const folder = nodeRights(access, GUEST_FOLDER)
    return {
      ...NO_DEPOT_RIGHTS,
      download: true,
      shareUpload: node.shareUpload,
      move: node.move,
      delete: node.delete,
      moveGuestFolder: folder.move && write,
      deleteGuestFolder: folder.delete,
    }
  }
  return NO_DEPOT_RIGHTS
}

function guestRights(access: Access, at: RightsTarget): DepotRights {
  // A guest is never at the scope root.
  if (access.mode !== 'guest' || at.kind === null) return NO_DEPOT_RIGHTS
  switch (access.role) {
    case 'READ':
      return at.kind === FOLDER_KIND || at.kind === DROPBOX_KIND || at.kind === FILE_KIND
        ? { ...NO_DEPOT_RIGHTS, download: true }
        : NO_DEPOT_RIGHTS
    case 'READ_WRITE':
      // A contributor uploads, replaces and moves any file of a folder (the owner's too — core's `FolderGuestNodeRules`
      // lets any kind out of and into a folder; the move dialog offers the subtree's folders only); nothing in a guest's
      // identity folder (only a member moves out of a drop box, inbox #377 (a)); a drop box below the link is read-only
      // for it, its move included (inbox #380). Rename is the server's (core's `updateShareNode`) but offered nowhere
      // yet; a guest deletes no file (ADR 0080 decision 6: no node that holds anything).
      if (at.kind === FOLDER_KIND) {
        const place = !at.inIdentityFolder
        return { ...NO_DEPOT_RIGHTS, download: true, upload: place, replace: place }
      }
      if (at.kind === FILE_KIND) {
        // A file (the link's root, or one inside a folder link): download, and replace the content (the owner's file
        // too, after the question); core's move - only out of a folder into the subtree's other folders, never the
        // link's root (whose parent lies above the link) nor in or out of an identity folder. No rename, no delete.
        return { ...NO_DEPOT_RIGHTS, download: true, replace: !at.inIdentityFolder, move: nodeRights(access, at).move }
      }
      return at.kind === DROPBOX_KIND ? { ...NO_DEPOT_RIGHTS, download: true } : NO_DEPOT_RIGHTS
    case 'WRITE_ONLY':
      // The drop box's own guest: sends into its identity folder, holds no key to read anything back. A file is no
      // `WRITE_ONLY` root (the backend refuses that link), so nothing on a file.
      return at.kind === DROPBOX_KIND ? { ...NO_DEPOT_RIGHTS, upload: true } : NO_DEPOT_RIGHTS
    default:
      return NO_DEPOT_RIGHTS
  }
}

/** "Upload folder…" (inbox #424): a tree of folders and files, so it needs both Depot's upload at the level and core's folder
 *  create there (`folderRights`) - a member and a `READ_WRITE` guest alike (share view = member view), never in a guest's
 *  identity folder, never during a pending deletion. */
export function canUploadFolder(access: Access, at: RightsTarget): boolean {
  return depotRights(access, at).upload && folderRights(access, at).createFolder
}
