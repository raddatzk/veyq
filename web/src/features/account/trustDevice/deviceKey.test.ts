// uc.account.trust-device — the local trust records, keyed by the normalised email. Only what the sign-in screen
// reads of them without an email in hand is pinned here (inbox #450): which accounts this device trusts.
import 'fake-indexeddb/auto'
import { describe, it, expect, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { saveTrust, clearTrust, listTrustedEmails } from './deviceKey'

const record = { cryptoKey: {} as CryptoKey, wrapId: 'wrap-1' }

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory() // a fresh, empty local store per test
})

describe('listTrustedEmails', () => {
  it('lists each trusted account once, by its normalised email, and drops one that is untrusted', async () => {
    expect(await listTrustedEmails()).toEqual([])
    await saveTrust('A@Example.com', record)
    await saveTrust('a@example.com ', record)
    await saveTrust('b@example.com', record)
    expect((await listTrustedEmails()).sort()).toEqual(['a@example.com', 'b@example.com'])
    await clearTrust('b@example.com')
    expect(await listTrustedEmails()).toEqual(['a@example.com'])
  })
})
