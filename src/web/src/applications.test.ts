// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { registerApplications } from './applications'
import { registry } from './core/applications/registry'
import { MEMORY_KIND, CHAPTER_KIND } from './features/memories/kinds'
import { DROPBOX_KIND, FILE_KIND } from './features/depot/kinds'
import { fileNameSlug } from './shared/naming'
import { memoryRows } from './features/memories/levelRows'
import { depotRows } from './features/depot/levelRows'
import { potRows } from './features/expenses/levelRows'
import { POT_KIND } from './features/expenses/kinds'
import FileDetail from './features/depot/components/FileDetail.vue'
import FileShareDialog from './features/depot/components/FileShareDialog.vue'
import MemoryNodeDialog from './features/memories/components/MemoryNodeDialog.vue'
import ShareDialog from './features/memories/components/ShareDialog.vue'
import { FOLDER_KIND } from './core/node/kind'

// `src/test/setup.ts` has already called registerApplications() once for this file, exactly as main.ts does.
describe('composition root', () => {
  // Both Memories kinds are share roots (ADR 0080 Decision 13, the backend's `MemoryShareRoots`): a chapter is shareable
  // on its own and its public page is the same Memories body as a Memory's. A kind is shareable by bringing its share dialog.
  it('registers the Memories kinds into the real registry, both shareable', () => {
    expect(registry.kind(MEMORY_KIND)).toMatchObject({ supported: true, shareDialog: ShareDialog })
    expect(registry.kind(CHAPTER_KIND)).toMatchObject({ supported: true, shareDialog: ShareDialog })
  })

  // Depot, the second application (cr-files-application F0): its file kind, a READ / READ_WRITE share root with its own
  // page and link dialog (inbox #378), its names folded as file names.
  it('registers the Depot kind into the same registry, shareable, with the file-name folding', () => {
    expect(registry.kind(FILE_KIND)).toMatchObject({ supported: true, slug: fileNameSlug, detail: FileDetail, shareDialog: FileShareDialog })
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

  // The level view is core's (P4b) and draws ONE list (inbox #407): Memories' row factory serves both its kinds, Depot's serves
  // FILE and the drop box; each kind has its tab, in registration order (Memories first).
  it('gives the level view three row sources: Memories\' for MEMORY and CHAPTER, Depot\'s for FILE and the drop box, Expenses\' for the Pot', () => {
    // Memories names the grid, the list and its map as its presentations (inbox #370): core's level toolbar offers them.
    expect(registry.lists().map((l) => ({ ...l, views: l.views.map((v) => v.mode) }))).toEqual([
      { rows: memoryRows, views: ['grid', 'list', 'map'], kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { rows: depotRows, views: [], kinds: [FILE_KIND, DROPBOX_KIND] },
      { rows: potRows, views: [], kinds: [POT_KIND] },
    ])
  })

  it('gives the level view its tabs: Memories, Depot, Drop boxes, Expenses - the filter of the one list', () => {
    expect(registry.tabs().map((t) => ({ id: t.id, label: t.label, kinds: t.kinds }))).toEqual([
      { id: MEMORY_KIND, label: 'memory.memories', kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { id: FILE_KIND, label: 'depot.title', kinds: [FILE_KIND] },
      { id: DROPBOX_KIND, label: 'depot.dropBox.section', kinds: [DROPBOX_KIND] },
      { id: POT_KIND, label: 'expenses.title', kinds: [POT_KIND] },
    ])
  })

  it('lets Depot\'s uploads take what is dropped (files, a directory), and nothing else', () => {
    expect(registry.levelMenu().filter((e) => e.acceptsDrop).map((e) => e.id)).toEqual(['depot.upload', 'depot.uploadFolder'])
  })

  // Memories seals its item marks under the Master Key, so its hooks (with the required `onMasterKeyRotated`) must reach
  // the registry: this is the test that goes red when the composition root (or Memories' registration) stops registering
  // them, which rotateKeys.test.ts cannot see (it registers its own). A second registration of a registered id throws.
  it('registers the Memories lifecycle under the Memories application', () => {
    expect(() => registry.registerLifecycle('memories', { onMasterKeyRotated: async () => {} })).toThrow(/already registered/)
  })

  // The composition is whole: every kind an application registered belongs to exactly one registered application (its
  // switch, uc.platform.switch-application-off) and has its route decided by it - a slug claimed or "no segment" declared
  // (ADR 0015). A kind left out of either would be never switched off, or numbered among another application's plain names.
  // Two owners or two route claims of one kind throw at registration (`registry.test.ts`), so "at least one" here is
  // "exactly one".
  it('gives every registered kind but FOLDER exactly one application and one route decision', () => {
    const kinds = registry.kinds().filter((k) => k !== FOLDER_KIND)
    expect(kinds).toEqual(expect.arrayContaining([MEMORY_KIND, CHAPTER_KIND, FILE_KIND, DROPBOX_KIND, POT_KIND])) // the anchor
    const ids = registry.applications().map((a) => a.id)
    for (const kind of kinds) {
      const owner = registry.applicationOf(kind)
      expect(owner, `${kind}'s application`).not.toBeNull()
      expect(ids.filter((id) => id === owner), `${kind}'s application is registered once`).toHaveLength(1)
      expect(registry.routeDecided(kind), `${kind}'s route`).toBe(true)
    }
    expect(registry.applicationOf(FOLDER_KIND)).toBeNull() // core's own kind: no application, no claim
    expect(registry.routeDecided(FOLDER_KIND)).toBe(false)
  })

  it('is write-once end to end: registering the applications a second time throws', () => {
    expect(() => registerApplications()).toThrow(/already registered/)
  })
})
