// @vitest-environment happy-dom
// uc.platform.support-the-project, a member's banner (ADR 0124 decision 2): it shows only while neither the account
// (`donationBannerDismissed` on the profile) nor this browser holds a dismissal; dismissing writes the account's flag
// through the profile update AND the browser's; an account flag found set is copied into the browser; a profile that
// cannot be read shows the banner (A4), and a write that fails keeps the browser's dismissal.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../features/account/profile/profile', () => ({ getMyProfile: vi.fn(), updateMyProfile: vi.fn() }))

import DonationBanner from './DonationBanner.vue'
import { forgetDonationDismissalForTest } from './donationDismissal'
import { getMyProfile, updateMyProfile } from '../../features/account/profile/profile'
import { memberProfile } from '../../test/models'
import { realI18n } from '../../test/i18n'

const BANNER = '[data-testid="donation-banner"]'
const KEY = 'veyq.donationBannerDismissed'
const mounted: { unmount: () => void }[] = []

async function mountMember() {
  const w = mount(DonationBanner, { props: { member: true }, global: { plugins: [realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return w
}

beforeEach(() => {
  localStorage.clear()
  forgetDonationDismissalForTest()
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(memberProfile({ donationBannerDismissed: false }))
  vi.mocked(updateMyProfile).mockReset().mockResolvedValue(memberProfile({ donationBannerDismissed: true }))
})
afterEach(() => mounted.splice(0).forEach((w) => w.unmount()))

describe('DonationBanner as a member (uc.platform.support-the-project)', () => {
  it('shows while neither the account nor the browser holds a dismissal', async () => {
    const w = await mountMember()
    expect(getMyProfile).toHaveBeenCalledOnce()
    expect(w.find(BANNER).exists()).toBe(true)
  })

  it('dismissing writes the account flag through the profile update and the browser flag', async () => {
    const w = await mountMember()
    await w.find('[data-testid="donation-banner-dismiss"]').trigger('click')
    await flushPromises()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(updateMyProfile).toHaveBeenCalledExactlyOnceWith({ donationBannerDismissed: true })
    expect(localStorage.getItem(KEY)).toBe('1')
  })

  it('shows nothing when the account holds the dismissal, and copies it into this browser', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ donationBannerDismissed: true }))
    const w = await mountMember()
    expect(getMyProfile).toHaveBeenCalledOnce()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(localStorage.getItem(KEY)).toBe('1')
  })

  it('shows nothing and reads no profile when this browser holds the dismissal', async () => {
    localStorage.setItem(KEY, '1')
    const w = await mountMember()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(getMyProfile).not.toHaveBeenCalled()
  })

  it('shows when the profile cannot be read (A4), and a failed write keeps the browser dismissal', async () => {
    vi.mocked(getMyProfile).mockRejectedValue(new Error('offline'))
    vi.mocked(updateMyProfile).mockRejectedValue(new Error('offline'))
    const w = await mountMember()
    expect(w.find(BANNER).exists()).toBe(true)
    await w.find('[data-testid="donation-banner-dismiss"]').trigger('click')
    await flushPromises()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(updateMyProfile).toHaveBeenCalledOnce()
    expect(localStorage.getItem(KEY)).toBe('1')
  })
})
