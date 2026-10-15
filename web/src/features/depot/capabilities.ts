// What a Depot view may offer to whoever looks (inbox #401): one pure, FAIL-CLOSED function over core's `Access` and the
// node on screen, so Depot's rows (`levelRows.ts`) and `DropBoxDetail` show a control only where this says yes — a
// member and a share guest see the same view with functions removed. The matrix is written down once, in
// `docs/apps/depot.md` § Guests (ADR 0080 decision 6); this is its code. Every field defaults to false: an unknown mode,
// role or kind grants nothing.
import { FOLDER_KIND } from '../../core/node/kind'
import { folderCapabilities, type Access, type CapabilityTarget, type MemberAccess } from '../../core/access'
import { DROPBOX_KIND, FILE_KIND } from './kinds'

export interface DepotCapabilities {
  /** Download a file of the level, open a drop box or one of its guests' folders. */
  download: boolean
  /** Upload new files into the level (a member and a contributor: a `FILE` node each; a drop box's own guest: into its
   *  identity folder). */
  upload: boolean
  /** Replace a file's content (or upload into one that holds none). */
  replace: boolean
  /** Rename a file. */
  rename: boolean
  /** Delete a file (into the scope's trash) — also a guest's folder of a drop box. */
  deleteFile: boolean
  /** Create a drop box at this level (needs `core.public-shares.drop-box`: the server refuses the node without it). */
  createDropBox: boolean
  /** Manage the node's public links: an opened file's READ / READ_WRITE ones, a drop box's WRITE_ONLY ones. */
  share: boolean
  /** The file's share dialog offers the READ_WRITE (contribute) role for a new link (`core.public-shares.contribute`). A
   *  drop box's dialog has no role to offer, so it is false there. */
  shareContribute: boolean
  /** Delete the drop box on screen. */
  deleteNode: boolean
  /** Move a file of the level elsewhere — also a guest's folder out of a drop box (core's `MoveNodeDialog`, inbox #358). */
  moveFile: boolean
  /** Move the drop box on screen elsewhere. */
  moveNode: boolean
}

export const NO_DEPOT_CAPABILITIES: Readonly<DepotCapabilities> = Object.freeze({
  download: false,
  upload: false,
  replace: false,
  rename: false,
  deleteFile: false,
  createDropBox: false,
  share: false,
  shareContribute: false,
  deleteNode: false,
  moveFile: false,
  moveNode: false,
})

/** The Depot matrix (see the header; the table is `docs/apps/depot.md`). `at` is the node on screen: a folder level whose
 *  files and drop boxes the sections show (`FOLDER`; null at a member's scope root), an opened drop box, or an opened
 *  file (`FileDetail`: the page of one file, the root of a single-file link included). On an opened file `replace`,
 *  `rename`, `share`, `deleteNode` and `moveNode` are about THAT file. */
export function depotCapabilities(access: Access, at: CapabilityTarget): DepotCapabilities {
  if (access.mode === 'member') return memberCapabilities(access, at)
  if (access.mode === 'guest') return guestCapabilities(access.role, at)
  return NO_DEPOT_CAPABILITIES
}

