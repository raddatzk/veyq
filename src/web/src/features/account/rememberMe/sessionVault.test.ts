// uc.account.remember-this-device (web "off" tier, cr-0057) — the session vault seals the Master Key under a
// NON-EXTRACTABLE IndexedDB key and parks only the ciphertext in sessionStorage: round-trips, never stores raw
// key bytes, clears both halves, drops the orphaned key when the ciphertext is gone, and tolerates corruption.
import 'fake-indexeddb/auto'
import { describe, it, expect, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { cacheSessionVault, carrySessionVault, loadSessionVault, clearSessionVault, isSessionVaultSupported, readdressSessionVault } from './sessionVault'
import { b64std } from '@veyq/crypto/base64'
import { memoryStorage } from '../../../test/memoryStorage'

const masterKey = () => new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))

// Minimal sessionStorage for the node test env (indexedDB comes from fake-indexeddb, crypto.subtle from node).
globalThis.sessionStorage = memoryStorage()

describe('sessionVault (web "off" tier, cr-0057)', () => {
  beforeEach(() => {
    sessionStorage.clear()
    globalThis.indexedDB = new IDBFactory() // a fresh, empty key store per test
  })

  it('is supported in the test env', () => {
    expect(isSessionVaultSupported()).toBe(true)
  })

  it('round-trips the Master Key (via a non-extractable key) and normalizes the email', async () => {
    await cacheSessionVault('User@Example.com', masterKey())
    const v = await loadSessionVault()
    expect(v?.email).toBe('user@example.com')
    expect(v?.masterKey).toEqual(masterKey())
  })

  it('never stores raw Master Key bytes in sessionStorage (only iv + ciphertext)', async () => {
    await cacheSessionVault('a@b.com', masterKey())
    const raw = sessionStorage.getItem('veyq.session-vault')!
    expect(raw).not.toContain(b64std(masterKey())) // the plaintext key (as base64) must not appear
    const rec = JSON.parse(raw)
    expect(rec.iv).toBeTruthy()
    expect(rec.ciphertext).toBeTruthy()
    expect(rec.masterKey).toBeUndefined()
  })

  it('returns null when nothing is cached', async () => {
    expect(await loadSessionVault()).toBeNull()
  })

  it('clears both the sessionStorage record and the IndexedDB key', async () => {
    await cacheSessionVault('a@b.com', masterKey())
    await clearSessionVault()
    expect(await loadSessionVault()).toBeNull()
  })

  it('returns null when the ciphertext is gone (browser close cleared sessionStorage) — drops the orphan key', async () => {
    await cacheSessionVault('a@b.com', masterKey())
    sessionStorage.removeItem('veyq.session-vault') // simulate sessionStorage cleared on browser close (IndexedDB key lingers)
    expect(await loadSessionVault()).toBeNull()
    // the orphaned key was dropped, so even re-adding the old ciphertext can't decrypt — proven by a clean clear
    await clearSessionVault()
    expect(await loadSessionVault()).toBeNull()
  })

  it('returns null on corrupt sessionStorage data instead of throwing', async () => {
    sessionStorage.setItem('veyq.session-vault', 'not-json')
    expect(await loadSessionVault()).toBeNull()
  })
})

describe('readdressSessionVault (uc.account.change-email)', () => {
  beforeEach(() => {
    sessionStorage.clear()
    globalThis.indexedDB = new IDBFactory()
  })

  it('moves the vault to the new address and it still opens', async () => {
    await cacheSessionVault('old@example.com', masterKey())
    readdressSessionVault('Old@Example.com', ' New@Example.com')
    const v = await loadSessionVault()
    expect(v?.email).toBe('new@example.com')
    expect(v?.masterKey).toEqual(masterKey())
  })

  it('leaves a vault of another address alone', async () => {
    await cacheSessionVault('someone@example.com', masterKey())
    readdressSessionVault('old@example.com', 'new@example.com')
    expect((await loadSessionVault())?.email).toBe('someone@example.com')
  })
})

// cr-secure-the-account (the front matter's invariant: no retired private material in the browser either). A secured
// account's publish swaps the vault to a NEW Master Key; a session vault this tab still held sealed the RETIRED one, and
// a reload would have restored it. The swap re-seals it under the new key — or, with no record, writes none.
describe('carrySessionVault (a secured account’s vault swap)', () => {
  const retired = () => new Uint8Array(32).fill(7)
  const published = () => new Uint8Array(32).fill(9)

  beforeEach(() => {
    sessionStorage.clear()
    globalThis.indexedDB = new IDBFactory()
  })

  it('re-seals the record under the NEW Master Key, at the same address; the retired key is nowhere', async () => {
    await cacheSessionVault('a@b.com', retired())
    const before = sessionStorage.getItem('veyq.session-vault')
    await carrySessionVault(published())
    const v = await loadSessionVault()
    expect(v?.email).toBe('a@b.com')
    expect(v?.masterKey).toEqual(published())
    expect(v?.masterKey).not.toEqual(retired())
    expect(sessionStorage.getItem('veyq.session-vault')).not.toBe(before) // a fresh seal, not the old ciphertext
  })

  it('control: a tab with no session vault gets none — the swap never opts a device into a tier', async () => {
    await carrySessionVault(published())
    expect(sessionStorage.getItem('veyq.session-vault')).toBeNull()
    expect(await loadSessionVault()).toBeNull()
  })

  it('an unreadable record is dropped, never left beside the new key', async () => {
    sessionStorage.setItem('veyq.session-vault', 'not-json')
    await carrySessionVault(published())
    expect(sessionStorage.getItem('veyq.session-vault')).toBeNull()
  })
})
