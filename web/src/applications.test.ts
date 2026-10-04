// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { registerApplications } from './applications'
import { registry } from './core/applications/registry'
import { NodeKind } from './api/generated/content'

// `src/test/setup.ts` has already called registerApplications() once for this file, exactly as main.ts does.
describe('composition root', () => {
  it('registers the Memories kinds into the real registry', () => {
    expect(registry.kind(NodeKind.Memory)).toMatchObject({ supported: true, shareable: true })
    expect(registry.kind(NodeKind.Chapter)).toMatchObject({ supported: true, shareable: false })
  })

  it('is write-once end to end: registering the applications a second time throws', () => {
    expect(() => registerApplications()).toThrow(/already registered/)
  })
})
