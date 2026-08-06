import { describe, it, expect, vi, afterEach } from 'vitest'

import { deriveAuthKeypairAsync, unlockKekAsync } from './argonWorker'
import * as v1 from './v1'
import { TEST_ARGON } from '../../test/argon'

// In the test env there is no Worker global, so the async wrappers take the synchronous fallback. These
// assert the fallback is byte-identical to the conformance-pinned suite (the worker runs the SAME v1 code).
//
// Both sides are given TEST_ARGON explicitly (see src/test/argon.ts) — the assertion is byte-IDENTITY between
// two code paths, which holds at any cost params, and the params are the wrapper's own pass-through argument.
// It also closes a gap the production-params version had: a wrapper that dropped `params` on the way to the
// fallback would still match when both sides silently defaulted to v1.ARGON. Here it would not.
describe('argonWorker sync fallback', () => {
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
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  it('finishes on the sync path when the worker errors instead of hanging or failing', async () => {
    // A Worker that "dies" on its first postMessage by firing its error listener — the wrapper must then
    // resolve the request on the synchronous fallback, byte-identical to the worker result.
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
    const got = await mod.deriveAuthKeypairAsync('correct horse battery', salt, TEST_ARGON)

    expect(got.publicKey).toEqual(fresh.deriveAuthKeypair('correct horse battery', salt, TEST_ARGON).publicKey)
  })
})
