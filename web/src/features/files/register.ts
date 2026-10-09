// The Files application's registration with the platform registry (ADR 0079). Called once, from the composition root
// (`web/src/applications.ts`), which is the only core file allowed to import an application. The registry is a
// parameter, not an import of the singleton, so a test can register into a fresh one.
//
// The `FILE` kind with its level SECTION (cr-files-application F1b): the file list of a folder, the upload, replace,
// download, rename and delete. No `detail` — a file is not opened, it is downloaded from its row (an opened FILE, e.g.
// by a typed URL, renders the host's explicit unsupported state, `home.unsupportedKind`), no `createDialog` (a file is
// created by uploading it, never by naming an empty one) and no `shareDialog` (a file is shared through its folder).
// Its SHARE SECTION (F2): the file list of a shared folder on the public page, the download for every guest, the upload
// and replace for a contributor (`components/ShareFilesSection.vue`); no share body — a file is never a share root.
//
// The `FILES_DROPBOX` kind (F3, ADR 0080 decision 11): its level section (the drop boxes of a folder, a "New drop box"
// action), its `createDialog` (a name), its `detail` (its guests' folders), its `shareDialog` (WRITE_ONLY links only)
// and the share BODY: on its own WRITE_ONLY link the guest's page (name yourself, pick files, upload into your own
// folder); embedded by core's folder page of a READ or READ_WRITE link above, the drop box read-only — its guests'
// folders to open and download from, nothing to send (`DropBoxShareBody`, inbox #380, owner decision 2026-10-08).
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import type { Registry } from '../../core/applications/registry'
import { fileNameSlug } from '../../shared/naming'
import FilesSection from './components/FilesSection.vue'
import ShareFilesSection from './components/ShareFilesSection.vue'
import DropBoxSection from './components/DropBoxSection.vue'
import DropBoxCreateDialog from './components/DropBoxCreateDialog.vue'
import DropBoxDetail from './components/DropBoxDetail.vue'
import DropBoxShareDialog from './components/DropBoxShareDialog.vue'
import DropBoxShareBody from './components/DropBoxShareBody.vue'

/** What a rename selects of a file's name (core's rename dialog, inbox #385): the name without its extension — up to
 *  the last dot, unless the name starts with its only dot (`.env` stays whole). */
export function fileNameStemEnd(name: string): number {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? dot : name.length
}

/** Where Files' section sorts among the level view's sections: after Memories' (10). */
export const FILES_SECTION_ORDER = 20
/** The drop boxes' section: right after the files. */
export const DROPBOX_SECTION_ORDER = 21

export function registerFiles(registry: Registry): void {
  // A file is no share root (the backend declares no `ShareRoots` for it): it is shared through its folder. Its name
  // folds by `fileNameSlug` (inbox #350: `Foto.JPG` and `foto.jpg` collide, `Day1.md` and `Day1 md` do not) — frozen
  // into every stored slug token from the first `FILE` on, and pinned by `name_folding` in `contracts/crypto/vectors/v1.json`.
  registry.registerKind({
    kind: FILE_KIND,
    icon: 'pi pi-file',
    shareable: false,
    slug: fileNameSlug,
    renameSelectionEnd: fileNameStemEnd,
    // A trashed file that holds no blob is one whose upload never finished (inbox #391): core's trash says so.
    emptyInTrash: 'files.trashEmpty',
    // Core's create and rename dialogs name a colliding file with it: "There is already a file named …" (#389).
    nounWithArticle: 'files.aFile',
    section: { order: FILES_SECTION_ORDER, component: FilesSection },
    shareSection: { order: FILES_SECTION_ORDER, component: ShareFilesSection },
  })
  // Shared WRITE_ONLY only (the backend's `FilesShareRoots`); its name folds as a container's (`slugify`, the default).
  registry.registerKind({
    kind: DROPBOX_KIND,
    icon: 'pi pi-inbox',
    shareable: true,
    detail: DropBoxDetail,
    // Core's delete dialog (the detail's delete): "Delete drop box", the detail's own button label.
    deleteTitle: 'files.dropBox.delete',
    // …and its sentence names what goes with it: the guests' folders, and the links that stop taking files (#395).
    deleteCopy: 'files.dropBox.deleteConfirm',
    nounWithArticle: 'files.dropBox.aDropBox',
    section: { order: DROPBOX_SECTION_ORDER, component: DropBoxSection },
    createDialog: DropBoxCreateDialog,
    shareDialog: DropBoxShareDialog,
  })
  registry.claimShareBody(DROPBOX_KIND, DropBoxShareBody)
}
