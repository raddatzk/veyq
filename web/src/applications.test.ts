// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { registerApplications } from './applications'
import { registry } from './core/applications/registry'
import { MEMORY_KIND, CHAPTER_KIND } from './features/memory/kinds'
import { favoritesStore } from './features/memory/favoritesStore'
import UploadStatusMenu from './features/memory/components/UploadStatusMenu.vue'
import PinnedMemories from './features/memory/components/PinnedMemories.vue'

// `src/test/setup.ts` has already called registerApplications() once for this file, exactly as main.ts does.
describe('composition root', () => {
  // Both Memories kinds are share roots (ADR 0080 Decision 13, the backend's `MemoryShareRoots`): a chapter is shareable
  // on its own and its public page is the same Memories body as a Memory's.
  it('registers the Memories kinds into the real registry, both shareable', () => {
    expect(registry.kind(MEMORY_KIND)).toMatchObject({ supported: true, shareable: true })
    expect(registry.kind(CHAPTER_KIND)).toMatchObject({ supported: true, shareable: true })
  })

  it('claims the one Memories share body for both root kinds', () => {
    expect(registry.shareBody(MEMORY_KIND)).not.toBeNull()
    expect(registry.shareBody(CHAPTER_KIND)).toBe(registry.shareBody(MEMORY_KIND))
  })

  // The tray and the pins are list slots (P4a): Memories adds one item to each, under an id namespaced by the application.
  it('adds the Memories upload status to the top-bar tray and the pinned Memories to the sidebar pins', () => {
    expect(registry.slotItems('topbarTray')).toEqual([{ id: 'memories.upload-status', component: UploadStatusMenu }])
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
