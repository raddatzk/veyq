// @vitest-environment happy-dom
// The shell's search field (uc.platform.search) over a stubbed index and the REAL registry (Memories registered, so its
// kind words are the real ones): the first focus asks for the index, a suggestion becomes a chip and Backspace
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
import { i18n } from '../../i18n'
import { treeNode } from '../../test/models'
import { FOLDER_KIND, SHARE_BOOKMARK_KIND } from '../node/kind'
import { ScopeKind } from '../../api/generated/scopes'
import { defineComponent, h, provide } from 'vue'
import { contributeSearchDocuments, searchContributionsKey } from './contribution'
import { searchOpenRequest } from './openRequest'

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
/** A node's text, its parts apart (the name, where it lives, a chip's value); a name is one part, its bold match inside it. */
const words = (n: Node): string[] =>
  n.nodeType === 3 || (n as Element).classList?.contains('veyq-search__result-name')
    ? [n.textContent!.trim()].filter(Boolean)
    : [...n.childNodes].flatMap(words)
const rows = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__option')].map((o) => words(o).join(' '))
const headings = () => [...document.body.querySelectorAll('.veyq-tags-input__group-label')].map((h) => h.textContent!.trim())
const chips = () => [...document.body.querySelectorAll('.veyq-tags-input__tag .veyq-tags-input__label')].map((c) => c.textContent!.trim())
const status = () => document.body.querySelector('.veyq-tags-input__status')?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
/** The highlighted row (Enter takes it), or null. */
const highlighted = () => {
  const o = document.body.querySelector<HTMLElement>('.veyq-tags-input__option[data-highlighted]')
  return o ? words(o).join(' ') : null
}
const suggestionRows = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__suggestions .veyq-tags-input__option')].map((o) => words(o).join(' '))
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
/** The first entry - here a chip suggestion - is the highlighted one, so Enter takes it (uc.platform.search step 4). */
function expectSuggestionHighlighted() {
  expect(suggestionRows(), 'the highlight is on a suggestion').toContain(highlighted())
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
  it('asks for the index on focus and says what it searches - every photo too, so no note of what it searches in part', async () => {
    await mountField()
    expect(refreshSearchIndex).toHaveBeenCalled()
    expect(status()).toBe(t('shell.search.hint'))
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
    expectSuggestionHighlighted()
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
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(rows()).toEqual(['Rome 2024 Trips Anna Schmidt'])
  })

  it('keeps the words a chip did not take as text', async () => {
    await mountField()
    await type('rome schm')
    expectSuggestionHighlighted()
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

  describe('a photo of every space (ADR 0125 decision 8a)', () => {
    const TAKEN = Date.UTC(2023, 6, 14, 12)
    const strand = () => doc({ id: 'p1', scopeId: 'G1', nodeId: 'garden', kind: 'memories.photo', name: 'strand.jpg', path: ['Rome garden'], facets: { capturedAt: TAKEN, places: ['Roma'] } })
    beforeEach(() => vi.mocked(indexedDocuments).mockReturnValue([...indexedDocuments(), strand()]))
    afterEach(() => {
      searchOpenRequest.value = null
    })

    it('is found by a place chip, with its space, its Memory and its day in the second line', async () => {
      await mountField()
      await type('roma')
      expect(rows()[0]).toBe(`Roma ${t('shell.search.chipPlace')}`)
      await press('Enter')
      expect(chips()).toEqual(['Roma'])
      const row = rows().find((r) => r.startsWith('strand.jpg'))!
      expect(row).toContain('Family group · Rome garden · ')
      expect(row.endsWith('Roma')).toBe(true)
    })

    it('is found by a year chip, by its capture year', async () => {
      await mountField()
      await type('2023')
      await press('Enter')
      expect(chips()).toEqual(['2023'])
      expect(rows().some((r) => r.startsWith('strand.jpg') && r.endsWith('2023'))).toBe(true)
    })

    it('opens its Memory through the navigation and asks that page to open it', async () => {
      await mountField()
      await type('strand')
      await press('Enter')
      expect(push).toHaveBeenCalledWith({ name: 'home', hash: '#@G1/memories/rome-garden' })
      expect(searchOpenRequest.value).toEqual({ scopeId: 'G1', nodeId: 'garden', itemId: 'p1' })
    })

    it('gives way to the open Memory\'s own document of the same medium, which opens in place', async () => {
      setBreadcrumb([{ id: null, name: 'Familie', hash: '' }, { id: 'rome', name: 'Rome 2024', hash: '#trips/memories/rome-2024' }])
      const opened = vi.fn()
      vi.mocked(indexedDocuments).mockReturnValue([
        doc({ id: 'rome', name: 'Rome 2024', path: ['Trips'] }),
        doc({ id: 'p2', nodeId: 'rome', kind: 'memories.photo', name: 'IMG_7.jpg', path: ['Trips', 'Rome 2024'], facets: { capturedAt: TAKEN } }),
      ])
      const Page = defineComponent({
        setup() {
          contributeSearchDocuments(() => ({
            scopeId: 'S1', nodeId: 'rome',
            documents: [{ kind: 'memories.photo', id: 'p2', revision: '0:b', name: 'IMG_7.jpg', facets: { capturedAt: TAKEN }, open: opened }],
          }))
          return () => h('div')
        },
      })
      const host = mount(defineComponent({ setup: () => (provide(searchContributionsKey, true), () => h(Page)) }))
      mounted.push(host)
      await mountField()
      await type('img_7')
      expect(rows().filter((r) => r.startsWith('IMG_7.jpg'))).toHaveLength(1)
      expect(headings()).toEqual([t('shell.search.groupOpen', { name: 'Rome 2024' })])
      await press('Enter')
      expect(opened).toHaveBeenCalled()
      expect(push).not.toHaveBeenCalled()
      expect(searchOpenRequest.value).toBeNull()
    })
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
    expect(status()).toBe(`${t('shell.search.empty')}${t('shell.search.hint')}`) // nothing else: no note of a part not searched
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
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.groupPeople')])
    expect(rows()).toContain('A Anna Schmidt ~anna') // the initial the roster has, the name, the handle
    await type('~anna')
    expect(rows()).toEqual(['A Anna Schmidt ~anna'])
  })

  it('puts the People group after every space\'s group', async () => {
    vi.mocked(tenantPeopleNow).mockReturnValue([{ userId: 'r', username: 'romeo', realName: null, displayName: 'romeo' }])
    await mountField()
    await type('rom')
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.groupSpace'), t('shell.search.groupOther'), t('shell.search.groupPeople')])
  })

  it('offers a member as a chip AND lists them as a person: the chip filters, the result opens their profile', async () => {
    await mountField()
    await type('kevin')
    expect(rows()).toEqual([`kevin ${t('shell.search.chipPerson')}`, 'K kevin ~kevin'])
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.groupPeople')])
    expect(suggestionRows()).toEqual([`kevin ${t('shell.search.chipPerson')}`])
    await press('ArrowDown')
    expect(highlighted()).toBe('K kevin ~kevin')
    await press('Enter')
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: `#people/${KEVIN}` })
    expect(chips()).toEqual([])
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
    expectSuggestionHighlighted()
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

describe('the kind words of Depot, Expenses and core\'s bookmark', () => {
  it.each([
    ['a Depot file', FILE_KIND, 'depot.kindFile'],
    ['a Pot', POT_KIND, 'expenses.kindPot'],
    ['a bookmark', SHARE_BOOKMARK_KIND, 'node.bookmark.kind'],
  ])('a kind chip reaches %s', async (_what, kind, word) => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'k', kind, name: 'Rome thing' }),
      doc({ id: 'rome', name: 'Rome 2024' }),
    ])
    await mountField()
    await type(t(word))
    expect(rows()[0]).toBe(`${t(word)} ${t('shell.search.chipKind')}`)
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual([t(word)])
    expect(rows()).toEqual(['Rome thing'])
  })
})

