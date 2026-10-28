// The identity-pin store's server side, for the tests that run the REAL `features/account/identityPins.ts` against a fake
// endpoint (ADR 0104): a head plus one sealed entry per pin, with the store's compare-and-set and keyset listing.
//
// The writer here is independent of the module on purpose: it seals the documented format (v1.json#identity_pin — the
// padded `{ u: userId, f: fingerprint, h: head, s: state, t: firstSeenAt }` entry at `HMAC(K_pinIndex, userId)`, the
// `{ version: 2, count, digest }` head at its revision), so a store "another device wrote" is the wire's, not the
// module's own idea of it, and `pinsOn` reads what the module wrote back the same way.
import { expect, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountIdentityPinsApi } from '../api/client'
import type { AccountIdentityPinsApi, IdentityPinEntry } from '../api/generated/account'
import { ENTRY_BYTES, type Pin } from '../features/account/identityPins'
import { fingerprintDigestB64 } from '../features/account/safetyCode'
import { httpError } from './httpError'
import { fakeApi } from './apiMock'

const enc = new TextEncoder()
const dec = new TextDecoder()
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!, (b) => parseInt(b, 16))

/** What the server holds: the sealed head (absent until the first write), its revision, and the entries by hex id. */
export type PinServerState = { blob: string | undefined; revision: number; entries: Map<string, string> }

/** A store never written. */
export const emptyPinStore = (): PinServerState => ({ blob: undefined, revision: 0, entries: new Map() })

/** A head that names no chain — for a pin whose head no test reads a chain for (32 bytes of 0x11, base64). */
export const SOME_HEAD = 'ERERERERERERERERERERERERERERERERERERERERERE='

/** A pin of a served identity bundle, as the store holds it — its fingerprint, not the bundle — at `head`. */
export const pinOf = (identityPublicKeyB64: string, state: Pin['state'] = 'pinned', firstSeenAt = 1, head = SOME_HEAD): Pin => ({
  fingerprint: fingerprintDigestB64(identityPublicKeyB64),
  head,
  state,
  firstSeenAt,
})

/** A real identity bundle (base64) — a pin is a fingerprint, so a test key must be one the codec decodes. */
export const identityKey = (): string => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))

/** The hex id `userId`'s pin is stored at under `mk`. */
export const pinIdOf = (mk: Uint8Array, userId: string): string =>
  hex(v1.identityPinId(v1.identityPinIndexKey(mk), userId))

/** One entry sealed in the documented format, for `userId`, at the hex id `at`. */
export function sealPinEntry(mk: Uint8Array, userId: string, pin: Pin, at = pinIdOf(mk, userId)): string {
  const body = enc.encode(JSON.stringify({ u: userId, f: pin.fingerprint, h: pin.head, s: pin.state, t: pin.firstSeenAt }))
  const padded = new Uint8Array(ENTRY_BYTES).fill(0x20)
  padded.set(body)
  return b64std(v1.seal({ purpose: 'identity-pin', pinId: unhex(at) }, mk, padded))
}

const digestOf = (entries: Map<string, string>) =>
  b64std(v1.identityPinSetDigest([...entries].map(([id, e]) => ({ pinId: unhex(id), entry: fromB64std(e) }))))

/** The head over `entries`, sealed for `revision`. */
export function sealPinHead(mk: Uint8Array, entries: Map<string, string>, revision: number): string {
  const json = JSON.stringify({ version: 2, count: entries.size, digest: digestOf(entries) })
  return b64std(v1.seal({ purpose: 'identity-pins', revision }, mk, enc.encode(json)))
}

/** A store as another device of the account wrote it: every pin an entry, and the head at `revision`. */
export function sealPinStore(mk: Uint8Array, pins: Record<string, Pin>, revision: number): PinServerState {
  const entries = new Map<string, string>()
  for (const [userId, pin] of Object.entries(pins)) entries.set(pinIdOf(mk, userId), sealPinEntry(mk, userId, pin))
  return { blob: sealPinHead(mk, entries, revision), revision, entries }
}

/** What the server holds, opened with the test's own key and checked against the documented format — every entry its
 *  padded length at the id its contact derives, the head counting and digesting exactly those entries. */
