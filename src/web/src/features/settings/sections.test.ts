// @vitest-environment happy-dom
// The one list of settings sections and its gates (ADR 0125 decision 9), at the module: what the rail AND the search are
// offered. Each gate with its positive control - an owner gets mail, storage and capabilities, a member none; an appointee
// gets moderation, nobody else; device linking on gets pairing, off none - and nothing is offered before every answer is in.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../spaces/scopes', () => ({ listMyGroups: vi.fn(), listMyTenants: vi.fn() }))
vi.mock('../account/trustReset/refusedDocuments', () => ({ refusedDocuments: vi.fn() }))
vi.mock('../moderation/appointment', () => ({ myAppointment: vi.fn() }))

import { useSettingsSections } from './sections'
import { listMyGroups, listMyTenants } from '../spaces/scopes'
import { refusedDocuments } from '../account/trustReset/refusedDocuments'
import { myAppointment, type ModeratorAppointment } from '../moderation/appointment'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { capabilityState } from '../../shared/state/capabilities'
import { CORE_DEVICE_LINKING } from '../platform/capabilities'
import { realI18n } from '../../test/i18n'
import { i18n } from '../../i18n'
import { settingsDocuments } from '../../core/search/peopleAndSettings'
import { textRank } from '../../core/search/query'

type Tenants = Awaited<ReturnType<typeof listMyTenants>>
const role = (r: 'OWNER' | 'MEMBER') => [{ tenantId: 't-1', role: r }] as unknown as Tenants
const appointmentIn = (state: ModeratorAppointment['state']): ModeratorAppointment => ({
  state, address: 'deputy@example.com', confirmation: 'NOT_SENT', awaitingKey: false,
})

/** The composable in a bare component, loaded or not. */
async function sections(load = true) {
  let api!: ReturnType<typeof useSettingsSections>
  mount(defineComponent({ setup: () => ((api = useSettingsSections()), () => null) }), { global: { plugins: [realI18n()] } })
  if (load) await api.load()
  await flushPromises()
  return api
}
const offeredIds = (api: ReturnType<typeof useSettingsSections>) => api.offered.value.map((o) => o.section.id)

beforeEach(() => {
  vi.clearAllMocks()
  clearActiveSpace()
  activeSpace.tenantId = 't-1'
  activeSpace.kind = ScopeKind.Tenant
  vi.mocked(listMyGroups).mockResolvedValue([])
  vi.mocked(listMyTenants).mockResolvedValue(role('MEMBER'))
  vi.mocked(refusedDocuments).mockResolvedValue([])
  vi.mocked(myAppointment).mockResolvedValue(appointmentIn('NONE'))
})
afterEach(() => {
  capabilityState.ids = null
})

describe('the settings sections and their gates', () => {
  it('a member is offered no owner-only section; control: an owner is offered mail, storage and capabilities', async () => {
    const member = offeredIds(await sections())
    expect(member).toContain('tenant-members')
    for (const id of ['mail', 'storage', 'capabilities']) expect(member).not.toContain(id)
    vi.mocked(listMyTenants).mockResolvedValue(role('OWNER'))
    expect(offeredIds(await sections())).toEqual(expect.arrayContaining(['mail', 'storage', 'capabilities']))
  })

  it('a tenant role that cannot be read is no owner', async () => {
    vi.mocked(listMyTenants).mockRejectedValue(new Error('offline'))
    expect(offeredIds(await sections())).not.toContain('mail')
  })

  it('no appointment, no moderation; control: a pending or accepted appointee is offered it', async () => {
    expect(offeredIds(await sections())).not.toContain('moderation')
    vi.mocked(myAppointment).mockRejectedValue(new Error('offline'))
    expect(offeredIds(await sections())).not.toContain('moderation')
    for (const state of ['PENDING', 'ACCEPTED'] as const) {
      vi.mocked(myAppointment).mockResolvedValue(appointmentIn(state))
      expect(offeredIds(await sections())).toContain('moderation')
    }
  })

  it('device linking off, no pairing; control: on, pairing is offered', async () => {
    expect(offeredIds(await sections())).not.toContain('pairing')
    capabilityState.ids = [CORE_DEVICE_LINKING]
    expect(offeredIds(await sections())).toContain('pairing')
  })

  it('nothing is offered before every answer is in: before a load, while the role is out, while a space is entered', async () => {
    const idle = await sections(false)
    expect(idle.ready.value).toBe(false)
    expect(offeredIds(idle)).toEqual([])

    let answer: (v: Tenants) => void = () => {}
    vi.mocked(listMyTenants).mockReturnValue(new Promise<Tenants>((r) => { answer = r }))
    const pending = await sections(false)
    const loading = pending.load()
    await flushPromises()
    expect(offeredIds(pending)).toEqual([])
    answer(role('OWNER'))
    await loading
    expect(offeredIds(pending)).toContain('mail') // control: the same list once the answer is in

    activeSpace.status = 'loading'
    expect(offeredIds(pending)).toEqual([])
  })

  it('a role read for another tenant is no answer for this one', async () => {
    const api = await sections()
    expect(api.ready.value).toBe(true)
    vi.mocked(listMyTenants).mockReturnValue(new Promise<Tenants>(() => {}))
    activeSpace.tenantId = 't-2'
    await flushPromises()
    expect(api.ready.value).toBe(false)
    expect(offeredIds(api)).toEqual([])
  })

  it('heads each section by its axis: the account, this space, the groups the caller owns', async () => {
    vi.mocked(listMyGroups).mockResolvedValue([{ scopeId: 'g-1', name: 'Familie', role: 'OWNER' }, { scopeId: 'g-2', name: 'Verein', role: 'MEMBER' }] as unknown as Awaited<ReturnType<typeof listMyGroups>>)
    const api = await sections()
    const byId = new Map(api.offered.value.map((o) => [o.section.id, o.category.key]))
    expect(byId.get('password')).toBe('personal')
    expect(byId.get('terms')).toBe('tenant')
    expect(byId.get('group-g-1')).toBe('groups')
    expect(byId.has('group-g-2')).toBe(false) // a group the caller only belongs to has nothing to manage here
  })
})

// uc.memory.place A6: the section is "Map services" now (de "Kartendienste"); its old name stays among its search words, so
// what a member learned to type still finds it. Asserted through the search's own documents and rank.
describe('the map services section in the settings search', () => {
  const mapsDocument = (api: Awaited<ReturnType<typeof sections>>) =>
    settingsDocuments(api.offered.value, () => {}).find((d) => d.id === 'maps')!
  const finds = (doc: ReturnType<typeof mapsDocument>, text: string) => textRank(doc, text.toLocaleLowerCase()) !== null

  it('is found by its old words in German, "Karte" among them; control: a word it does not carry finds nothing', async () => {
    const api = await sections()
    i18n.global.locale.value = 'de'
    await flushPromises()
    const doc = mapsDocument(api)
    expect(doc.name).toBe('Kartendienste')
    for (const text of ['Karte', 'Karte und Ortsnamen', 'Ortsnamen', 'Zustimmung']) expect(finds(doc, text), text).toBe(true)
    expect(finds(doc, 'Kennwort')).toBe(false)
  })

  it('is found by its old name in English', async () => {
    const doc = mapsDocument(await sections())
    expect(doc.name).toBe('Map services')
    for (const text of ['map and place names', 'place names', 'consent']) expect(finds(doc, text), text).toBe(true)
  })
})
