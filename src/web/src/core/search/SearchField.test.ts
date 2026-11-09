// @vitest-environment happy-dom
// The shell's search field (uc.platform.search) over a stubbed index and the REAL registry (Memories registered, so its
// kind words and its note are the real ones): the first focus asks for the index, a suggestion becomes a chip and Backspace
// takes it away, the results come in their groups with where they live, choosing one opens it, and the field says what it
// searched when it found nothing; the tenant's members and the settings sections the rail offers are results of their own
// (ADR 0125 decision 9), and Depot's and Expenses' kinds have their word. Driven by the key events the layer's combobox
// listens to.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }), useRoute: () => ({ name: 'home', hash: '#trips/' }) }))
vi.mock('./searchIndex', async () => {
  const { reactive } = await import('vue')
  return {
    searchState: reactive({ started: true, loading: [] as string[], failed: [] as unknown[], listFailed: false, scopes: [] as unknown[], revision: 0 }),
    refreshSearchIndex: vi.fn(async () => {}),
    indexedDocuments: vi.fn(() => []),
    indexedTree: vi.fn(() => null),
    indexedScope: vi.fn(() => null),
  }
})
vi.mock('../people/directory', async (orig) => ({
  ...(await orig<typeof import('../people/directory')>()), loadTenantPeople: vi.fn(async () => new Map()), tenantPeopleNow: vi.fn(() => []),
}))
// The settings list's reads (`features/settings/sections.ts`, the rail's own): its gates are answered here.
vi.mock('../../features/spaces/scopes', () => ({ listMyGroups: vi.fn(), listMyTenants: vi.fn() }))
vi.mock('../../features/account/trustReset/refusedDocuments', () => ({ refusedDocuments: vi.fn() }))
vi.mock('../../features/moderation/appointment', () => ({ myAppointment: vi.fn() }))
vi.mock('../../app/activeSpace', async (orig) => {
  const { reactive } = await import('vue')
  return { ...(await orig<typeof import('../../app/activeSpace')>()), activeSpace: reactive({ scopeId: 'S1', tenantId: 't-1', status: 'ready', kind: 'TENANT', name: null }) }
})

import { mount, flushPromises } from '@vue/test-utils'
import SearchField from './SearchField.vue'
import { indexedDocuments, indexedScope, indexedTree, refreshSearchIndex, searchState, type SearchScope } from './searchIndex'
import { loadTenantPeople, tenantPeopleNow, type Person } from '../people/directory'
import { listMyGroups, listMyTenants } from '../../features/spaces/scopes'
import { refusedDocuments } from '../../features/account/trustReset/refusedDocuments'
import { myAppointment } from '../../features/moderation/appointment'
import { activeSpace } from '../../app/activeSpace'
import { FILE_KIND } from '../../features/depot/kinds'
import { POT_KIND } from '../../features/expenses/kinds'
import { searchDocument, type SearchDocument } from './model'
import { setBreadcrumb } from '../../app/breadcrumb'
import { realI18n, t } from '../../test/i18n'
import { treeNode } from '../../test/models'
import { FOLDER_KIND } from '../node/kind'
import { ScopeKind } from '../../api/generated/scopes'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const KEVIN = 'kkkkkkkk-0000-4000-8000-000000000002'
const tenantRole = (role: 'OWNER' | 'MEMBER') => [{ tenantId: 't-1', role }] as unknown as Awaited<ReturnType<typeof listMyTenants>>
const doc = (o: Partial<SearchDocument> & Pick<SearchDocument, 'id' | 'name'>): SearchDocument =>
  searchDocument({ scopeId: 'S1', kind: 'MEMORY', nodeId: o.id, revision: '0:0', path: [], facets: {}, ...o })
const trees: Record<string, ReturnType<typeof treeNode>[]> = {
  S1: [treeNode({ id: 'trips', parentId: null, kind: FOLDER_KIND, name: 'Trips' }), treeNode({ id: 'rome', parentId: 'trips', name: 'Rome 2024' })],
  G1: [treeNode({ id: 'garden', parentId: null, name: 'Rome garden' })],
}
const scopes: SearchScope[] = [{ id: 'S1', kind: ScopeKind.Tenant, name: 'Familie' }, { id: 'G1', kind: ScopeKind.Group, name: 'Family group' }]

