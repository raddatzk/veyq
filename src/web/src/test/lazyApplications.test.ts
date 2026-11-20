// @vitest-environment happy-dom
// The test setup loads every lazy component the registry holds (`lazyApplications.ts`), so a test that mounts the shell
// draws an application's page at once, as it did while the composition root imported them statically.
import { describe, it, expect } from 'vitest'
import { registry } from '../core/applications/registry'
import { registeredComponents } from './lazyApplications'

type AsyncWrapper = { __asyncLoader?: () => Promise<unknown>; __asyncResolved?: unknown }
const isLazy = (v: unknown): v is AsyncWrapper => typeof (v as AsyncWrapper | null)?.__asyncLoader === 'function'

describe('the lazy applications in the test setup', () => {
  it('finds every lazy component a kind\'s descriptor holds', () => {
    const found = new Set(registeredComponents(registry))
    const lazy = registry.kinds().flatMap((kind) => {
      const d = registry.kind(kind)
      return d.supported ? Object.values(d).filter(isLazy) : []
    })
    expect(lazy.length).toBeGreaterThan(0) // the applications' pages are lazy: a walk that found none proves nothing
    for (const c of lazy) expect(found.has(c)).toBe(true)
  })

  it('has every one of them loaded before a test runs', () => {
    const lazy = registeredComponents(registry).filter(isLazy)
    expect(lazy.length).toBeGreaterThan(0)
    for (const c of lazy) expect(c.__asyncResolved).toBeTruthy()
  })
})
