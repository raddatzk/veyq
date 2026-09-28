// The device-local trust-state marks (ADR 0028 Decision 7) as a store: what one account's marks are, that they only
// ever rise, and that no account ever reads another's. The refusals they drive are exercised where the documents
// are read — identityPins.test.ts, deviceRegister.test.ts, scopes.test.ts and onboardTail.test.ts.
import 'fake-indexeddb/auto'
import { describe, it, expect, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import * as v1 from '@veyq/crypto/v1'
import { carryMarks, raiseMarks, readMarks } from './trustMarks'

const A = v1.randomBytes(32)
const B = v1.randomBytes(32)

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

describe('trust-state marks', () => {
  it('a device with nothing held reads zero everywhere', async () => {
    expect(await readMarks(A)).toEqual({ pins: 0, register: 0, epochs: {} })
  })

  it('a raise is a maximum — a later, lower raise never lowers a mark', async () => {
    await raiseMarks(A, { pins: 5, register: 2, epochs: { s1: 3 } })
    await raiseMarks(A, { pins: 3, register: 4, epochs: { s1: 1, s2: 2 } })
    expect(await readMarks(A)).toEqual({ pins: 5, register: 4, epochs: { s1: 3, s2: 2 } })
  })

  it('raises racing each other cannot lower a mark — each read-and-write is one transaction', async () => {
    await Promise.all([7, 3, 9, 5, 1].map((pins) => raiseMarks(A, { pins })))
    expect((await readMarks(A)).pins).toBe(9)
  })

  it('two accounts on one browser never read each other’s marks', async () => {
    await raiseMarks(A, { pins: 5, epochs: { s1: 2 } })
    expect(await readMarks(B)).toEqual({ pins: 0, register: 0, epochs: {} })
    expect((await readMarks(A)).pins).toBe(5) // control: the account that raised them reads them
  })

  it('carries an account’s marks to its new key, merging with what that key already holds', async () => {
    await raiseMarks(A, { pins: 5, register: 1, epochs: { s1: 2 } })
    await raiseMarks(B, { pins: 6, epochs: { s2: 1 } })
    await carryMarks(A, B)
    expect(await readMarks(B)).toEqual({ pins: 6, register: 1, epochs: { s1: 2, s2: 1 } })
  })

  it('an epoch-0 floor is not stored — it refuses nothing, and would only grow what pairing carries', async () => {
    await raiseMarks(A, { epochs: { unrotated: 0, rotated: 1 } })
    expect((await readMarks(A)).epochs).toEqual({ rotated: 1 })
  })

  it('a value that is not a mark is ignored, never stored', async () => {
    await raiseMarks(A, { pins: -1, register: 1.5, epochs: { s1: Number.NaN, s2: 2 } })
    expect(await readMarks(A)).toEqual({ pins: 0, register: 0, epochs: { s2: 2 } })
  })

  it('a browser without IndexedDB holds no marks — and neither read nor raise throws', async () => {
    const saved = globalThis.indexedDB
    // @ts-expect-error — the property is removed to model a browser that has none
    delete globalThis.indexedDB
    try {
      await expect(raiseMarks(A, { pins: 5 })).resolves.toBeUndefined()
      expect(await readMarks(A)).toEqual({ pins: 0, register: 0, epochs: {} })
    } finally {
      globalThis.indexedDB = saved
    }
  })
})