const field = () => document.body.querySelector<HTMLInputElement>('input.veyq-tags-input__input')!
/** A node's text, its parts apart (the name, where it lives, a chip's value). */
const words = (n: Node): string[] => (n.nodeType === 3 ? [n.textContent!.trim()].filter(Boolean) : [...n.childNodes].flatMap(words))
const rows = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__option')].map((o) => words(o).join(' '))
const headings = () => [...document.body.querySelectorAll('.veyq-tags-input__group-label')].map((h) => h.textContent!.trim())
const chips = () => [...document.body.querySelectorAll('.veyq-tags-input__tag .veyq-tags-input__label')].map((c) => c.textContent!.trim())
const status = () => document.body.querySelector('.veyq-tags-input__status')?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
async function press(key: string) {
  field().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  await flushPromises()
}
async function type(text: string) {
  const el = field()
  el.value = text
  el.setSelectionRange(text.length, text.length)
  el.dispatchEvent(new InputEvent('input', { data: text.slice(-1), bubbles: true }))
  await flushPromises()
}
/** Every field a test mounted, unmounted after it: a field left mounted would keep answering a later test's state. */
const mounted: { unmount(): void }[] = []
afterEach(() => mounted.splice(0).forEach((w) => w.unmount()))
async function mountField() {
  const w = mount(SearchField, { global: { plugins: [realI18n()] }, attachTo: document.body })
  mounted.push(w)
  field().focus()
  field().dispatchEvent(new FocusEvent('focus'))
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  setBreadcrumb([{ id: null, name: 'Familie', hash: '' }, { id: 'trips', name: 'Trips', hash: '#trips/' }])
  Object.assign(searchState, { loading: [], failed: [], listFailed: false, scopes })
  vi.mocked(indexedTree).mockImplementation((id) => trees[id] ?? null)
  vi.mocked(indexedScope).mockImplementation((id) => scopes.find((s) => s.id === id) ?? null)
  vi.mocked(indexedDocuments).mockReturnValue([
    doc({ id: 'trips', kind: FOLDER_KIND, name: 'Trips' }),
    doc({ id: 'rome', name: 'Rome 2024', path: ['Trips'], facets: { years: { from: 2024, to: 2024 }, people: [ANNA] } }),
    doc({ id: 'garden', scopeId: 'G1', name: 'Rome garden', facets: { years: { from: 2023, to: 2023 } } }),
  ])
  vi.mocked(tenantPeopleNow).mockReturnValue([
    { userId: ANNA, username: 'anna', realName: 'Anna Schmidt', displayName: 'Anna Schmidt' },
    { userId: KEVIN, username: 'kevin', realName: null, displayName: 'kevin' },
  ] satisfies Person[])
  vi.mocked(listMyGroups).mockResolvedValue([])
  vi.mocked(listMyTenants).mockResolvedValue(tenantRole('MEMBER'))
  vi.mocked(refusedDocuments).mockResolvedValue([])
  vi.mocked(myAppointment).mockResolvedValue({ state: 'NONE', address: '', confirmation: 'NOT_SENT', awaitingKey: false })
})

