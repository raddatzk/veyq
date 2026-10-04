// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { registerApplications } from './applications'
import { registry } from './core/applications/registry'
import { NodeKind } from './api/generated/content'
import { favoritesStore } from './features/memory/favoritesStore'

// `src/test/setup.ts` has already called registerApplications() once for this file, exactly as main.ts does.
describe('composition root', () => {
  it('registers the Memories kinds into the real registry', () => {
    expect(registry.kind(NodeKind.Memory)).toMatchObject({ supported: true, shareable: true })
    expect(registry.kind(NodeKind.Chapter)).toMatchObject({ supported: true, shareable: false })
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
