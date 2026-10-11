import { describe, it, expect } from 'vitest'
import { createRegistry } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import { fileNameSlug, slugify } from '../../shared/naming'
import { fileNameStemEnd, registerFiles } from './register'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import FilesSection from './components/FilesSection.vue'
import DropBoxSection from './components/DropBoxSection.vue'
import DropBoxCreateDialog from './components/DropBoxCreateDialog.vue'
import DropBoxDetail from './components/DropBoxDetail.vue'
import DropBoxShareDialog from './components/DropBoxShareDialog.vue'
import DropBoxShareBody from './components/DropBoxShareBody.vue'

describe('registerFiles', () => {
  it('registers the FILE kind: not shareable, a file icon, its level section (a guest\'s too), no detail', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.kind(FILE_KIND)).toEqual({
      supported: true,
      kind: FILE_KIND,
      icon: 'pi pi-file',
      shareable: false,
      slug: fileNameSlug,
      renameSelectionEnd: fileNameStemEnd,
      emptyInTrash: 'files.trashEmpty',
      nounWithArticle: 'files.aFile',
      section: { order: 20, component: FilesSection },
      placement: { root: true, parents: [FOLDER_KIND] },
    })
  })

  // inbox #358: core's move dialog mirrors the backend's `FilesNodeKindRules` — the root or a folder, never a file or a
  // drop box (which take no placed child), never another application's kind.
  it('places a file and a drop box at the root or in a folder only', () => {
    const registry = createRegistry()
    registerFiles(registry)
    for (const kind of [FILE_KIND, DROPBOX_KIND]) {
      expect(registry.placementAllows(kind, null), `${kind} at the root`).toBe(true)
      expect(registry.placementAllows(kind, FOLDER_KIND), `${kind} in a folder`).toBe(true)
      for (const parent of [FILE_KIND, DROPBOX_KIND, 'MEMORY']) expect(registry.placementAllows(kind, parent), `${kind} in ${parent}`).toBe(false)
    }
  })

  it('folds a file name case-insensitively and keeps its punctuation', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.slugFor(FILE_KIND, 'Foto.JPG')).toBe(registry.slugFor(FILE_KIND, 'foto.jpg'))
    expect(registry.slugFor(FILE_KIND, 'Day1.md')).not.toBe(registry.slugFor(FILE_KIND, 'Day1 md'))
  })

  it('leaves the folder folding alone', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.slugFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(registry.slugFor(FOLDER_KIND, 'Foto.JPG')).not.toBe(fileNameSlug('Foto.JPG'))
  })

  it('registers the FILES_DROPBOX kind: shareable, its section after the files, its dialogs, detail and share body (F3)', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.kind(DROPBOX_KIND)).toEqual({
      supported: true,
      kind: DROPBOX_KIND,
      icon: 'pi pi-inbox',
      shareable: true,
      detail: DropBoxDetail,
      deleteTitle: 'files.dropBox.delete',
      deleteCopy: 'files.dropBox.deleteConfirm',
      nounWithArticle: 'files.dropBox.aDropBox',
      section: { order: 21, component: DropBoxSection },
      createDialog: DropBoxCreateDialog,
      shareDialog: DropBoxShareDialog,
      placement: { root: true, parents: [FOLDER_KIND] },
    })
    expect(registry.shareBody(DROPBOX_KIND)).toBe(DropBoxShareBody)
    expect(registry.slugFor(DROPBOX_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG')) // a container's folding
  })

  it('brings its own level sections after Memories\' (files 20, drop boxes 21), and no FILE dialog, list item, share body or lifecycle', () => {
    const registry = createRegistry()
    registerFiles(registry)
    const file = registry.kind(FILE_KIND)
    expect(file.supported && file.createDialog).toBeUndefined() // a file is created by uploading it, never by a name dialog
    expect(file.supported && file.shareDialog).toBeUndefined() // not a share root: no share offered
    expect(registry.sections()).toEqual([
      { component: FilesSection, order: 20, views: [], kinds: [FILE_KIND] },
      { component: DropBoxSection, order: 21, views: [], kinds: [DROPBOX_KIND] },
    ])
    for (const slot of ['topbarTray', 'sidebarPins'] as const) expect(registry.slotItems(slot)).toEqual([])
    expect(registry.shareBody(FILE_KIND)).toBeNull()
  })

  it('is write-once: a second registration throws', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(() => registerFiles(registry)).toThrow(/already registered/)
  })

  it('a rename selects a file\'s name up to its last dot; a name that only starts with a dot stays whole', () => {
    expect(fileNameStemEnd('Bericht.final.pdf')).toBe('Bericht.final'.length)
    expect(fileNameStemEnd('README')).toBe(6)
    expect(fileNameStemEnd('.env')).toBe(4)
  })
})