describe('the search field', () => {
  it('asks for the index on focus and says what it searches, with each application\'s note', async () => {
    await mountField()
    expect(refreshSearchIndex).toHaveBeenCalled()
    expect(status()).toContain(t('shell.search.hint'))
    expect(status()).toContain(t('memory.search.note'))
    expect(status()).not.toContain(t('shell.search.empty'))
  })

  it('shows the results in their groups, another space\'s with its space and path', async () => {
    await mountField()
    await type('rome')
    expect(headings()).toEqual([t('shell.search.groupSpace'), t('shell.search.groupOther')])
    expect(rows()).toEqual(['Rome 2024 Trips', 'Rome garden Family group'])
  })

  it('heads the open node\'s group by its name', async () => {
    setBreadcrumb([{ id: null, name: 'Familie', hash: '' }, { id: 'trips', name: 'Trips', hash: '#trips/' }, { id: 'rome', name: 'Rome 2024', hash: '#trips/memories/rome-2024' }])
    trees.S1 = [...trees.S1, treeNode({ id: 'day', parentId: 'rome', name: 'Rome day one' })]
    vi.mocked(indexedDocuments).mockReturnValue([doc({ id: 'day', name: 'Rome day one' }), doc({ id: 'rome', name: 'Rome 2024' })])
    await mountField()
    await type('rome')
    expect(headings()).toEqual([t('shell.search.groupOpen', { name: 'Rome 2024' }), t('shell.search.groupSpace')])
    trees.S1 = trees.S1.slice(0, 2)
  })

  it('offers a year as a chip; choosing it filters, and Backspace takes it away again', async () => {
    await mountField()
    await type('2024')
    expect(rows()[0]).toBe(`2024 ${t('shell.search.chipYear')}`)
    await press('Enter')
    expect(chips()).toEqual(['2024'])
    expect(field().value).toBe('')
    expect(rows()).toEqual(['Rome 2024 Trips 2024'])
    await press('Backspace')
    expect(chips()).toEqual([])
  })

  it('offers a member by a word of their name, and finds what they took part in', async () => {
    await mountField()
    await type('schm')
    expect(rows()[0]).toBe(`Anna Schmidt ${t('shell.search.chipPerson')}`)
    await press('Enter')
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(rows()).toEqual(['Rome 2024 Trips Anna Schmidt'])
  })

  it('keeps the words a chip did not take as text', async () => {
    await mountField()
    await type('rome schm')
    await press('Enter')
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(field().value).toBe('rome')
    expect(rows()).toEqual(['Rome 2024 Trips Anna Schmidt'])
  })

  it('offers a kind by its word (a Memory)', async () => {
    await mountField()
    await type(t('memory.kindMemory').slice(0, 4))
    expect(rows()).toContain(`${t('memory.kindMemory')} ${t('shell.search.chipKind')}`)
  })

  it('opens a node of another space at its place there, through the navigation', async () => {
    await mountField()
    await type('garden')
    await press('Enter')
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: '#@G1/memories/rome-garden' })
  })

  it('keeps the address of the space on screen for a node of it', async () => {
    await mountField()
    await type('rome 2')
    await press('Enter')
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: '#trips/memories/rome-2024' })
  })

  it('says it found nothing, and what it searched', async () => {
    await mountField()
    await type('zzz')
    expect(rows()).toEqual([])
    expect(status()).toContain(t('shell.search.empty'))
    expect(status()).toContain(t('memory.search.note'))
  })

  it('names the spaces it is still reading and those it could not search', async () => {
    Object.assign(searchState, { loading: ['G1', 'P1'], failed: [{ id: 'X', kind: ScopeKind.Group, name: 'Old group' }] })
    await mountField()
    expect(status()).toContain(t('shell.search.loading', { count: 2 }, 2))
    expect(status()).toContain(t('shell.search.notSearched', { spaces: 'Old group' }))
  })

  it('shows a group\'s first results and more on request', async () => {
    vi.mocked(indexedDocuments).mockReturnValue(Array.from({ length: 11 }, (_, i) => doc({ id: `n${i}`, name: `Rome ${String(i).padStart(2, '0')}` })))
    await mountField()
    await type('rome')
    expect(rows()).toHaveLength(9)
    expect(rows().at(-1)).toBe(t('shell.search.showMore', { count: 3 }))
    for (let i = 0; i < 8; i++) await press('ArrowDown')
    await press('Enter')
    expect(rows()).toHaveLength(11)
    expect(push).not.toHaveBeenCalled()
  })
})

