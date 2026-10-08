// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { registerApplications } from './applications'
import { registry } from './core/applications/registry'
import { MEMORY_KIND, CHAPTER_KIND } from './features/memory/kinds'
import { DROPBOX_KIND, FILE_KIND } from './features/files/kinds'
import { fileNameSlug } from './shared/naming'
import { favoritesStore } from './features/memory/favoritesStore'
import PinnedMemories from './features/memory/components/PinnedMemories.vue'
import MemoriesSection from './features/memory/components/MemoriesSection.vue'
import FilesSection from './features/files/components/FilesSection.vue'
import DropBoxSection from './features/files/components/DropBoxSection.vue'
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

  // Files, the second application (cr-files-application F0): one kind, not a share root, its names folded as file names.
  it('registers the Files kind into the same registry, not shareable, with the file-name folding', () => {
    expect(registry.kind(FILE_KIND)).toMatchObject({ supported: true, shareable: false, slug: fileNameSlug })
    expect(registry.shareBody(FILE_KIND)).toBeNull()
  })

  // The dialogs are per kind (P4c): Memories' one create dialog and one share dialog serve both its kinds; FOLDER keeps
  // core's pre-registered share dialog; FILE brings neither (a file is created by uploading it, shared by its folder).
  it('gives both Memories kinds the Memories create and share dialogs, and leaves FOLDER and FILE alone', () => {
    for (const kind of [MEMORY_KIND, CHAPTER_KIND]) {
      expect(registry.kind(kind)).toMatchObject({ createDialog: MemoryNodeDialog, shareDialog: ShareDialog })
    }
    // Core's delete dialog names no application (inbox #375): Memories brings its "Delete Memory" with its kinds.
    for (const kind of [MEMORY_KIND, CHAPTER_KIND]) expect(registry.kind(kind)).toMatchObject({ deleteTitle: 'memory.deleteMemoryTitle' })
    const folder = registry.kind(FOLDER_KIND)
    expect(folder.supported && folder.shareDialog).toBeTruthy()
    expect(folder.supported && folder.shareDialog).not.toBe(ShareDialog)
    expect(registry.kind(FILE_KIND)).not.toHaveProperty('createDialog')
    expect(registry.kind(FILE_KIND)).not.toHaveProperty('shareDialog')
  })

  it('claims the one Memories share body for both root kinds', () => {
    expect(registry.shareBody(MEMORY_KIND)).not.toBeNull()
    expect(registry.shareBody(CHAPTER_KIND)).toBe(registry.shareBody(MEMORY_KIND))
  })

  // The level view is core's (P4b): Memories' one section serves both its kinds; Files' section (F1b) serves FILE, after it.
  it('gives the level view two sections: Memories\' for MEMORY and CHAPTER, then Files\' for FILE', () => {
    expect(registry.sections()).toEqual([
      { component: MemoriesSection, order: 10, kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { component: FilesSection, order: 20, kinds: [FILE_KIND] },
      { component: DropBoxSection, order: 21, kinds: [DROPBOX_KIND] },
    ])
  })

  // The tray and the pins are list slots (P4a). The upload status is core's since B3 (the top bar mounts it itself), so
  // no installed application adds to the tray; Memories adds its pinned Memories, under an id namespaced by it.
  it('adds nothing to the top-bar tray and the pinned Memories to the sidebar pins', () => {
    expect(registry.slotItems('topbarTray')).toEqual([])
    expect(registry.slotItems('sidebarPins')).toEqual([{ id: 'memories.pinned', component: PinnedMemories }])
  })

  // The rotation hook is REQUIRED (the registry's header): this is the test that goes red when the composition root
  // (or Memories' registration) stops registering it, which rotateKeys.test.ts cannot see (it registers its own).
  it('registers the Memories lifecycle, so the rotation gate is open on the real build', () => {
    expect(() => registry.assertRotationHooks()).not.toThrow()
  })

  it('registers the Memories reset hook: a scope reset drops the pins', () => {
    favoritesStore.ids = new Set(['m1'])
    favoritesStore.loaded = true
    registry.scopeReset()
    expect(favoritesStore.loaded).toBe(false)
    expect(favoritesStore.ids.size).toBe(0)
  })

  it('is write-once end to end: registering the applications a second time throws', () => {
    expect(() => registerApplications()).toThrow(/already registered/)
  })
})
