// What a Files view may offer to whoever looks (inbox #401): one pure, FAIL-CLOSED function over core's `Access` and the
// node on screen, so `FilesSection`, `DropBoxSection` and `DropBoxDetail` show a control only where this says yes — a
// member and a share guest see the same view with functions removed. The matrix is written down once, in
// `docs/apps/files.md` § Guests (ADR 0080 decision 6); this is its code. Every field defaults to false: an unknown mode,
// role or kind grants nothing.
import { FOLDER_KIND } from '../../core/node/kind'
import type { Access, CapabilityTarget } from '../../core/access'
import { DROPBOX_KIND } from './kinds'

export interface FilesCapabilities {
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
  /** Create a drop box at this level. */
  createDropBox: boolean
  /** Manage the drop box's (WRITE_ONLY) links. */
  share: boolean
  /** Delete the drop box on screen. */
  deleteNode: boolean
}

export const NO_FILES_CAPABILITIES: Readonly<FilesCapabilities> = Object.freeze({
  download: false,
  upload: false,
  replace: false,
  rename: false,
  deleteFile: false,
  createDropBox: false,
  share: false,
  deleteNode: false,
})

/** The Files matrix (see the header; the table is `docs/apps/files.md`). `at` is the node on screen: a folder level whose
 *  files and drop boxes the sections show (`FOLDER`; null at a member's scope root), or an opened drop box. A `FILE` is
 *  never "on screen" (it is downloaded from its row), so it grants nothing. */
export function filesCapabilities(access: Access, at: CapabilityTarget): FilesCapabilities {
  if (access.mode === 'member') return memberCapabilities(access.readonly, access.publicLinks, at)
  if (access.mode === 'guest') return guestCapabilities(access.role, at)
  return NO_FILES_CAPABILITIES
}

function memberCapabilities(readonly: boolean, publicLinks: boolean, at: CapabilityTarget): FilesCapabilities {
  const write = !readonly
  // Nothing is placed in a guest's identity folder (a server placement rule), but a member removes what a guest sent,
  // file by file (inbox #391).
  const place = write && !at.inIdentityFolder
  if (at.kind === null || at.kind === FOLDER_KIND) {
    return {
      ...NO_FILES_CAPABILITIES,
      download: true,
      upload: place,
      replace: place,
      rename: place,
      deleteFile: write,
      createDropBox: place,
    }
  }
  if (at.kind === DROPBOX_KIND) {
    // A drop box's listing and links stay readable during a pending deletion; deleting it, or a guest's folder, is a write.
    return { ...NO_FILES_CAPABILITIES, download: true, share: publicLinks && at.shareable, deleteFile: write, deleteNode: write }
  }
  return NO_FILES_CAPABILITIES
}

function guestCapabilities(role: string, at: CapabilityTarget): FilesCapabilities {
  // A guest is never at the scope root.
  if (at.kind === null) return NO_FILES_CAPABILITIES
  switch (role) {
    case 'READ':
      return at.kind === FOLDER_KIND || at.kind === DROPBOX_KIND ? { ...NO_FILES_CAPABILITIES, download: true } : NO_FILES_CAPABILITIES
    case 'READ_WRITE':
      // A contributor uploads and replaces any file of a folder (the owner's too); nothing in a guest's identity folder;
      // a drop box below the link is read-only for it (inbox #380). Rename is the server's (core's `updateShareNode`)
      // but offered nowhere yet (inbox #358); a guest deletes no file (nodes store no authorship).
      if (at.kind === FOLDER_KIND) {
        const place = !at.inIdentityFolder
        return { ...NO_FILES_CAPABILITIES, download: true, upload: place, replace: place }
      }
      return at.kind === DROPBOX_KIND ? { ...NO_FILES_CAPABILITIES, download: true } : NO_FILES_CAPABILITIES
    case 'WRITE_ONLY':
      // The drop box's own guest: sends into its identity folder, holds no key to read anything back.
      return at.kind === DROPBOX_KIND ? { ...NO_FILES_CAPABILITIES, upload: true } : NO_FILES_CAPABILITIES
    default:
      return NO_FILES_CAPABILITIES
  }
}
