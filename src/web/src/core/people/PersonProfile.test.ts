// @vitest-environment happy-dom
// A member's profile page (ADR 0083 decision 6): core's frame - name by the display rule, letter avatar, former member -
// around the sections the applications claim. The section here is a prop recorder; Memories' own is PersonMemories.test.ts.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import PrimeVue from 'primevue/config'

vi.mock('./directory', async (orig) => ({ ...(await orig<typeof import('./directory')>()), loadTenantPeople: vi.fn(), personNow: vi.fn(() => undefined) }))
vi.mock('../../features/account/profile/profile', async (orig) => ({
  ...(await orig<typeof import('../../features/account/profile/profile')>()),
  getMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
  openOwnNameKey: vi.fn(),
  setMyAvatar: vi.fn(),
  clearMyAvatar: vi.fn(),
}))
// The safety-number sections have their own suite (PersonSafetyNumber.test.ts); here they are recorders of WHERE they mount.
vi.mock('./MySafetyNumber.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('div', { class: 'my-safety-stub' }) }) }
})
vi.mock('./PersonSafetyNumber.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({ props: ['userId', 'name', 'username'], setup: (p) => () => h('div', { class: 'their-safety-stub' }, `${p.userId}|${p.name}|${p.username}`) }),
  }
})
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('../applications/registry', async (orig) => {
  const real = await orig<typeof import('../applications/registry')>()
  const Section = defineComponent({ props: ['userId', 'name'], setup: (p) => () => h('div', { class: 'section-stub' }, `${p.userId}|${p.name}`) })
  return { ...real, registry: { ...real.registry, personSections: () => [Section] } }
})

import PersonProfile from './PersonProfile.vue'
import { loadTenantPeople, personNow, type Person } from './directory'
import { realI18n, t } from '../../test/i18n'
import { account } from '../../app/account'
import { getMyProfile, updateMyProfile, openOwnNameKey, setMyAvatar, clearMyAvatar, sealName } from '../../features/account/profile/profile'
import { openAvatar } from '../../features/account/profile/memberAvatar'
import NamePictureFields from '../../features/account/profile/NamePictureFields.vue'
import * as v1 from '@veyq/crypto/v1'
import { memberProfile } from '../../test/models'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const load = vi.mocked(loadTenantPeople)
const anna: Person = { userId: ANNA, username: 'anna', realName: 'Anna Meyer', displayName: 'Anna Meyer' }

async function mountPage(userId = ANNA) {
  const w = mount(PersonProfile, { props: { userId }, global: { plugins: [realI18n(), PrimeVue] } })
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(personNow).mockReturnValue(undefined)
  account.userId = ''
})

describe('PersonProfile', () => {
  it('shows a member the device already knows at once, without waiting for the read', async () => {
    vi.mocked(personNow).mockReturnValue(anna)
    load.mockReturnValue(new Promise(() => {}))
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('Anna Meyer')
    expect(w.find('[role="status"]').exists()).toBe(false)
  })

  it('says it is loading until the directory is read', async () => {
    load.mockReturnValue(new Promise(() => {}))
    const w = await mountPage()
    expect(w.get('[role="status"]').text()).toBe('Loading…')
  })

  it('shows the real name, the username under it, a letter avatar and each claimed section with the id and the name', async () => {
    load.mockResolvedValue(new Map([[ANNA, anna]]))
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('Anna Meyer')
    expect(w.get('.person-username').text()).toBe('~anna')
    expect(w.get('.person-avatar').text()).toBe('A')
    expect(w.get('.section-stub').text()).toBe(`${ANNA}|Anna Meyer`)
  })

  it('names a member without a readable real name by the username, with no second line', async () => {
    load.mockResolvedValue(new Map([[ANNA, { ...anna, realName: null, displayName: 'anna' }]]))
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('anna')
    expect(w.find('.person-username').exists()).toBe(false)
  })

  it('an id the tenant does not list is a greyed former member - the sections still run, with no name', async () => {
    load.mockResolvedValue(new Map())
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('Former member')
    expect(w.get('.person-head').classes()).toContain('is-former')
    expect(w.get('.person-username').text()).toBe('No longer in this space.')
    expect(w.get('.section-stub').text()).toBe(`${ANNA}|`)
  })

  it('shows an error with a retry that reads again', async () => {
    load.mockRejectedValueOnce(new Error('offline'))
    const w = await mountPage()
    expect(w.get('[role="alert"]').text()).toContain('Could not load this person')
    expect(w.find('.section-stub').exists()).toBe(false)
    load.mockResolvedValueOnce(new Map([[ANNA, anna]]))
    await w.get('.person-retry').trigger('click')
    await flushPromises()
    expect(w.get('h1').text()).toBe('Anna Meyer')
  })

  it('re-reads when the route moves to another person', async () => {
    load.mockResolvedValue(new Map([[ANNA, anna]]))
    const w = await mountPage()
    await w.setProps({ userId: 'other' })
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(2)
    expect(w.get('h1').text()).toBe('Former member')
  })
})

