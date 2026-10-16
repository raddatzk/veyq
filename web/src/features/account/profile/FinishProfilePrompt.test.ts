// @vitest-environment happy-dom
// The one-time "finish your profile" prompt (ADR 0084 decision 7, A3): shown once, after the tenant key is held, when neither a name
// nor a picture is set; dismissed per user (the flag only - never an image) and then never again, not even after a reload.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { memberProfile } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('./profile', async (orig) => ({ ...(await orig<typeof import('./profile')>()), getMyProfile: vi.fn(), openOwnNameKey: vi.fn() }))

import FinishProfilePrompt from './FinishProfilePrompt.vue'
import { getMyProfile, openOwnNameKey, sealName } from './profile'
import { activeSpace } from '../../../app/activeSpace'
import { account } from '../../../app/account'

const K = v1.randomBytes(32)
const ADDRESS = { scopeId: '5c09e000-0000-4000-8000-0000000000aa', userId: '5c09e000-0000-4000-8000-0000000000ab' }
const KEY = 'veyq.finishProfileDismissed'
let user = 0

async function mountPrompt() {
  const w = mount(FinishProfilePrompt, { global: { plugins: [realI18n(), PrimeVue] } })
  await flushPromises()
  return w
}
const prompt = (w: Awaited<ReturnType<typeof mountPrompt>>) => w.find('[data-testid="finish-profile"]')

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  account.userId = `user-${++user}` // a fresh owner per test: the preference is kept per user
  activeSpace.status = 'ready'
  activeSpace.tenantId = 'tenant-1'
  vi.mocked(getMyProfile).mockResolvedValue(memberProfile())
  vi.mocked(openOwnNameKey).mockResolvedValue({ key: K, address: ADDRESS })
})

describe('FinishProfilePrompt', () => {
  it('shows when the key is held and neither a name nor a picture is set', async () => {
    const w = await mountPrompt()
    expect(prompt(w).exists()).toBe(true)
    expect(w.text()).toContain(t('profilePicture.finish.title'))
  })

  it('shows nothing when a name is set, or a picture is, or the key is not held yet', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ sealedName: sealName(K, ADDRESS, { firstName: 'A', lastName: 'B' }) }))
    expect(prompt(await mountPrompt()).exists()).toBe(false)

    account.userId = `user-${++user}`
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ avatarRevision: 2 }))
    expect(prompt(await mountPrompt()).exists()).toBe(false)

    account.userId = `user-${++user}`
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile())
    vi.mocked(openOwnNameKey).mockResolvedValue(null)
    expect(prompt(await mountPrompt()).exists()).toBe(false)
  })

  it('"Not now" dismisses it for this user: stored as a flag under the user\'s own key, and a reload reads nothing and shows nothing', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="finish-profile-dismiss"]').trigger('click')
    expect(prompt(w).exists()).toBe(false)
    expect(localStorage.getItem(`${KEY}:u:${account.userId}`)).toBe('1')

    vi.mocked(getMyProfile).mockClear()
    const again = await mountPrompt()
    expect(prompt(again).exists()).toBe(false)
    expect(getMyProfile).not.toHaveBeenCalled() // decided from the stored flag alone
  })

  it('following the link opens Settings > Your profile and counts as dismissed', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="finish-profile-open"]').trigger('click')
    expect(push).toHaveBeenCalledWith('/settings/profile')
    expect(localStorage.getItem(`${KEY}:u:${account.userId}`)).toBe('1')
    expect(prompt(w).exists()).toBe(false)
  })

  it('one user\'s dismissal does not silence another account on the same browser', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="finish-profile-dismiss"]').trigger('click')
    account.userId = `user-${++user}`
    expect(prompt(await mountPrompt()).exists()).toBe(true)
  })

  it('waits for the tenant: nothing is read before the space is ready, and never without a known account', async () => {
    activeSpace.status = 'loading'
    const w = await mountPrompt()
    expect(getMyProfile).not.toHaveBeenCalled()
    expect(prompt(w).exists()).toBe(false)
    activeSpace.status = 'ready'
    await flushPromises()
    expect(prompt(w).exists()).toBe(true)
  })

  it('stores no image and no name - only the flag', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="finish-profile-dismiss"]').trigger('click')
    expect(Object.keys(localStorage).filter((k) => k.startsWith('veyq.'))).toEqual([`${KEY}:u:${account.userId}`])
  })
})
