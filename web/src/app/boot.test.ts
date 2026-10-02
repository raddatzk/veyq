import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../test/httpError'

vi.mock('../features/account/instance/instance', () => ({ getSetupStatus: vi.fn() }))
// The refresh endpoint is stubbed at the client, not at `./sessionRefresh`: the real `refreshSession` →
// `isSessionRefusal` path has to run, or a swapped predicate inside it would pass unseen.
vi.mock('../api/client', () => ({
  tokenApi: vi.fn(),
  accountApi: vi.fn(),
  accountWrapsApi: vi.fn(),
  loginApi: vi.fn(),
  sessionApi: vi.fn(),
}))
// The DEV-only `?devlogin` opt-in reads `window`, which the node test env lacks; it decides nothing about the tiers.
vi.mock('./devAutoUnlock', () => ({ maybeDevAutoUnlock: vi.fn() }))

import { boot } from './boot'
import { getSetupStatus } from '../features/account/instance/instance'
import { tokenApi } from '../api/client'
import { fakeApi } from '../test/apiMock'
import { serverState } from '../shared/state/serverState'
import { EXPECTED_MAJOR } from '../shared/state/capabilities'
import { idbRun } from '../shared/idb'
import { REMEMBER_KEY } from './convenienceTiers'
import { isRemembered } from '../features/account/rememberMe/deviceKeyWrap'
import { cacheSessionVault, loadSessionVault } from '../features/account/rememberMe/sessionVault'

beforeEach(() => {
  vi.clearAllMocks()
  serverState.suspended = false
  serverState.unreachable = false
})

// Boot's one question to the instance is its public setup route, which a suspended tenant's own domain refuses
// like every API call (uc.spaces.scope-access A7): that is the offline notice, not "unreachable".
describe('boot on a suspended tenant’s own domain', () => {
  it('a 403 tenant_suspended marks the domain suspended, not unreachable', async () => {
    vi.mocked(getSetupStatus).mockRejectedValue(httpError(403, 'tenant_suspended'))
    await boot()
    expect(serverState.suspended).toBe(true)
    expect(serverState.unreachable).toBe(false)
  })

  it('control: a failure without that code is still "unreachable"', async () => {
    vi.mocked(getSetupStatus).mockRejectedValue(new TypeError('Failed to fetch'))
    await boot()
    expect(serverState.unreachable).toBe(true)
    expect(serverState.suspended).toBe(false)
  })
})

// A set-up instance whose refresh endpoint answers `failure` — the remembered-session branch of boot.
function refreshAnswers(failure: unknown) {
  vi.mocked(getSetupStatus).mockResolvedValue({
    setupRequired: false,
    capabilities: [],
    version: { major: EXPECTED_MAJOR, build: 'test' },
  } as never)
  fakeApi(tokenApi, { refreshSession: () => Promise.reject(failure) })
}

/** A minimal in-memory `Storage`, fresh per call. */
function memoryStorage(): Storage {
  const mem = new Map<string, string>()
  return {
    getItem: (k: string) => mem.get(k) ?? null,
    setItem: (k: string, v: string) => void mem.set(k, String(v)),
    removeItem: (k: string) => void mem.delete(k),
    clear: () => mem.clear(),
    key: (i: number) => [...mem.keys()][i] ?? null,
    get length() {
      return mem.size
    },
  }
}

/** Both local convenience tiers as real stored state: the remember-me record in IndexedDB and a session vault. */
async function seedBothTiers() {
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  await idbRun(REMEMBER_KEY.db, REMEMBER_KEY.store, 'readwrite', (s) =>
    s.put({ email: 'a@b.com', key, wrapId: 'wrap-1' }, REMEMBER_KEY.record),
  )
  await cacheSessionVault('a@b.com', new Uint8Array(32).fill(7))
  // The seed itself is read back, so a drop below is told apart from a seed that never landed.
  expect(await isRemembered()).toBe(true)
  expect(await loadSessionVault()).not.toBeNull()
}

// Only a refusal of the session drops the device's convenience tiers at boot (ADR 0070 Decision 3,
// uc.account.sign-out E2). A boot that got no answer about the session keeps them and says so.
describe('boot keeps the convenience tiers without a refusal', () => {
  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory() // a fresh, empty local store per test
    vi.stubGlobal('sessionStorage', memoryStorage()) // the node test env has none
    await seedBothTiers()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each([
    ['a 503', httpError(503)],
    ['a 502', httpError(502)],
    ['a 500', httpError(500)],
    ['a network failure', new TypeError('Failed to fetch')],
    ['a 408', httpError(408)],
    ['a 429', httpError(429)],
  ])('%s keeps both tiers and marks the instance unreachable', async (_, failure) => {
    refreshAnswers(failure)
    await boot()
    expect(await isRemembered()).toBe(true)
    expect(await loadSessionVault()).not.toBeNull()
    expect(serverState.unreachable).toBe(true)
    expect(serverState.suspended).toBe(false)
  })

  it('a 403 tenant_suspended keeps both tiers and marks the domain suspended', async () => {
    refreshAnswers(httpError(403, 'tenant_suspended'))
    await boot()
    expect(await isRemembered()).toBe(true)
    expect(await loadSessionVault()).not.toBeNull()
    expect(serverState.suspended).toBe(true)
    expect(serverState.unreachable).toBe(false)
  })

  // Control: the harness can see a drop at all — a refusal drops both tiers, and is not "unreachable".
  it.each([
    ['a 401', httpError(401)],
    ['a 400', httpError(400)],
    ['a 403 without a code', httpError(403)],
    ['a 404', httpError(404)],
  ])('control: %s is a refusal and drops both tiers', async (_, failure) => {
    refreshAnswers(failure)
    await boot()
    expect(await isRemembered()).toBe(false)
    expect(await loadSessionVault()).toBeNull()
    expect(serverState.unreachable).toBe(false)
    expect(serverState.suspended).toBe(false)
  })
})
