// @vitest-environment happy-dom
// uc.platform.support-the-project, a guest's banner (ADR 0124 decisions 1-2): a fresh browser sees it — a labelled region
// with the donation page's link in a tab of its own and a labelled ×; the × hides it, and it stays hidden on the next
// mount because this browser keeps the dismissal; storage that cannot be read shows it (A3), storage that cannot be
// written still hides it for the visit. A guest-mode mount: the profile module is the REAL one and every authenticated
// client factory throws and is recorded, so a guest's banner that read or wrote the account is red.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))

import DonationBanner from './DonationBanner.vue'
import { forgetDonationDismissalForTest } from './donationDismissal'
import { authenticatedCalls } from '../../test/guestMode'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'

const BANNER = '[data-testid="donation-banner"]'
const mounted: { unmount: () => void }[] = []

async function mountIt(locale: 'en' | 'de' = 'en') {
  const plugin = realI18n()
  plugin.global.locale.value = locale
  const w = mount(DonationBanner, { global: { plugins: [plugin] } })
  mounted.push(w)
  await flushPromises()
  return w
}

beforeEach(() => {
  localStorage.clear()
  forgetDonationDismissalForTest()
  authenticatedCalls.length = 0
  i18n.global.locale.value = 'en'
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  expect(authenticatedCalls).toEqual([])
})

describe('DonationBanner as a guest (uc.platform.support-the-project)', () => {
  it('shows a fresh browser a labelled region with the donation link and a labelled dismiss button', async () => {
    const w = await mountIt()
    const banner = w.find(BANNER)
    expect(banner.exists()).toBe(true)
    expect(banner.attributes('role')).toBe('region')
    expect(banner.attributes('aria-label')).toBe(t('project.banner.label'))
    expect(banner.text()).toContain(t('project.banner.text'))
    const link = banner.find('a')
    expect(link.text()).toBe(t('project.banner.donate'))
    expect(link.attributes('href')).toBe('https://veyq.app/donate/')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    const dismiss = banner.find('button[data-testid="donation-banner-dismiss"]')
    expect(dismiss.attributes('aria-label')).toBe(t('project.banner.dismiss'))
  })

  it('links the German donation page in a German client', async () => {
    const w = await mountIt('de')
    expect(w.find(`${BANNER} a`).attributes('href')).toBe('https://veyq.app/de/donate/')
  })

  it('goes on dismiss and stays gone on the next mount, the dismissal kept in this browser', async () => {
    const w = await mountIt()
    await w.find('[data-testid="donation-banner-dismiss"]').trigger('click')
    await flushPromises()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(localStorage.getItem('veyq.donationBannerDismissed')).toBe('1')

    w.unmount()
    mounted.splice(0)
    forgetDonationDismissalForTest() // a new visit: only what the browser stored remains
    const again = await mountIt()
    expect(again.find(BANNER).exists()).toBe(false)
  })

  it('shows when the stored dismissal cannot be read (A3)', async () => {
    localStorage.setItem('veyq.donationBannerDismissed', '1')
    // Spied on the instance, not on `Storage.prototype` (happy-dom's localStorage does not reach the prototype's methods,
    // so that spy changed nothing), and restored by hand: `vi.restoreAllMocks` left this one in place for the next test.
    const getItem = vi.spyOn(localStorage, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const w = await mountIt()
    getItem.mockRestore()
    expect(w.find(BANNER).exists()).toBe(true)
  })

  it('still hides for the rest of the visit when the dismissal cannot be written (A3)', async () => {
    const w = await mountIt()
    const setItem = vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    await w.find('[data-testid="donation-banner-dismiss"]').trigger('click')
    await flushPromises()
    setItem.mockRestore()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(localStorage.getItem('veyq.donationBannerDismissed')).toBeNull()

    w.unmount()
    mounted.splice(0)
    const again = await mountIt() // the same visit: the in-memory dismissal holds
    expect(again.find(BANNER).exists()).toBe(false)
  })
})
