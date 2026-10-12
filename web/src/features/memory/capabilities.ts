// What a Memories view may offer to whoever looks (inbox #401): one pure, FAIL-CLOSED function over core's `Access` and
// the node on screen, so `MemoryDetail` and Memories' rows (`levelRows.ts`) show a control only where this says yes — a member and a
// share guest see the same view with functions removed. The matrix is written down once, in `docs/apps/memories.md`
// § What a guest may do (ADR 0080 decision 6); this is its code. Every field defaults to false: an unknown mode, role or
// kind grants nothing, so a new kind or a new role never inherits a right by accident.
//
// Member-only by construction (rule 2 of #401): item marks (`K_SC` at `E_0`), the
// node's sealed meta (date, place, cover), the media trash and the contributions organizer (`MemoryBuckets`, member
// globals) — no guest role grants them, whatever a source implements.
import { FOLDER_KIND } from '../../core/node/kind'
import type { Access, CapabilityTarget } from '../../core/access'
import { CHAPTER_KIND, MEMORY_KIND } from './kinds'

export interface MemoryCapabilities {
  /** Open what lies below: a Memory from a level, a chapter from a Memory (the Chapters tab). */
  navigate: boolean
  /** Open a medium and download its original. */
  download: boolean
  /** Upload media into the node on screen (a Memory or a chapter). */
  upload: boolean
  /** Which media a delete reaches: a member's trash takes any; a guest purges only its own (`listShareMedia`'s own
   *  set — irreversible, so the view confirms); none. */
  deleteMedia: 'none' | 'own' | 'any'
  /** Create a Memory at a folder level (or a member's scope root). */
  createMemory: boolean
  /** Create a chapter in the Memory on screen (a chapter lives only directly in a Memory: `MemoryNodeKindRules`). */
  createChapter: boolean
  /** Edit the node's date and place, and a medium's capture time (the node's sealed `wrappedMeta`). The automatic
   *  date/place reconcile, which writes that meta, runs only with it. */
  editMeta: boolean
  /** Choose the node's cover. */
  setCover: boolean
  /** Mark media and filter by marks (shared with the scope, sealed under `K_SC` at `E_0`, ADR 0068). */
  mark: boolean
  /** Manage the node's public links (READ / READ_WRITE). */
  share: boolean
  /** Create an uploader (WRITE_ONLY) link — its own action and dialog. */
  shareUpload: boolean
  /** Rename the node on screen. */
  rename: boolean
  /** Delete the node on screen (into the scope's trash). */
  deleteNode: boolean
  /** Move the node on screen elsewhere (core's `MoveNodeDialog`, inbox #358): a Memory to the root or a folder, a chapter
   *  into another Memory (the kinds' `placement`, the backend's `MemoryNodeKindRules`). */
  move: boolean
  /** The node's media trash (restore, purge). */
  mediaTrash: boolean
  /** The contributions organizer (buckets: create, rename, move media). */
  buckets: boolean
}

export const NO_MEMORY_CAPABILITIES: Readonly<MemoryCapabilities> = Object.freeze({
  navigate: false,
  download: false,
  upload: false,
  deleteMedia: 'none',
  createMemory: false,
  createChapter: false,
  editMeta: false,
  setCover: false,
  mark: false,
  share: false,
  shareUpload: false,
  rename: false,
  deleteNode: false,
  move: false,
  mediaTrash: false,
  buckets: false,
})

const isMemoriesNode = (kind: string | null) => kind === MEMORY_KIND || kind === CHAPTER_KIND
const isLevel = (kind: string | null) => kind === null || kind === FOLDER_KIND

/** The Memories matrix (see the header; the table is `docs/apps/memories.md`). `at` is the node on screen: a `MEMORY` or
 *  `CHAPTER` opened (the detail), or the folder level whose Memories the section shows (`FOLDER`; null at a member's
 *  scope root). */
export function memoryCapabilities(access: Access, at: CapabilityTarget): MemoryCapabilities {
  if (access.mode === 'member') return memberCapabilities(access.readonly, access.publicLinks, access.uploaderLinks, at)
  if (access.mode === 'guest') return guestCapabilities(access.role, at)
  return NO_MEMORY_CAPABILITIES
}

function memberCapabilities(readonly: boolean, publicLinks: boolean, uploaderLinks: boolean, at: CapabilityTarget): MemoryCapabilities {
  const write = !readonly
  if (isLevel(at.kind)) {
    // A guest's identity folder (Files' drop box) takes no Memory. (The favorite heart is core's: `core/favorites`.)
    return { ...NO_MEMORY_CAPABILITIES, navigate: true, createMemory: write && !at.inIdentityFolder }
  }
  if (!isMemoriesNode(at.kind)) return NO_MEMORY_CAPABILITIES
  return {
    ...NO_MEMORY_CAPABILITIES,
    navigate: true,
    download: true,
    mark: true,
    // Listing and copying links is a read: a pending deletion keeps them (a new link is refused server-side).
    share: publicLinks && at.shareable,
    shareUpload: publicLinks && uploaderLinks && at.shareable,
    // The media trash and the organizer are views first; their writes are refused during a pending deletion.
    mediaTrash: true,
    buckets: true,
    upload: write,
    deleteMedia: write ? 'any' : 'none',
    createChapter: write && at.kind === MEMORY_KIND,
    editMeta: write,
    setCover: write,
    rename: write,
    deleteNode: write,
    move: write,
  }
}

function guestCapabilities(role: string, at: CapabilityTarget): MemoryCapabilities {
  switch (role) {
    case 'READ':
      if (isLevel(at.kind)) return at.kind === FOLDER_KIND ? { ...NO_MEMORY_CAPABILITIES, navigate: true } : NO_MEMORY_CAPABILITIES
      return isMemoriesNode(at.kind) ? { ...NO_MEMORY_CAPABILITIES, navigate: true, download: true } : NO_MEMORY_CAPABILITIES
    case 'READ_WRITE':
      // A folder link's contributor creates a Memory under a folder (core's `FolderGuestNodeRules`) and moves one a folder
      // holds between the subtree's folders; a Memory or chapter link's, nothing of the tree (Memories declares no
      // `GuestNodeRules`, so nothing moves out of or into a Memory, and the link's root has no parent the guest reads).
      // Uploads go into the Memory or chapter on screen (`nodeId`), deletes reach its own media only.
      if (isLevel(at.kind)) {
        return at.kind === FOLDER_KIND ? { ...NO_MEMORY_CAPABILITIES, navigate: true, createMemory: !at.inIdentityFolder } : NO_MEMORY_CAPABILITIES
      }
      return isMemoriesNode(at.kind)
        ? {
            ...NO_MEMORY_CAPABILITIES,
            navigate: true,
            download: true,
            upload: true,
            deleteMedia: 'own',
            move: at.kind === MEMORY_KIND && at.parentKind === FOLDER_KIND && !at.inIdentityFolder,
          }
        : NO_MEMORY_CAPABILITIES
    case 'WRITE_ONLY':
      // The drop: no key below the root, so nothing to open or download; it sends into the root and removes its own.
      return isMemoriesNode(at.kind) ? { ...NO_MEMORY_CAPABILITIES, upload: true, deleteMedia: 'own' } : NO_MEMORY_CAPABILITIES
    default:
      return NO_MEMORY_CAPABILITIES
  }
}
