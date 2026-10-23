import { describe, it, expect } from 'vitest'
import { createRegistry } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import { fileNameSlug, slugify } from '../../shared/naming'
import { fileNameStemEnd, registerDepot } from './register'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { depotRows } from './levelRows'
import DropBoxCreateDialog from './components/DropBoxCreateDialog.vue'
import DropBoxDetail from './components/DropBoxDetail.vue'
import DropBoxShareDialog from './components/DropBoxShareDialog.vue'
import DropBoxShareBody from './components/DropBoxShareBody.vue'
import FileDetail from './components/FileDetail.vue'
import FileShareDialog from './components/FileShareDialog.vue'
import { exportDropBox, exportFile, importFile, validateDropBoxContent, validateFileContent } from './exportImport'

describe('registerDepot', () => {
  it('registers the FILE kind: shareable (READ / READ_WRITE, a single-file link), a file icon, its rows, its own page and link dialog', () => {
    const registry = createRegistry()
    registerDepot(registry)
    expect(registry.kind(FILE_KIND)).toEqual({
      supported: true,
      kind: FILE_KIND,
      icon: 'file',
      shareable: true,
      detail: FileDetail,
      shareDialog: FileShareDialog,
      deleteTitle: 'depot.deleteTitle',
      deleteCopy: 'depot.deleteConfirm',
      slug: fileNameSlug,
      exportContent: exportFile,
      importContent: importFile,
      // a file downloads as itself and is one file in a folder's ZIP (ADR 0082)
      contentSchema: { application: 'depot', version: 1 },
      validateContent: validateFileContent,
      downloadsAsFile: true,
      // its import reads the payload only by frame (the upload pump), so a copy hands it the original by frame (cr-copy-node)
      importStreams: true,
      renameSelectionEnd: fileNameStemEnd,
      emptyInTrash: 'depot.trashEmpty',
      nounWithArticle: 'depot.aFile',
      list: { rows: depotRows, tabLabel: 'depot.title', emptyText: expect.any(Function) },
      // "Upload files" takes files dropped onto the level too (core hands them over only while the entry is enabled)
      // …and so does "Upload folder…" beside it (a dropped directory, inbox #424)
      levelActions: [
        { id: 'depot.upload', label: 'depot.upload', icon: 'upload', order: 30, acceptsDrop: true, allowed: expect.any(Function) },
        { id: 'depot.uploadFolder', label: 'depot.uploadFolder', icon: 'folder-open', order: 31, acceptsDrop: true, allowed: expect.any(Function) },
      ],
      placement: { root: true, parents: [FOLDER_KIND] },
    })
  })

  // inbox #358: core's move dialog mirrors the backend's `DepotNodeKindRules` — the root or a folder, never a file or a
  // drop box (which take no placed child), never another application's kind.
  it('places a file and a drop box at the root or in a folder only', () => {
    const registry = createRegistry()
    registerDepot(registry)
    for (const kind of [FILE_KIND, DROPBOX_KIND]) {
      expect(registry.placementAllows(kind, null), `${kind} at the root`).toBe(true)
      expect(registry.placementAllows(kind, FOLDER_KIND), `${kind} in a folder`).toBe(true)
      for (const parent of [FILE_KIND, DROPBOX_KIND, 'MEMORY']) expect(registry.placementAllows(kind, parent), `${kind} in ${parent}`).toBe(false)
    }
  })

  it('folds a file name case-insensitively and keeps its punctuation', () => {
    const registry = createRegistry()
    registerDepot(registry)
    expect(registry.slugFor(FILE_KIND, 'Foto.JPG')).toBe(registry.slugFor(FILE_KIND, 'foto.jpg'))
    expect(registry.slugFor(FILE_KIND, 'Day1.md')).not.toBe(registry.slugFor(FILE_KIND, 'Day1 md'))
  })

  it('leaves the folder folding alone', () => {
    const registry = createRegistry()
    registerDepot(registry)
    expect(registry.slugFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(registry.slugFor(FOLDER_KIND, 'Foto.JPG')).not.toBe(fileNameSlug('Foto.JPG'))
  })

  it('registers the DEPOT_DROPBOX kind: shareable, its rows beside the files\', its dialogs, detail and share body (F3)', () => {
    const registry = createRegistry()
    registerDepot(registry)
    expect(registry.kind(DROPBOX_KIND)).toEqual({
      supported: true,
      kind: DROPBOX_KIND,
      icon: 'inbox',
      cover: {}, // a drop box carries a cover (inbox #411)
      shareable: true,
      detail: DropBoxDetail,
      deleteTitle: 'depot.dropBox.delete',
      deleteCopy: 'depot.dropBox.deleteConfirm',
      nounWithArticle: 'depot.dropBox.aDropBox',
      list: { rows: depotRows, tabLabel: 'depot.dropBox.section', emptyText: expect.any(Function) },
      createDialog: DropBoxCreateDialog,
      createEntry: { label: 'depot.dropBox.create', icon: 'inbox', order: 20, allowed: expect.any(Function), enabled: expect.any(Function) },
      shareDialog: DropBoxShareDialog,
      placement: { root: true, parents: [FOLDER_KIND] },
      // downloaded as a directory of its guests' folders, imported as a plain folder (cr-download-is-the-export Decision 7)
      exportContent: exportDropBox,
      contentSchema: { application: 'depot', version: 1 },
      validateContent: validateDropBoxContent,
      importsAsFolder: true,
      // a copy makes it a plain folder too: its dialog names what stays behind (cr-copy-node Decision 4)
      copyLosses: ['depot.dropBox.copyLoss'],
    })
    expect(registry.shareBody(DROPBOX_KIND)).toBe(DropBoxShareBody)
    expect(registry.slugFor(DROPBOX_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG')) // a container's folding
  })

  it('brings its own rows and tabs (files, then drop boxes), and no FILE create dialog, list item, drop page or lifecycle', () => {
    const registry = createRegistry()
    registerDepot(registry)
    const file = registry.kind(FILE_KIND)
    expect(file.supported && file.createDialog).toBeUndefined() // a file is created by uploading it, never by a name dialog
    // inbox #407: both kinds name ONE row factory (one source per level, called once) and one tab each
    expect(registry.lists()).toEqual([{ rows: depotRows, kinds: [FILE_KIND, DROPBOX_KIND], views: [] }])
    expect(registry.tabs().map((t) => [t.id, t.label, t.kinds])).toEqual([
      [FILE_KIND, 'depot.title', [FILE_KIND]],
      [DROPBOX_KIND, 'depot.dropBox.section', [DROPBOX_KIND]],
    ])
    for (const slot of ['topbarTray', 'sidebarPins'] as const) expect(registry.slotItems(slot)).toEqual([])
    expect(registry.shareBody(FILE_KIND)).toBeNull() // no WRITE_ONLY link on a file: core's page serves every file link
  })

  it('is write-once: a second registration throws', () => {
    const registry = createRegistry()
    registerDepot(registry)
    expect(() => registerDepot(registry)).toThrow(/already registered/)
  })

  it('a rename selects a file\'s name up to its last dot; a name that only starts with a dot stays whole', () => {
    expect(fileNameStemEnd('Bericht.final.pdf')).toBe('Bericht.final'.length)
    expect(fileNameStemEnd('README')).toBe(6)
    expect(fileNameStemEnd('.env')).toBe(4)
  })
})
