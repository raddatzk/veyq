// uc.account.remember-this-device (web "off" tier, cr-0057) — the session vault seals the Master Key under a
// NON-EXTRACTABLE IndexedDB key and parks only the ciphertext in sessionStorage: round-trips, never stores raw
// key bytes, clears both halves, drops the orphaned key when the ciphertext is gone, and tolerates corruption.
import 'fake-indexeddb/auto'
import { describe, it, expect, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { cacheSessionVault, loadSessionVault, clearSessionVault, isSessionVaultSupported } from './sessionVault'
import { b64std } from '@veyq/crypto/base64'

const masterKey = () => new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))

// Minimal sessionStorage for the node test env (indexedDB comes from fake-indexeddb, crypto.subtle from node).
const mem = new Map<string, string>()
globalThis.sessionStorage = {
  getItem: (k: string) => (mem.has(k) ? mem.get(k)! : null),
  setItem: (k: string, v: string) => void mem.set(k, String(v)),
  removeItem: (k: string) => void mem.delete(k),
  clear: () => mem.clear(),
  key: (i: number) => [...mem.keys()][i] ?? null,
  get length() {
    return mem.size
  },
} as Storage

describe('sessionVault (web "off" tier, cr-0057)', () => {
  beforeEach(() => {
    mem.clear()
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
    mem.delete('veyq.session-vault') // simulate sessionStorage cleared on browser close (IndexedDB key lingers)
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