describe('PersonProfile - editing one\'s own name and picture (ADR 0084 decision 6)', () => {
  const K = v1.randomBytes(32)
  const ME = '5c09e000-0000-4000-8000-0000000000b2'
  const ADDRESS = { scopeId: '5c09e000-0000-4000-8000-0000000000b1', userId: ME }
  const me: Person = { userId: ME, username: 'kevin', realName: 'Kevin R', displayName: 'Kevin R' }

  beforeEach(() => {
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ sealedName: sealName(K, ADDRESS, { firstName: 'Kevin', lastName: 'R' }) }))
    vi.mocked(openOwnNameKey).mockResolvedValue({ key: K, address: ADDRESS })
    vi.mocked(updateMyProfile).mockResolvedValue(memberProfile())
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:p'), revokeObjectURL: vi.fn() }))
  })

  it('shows the edit control on MY page', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ME, me]]))
    const w = await mountPage(ME)
    expect(w.find('[data-testid="person-edit"]').exists()).toBe(true)
  })

  it('shows NO edit control on anyone else\'s page - not the button, not the form', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ANNA, anna], [ME, me]]))
    const w = await mountPage(ANNA)
    expect(w.find('[data-testid="person-edit"]').exists()).toBe(false)
    expect(w.find('[data-testid="person-edit-form"]').exists()).toBe(false)
    expect(w.findComponent(NamePictureFields).exists()).toBe(false)
    expect(getMyProfile).not.toHaveBeenCalled()
  })

  it('shows no edit control while the signed-in id is unknown, and none on a former member\'s page', async () => {
    load.mockResolvedValue(new Map([[ME, me]]))
    expect((await mountPage(ME)).find('[data-testid="person-edit"]').exists()).toBe(false)
    account.userId = ME
    load.mockResolvedValue(new Map())
    expect((await mountPage(ME)).find('[data-testid="person-edit"]').exists()).toBe(false)
  })

  it('opens the shared editor, saves the sealed name and the chosen picture, then closes and re-reads the page', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ME, me]]))
    const w = await mountPage(ME)
    await w.get('[data-testid="person-edit"]').trigger('click')
    await flushPromises()
    const model = w.findComponent(NamePictureFields).props('model')
    expect(model.firstName).toBe('Kevin')
    model.choose(new Uint8Array([8, 8]))
    await w.get('form').trigger('submit')
    await flushPromises()
    expect(updateMyProfile).toHaveBeenCalledTimes(1)
    expect(Object.keys(vi.mocked(updateMyProfile).mock.calls[0][0])).toEqual(['sealedName']) // only the name rides along
    expect([...openAvatar(K, ADDRESS, vi.mocked(setMyAvatar).mock.calls[0][0])!]).toEqual([8, 8])
    expect(clearMyAvatar).not.toHaveBeenCalled()
    expect(w.find('[data-testid="person-edit-form"]').exists()).toBe(false)
    expect(load).toHaveBeenCalledTimes(3) // the page, the editor's cache warm-up, and the re-read after the save
  })

  it('Cancel drops the draft and writes nothing', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ME, me]]))
    const w = await mountPage(ME)
    await w.get('[data-testid="person-edit"]').trigger('click')
    await flushPromises()
    w.findComponent(NamePictureFields).props('model').choose(new Uint8Array([1]))
    const cancel = w.findAll('button').find((b) => b.text() === t('common.cancel'))!
    await cancel.trigger('click')
    expect(w.find('[data-testid="person-edit-form"]').exists()).toBe(false)
    expect(updateMyProfile).not.toHaveBeenCalled()
    expect(setMyAvatar).not.toHaveBeenCalled()
  })
})

describe('PersonProfile - the safety number (ADR 0028 Decision 3)', () => {
  const ME = '5c09e000-0000-4000-8000-0000000000b2'
  const me: Person = { userId: ME, username: 'kevin', realName: 'Kevin R', displayName: 'Kevin R' }

  it('my own page shows MY number, never the compare', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ME, me]]))
    const w = await mountPage(ME)
    expect(w.find('.my-safety-stub').exists()).toBe(true)
    expect(w.find('.their-safety-stub').exists()).toBe(false)
  })

  it('another member\'s page shows THEIR number and the compare, handed their id, name and username', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ANNA, anna], [ME, me]]))
    const w = await mountPage(ANNA)
    expect(w.get('.their-safety-stub').text()).toBe(`${ANNA}|Anna Meyer|anna`)
    expect(w.find('.my-safety-stub').exists()).toBe(false)
  })

  it('a former member\'s page shows neither', async () => {
    account.userId = ME
    load.mockResolvedValue(new Map([[ME, me]]))
    const w = await mountPage(ANNA)
    expect(w.find('.their-safety-stub').exists()).toBe(false)
    expect(w.find('.my-safety-stub').exists()).toBe(false)
  })
})