export function pinsOn(mk: Uint8Array, state: PinServerState): Record<string, Pin> {
  const out: Record<string, Pin> = {}
  for (const [id, entry] of state.entries) {
    const plain = v1.open({ purpose: 'identity-pin', pinId: unhex(id) }, mk, fromB64std(entry))
    expect(plain.length).toBe(ENTRY_BYTES)
    const { u: userId, f: fingerprint, h: head, s: state, t: firstSeenAt, ...rest } = JSON.parse(dec.decode(plain)) as {
      u: string; f: string; h: string; s: Pin['state']; t: number
    }
    expect(rest).toEqual({})
    expect(pinIdOf(mk, userId)).toBe(id)
    out[userId] = { fingerprint, head, state, firstSeenAt }
  }
  const head = JSON.parse(dec.decode(v1.open({ purpose: 'identity-pins', revision: state.revision }, mk, fromB64std(state.blob!))))
  expect(head).toEqual({ version: 2, count: state.entries.size, digest: digestOf(state.entries) })
  return out
}

/**
 * A fake pin-store endpoint with REAL optimistic concurrency and keyset paging: a write whose revision is not the stored
 * one is `409 stale_revision` and lands nothing; `replaceAll` clears first; the listing pages by hex id.
 */
export function fakePinServer(initial: PinServerState = emptyPinStore(), pageSize = 1000) {
  const state: PinServerState = { blob: initial.blob, revision: initial.revision, entries: new Map(initial.entries) }
  /** What the listing serves — the stored entries unless a test tampers with them (the server's lever). */
  let served = (): Map<string, string> => state.entries
  const stale = () => httpError(409, 'stale_revision')
  const getIdentityPins = vi.fn<AccountIdentityPinsApi['getIdentityPins']>(async () => ({ blob: state.blob, revision: state.revision }))
  const listIdentityPinEntries = vi.fn<AccountIdentityPinsApi['listIdentityPinEntries']>(async ({ after, limit } = {}) => {
    const all: IdentityPinEntry[] = [...served()].sort(([a], [b]) => (a < b ? -1 : 1)).map(([pinId, entry]) => ({ pinId, entry }))
    const from = all.filter((e) => after === undefined || e.pinId > after)
    const size = Math.min(limit ?? 1000, pageSize)
    return { entries: from.slice(0, size), more: from.length > size }
  })
  const land = (w: Parameters<AccountIdentityPinsApi['writeIdentityPins']>[0]['identityPinsWrite']) => {
    if (w.revision !== state.revision) throw stale()
    if (w.replaceAll) state.entries.clear()
    w.remove.forEach((id) => state.entries.delete(id))
    w.put.forEach((e) => state.entries.set(e.pinId, e.entry))
    state.blob = w.head
    state.revision += 1
    return { blob: state.blob, revision: state.revision }
  }
  const writeIdentityPins = vi.fn<AccountIdentityPinsApi['writeIdentityPins']>(async ({ identityPinsWrite }) => land(identityPinsWrite))
  fakeApi(accountIdentityPinsApi, { getIdentityPins, listIdentityPinEntries, writeIdentityPins })
  return {
    state,
    getIdentityPins,
    listIdentityPinEntries,
    writeIdentityPins,
    stale,
    /** The store's own write rule, for a test that routes a write elsewhere (the rotation's request). */
    land,
    /** Replace what the server holds — another device's write, a restore. */
    become(next: PinServerState) {
      Object.assign(state, { blob: next.blob, revision: next.revision, entries: new Map(next.entries) })
    },
    /** Serve a different entry set than the stored one — the head stays as it is. */
    tamper(f: (entries: Map<string, string>) => Map<string, string>) {
      served = () => f(new Map(state.entries))
    },
    /** Let another device's write land first: the next write meets it as a 409, then the server behaves. */
    racedBy(next: PinServerState) {
      let first = true
      writeIdentityPins.mockImplementation(async ({ identityPinsWrite }) => {
        if (first) {
          first = false
          Object.assign(state, { blob: next.blob, revision: next.revision, entries: new Map(next.entries) })
          throw stale()
        }
        return land(identityPinsWrite)
      })
    },
  }
}