function memberCapabilities(access: MemberAccess, at: CapabilityTarget): DepotCapabilities {
  const write = !access.readonly
  // Nothing is placed in a guest's identity folder (a server placement rule), but a member removes what a guest sent,
  // file by file (inbox #391), and moves it out (a move out of a drop box is a member's, inbox #377 (a); the move dialog
  // offers no identity folder as a destination).
  const place = write && !at.inIdentityFolder
  if (at.kind === null || at.kind === FOLDER_KIND) {
    return {
      ...NO_DEPOT_CAPABILITIES,
      download: true,
      upload: place,
      replace: place,
      rename: place,
      deleteFile: write,
      createDropBox: place && access.publicSharesDropBox,
      moveFile: write,
    }
  }
  if (at.kind === FILE_KIND) {
    // The opened file: what its row offers, plus its own share (not in a guest's identity folder, where the guest's
    // folder is the owner's to delete or move out only) and delete/move of itself. Download stays during a pending
    // deletion; a share's links are a read there, like a drop box's.
    const shareable = at.shareable && !at.inIdentityFolder
    return {
      ...NO_DEPOT_CAPABILITIES,
      download: true,
      replace: place,
      rename: place,
      share: access.publicShares && shareable,
      shareContribute: access.publicSharesContribute && shareable,
      deleteNode: write,
      moveNode: write,
    }
  }
  if (at.kind === DROPBOX_KIND) {
    // A drop box's listing and links stay readable during a pending deletion; deleting or moving it, or a guest's folder
    // (out of it: the folder's identity ends, ADR 0080 decision 11), is a write. Its links are WRITE_ONLY ones, so its
    // share needs the drop-box leaf, not the READ one.
    return {
      ...NO_DEPOT_CAPABILITIES,
      download: true,
      share: access.publicSharesDropBox && at.shareable,
      deleteFile: write,
      deleteNode: write,
      moveFile: write,
      moveNode: write,
    }
  }
  return NO_DEPOT_CAPABILITIES
}

function guestCapabilities(role: string, at: CapabilityTarget): DepotCapabilities {
  // A guest is never at the scope root.
  if (at.kind === null) return NO_DEPOT_CAPABILITIES
  switch (role) {
    case 'READ':
      return at.kind === FOLDER_KIND || at.kind === DROPBOX_KIND || at.kind === FILE_KIND
        ? { ...NO_DEPOT_CAPABILITIES, download: true }
        : NO_DEPOT_CAPABILITIES
    case 'READ_WRITE':
      // A contributor uploads, replaces and moves any file of a folder (the owner's too — core's `FolderGuestNodeRules`
      // lets any kind out of and into a folder; the move dialog offers the subtree's folders only); nothing in a guest's
      // identity folder (only a member moves out of a drop box, inbox #377 (a)); a drop box below the link is read-only
      // for it, its move included (inbox #380). Rename is the server's (core's `updateShareNode`) but offered nowhere
      // yet; a guest deletes no file (nodes store no authorship).
      if (at.kind === FOLDER_KIND) {
        const place = !at.inIdentityFolder
        return { ...NO_DEPOT_CAPABILITIES, download: true, upload: place, replace: place, moveFile: place }
      }
      if (at.kind === FILE_KIND) {
        // An opened file (the link's root, or one inside a folder link): download, and replace the content (the owner's
        // file too, after the question); moved only out of a folder into the subtree's other folders, never the link's
        // root (whose parent lies above the link) nor in or out of an identity folder. No rename, no delete.
        return {
          ...NO_DEPOT_CAPABILITIES,
          download: true,
          replace: !at.inIdentityFolder,
          moveNode: at.parentKind === FOLDER_KIND && !at.inIdentityFolder,
        }
      }
      return at.kind === DROPBOX_KIND ? { ...NO_DEPOT_CAPABILITIES, download: true } : NO_DEPOT_CAPABILITIES
    case 'WRITE_ONLY':
      // The drop box's own guest: sends into its identity folder, holds no key to read anything back. A file is no
      // `WRITE_ONLY` root (the backend refuses that link), so nothing on a file.
      return at.kind === DROPBOX_KIND ? { ...NO_DEPOT_CAPABILITIES, upload: true } : NO_DEPOT_CAPABILITIES
    default:
      return NO_DEPOT_CAPABILITIES
  }
}

/** "Upload folder…" (inbox #424): a tree of folders and files, so it needs both Depot's upload at the level and core's folder
 *  create there (`folderCapabilities`) - a member and a `READ_WRITE` guest alike (share view = member view), never in a guest's
 *  identity folder, never during a pending deletion. */
export function canUploadFolder(access: Access, at: CapabilityTarget): boolean {
  return depotCapabilities(access, at).upload && folderCapabilities(access, at).createFolder
}
