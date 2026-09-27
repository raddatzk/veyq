// uc.account.sign-out § The ending (ADR 0070). What jsdom-free unit tests CAN pin: the order of the four steps,
// that a tab holding no session does nothing, that a received announcement ends this tab without echoing it or
// calling the server, and that the last step is a document load. What they cannot — that the new document really
// takes every decrypted store with it, in a real browser with a real service worker — is the CR's live check.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const { dropConvenienceTiers, endAllPlaybacks } = vi.hoisted(() => ({
  dropConvenienceTiers: vi.fn(),
  endAllPlaybacks: vi.fn(),
}))
vi.mock('./convenienceTiers', () => ({ dropConvenienceTiers }))
vi.mock('../sw/register', () => ({ endAllPlaybacks }))

/**
 * A same-process stand-in for `BroadcastChannel`: a message reaches every OTHER open instance of the same name,
 * asynchronously, and never the sender — the two properties of the real one the no-echo test rests on. It also
 * records who posted what, which the real one cannot tell a test.
 */
class FakeChannel {
  static open: FakeChannel[] = []
  static posted: { from: FakeChannel; data: unknown }[] = []
  onmessage: ((e: MessageEvent) => void) | null = null
  constructor(readonly name: string) {
    FakeChannel.open.push(this)
  }
  postMessage(data: unknown) {
    FakeChannel.posted.push({ from: this, data })
    for (const other of FakeChannel.open) {
      if (other !== this && other.name === this.name) queueMicrotask(() => other.onmessage?.({ data } as MessageEvent))
    }
  }
  close() {
    FakeChannel.open = FakeChannel.open.filter((c) => c !== this)
  }
}

const replace = vi.fn()
const fetchSpy = vi.fn()
const order: string[] = []

/** A fresh module graph per test: the ending runs at most once per document, so its latch must not leak. */
async function freshTab() {
  vi.resetModules()
  const end = await import('./sessionEnd')
  const { session } = await import('./session')
  const { vaultState } = await import('./vault')
  return { ...end, session, vaultState }
}