describe('people and settings sections as results (ADR 0125 decision 9)', () => {
  it('asks for the roster and the settings answers on focus', async () => {
    await mountField()
    expect(loadTenantPeople).toHaveBeenCalled()
    expect(listMyTenants).toHaveBeenCalled()
  })

  it('finds a member by their name and by their username', async () => {
    await mountField()
    await type('anna')
    expect(headings()).toEqual([t('shell.search.groupPeople')])
    expect(rows()).toContain('A Anna Schmidt ~anna') // the initial the roster has, the name, the handle
    await type('~anna')
    expect(rows()).toEqual(['A Anna Schmidt ~anna'])
  })

  it('puts the People group after every space\'s group', async () => {
    vi.mocked(tenantPeopleNow).mockReturnValue([{ userId: 'r', username: 'romeo', realName: null, displayName: 'romeo' }])
    await mountField()
    await type('rom')
    expect(headings()).toEqual([t('shell.search.groupSpace'), t('shell.search.groupOther'), t('shell.search.groupPeople')])
  })

  it('offers a member as a chip AND lists them as a person: the chip filters, the result opens their profile', async () => {
    await mountField()
    await type('kevin')
    expect(rows()).toEqual([`kevin ${t('shell.search.chipPerson')}`, 'K kevin ~kevin'])
    await press('ArrowDown')
    await press('Enter')
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: `#people/${KEVIN}` })
  })

  it('finds a section by its label and opens it on the settings page, the space on screen kept', async () => {
    await mountField()
    await type(t('settings.password').slice(0, 5))
    expect(headings()).toEqual([t('shell.search.groupSettings')])
    expect(rows()).toEqual([`${t('settings.password')} ${t('settings.personal')}`])
    await press('Enter')
    expect(push).toHaveBeenCalledWith('/settings/password')
    expect(activeSpace.scopeId).toBe('S1')
  })

  it('never offers a section the rail hides: a member finds no owner-only one; control: the owner does', async () => {
    const storage = t('storage.navTitle')
    await mountField()
    await type(storage)
    expect(rows()).not.toContain(`${storage} ${t('settings.tenant')}`)
    mounted.splice(0).forEach((w) => w.unmount())
    document.body.innerHTML = ''
    vi.mocked(listMyTenants).mockResolvedValue(tenantRole('OWNER'))
    await mountField()
    await type(storage)
    expect(rows()).toContain(`${storage} ${t('settings.tenant')}`)
  })

  it('offers no section while a gate\'s answer is out, and says nothing of its own meanwhile', async () => {
    let answer: (v: ReturnType<typeof tenantRole>) => void = () => {}
    vi.mocked(listMyTenants).mockReturnValue(new Promise((r) => { answer = r }))
    await mountField()
    await type(t('settings.password').slice(0, 5))
    expect(rows()).toEqual([])
    expect(headings()).toEqual([])
    answer(tenantRole('MEMBER'))
    await flushPromises()
    expect(headings()).toEqual([t('shell.search.groupSettings')])
  })

  it('hides both groups once a chip is set: a chip filters content', async () => {
    await mountField()
    await type('anna')
    expect(headings()).toContain(t('shell.search.groupPeople'))
    await press('Enter') // the person chip
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(headings()).not.toContain(t('shell.search.groupPeople'))
    expect(headings()).not.toContain(t('shell.search.groupSettings'))
    expect(rows()).toEqual(['Rome 2024 Trips Anna Schmidt'])
  })

  it('names what it searches: members and settings too', async () => {
    await mountField()
    expect(status()).toContain(t('shell.search.hint'))
    expect(t('shell.search.hint')).toMatch(/members and settings/)
  })
})

describe('the kind words of Depot and Expenses', () => {
  it.each([
    ['a Depot file', FILE_KIND, 'depot.kindFile'],
    ['a Pot', POT_KIND, 'expenses.kindPot'],
  ])('a kind chip reaches %s', async (_what, kind, word) => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'k', kind, name: 'Rome thing' }),
      doc({ id: 'rome', name: 'Rome 2024' }),
    ])
    await mountField()
    await type(t(word))
    expect(rows()[0]).toBe(`${t(word)} ${t('shell.search.chipKind')}`)
    await press('Enter')
    expect(chips()).toEqual([t(word)])
    expect(rows()).toEqual(['Rome thing'])
  })
})
