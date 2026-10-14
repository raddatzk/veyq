// The Files application's registration with the platform registry (ADR 0079). Called once, from the composition root
// (`web/src/applications.ts`), which is the only core file allowed to import an application. The registry is a
// parameter, not an import of the singleton, so a test can register into a fresh one.
//
// The `FILE` kind with its ROWS in the unified level list (cr-files-application F1b, inbox #407): the files of a folder as
// rows core draws - download first-class, replace, rename, move and delete in the row's menu - the upload (a level action that
// also takes dropped files); its `detail` (inbox #378) is the page of one opened file (`FileDetail`: facts, download,
// replace, and the header card's share, rename, move and delete) and its `shareDialog` the links of that one file
// (`FileShareDialog`, READ and READ_WRITE — a single-file share; the backend's `FilesShareRoots`). No `createDialog` (a
// file is created by uploading it, never by naming an empty one). The same rows are a shared folder's file list on the
// public page (inbox #401 S3): core's folder page hands the row factory the guest's access and tree, the download for every
// guest, the upload and replace for a contributor; the same `detail` is a single-file link's whole page (core's
// `ShareTreePage` mounts the kind's detail at the link's root), and a file opened inside a folder link. No share body —
// a file is never a `WRITE_ONLY` root, and every other role gets core's page.
//
// The `FILES_DROPBOX` kind (F3, ADR 0080 decision 11): its rows in the same list (the drop boxes of a folder, a "New drop box"
// entry in the level's menu), its `createDialog` (a name), its `detail` (its guests' folders — for a member, and read-only for a READ or
// READ_WRITE guest of a folder link above it, inbox #380 and #401 S3), its `shareDialog` (WRITE_ONLY links only) and the
// share BODY: on its own WRITE_ONLY link the guest's drop page (name yourself, pick files, upload into your own folder),
// the one page core renders by claim (every other guest page is the member's views, inbox #401).
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import type { LevelActionContext, Registry } from '../../core/applications/registry'
import { fileNameSlug } from '../../shared/naming'
import DropBoxCreateDialog from './components/DropBoxCreateDialog.vue'
import DropBoxDetail from './components/DropBoxDetail.vue'
import DropBoxShareDialog from './components/DropBoxShareDialog.vue'
import DropBoxShareBody from './components/DropBoxShareBody.vue'
import FileDetail from './components/FileDetail.vue'
import FileShareDialog from './components/FileShareDialog.vue'
import { filesCapabilities } from './capabilities'
import { filesRows, UPLOAD_ACTION } from './levelRows'
import { targetOf } from '../../core/access'
import { exportFile, importFile, validateFileContent, FILES_CONTENT_SCHEMA } from './exportImport'

/** What a rename selects of a file's name (core's rename dialog, inbox #385): the name without its extension — up to
 *  the last dot, unless the name starts with its only dot (`.env` stays whole). */
export function fileNameStemEnd(name: string): number {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? dot : name.length
}

/** Where Files' entries sit in the level's "New" menu: after core's folder (0) and Memories' Memory (10). */
const NEW_DROPBOX_ORDER = 20
const NEW_UPLOAD_ORDER = 30

/** Where a file and a drop box live (the backend's `FilesNodeKindRules`): at the root or directly in a folder — never in
 *  another file or a drop box, which take no placed child. */
const FILES_PLACEMENT = { root: true, parents: [FOLDER_KIND] } as const

/** The plain text of the Files tab when it has nothing: where a file may be uploaded it points at "New"; a reader is not
 *  pointed at a menu it does not have, a guest says what its link shows. */
function filesEmptyText(ctx: LevelActionContext): string {
  if (filesCapabilities(ctx.access, targetOf(ctx.tree, ctx.folder)).upload) return 'files.empty'
  return ctx.access.mode === 'guest' ? 'files.guest.empty' : 'files.emptyReadOnly'
}

