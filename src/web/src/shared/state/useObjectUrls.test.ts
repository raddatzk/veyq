// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { computed, defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useObjectUrl, useObjectUrls } from './useObjectUrls'

// Track mint/revoke so tests can assert the URL is revoked exactly once, in order.
let seq = 0
let revoked: string[] = []
beforeEach(() => {
  seq = 0
  revoked = []
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:${seq++}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u) => void revoked.push(u as string))
})

/** Mount a component that runs `factory()` in its setup and exposes the result, so `onBeforeUnmount` fires on unmount. */
function harness<T>(factory: () => T) {
  const wrapper = mount(
    defineComponent({
      setup: (_, { expose }) => {
        const api = factory()
        expose({ api })
        return () => h('div')
      },
    }),
  )
  return { api: (wrapper.vm as unknown as { api: T }).api, wrapper }
}

const blob = () => new Blob(['x'])

describe('useObjectUrl', () => {
  it('mints on set and exposes the URL', () => {
    const { api } = harness(useObjectUrl)
    api.set(blob())
    expect(api.url.value).toBe('blob:0')
  })

  it('revokes the previous URL before minting the next', () => {
    const { api } = harness(useObjectUrl)
    api.set(blob())
    api.set(blob())
    expect(api.url.value).toBe('blob:1')
    expect(revoked).toEqual(['blob:0'])
  })

  it('set(null) / clear revoke and null out', () => {
    const { api } = harness(useObjectUrl)
    api.set(blob())
    api.clear()
    expect(api.url.value).toBeNull()
    expect(revoked).toEqual(['blob:0'])
    api.set(null) // no-op on an empty slot
    expect(revoked).toEqual(['blob:0'])
  })

  it('revokes the last URL on unmount', () => {
    const { api, wrapper } = harness(useObjectUrl)
    api.set(blob())
    expect(revoked).toEqual([])
    wrapper.unmount()
    expect(revoked).toEqual(['blob:0'])
  })
})

describe('useObjectUrls', () => {
  it('mints per key and reflects them in the exposed map', () => {
    const { api } = harness(useObjectUrls)
    api.set('a', blob())
    api.set('b', blob())
    expect(api.get('a')).toBe('blob:0')
    expect(api.urls.value.get('b')).toBe('blob:1')
    expect(api.urls.value.size).toBe(2)
  })

  it('revokes the replaced URL when a key is re-set', () => {
    const { api } = harness(useObjectUrls)
    api.set('a', blob())
    api.set('a', blob())
    expect(api.get('a')).toBe('blob:1')
    expect(revoked).toEqual(['blob:0'])
  })

  it('drop revokes and removes one key', () => {
    const { api } = harness(useObjectUrls)
    api.set('a', blob())
    api.set('b', blob())
    api.drop('a')
    expect(revoked).toEqual(['blob:0'])
    expect(api.get('a')).toBeUndefined()
    expect(api.urls.value.size).toBe(1)
  })

  it('clear revokes all and empties the map', () => {
    const { api } = harness(useObjectUrls)
    api.set('a', blob())
    api.set('b', blob())
    api.clear()
    expect(revoked).toEqual(['blob:0', 'blob:1'])
    expect(api.urls.value.size).toBe(0)
  })

  it('revokes all remaining URLs on unmount', () => {
    const { api, wrapper } = harness(useObjectUrls)
    api.set('a', blob())
    api.set('b', blob())
    expect(revoked).toEqual([])
    wrapper.unmount()
    expect(revoked).toEqual(['blob:0', 'blob:1'])
  })

  it('setMany mints every key in place, without copying the map, revokes each URL it replaces, and a reader sees it', async () => {
    const { api } = harness(useObjectUrls)
    api.set('a', blob())
    const before = api.urls.value
    const read = computed(() => api.urls.value.get('b') ?? null) // a reader that tracks one key, as a tile does
    expect(read.value).toBeNull()
    api.setMany([['a', blob()], ['b', blob()]])
    expect(api.urls.value).toBe(before) // the same map, changed in place: nothing copies thousands of entries per batch
    expect([...api.urls.value.keys()]).toEqual(['a', 'b'])
    expect(read.value).toBe('blob:2')
    expect(revoked).toEqual(['blob:0'])
  })

  it('adopt stores an externally-minted URL and takes over its lifecycle', () => {
    const { api, wrapper } = harness(useObjectUrls)
    api.adopt('a', 'blob:external')
    expect(api.get('a')).toBe('blob:external')
    wrapper.unmount() // the adopted URL is now owned here → revoked on unmount
    expect(revoked).toEqual(['blob:external'])
  })

  it('set after adopt revokes the adopted URL exactly once', () => {
    const { api } = harness(useObjectUrls)
    api.adopt('a', 'blob:donated') // e.g. an optimistic-preview donation
    api.set('a', blob()) // the real thumbnail supersedes it
    expect(api.get('a')).toBe('blob:0')
    expect(revoked).toEqual(['blob:donated'])
  })

  it('drop revokes an adopted URL', () => {
    const { api } = harness(useObjectUrls)
    api.adopt('a', 'blob:donated')
    api.drop('a')
    expect(revoked).toEqual(['blob:donated'])
    expect(api.get('a')).toBeUndefined()
  })

  it('adopting the same URL again is idempotent (no revoke)', () => {
    const { api } = harness(useObjectUrls)
    api.adopt('a', 'blob:donated')
    api.adopt('a', 'blob:donated')
    expect(revoked).toEqual([])
    expect(api.get('a')).toBe('blob:donated')
  })
})
