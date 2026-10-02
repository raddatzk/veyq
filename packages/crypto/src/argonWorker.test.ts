import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'

import { deriveAuthKeypairAsync, unlockKekAsync } from './argonWorker'
import * as v1 from './v1'
import { TEST_ARGON } from './testArgon'

// In the test env there is no Worker global, so the async wrappers take the synchronous fallback. These
// assert the fallback is byte-identical to the conformance-pinned suite (the worker runs the SAME v1 code).
//
// Both sides are given TEST_ARGON explicitly (see src/test/argon.ts) — the assertion is byte-IDENTITY between
// two code paths, which holds at any cost params, and the params are the wrapper's own pass-through argument.
// It also closes a gap the production-params version had: a wrapper that dropped `params` on the way to the
// fallback would still match when both sides silently defaulted to v1.ARGON. Here it would not.
describe('argonWorker sync fallback', () => {
  // The designed node path (no Worker global at all) is not a failure, so it says nothing.
  beforeEach(() => {
    vi.spyOn(console, 'warn')
  })
  afterEach(() => {
    // Restore BEFORE asserting: a failed expect would otherwise leave the spy armed for the next describe.
    const warnings = vi.mocked(console.warn).mock.calls
    vi.restoreAllMocks()
    expect(warnings).toEqual([])
  })

  it('deriveAuthKeypairAsync matches v1.deriveAuthKeypair', async () => {
    const salt = new Uint8Array(16).fill(5)
    const got = await deriveAuthKeypairAsync('correct horse battery', salt, TEST_ARGON)
    const expected = v1.deriveAuthKeypair('correct horse battery', salt, TEST_ARGON)
    expect(got.publicKey).toEqual(expected.publicKey)
    expect(got.privateKey).toEqual(expected.privateKey)
  })

  it('unlockKekAsync matches v1.kekPasswordSecret', async () => {
    const secretKey = new Uint8Array(32).fill(9)
    const salt = new Uint8Array(16).fill(7)
    const got = await unlockKekAsync(secretKey, 'pw', salt, TEST_ARGON)
    expect(got).toEqual(v1.kekPasswordSecret(secretKey, 'pw', salt, TEST_ARGON))
  })
})

describe('argonWorker graceful degradation', () => {
  const WARNING = '[veyq] Argon2 worker unavailable — deriving on the page thread'

  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  it('finishes on the sync path when the worker errors, and says so exactly once', async () => {
    // A Worker that "dies" on each postMessage by firing its error listener — the wrapper must then resolve every
    // request on the synchronous fallback, byte-identical to the worker result. Two requests are in flight when it
    // dies, so the error listener runs twice: the warning is once per page, not once per failure event.
    class DyingWorker {
      private handlers: Record<string, Array<(e: unknown) => void>> = {}
      addEventListener(type: string, cb: (e: unknown) => void): void {
        ;(this.handlers[type] ??= []).push(cb)
      }
      postMessage(): void {
        queueMicrotask(() => this.handlers['error']?.forEach((cb) => cb({})))
      }
      terminate(): void {}
    }
    vi.resetModules()
    vi.stubGlobal('Worker', DyingWorker as unknown as typeof Worker)
    const mod = await import('./argonWorker')
    const fresh = await import('./v1')

    const salt = new Uint8Array(16).fill(5)
    const [got, again] = await Promise.all([
      mod.deriveAuthKeypairAsync('correct horse battery', salt, TEST_ARGON),
      mod.deriveAuthKeypairAsync('correct horse battery', salt, TEST_ARGON),
    ])

    const expected = fresh.deriveAuthKeypair('correct horse battery', salt, TEST_ARGON).publicKey
    expect(got.publicKey).toEqual(expected)
    expect(again.publicKey).toEqual(expected)
    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(WARNING)
  })

  it('finishes on the sync path when the Worker cannot be constructed, and says so once', async () => {
    // A refused worker script can surface as a constructor throw rather than an `error` event.
    class UnbuildableWorker {
      constructor() {
        throw new DOMException('refused', 'SecurityError')
      }
    }
    vi.resetModules()
    vi.stubGlobal('Worker', UnbuildableWorker as unknown as typeof Worker)
    const mod = await import('./argonWorker')
    const fresh = await import('./v1')

    const secretKey = new Uint8Array(32).fill(9)
    const salt = new Uint8Array(16).fill(7)
    const got = await mod.unlockKekAsync(secretKey, 'pw', salt, TEST_ARGON)
    const again = await mod.unlockKekAsync(secretKey, 'pw', salt, TEST_ARGON)

    const expected = fresh.kekPasswordSecret(secretKey, 'pw', salt, TEST_ARGON)
    expect(got).toEqual(expected)
    expect(again).toEqual(expected)
    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(WARNING)
  })
})