// uc.platform.search step 4 (owner 2026-11-09): the suggestions stand under their own heading; the first entry of the list is
// highlighted, a suggestion or a result, and Enter takes it; an entry the member moved to stays highlighted while they type
// on and it is listed (the layer's own test holds the drop-out and the pointer).
describe('the suggestions\' heading and the entry Enter takes', () => {
  it('heads the chip suggestions "Filter by", above the result groups', async () => {
    await mountField()
    await type('2024')
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.groupSpace')])
    expect(suggestionRows()).toEqual([`2024 ${t('shell.search.chipYear')}`])
    expect(rows()).toEqual([`2024 ${t('shell.search.chipYear')}`, 'Rome 2024 Trips'])
  })

  it('highlights the first entry right after a chip was chosen, and Enter opens it', async () => {
    await mountField()
    await type('2024')
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual(['2024'])
    expect(highlighted()).toBe('Rome 2024 Trips 2024')
    await press('Enter')
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: '#trips/memories/rome-2024' })
  })

  // The owner's example and its control: the same word, the same list - Enter alone takes the first entry, the person
  // suggestion, and makes the chip; moving onto the person result first opens the profile instead.
  it('Enter on a typed name makes the person chip, the first entry; control: ↓ onto the person result, Enter opens it', async () => {
    await mountField()
    await type('anna')
    expect(rows()).toEqual([`Anna Schmidt ${t('shell.search.chipPerson')}`, 'A Anna Schmidt ~anna'])
    expect(highlighted()).toBe(`Anna Schmidt ${t('shell.search.chipPerson')}`)
    await press('Enter')
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(push).not.toHaveBeenCalled()
    // control
    await press('Backspace')
    expect(chips()).toEqual([])
    await type('anna')
    await press('ArrowDown')
    await press('Enter')
    expect(chips()).toEqual([])
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: `#people/${ANNA}` })
  })

  it('with suggestions and no result, the first suggestion is highlighted and Enter makes it a chip', async () => {
    await mountField()
    await type('rome schm')
    expect(suggestionRows()).toEqual([`Anna Schmidt ${t('shell.search.chipPerson')}`])
    expect(rows()).toHaveLength(1)
    expect(highlighted()).toBe(`Anna Schmidt ${t('shell.search.chipPerson')}`)
    await press('Enter')
    expect(chips()).toEqual(['Anna Schmidt'])
  })

  // The owner's example in the field: "K" lists Ken and Kevin as people (one letter offers no chip yet), ↓ moves to Kevin,
  // and "Ke" - which adds both as chip suggestions above them - keeps Kevin highlighted.
  it('keeps the entry the member moved to while they type on: two members sharing a prefix', async () => {
    vi.mocked(tenantPeopleNow).mockReturnValue([
      { userId: 'ken', username: 'ken', realName: 'Ken', displayName: 'Ken' },
      { userId: KEVIN, username: 'kevin', realName: 'Kevin', displayName: 'Kevin' },
    ] satisfies Person[])
    // the two sections found by a word starting with k ("keys"), after the people
    const keys = [`${t('settings.security')} ${t('settings.personal')}`, `${t('whoIsWho.navTitle')} ${t('settings.tenant')}`]
    await mountField()
    await type('k')
    expect(rows()).toEqual(['K Ken ~ken', 'K Kevin ~kevin', ...keys])
    expect(highlighted()).toBe('K Ken ~ken')
    await press('ArrowDown')
    expect(highlighted()).toBe('K Kevin ~kevin')
    await type('ke')
    expect(rows()).toEqual([`Ken ${t('shell.search.chipPerson')}`, `Kevin ${t('shell.search.chipPerson')}`, 'K Ken ~ken', 'K Kevin ~kevin', ...keys])
    expect(highlighted()).toBe('K Kevin ~kevin')
    await press('Enter')
    expect(push).toHaveBeenCalledWith({ name: 'home', hash: `#people/${KEVIN}` })
  })
})

