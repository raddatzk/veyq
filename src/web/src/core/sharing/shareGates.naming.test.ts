// @vitest-environment happy-dom
// The page's ONE naming flow with what a WRITE_ONLY page's name makes (inbox #1090, ADR 0154 decision 1): Depot's drop page
// and Memories' uploader make their own identity (an uploader's own `K_up`), so they hand `requestGuest` a `make`; the
// shell's dialog asks, its save runs `make` instead of the link's own naming, and the request resolves with what `make`
// made — null where the guest cancelled. A saved session does not answer such a request (the page asks only when it holds
// none it can write with); without `make` it still does (the control).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('./guestIdentity', async (importOriginal) => ({ ...(await importOriginal<typeof import('./guestIdentity')>()), nameShareGuest: vi.fn() }))

import { useShareGates } from './shareGates'
import { nameShareGuest, saveGuestSession, type GuestIdentity } from './guestIdentity'

const ANNA: GuestIdentity = { identityId: 'id-anna', contributorToken: 'tok-anna', displayName: 'Anna' }
const UPLOADER: GuestIdentity = { identityId: 'id-up', contributorToken: 'tok-up', displayName: 'Ada' }

const gatesOf = () => useShareGates({ at: 's1', fragmentKey: () => 'k', handedPassword: () => null, locale: ref('en') })

beforeEach(() => {
  localStorage.clear()
  vi.mocked(nameShareGuest).mockReset()
})

describe('requestGuest with what the name makes (a WRITE_ONLY page, inbox #1090)', () => {
  it('asks in the dialog; its save runs `make`, not the link\'s own naming; the request resolves with what it made', async () => {
    const g = gatesOf()
    const make = vi.fn(async () => UPLOADER)
    const asked = g.requestGuest(make)
    expect(g.naming.value, 'the dialog asks').toBe(true)
    await g.nameGuest('Ada')
    expect(make).toHaveBeenCalledWith('Ada')
    expect(nameShareGuest).not.toHaveBeenCalled()
    g.onNamingClose() // the dialog closes once named
    await expect(asked).resolves.toBe(UPLOADER)
    expect(g.naming.value).toBe(false)
  })

  it('cancelled, it resolves with null and nothing is made', async () => {
    const g = gatesOf()
    const make = vi.fn(async () => UPLOADER)
    const asked = g.requestGuest(make)
    g.onNamingClose()
    await expect(asked).resolves.toBeNull()
    expect(make).not.toHaveBeenCalled()
  })

  it('asks even where the device holds a session (the page asks only when it has none it can write with)', async () => {
    saveGuestSession('s1', ANNA)
    const g = gatesOf()
    void g.requestGuest(async () => UPLOADER)
    expect(g.naming.value).toBe(true)
  })

  it('control: without `make` a saved session answers at once, and the dialog never opens', async () => {
    saveGuestSession('s1', ANNA)
    const g = gatesOf()
    await expect(g.requestGuest()).resolves.toEqual(ANNA)
    expect(g.naming.value).toBe(false)
  })

  it('one question at a time: a second request while the dialog asks waits on the same answer', async () => {
    const g = gatesOf()
    const make = vi.fn(async () => UPLOADER)
    const first = g.requestGuest(make)
    const second = g.requestGuest(make)
    await g.nameGuest('Ada')
    g.onNamingClose()
    await expect(first).resolves.toBe(UPLOADER)
    await expect(second).resolves.toBe(UPLOADER)
    expect(make).toHaveBeenCalledOnce()
  })

  it('a `make` that made nothing (the link gone at the name) closes the question with null', async () => {
    const g = gatesOf()
    const asked = g.requestGuest(async () => null)
    await g.nameGuest('Ada')
    g.onNamingClose()
    await expect(asked).resolves.toBeNull()
  })
})