beforeEach(() => {
  vi.clearAllMocks()
  order.length = 0
  FakeChannel.open = []
  FakeChannel.posted = []
  vi.stubGlobal('BroadcastChannel', FakeChannel)
  vi.stubGlobal('location', { replace })
  vi.stubGlobal('fetch', fetchSpy)
  dropConvenienceTiers.mockImplementation(async () => void order.push('tiers'))
  endAllPlaybacks.mockImplementation(async () => void order.push('service-worker'))
  replace.mockImplementation(() => void order.push('document-load'))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the session ending (uc.account.sign-out, ADR 0070)', () => {
  it('drops the tiers, then the playbacks, then announces, then loads the sign-in page', async () => {
    const tab = await freshTab()
    tab.session.accessToken = 'a1'
    // The announcement is recorded where it is posted, so it lands in the same sequence as the other steps.
    const post = FakeChannel.prototype.postMessage
    vi.spyOn(FakeChannel.prototype, 'postMessage').mockImplementation(function (this: FakeChannel, data: unknown) {
      order.push('announce')
      post.call(this, data)
    })

    await tab.endSession()

    expect(order).toEqual(['tiers', 'service-worker', 'announce', 'document-load'])
    expect(FakeChannel.posted.map((p) => p.data)).toEqual([{ type: 'ended' }])
  })

  it('ends a tab whose vault is unlocked even when it holds no access token', async () => {
    const tab = await freshTab()
    tab.vaultState.unlocked = true

    await tab.endSession()

    expect(replace).toHaveBeenCalledTimes(1)
  })

  it('zeroes the vault before the document load — hygiene for the heap the old page leaves behind', async () => {
    const tab = await freshTab()
    const { setUnlocked } = await import('./vault')
    setUnlocked(new Uint8Array(32).fill(7), { kemX: new Uint8Array(1), mlkemSeed: new Uint8Array(1), sign: new Uint8Array(1) }, 'cold')
    let unlockedAtLoad: boolean | null = null
    replace.mockImplementation(() => void (unlockedAtLoad = tab.vaultState.unlocked))

    await tab.endSession()

    expect(unlockedAtLoad).toBe(false)
  })

  it('does nothing in a tab that holds no session — no storage touched, no announcement, no reload (E2)', async () => {
    const tab = await freshTab()

    await tab.endSession()

    expect(dropConvenienceTiers).not.toHaveBeenCalled()
    expect(endAllPlaybacks).not.toHaveBeenCalled()
    expect(FakeChannel.posted).toEqual([])
    expect(replace).not.toHaveBeenCalled()
  })

  it('runs once: a second trigger while the ending runs joins it', async () => {
    const tab = await freshTab()
    tab.session.accessToken = 'a1'

    await Promise.all([tab.endSession(), tab.endSession()])

    expect(dropConvenienceTiers).toHaveBeenCalledTimes(1)
    expect(replace).toHaveBeenCalledTimes(1)
  })

  it('checks for a session before anything awaits, so the caller may drop the tokens right after', async () => {
    // The refresh path relies on this: it starts the ending, then drops the dead tokens.
    const tab = await freshTab()
    tab.session.accessToken = 'a1'

    const ended = tab.endSession()
    tab.session.accessToken = null
    await ended

    expect(replace).toHaveBeenCalledTimes(1)
  })

  it('ends this tab on another tab’s announcement, without echoing it and without calling the server (E3)', async () => {
    const tab = await freshTab()
    tab.session.accessToken = 'a1'
    const stopListening = tab.listenForSessionEnd()
    const otherTab = new FakeChannel('veyq-session')

    otherTab.postMessage({ type: 'ended' })
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/login'))

    expect(dropConvenienceTiers).toHaveBeenCalledTimes(1)
    expect(endAllPlaybacks).toHaveBeenCalledTimes(1)
    expect(FakeChannel.posted.filter((p) => p.from !== otherTab)).toEqual([])
    expect(fetchSpy).not.toHaveBeenCalled()
    stopListening()
  })

  it('ignores the announcement in a tab that holds no session (E2)', async () => {
    const signedOut = await freshTab()
    const stopSignedOut = signedOut.listenForSessionEnd()
    // The positive control: a second tab (its own module graph) that does hold a session, on the same channel.
    // Once IT has reloaded, the message has reached every listener — so the tab under test had its chance.
    const signedIn = await freshTab()
    signedIn.session.accessToken = 'a1'
    const stopSignedIn = signedIn.listenForSessionEnd()

    new FakeChannel('veyq-session').postMessage({ type: 'ended' })
    await vi.waitFor(() => expect(replace).toHaveBeenCalled())

    expect(replace).toHaveBeenCalledTimes(1)
    expect(dropConvenienceTiers).toHaveBeenCalledTimes(1)
    stopSignedOut()
    stopSignedIn()
  })

  it('ignores a message that is not the announcement', async () => {
    const tab = await freshTab()
    tab.session.accessToken = 'a1'
    const stopListening = tab.listenForSessionEnd()
    // A probe opened AFTER the tab's listener: the fake delivers in open order, so once the probe has the message,
    // the tab has handled it. (Not a second `ended` as the control — the run-once latch would hide a double.)
    const probe = new FakeChannel('veyq-session')
    const delivered = new Promise((resolve) => (probe.onmessage = resolve))

    new FakeChannel('veyq-session').postMessage({ type: 'something-else' })
    await delivered
    await Promise.resolve()

    expect(dropConvenienceTiers).not.toHaveBeenCalled()
    expect(replace).not.toHaveBeenCalled()
    stopListening()
  })

  it('ends in a document load of the sign-in page, never an in-app route change', async () => {
    const tab = await freshTab()
    tab.session.accessToken = 'a1'

    await tab.endSession()

    // The one change that voids ADR 0070's guarantee is swapping this for a router push: the page's memory, and
    // every decrypted store in it, would survive. So the assertion is on the document navigation itself.
    expect(replace).toHaveBeenCalledExactlyOnceWith('/login')
  })
})
