// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { registerApplications } from './applications'
import { registry } from './core/applications/registry'
import { MEMORY_KIND, CHAPTER_KIND } from './features/memory/kinds'
import { DROPBOX_KIND, FILE_KIND } from './features/files/kinds'
import { fileNameSlug } from './shared/naming'
import { memoryRows } from './features/memory/levelRows'
import { filesRows } from './features/files/levelRows'
import FileDetail from './features/files/components/FileDetail.vue'
import FileShareDialog from './features/files/components/FileShareDialog.vue'
import MemoryNodeDialog from './features/memory/components/MemoryNodeDialog.vue'
import ShareDialog from './features/memory/components/ShareDialog.vue'
import { FOLDER_KIND } from './core/node/kind'

// `src/test/setup.ts` has already called registerApplications() once for this file, exactly as main.ts does.
describe('composition root', () => {
  // Both Memories kinds are share roots (ADR 0080 Decision 13, the backend's `MemoryShareRoots`): a chapter is shareable
  // on its own and its public page is the same Memories body as a Memory's.
  it('registers the Memories kinds into the real registry, both shareable', () => {
    expect(registry.kind(MEMORY_KIND)).toMatchObject({ supported: true, shareable: true })
    expect(registry.kind(CHAPTER_KIND)).toMatchObject({ supported: true, shareable: true })
  })

  // Files, the second application (cr-files-application F0): its file kind, a READ / READ_WRITE share root with its own
  // page and link dialog (inbox #378), its names folded as file names.
  it('registers the Files kind into the same registry, shareable, with the file-name folding', () => {
    expect(registry.kind(FILE_KIND)).toMatchObject({ supported: true, shareable: true, slug: fileNameSlug, detail: FileDetail, shareDialog: FileShareDialog })
    expect(registry.shareBody(FILE_KIND)).toBeNull()
  })

  // The dialogs are per kind (P4c): Memories' one create dialog and one share dialog serve both its kinds; FOLDER keeps
  // core's pre-registered share dialog; FILE brings its own share dialog and no create dialog (a file is created by uploading it).
  it('gives both Memories kinds the Memories create and share dialogs, and leaves FOLDER and FILE alone', () => {
    for (const kind of [MEMORY_KIND, CHAPTER_KIND]) {
      expect(registry.kind(kind)).toMatchObject({ createDialog: MemoryNodeDialog, shareDialog: ShareDialog })
    }
    // Core's delete dialog names no application (inbox #375): Memories brings each kind's own title with it.
    expect(registry.kind(MEMORY_KIND)).toMatchObject({ deleteTitle: 'memory.deleteMemoryTitle' })
    expect(registry.kind(CHAPTER_KIND)).toMatchObject({ deleteTitle: 'memory.deleteChapterTitle' })
    const folder = registry.kind(FOLDER_KIND)
    expect(folder.supported && folder.shareDialog).toBeTruthy()
    expect(folder.supported && folder.shareDialog).not.toBe(ShareDialog)
    expect(registry.kind(FILE_KIND)).not.toHaveProperty('createDialog')
    expect(registry.kind(FILE_KIND)).toMatchObject({ shareDialog: FileShareDialog })
    expect(registry.kind(FILE_KIND)).not.toMatchObject({ shareDialog: ShareDialog })
  })

  it('claims the one Memories share body for both root kinds', () => {
    expect(registry.shareBody(MEMORY_KIND)).not.toBeNull()
    expect(registry.shareBody(CHAPTER_KIND)).toBe(registry.shareBody(MEMORY_KIND))
  })

  // The level view is core's (P4b) and draws ONE list (inbox #407): Memories' row factory serves both its kinds, Files' serves
  // FILE and the drop box; each kind has its tab, in registration order (Memories first).
  it('gives the level view two row sources: Memories\' for MEMORY and CHAPTER, then Files\' for FILE and the drop box', () => {
    // Memories names the grid, the list and its map as its presentations (inbox #370): core's level toolbar offers them.
    expect(registry.lists().map((l) => ({ ...l, views: l.views.map((v) => v.mode) }))).toEqual([
      { rows: memoryRows, views: ['grid', 'list', 'map'], kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { rows: filesRows, views: [], kinds: [FILE_KIND, DROPBOX_KIND] },
    ])
  })

  it('gives the level view its tabs: Memories, Files, Drop boxes - the filter of the one list', () => {
    expect(registry.tabs().map((t) => ({ id: t.id, label: t.label, kinds: t.kinds }))).toEqual([
      { id: MEMORY_KIND, label: 'memory.memories', kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { id: FILE_KIND, label: 'files.title', kinds: [FILE_KIND] },
      { id: DROPBOX_KIND, label: 'files.dropBox.section', kinds: [DROPBOX_KIND] },
    ])
  })

  it('lets Files\' upload take dropped files, and nothing else', () => {
    expect(registry.levelMenu().filter((e) => e.acceptsDrop).map((e) => e.id)).toEqual(['files.upload'])
  })

  // The tray and the pins are list slots (P4a). The upload status is core's since B3 (the top bar mounts it itself) and so is
  // the Favorites section since inbox #409 (`core/favorites/PinnedNodes.vue`), so no installed application adds to either.
  it('adds nothing to the top-bar tray and nothing to the sidebar pins', () => {
    expect(registry.slotItems('topbarTray')).toEqual([])
    expect(registry.slotItems('sidebarPins')).toEqual([])
  })

  // The rotation hook is REQUIRED (the registry's header): this is the test that goes red when the composition root
  // (or Memories' registration) stops registering it, which rotateKeys.test.ts cannot see (it registers its own).
  it('registers the Memories lifecycle, so the rotation gate is open on the real build', () => {
    expect(() => registry.assertRotationHooks()).not.toThrow()
  })

  it('is write-once end to end: registering the applications a second time throws', () => {
    expect(() => registerApplications()).toThrow(/already registered/)
  })
})