// cr-search-polish: the matched part in bold, the month chip, the settings sections' words and read, a screen reader's view
describe('the polish (cr-search-polish)', () => {
  const names = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-search__result-name')]
  const announced = () => document.body.querySelector('[data-testid="search-announcement"]')?.textContent?.trim() ?? ''

  it('draws the matched part of a name as a strong element with a text child, never as markup', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'k', name: 'Kevins Geburtstag' }),
      doc({ id: 'm', name: '<b>Kev</b> & co' }),
    ])
    await mountField()
    await type('kev')
    const [first, second] = names()
    const strong = first.querySelector('strong')!
    expect(strong.textContent).toBe('Kev')
    expect([...strong.childNodes].map((n) => n.nodeType)).toEqual([Node.TEXT_NODE])
    expect(first.textContent).toBe('Kevins Geburtstag')
    // a name that reads as markup is text: no element of its own, its strong part its letters
    expect(second.textContent).toBe('<b>Kev</b> & co')
    expect(second.querySelector('b')).toBeNull()
    expect(second.querySelector('strong')!.textContent).toBe('Kev')
  })

  it('draws "Straße" bold whole for "strasse"', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([doc({ id: 's', name: 'Große Straße' })])
    await mountField()
    await type('strasse')
    expect(names()[0].querySelector('strong')!.textContent).toBe('Straße')
  })

  it('offers a month chip for "may 2024" and narrows to what overlaps it; "may" alone offers none', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'rome', name: 'Roma 2024', facets: { years: { from: 2024, to: 2024 }, months: { from: '2024-05', to: '2024-05' } } }),
      doc({ id: 'garden', name: 'Gartenfest', facets: { years: { from: 2024, to: 2024 }, months: { from: '2024-07', to: '2024-07' } } }),
      doc({ id: 'undated', name: 'Undated' }),
    ])
    await mountField()
    await type('may')
    expect(suggestionRows()).toEqual([])
    await type('may 2024')
    expect(suggestionRows()[0]).toBe(`May 2024 ${t('shell.search.chipMonth')}`)
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual(['May 2024'])
    expect(rows()).toEqual(['Roma 2024 May 2024'])
  })

  it('reads the month in the UI\'s language: "Mai 2024" in German', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'rome', name: 'Roma 2024', facets: { months: { from: '2024-05', to: '2024-05' } } }),
    ])
    await mountField()
    i18n.global.locale.value = 'de'
    try {
      await type('Mai 2024')
      expect(suggestionRows()[0]).toBe('Mai 2024 Monat')
    } finally {
      i18n.global.locale.value = 'en'
    }
  })

  it('finds the mail section by "E-Mail" for an owner, and for a member nothing - the gate holds', async () => {
    const mail = `${t('mail.navTitle')} ${t('settings.tenant')}`
    vi.mocked(listMyTenants).mockResolvedValue(tenantRole('OWNER'))
    await mountField()
    await type('E-Mail')
    expect(rows()).toContain(mail)
    expect(t('mail.navTitle')).not.toMatch(/e-mail/i) // found by its word, not by its English label
    mounted.splice(0).forEach((w) => w.unmount())
    document.body.innerHTML = ''
    vi.mocked(listMyTenants).mockResolvedValue(tenantRole('MEMBER'))
    await mountField()
    await type('E-Mail')
    expect(rows()).toEqual([])
    expect(headings()).toEqual([])
  })

  it('matches a section\'s words from a word\'s start only: "mtp" finds no mail section, "smtp" does', async () => {
    vi.mocked(listMyTenants).mockResolvedValue(tenantRole('OWNER'))
    await mountField()
    await type('mtp')
    expect(rows()).not.toContain(`${t('mail.navTitle')} ${t('settings.tenant')}`)
    await type('smtp')
    expect(rows()).toContain(`${t('mail.navTitle')} ${t('settings.tenant')}`) // control
  })

  it('reads the settings answers once over two focuses', async () => {
    await mountField()
    const focuses = vi.mocked(refreshSearchIndex).mock.calls.length
    field().blur()
    field().dispatchEvent(new FocusEvent('blur'))
    field().focus()
    field().dispatchEvent(new FocusEvent('focus'))
    await flushPromises()
    expect(vi.mocked(refreshSearchIndex).mock.calls.length).toBeGreaterThan(focuses) // control: the second focus happened
    expect(listMyGroups).toHaveBeenCalledTimes(1)
    expect(refusedDocuments).toHaveBeenCalledTimes(1)
    expect(myAppointment).toHaveBeenCalledTimes(1)
    expect(listMyTenants).toHaveBeenCalledTimes(1)
  })

  it('reads the groups again when the spaces the index lists gain a group the answers do not know', async () => {
    await mountField()
    expect(listMyGroups).toHaveBeenCalledTimes(1)
    searchState.scopes = [...scopes, { id: 'G2', kind: ScopeKind.Group, name: 'New group' }]
    await flushPromises()
    expect(listMyGroups).toHaveBeenCalledTimes(2)
    expect(listMyTenants).toHaveBeenCalledTimes(1)
  })

  it('announces the result count as it changes, politely', async () => {
    await mountField()
    const region = document.body.querySelector('[data-testid="search-announcement"]')!
    expect(region.getAttribute('aria-live')).toBe('polite')
    expect(region.getAttribute('role')).toBe('status')
    expect(announced()).toBe('')
    await type('rome')
    expect(announced()).toBe(t('shell.search.announceResults', { count: 2 }, 2))
    await type('rome g')
    expect(announced()).toBe(t('shell.search.announceResults', { count: 1 }, 1))
  })

  it('announces a chip added, then removed', async () => {
    await mountField()
    await type('2024')
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual(['2024'])
    expect(announced()).toBe(`${t('shell.search.announceChipAdded', { label: '2024' })} ${t('shell.search.announceResults', { count: 1 }, 1)}`)
    await press('Backspace')
    expect(chips()).toEqual([])
    expect(announced()).toBe(t('shell.search.announceChipRemoved', { label: '2024' }))
    await type('r')
    expect(announced()).not.toContain(t('shell.search.announceChipRemoved', { label: '2024' }))
  })

  it('names each group by its heading, and the field is a combobox that owns the list', async () => {
    await mountField()
    await type('rome')
    const groups = [...document.body.querySelectorAll<HTMLElement>('[role="listbox"] [role="group"]')]
    expect(groups.length).toBe(2)
    for (const g of groups) {
      const labelledBy = g.getAttribute('aria-labelledby') ?? ''
      const label = labelledBy ? document.getElementById(labelledBy) : null
      expect(label?.textContent?.trim(), `group ${g.id}`).toBeTruthy()
      expect(headings()).toContain(label!.textContent!.trim())
    }
    expect(groups.map((g) => document.getElementById(g.getAttribute('aria-labelledby')!)!.textContent!.trim())).toEqual(headings())
    const input = field()
    expect(input.getAttribute('role')).toBe('combobox')
    expect(input.getAttribute('aria-expanded')).toBe('true')
    expect(document.getElementById(input.getAttribute('aria-controls') ?? '')?.getAttribute('role')).toBe('listbox')
  })
})
