// restoreVaultFromCache (cr-0055/cr-0057): boot-restore from the "off" session vault — opens the vault when the
// cached Master Key fits the served material, and DROPS the stale cache (falls back to the floor) when it
// doesn't, so a reload can't keep retrying a broken vault. Regression guard for the review finding.
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'

vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  accountWrapsApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { restoreVaultFromCache } from './unlock'
import { accountApi } from '../../../api/client'
import { cacheSessionVault, loadSessionVault } from '../rememberMe/sessionVault'
import { isUnlocked, clearVault } from '../../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

// Minimal sessionStorage for the node test env (indexedDB from fake-indexeddb, crypto.subtle from node).
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

/** Server key material whose identity key is wrapped under [masterKey] (a PASSWORD wrap rounds out minItems). */
function materialFor(masterKey: Uint8Array) {
  const id = v1.generateIdentity()
  const wrappedPriv = v1.symWrap(masterKey, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))
  return {
    suiteId: 'v1',
    wraps: [
      { method: 'PASSWORD', wrappedKey: b64std(v1.randomBytes(60)), kdfParams: b64std(v1.randomBytes(40)) },
    ],
    wrappedIdentityPrivateKey: b64std(wrappedPriv),
  }
}

function serve(material: unknown) {
  vi.mocked(accountApi).mockReturnValue({
    getAccountKeyMaterial: () => Promise.resolve(material),
  } as unknown as ReturnType<typeof accountApi>)
}

describe('restoreVaultFromCache — session vault (cr-0055)', () => {
  beforeEach(() => {
    clearVault()
    mem.clear()
    globalThis.indexedDB = new IDBFactory()
    vi.clearAllMocks()
  })

  it('opens the vault from the session vault when the cached Master Key fits the material', async () => {
    const masterKey = v1.randomBytes(32)
    serve(materialFor(masterKey))
    await cacheSessionVault('user@example.com', masterKey)

    expect(await restoreVaultFromCache()).toBe(true)
    expect(isUnlocked()).toBe(true)
  })

  it('drops the stale session vault and falls back when the cached Master Key no longer fits', async () => {
    serve(materialFor(v1.randomBytes(32))) // material wrapped under a DIFFERENT key than the cache holds
    await cacheSessionVault('user@example.com', v1.randomBytes(32))

    expect(await restoreVaultFromCache()).toBe(false)
    expect(isUnlocked()).toBe(false)
    expect(await loadSessionVault()).toBeNull() // cleared, so a reload won't keep retrying the broken vault
  })
})
