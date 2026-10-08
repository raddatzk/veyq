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
// and the share BODY of its links (the guest's page: name yourself, pick files, upload into your own folder).
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
    section: { order: FILES_SECTION_ORDER, component: FilesSection },
    shareSection: { order: FILES_SECTION_ORDER, component: ShareFilesSection },
  })
  // Shared WRITE_ONLY only (the backend's `FilesShareRoots`); its name folds as a container's (`slugify`, the default).
  registry.registerKind({
    kind: DROPBOX_KIND,
    icon: 'pi pi-inbox',
    shareable: true,
    detail: DropBoxDetail,
    section: { order: DROPBOX_SECTION_ORDER, component: DropBoxSection },
    createDialog: DropBoxCreateDialog,
    shareDialog: DropBoxShareDialog,
  })
  registry.claimShareBody(DROPBOX_KIND, DropBoxShareBody)
}
