// `lazyHook` (ADR 0079 Consequences): a registered hook whose module is fetched on its first call, not at registration.
import { describe, it, expect, vi } from 'vitest'
import { lazyHook } from './lazy'

describe('lazyHook', () => {
  it('loads nothing when registered, and the hook it loads on each call, with the call\'s arguments', async () => {
    const real = vi.fn(async (a: number, b: string) => `${a}${b}`)
    const load = vi.fn(async () => real)
    const hook = lazyHook(load)
    expect(load).not.toHaveBeenCalled()
    await expect(hook(1, 'x')).resolves.toBe('1x')
    await expect(hook(2, 'y')).resolves.toBe('2y')
    expect(real.mock.calls).toEqual([[1, 'x'], [2, 'y']])
    await expect(hook.load()).resolves.toBe(real)
  })

  it('rejects the call when its module does not load, as the hook\'s own failure would', async () => {
    const hook = lazyHook(async (): Promise<() => Promise<void>> => {
      throw new TypeError('Failed to fetch dynamically imported module')
    })
    await expect(hook()).rejects.toThrow('Failed to fetch dynamically imported module')
  })

  it('rejects with the hook\'s own failure', async () => {
    const hook = lazyHook(async () => async () => {
      throw new Error('stale_epoch')
    })
    await expect(hook()).rejects.toThrow('stale_epoch')
  })
})