export function registerFiles(registry: Registry): void {
  // A file is a READ / READ_WRITE share root (the backend's `FilesShareRoots`), and is also shared through its folder.
  // Its name folds by `fileNameSlug` (inbox #350: `Foto.JPG` and `foto.jpg` collide, `Day1.md` and `Day1 md` do not) — frozen
  // into every stored slug token from the first `FILE` on, and pinned by `name_folding` in `contracts/crypto/vectors/v1.json`.
  registry.registerKind({
    kind: FILE_KIND,
    icon: 'pi pi-file',
    shareable: true,
    detail: FileDetail,
    shareDialog: FileShareDialog,
    // Core's delete dialog (the detail's delete) names the file, not "item".
    deleteTitle: 'files.deleteTitle',
    deleteCopy: 'files.deleteConfirm',
    slug: fileNameSlug,
    // A file downloads as itself (its row's and page's own action) and is one file inside a folder's ZIP (ADR 0082).
    exportContent: exportFile,
    importContent: importFile,
    contentSchema: FILES_CONTENT_SCHEMA,
    validateContent: validateFileContent,
    downloadsAsFile: true,
    renameSelectionEnd: fileNameStemEnd,
    // A trashed file that holds no blob is one whose upload never finished (inbox #391): core's trash says so.
    emptyInTrash: 'files.trashEmpty',
    // Core's create and rename dialogs name a colliding file with it: "There is already a file named …" (#389).
    nounWithArticle: 'files.aFile',
    list: { rows: filesRows, tabLabel: 'files.title', emptyText: filesEmptyText },
    // "Upload files" in core's level "New" menu (inbox #406): no create dialog - the row factory registers the handler that
    // opens its picker under this id (`core/node/levelActions.ts`) for the level's whole life, whichever tab is shown; the same
    // handler takes the files dropped onto the level (`acceptsDrop`, inbox #407).
    levelActions: [{
      id: UPLOAD_ACTION, label: 'files.upload', icon: 'pi pi-upload', order: NEW_UPLOAD_ORDER, acceptsDrop: true,
      allowed: (ctx: LevelActionContext) => filesCapabilities(ctx.access, targetOf(ctx.tree, ctx.folder)).upload,
    }],
    // Where it may be moved (core's move dialog, inbox #358): the backend's `FilesNodeKindRules`.
    placement: FILES_PLACEMENT,
  })
  // Shared WRITE_ONLY only (the backend's `FilesShareRoots`); its name folds as a container's (`slugify`, the default).
  registry.registerKind({
    kind: DROPBOX_KIND,
    icon: 'pi pi-inbox',
    // A drop box carries a cover (inbox #411: core's action, picker, upload and display); a file does not.
    cover: {},
    shareable: true,
    detail: DropBoxDetail,
    // Core's delete dialog (the detail's delete): "Delete drop box", the detail's own button label.
    deleteTitle: 'files.dropBox.delete',
    // …and its sentence names what goes with it: the guests' folders, and the links that stop taking files (#395).
    deleteCopy: 'files.dropBox.deleteConfirm',
    nounWithArticle: 'files.dropBox.aDropBox',
    list: { rows: filesRows, tabLabel: 'files.dropBox.section', emptyText: () => 'files.dropBox.sectionEmpty' },
    createDialog: DropBoxCreateDialog,
    createEntry: {
      label: 'files.dropBox.create', icon: 'pi pi-inbox', order: NEW_DROPBOX_ORDER,
      allowed: (ctx: LevelActionContext) => filesCapabilities(ctx.access, targetOf(ctx.tree, ctx.folder)).createDropBox,
      enabled: (ctx: LevelActionContext) => ctx.epochs !== null,
    },
    shareDialog: DropBoxShareDialog,
    placement: FILES_PLACEMENT,
  })
  registry.claimShareBody(DROPBOX_KIND, DropBoxShareBody)
  // Files is the one application with NO route segment (inbox #328, ADR 0015 amendment): a file or a drop box is its plain
  // name in the path (`#reisen/report`), a folder of the same name needs the trailing slash (`#reisen/report/`). Declared,
  // not defaulted, so the registry knows it was chosen — and no second application can choose it too.
  registry.claimNoRouteSegment([FILE_KIND, DROPBOX_KIND])
}
