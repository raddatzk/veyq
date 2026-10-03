// uc.account.onboard-device E6 on the refresh path: a session refused while a pairing grant runs ends in the sign-in
// page, and that page must be told why — the panel that would have said so is gone with the old document. The ending
// and the refresh run for real (their has-a-session check is half of what is asserted); only the refresh endpoint and
// the two steps that reach outside the page are stubbed. The notice is read the way the sign-in page reads it.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../test/httpError'
import { fakeApi } from '../test/apiMock'
import { memoryStorage } from '../test/memoryStorage'

vi.mock('../api/client', () => ({ tokenApi: vi.fn() }))
vi.mock('./convenienceTiers', () => ({ dropConvenienceTiers: vi.fn() }))
vi.mock('../sw/register', () => ({ endAllPlaybacks: vi.fn() }))

const replace = vi.fn()

/** A fresh module graph per test: the ending runs at most once per document, so its latch must not leak. */
async function freshTab(opts: { holdsSession: boolean }) {
  vi.resetModules()
  const refresh = await import('./sessionRefresh')
  const notice = await import('./endingNotice')
  const { session } = await import('./session')
  const { tokenApi } = await import('../api/client')
  if (opts.holdsSession) session.accessToken = 'a1'
  // The delegation's own 401, then the refresh it triggers, refused: the session really is over.
  fakeApi(tokenApi, { refreshSession: () => Promise.reject(httpError(401)) })
  const refusedCall = () => refresh.withRefresh(() => Promise.reject(httpError(401, 'authentication_failed')))
  return { ...notice, refusedCall }
}

beforeEach(() => {
  replace.mockClear()
  vi.stubGlobal('location', { replace })
  vi.stubGlobal('BroadcastChannel', undefined)
  vi.stubGlobal('sessionStorage', memoryStorage())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('a session refused mid-grant tells the sign-in page (uc.account.onboard-device E6)', () => {
  it('a refusal under the grant’s explanation ends the session and leaves that explanation for the sign-in page', async () => {
    const tab = await freshTab({ holdsSession: true })

    await expect(tab.explainEnding('grantInterrupted', tab.refusedCall)).rejects.toThrow('http 401')

    expect(replace).toHaveBeenCalledExactlyOnceWith('/login')
    expect(tab.takeEndingNotice()).toBe('grantInterrupted')
    // Read once: a second sign-in page in this tab says nothing.
    expect(tab.takeEndingNotice()).toBeNull()
  })

  it('the same refusal outside a grant ends the session and explains nothing', async () => {
    // The control: the notice comes from the explanation, not from the refusal.
    const tab = await freshTab({ holdsSession: true })

    await expect(tab.refusedCall()).rejects.toThrow('http 401')

    expect(replace).toHaveBeenCalledExactlyOnceWith('/login')
    expect(tab.takeEndingNotice()).toBeNull()
  })

  it('a refusal in a tab that holds no session runs no ending, so it leaves no notice for a later sign-in page', async () => {
    const tab = await freshTab({ holdsSession: false })

    await expect(tab.explainEnding('grantInterrupted', tab.refusedCall)).rejects.toThrow('http 401')

    expect(replace).not.toHaveBeenCalled()
    expect(tab.takeEndingNotice()).toBeNull()
  })

  it('the explanation holds only while its call runs', async () => {
    const tab = await freshTab({ holdsSession: true })

    await tab.explainEnding('grantInterrupted', async () => expect(tab.endingExplanation()).toBe('grantInterrupted'))

    expect(tab.endingExplanation()).toBeNull()
    await expect(tab.refusedCall()).rejects.toThrow('http 401')
    expect(tab.takeEndingNotice()).toBeNull()
  })
})
