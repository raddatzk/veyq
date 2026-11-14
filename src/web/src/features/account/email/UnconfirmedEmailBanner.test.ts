// @vitest-environment happy-dom
// The unconfirmed-address banner (ADR 0129 decision 5, uc.account.confirm-email-address): shown while the server's copy
// says the address is unconfirmed, naming it; hidden once confirmed; it sends the confirmation mail through the request
// the Security section sends and says "sent", says where the space cannot send that an owner must set up mail, and
// offers no way to dismiss it. Where it is drawn (the signed-in app only) is `AppShell.unconfirmedEmail.test.ts`'s.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./emailConfirmation', async (orig) => ({
  ...(await orig<typeof import('./emailConfirmation')>()),
  getAccountEmail: vi.fn(),
  requestEmailConfirmation: vi.fn(),
}))

import UnconfirmedEmailBanner from './UnconfirmedEmailBanner.vue'
import { getAccountEmail, requestEmailConfirmation } from './emailConfirmation'
import { account } from '../../../app/account'
import { unconfirmedAddress } from './unconfirmedAddress'
import { realI18n, t } from '../../../test/i18n'
import { httpError } from '../../../test/httpError'

const BANNER = '[data-testid="unconfirmed-email-banner"]'
const mounted: VueWrapper[] = []
async function mountBanner() {
  const w = mount(UnconfirmedEmailBanner, { global: { plugins: [realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  account.userId = 'u-1'
  vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: false })
  vi.mocked(requestEmailConfirmation).mockResolvedValue(undefined)
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  account.userId = ''
})

describe('UnconfirmedEmailBanner', () => {
  it('names the unconfirmed address, and offers the confirmation mail and nothing to close it with', async () => {
    const w = await mountBanner()
    expect(w.get(BANNER).text()).toContain(t('emailConfirm.banner.text', { email: 'bea@example.org' }))
    const buttons = w.get(BANNER).findAll('button')
    expect(buttons).toHaveLength(1)
    expect(buttons[0].text()).toContain(t('emailConfirm.banner.send'))
    expect(w.find(`[aria-label="${t('project.banner.dismiss')}"], [aria-label="${t('common.close')}"]`).exists()).toBe(false)
  })

  it('control: a confirmed address shows no banner', async () => {
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: true })
    const w = await mountBanner()
    expect(getAccountEmail).toHaveBeenCalledOnce()
    expect(w.find(BANNER).exists()).toBe(false)
  })

  it('an address that cannot be read shows nothing (the banner cannot decide)', async () => {
    vi.mocked(getAccountEmail).mockRejectedValue(new Error('offline'))
    const w = await mountBanner()
    expect(w.find(BANNER).exists()).toBe(false)
  })

  it('sends the confirmation mail and says it was sent — the banner stays until the address is confirmed', async () => {
    const w = await mountBanner()
    await w.get(`${BANNER} button`).trigger('click')
    await flushPromises()
    expect(requestEmailConfirmation).toHaveBeenCalledOnce()
    expect(w.get(BANNER).text()).toContain(t('emailConfirm.banner.sent'))
  })

  it('where the space cannot send, says an owner must set up mail — not "sent"', async () => {
    vi.mocked(requestEmailConfirmation).mockRejectedValue(httpError(503, 'mail_unavailable'))
    const w = await mountBanner()
    await w.get(`${BANNER} button`).trigger('click')
    await flushPromises()
    expect(w.get(BANNER).text()).toContain(t('emailConfirm.mailUnavailable'))
    expect(w.get(BANNER).text()).not.toContain(t('emailConfirm.banner.sent'))
  })

  it('goes once the address is confirmed and the tab comes back into view (a confirmation opened in another tab)', async () => {
    const w = await mountBanner()
    expect(w.find(BANNER).exists()).toBe(true)
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(w.find(BANNER).exists()).toBe(false)
  })

  it('reads again for another account signed in', async () => {
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: true })
    const w = await mountBanner()
    expect(w.find(BANNER).exists()).toBe(false)
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'cid@example.org', confirmed: false })
    account.userId = 'u-2'
    await flushPromises()
    expect(w.get(BANNER).text()).toContain('cid@example.org')
  })

  it('its answer is the shell\'s one copy, and an unmounted banner leaves it unknown', async () => {
    const w = await mountBanner()
    expect(unconfirmedAddress.value).toBe('bea@example.org')
    w.unmount()
    mounted.splice(0)
    expect(unconfirmedAddress.value).toBeUndefined()
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: true })
    await mountBanner()
    expect(unconfirmedAddress.value).toBeNull() // control: a confirmed answer is null, not unknown
  })

  it('another account signed in is unknown until its own read answers', async () => {
    const w = await mountBanner()
    let answer!: (v: { email: string; confirmed: boolean }) => void
    vi.mocked(getAccountEmail).mockReturnValue(new Promise((r) => { answer = r }))
    account.userId = 'u-2'
    await w.vm.$nextTick()
    expect(unconfirmedAddress.value).toBeUndefined()
    answer({ email: 'cid@example.org', confirmed: true })
    await flushPromises()
    expect(unconfirmedAddress.value).toBeNull()
  })
})
