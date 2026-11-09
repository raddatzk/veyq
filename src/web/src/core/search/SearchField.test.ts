// @vitest-environment happy-dom
// The shell's search field (uc.platform.search) over a stubbed index and the REAL registry (Memories registered, so its
// kind words are the real ones): the first focus asks for the index and opens nothing until a character is typed, a suggestion becomes a chip and Backspace
// takes it away, the results come as one list after the suggestions with where each lives, choosing one opens it, and the
// field says what it searched when it found nothing; the tenant's members and the settings sections the rail offers are
// results too (ADR 0125 decision 9), every kind has its word - core's Person and Setting, Depot's and Expenses' -, and a
// node something lies below is an In: chip (cr-search-chips-for-everything). Driven by the key events the layer's
// combobox listens to.
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
  n.nodeType === 3 || (n as Element).classList?.contains('veyq-search__result-name') || (n as Element).classList?.contains('veyq-search__result-meta')
    ? [n.textContent!.trim()].filter(Boolean)
    : [...n.childNodes].flatMap(words)
const rows = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__option')].map((o) => words(o).join(' '))
const headings = () => [...document.body.querySelectorAll('.veyq-tags-input__group-label')].map((h) => h.textContent!.trim())
const chips = () => [...document.body.querySelectorAll('.veyq-tags-input__tag .veyq-tags-input__label')].map((c) => c.textContent!.trim())
const status = () => document.body.querySelector('.veyq-tags-input__status')?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
/** The combobox says its list is open (Reka's `aria-expanded`). */
const listOpen = () => field().getAttribute('aria-expanded') === 'true'
/** A panel is on screen: the list is drawn and not hidden as empty. */
const panelShown = () => {
  const list = document.body.querySelector<HTMLElement>('.veyq-tags-input__list')
  return !!list && list.style.display !== 'none'
}
/** The highlighted row (Enter takes it), or null. */
const highlighted = () => {
  const o = document.body.querySelector<HTMLElement>('.veyq-tags-input__option[data-highlighted]')
  return o ? words(o).join(' ') : null
}
const suggestionRows = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__suggestions .veyq-tags-input__option')].map((o) => words(o).join(' '))
/** The rows of the one list of results, after the suggestions. */
const resultRows = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__results .veyq-tags-input__option')].map((o) => words(o).join(' '))
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
    doc({ id: 'rome', name: 'Rome 2024', path: ['Trips'], pathIds: ['trips'], facets: { years: { from: 2024, to: 2024 }, people: [ANNA] } }),
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
  // was: "asks for the index on focus and says what it searches" - the focus showed the hint in an open list; now the
  // focus starts the index and opens nothing, and the first character opens the list (cr-search-calm-focus)
  it('asks for the index on focus and opens nothing; the first typed character opens the list', async () => {
    await mountField()
    expect(refreshSearchIndex).toHaveBeenCalled()
    expect(listOpen()).toBe(false)
    expect(panelShown()).toBe(false)
    expect(status()).toBe('')
    await press('ArrowDown') // nothing asked: the combobox's own open on an arrow key is taken back
    expect(listOpen()).toBe(false)
    expect(panelShown()).toBe(false)
    await type('r')
    expect(listOpen()).toBe(true) // control: the same field opens on a character
    expect(panelShown()).toBe(true)
    expect(resultRows()).toContain('Rome 2024 Familie · Trips')
  })

  it('closes the list on Backspace to an empty field; with a chip left it stays open and follows the chip', async () => {
    await mountField()
    await type('r')
    expect(listOpen()).toBe(true)
    await type('')
    expect(listOpen()).toBe(false)
    expect(panelShown()).toBe(false)
    await type('2024')
    await press('Enter') // the year chip; the text is empty now
    expect(chips()).toEqual(['2024'])
    expect(listOpen()).toBe(true)
    await type('r')
    await type('')
    expect(listOpen()).toBe(true) // the chip is still asked
    expect(resultRows()).toEqual(['Rome 2024 Familie · Trips 2024'])
    await press('Backspace') // removes the chip: nothing is asked any more
    expect(chips()).toEqual([])
    expect(listOpen()).toBe(false)
  })

  // owner 2026-11-09: with a chip set the field already asks something, so focusing or clicking into it shows the results
  it('with a chip set, a focus and a click into the field open the list with what the chip finds; control: no chip, no list', async () => {
    await mountField()
    await type('2024')
    await press('Enter') // the year chip
    expect(chips()).toEqual(['2024'])
    await press('Escape')
    expect(listOpen()).toBe(false)
    field().dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(listOpen()).toBe(true) // a click into the field, still focused
    expect(resultRows()).toEqual(['Rome 2024 Familie · Trips 2024'])
    await press('Escape')
    field().blur()
    field().dispatchEvent(new FocusEvent('focus'))
    await flushPromises()
    expect(listOpen()).toBe(true) // a new focus
    await press('Backspace') // the chip goes: nothing is asked
    expect(listOpen()).toBe(false)
    field().dispatchEvent(new MouseEvent('click', { bubbles: true }))
    field().dispatchEvent(new FocusEvent('focus'))
    await flushPromises()
    expect(listOpen()).toBe(false) // control: without a chip a click and a focus stay calm
  })

  // was: the results in their groups (In this space, Other spaces) - now one list with no heading, each row saying where
  it('shows the results as one list under its heading, each with its space and path - the space on screen too', async () => {
    await mountField()
    await type('rome')
    expect(headings()).toEqual([t('shell.search.results')]) // the results are headed, always (owner 2026-11-09)
    expect(rows()).toEqual(['Rome 2024 Familie · Trips', 'Rome garden Family group'])
  })

  // owner 2026-11-09: a node at the top level of the space on screen says its space, not nothing
  it('names the space of a node at the top level of the space on screen', async () => {
    await mountField()
    await type('trips')
    expect(resultRows()).toContain('Trips Familie')
  })

  // was: the open node's group headed by its name - now what lies below the open node comes first at an equal match
  it('puts what lies below the open node first at an equal match, with no heading of its own', async () => {
    setBreadcrumb([{ id: null, name: 'Familie', hash: '' }, { id: 'trips', name: 'Trips', hash: '#trips/' }, { id: 'rome', name: 'Rome 2024', hash: '#trips/memories/rome-2024' }])
    trees.S1 = [...trees.S1, treeNode({ id: 'day', parentId: 'rome', name: 'Rome day one' })]
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'day', name: 'Rome day one', path: ['Trips', 'Rome 2024'], pathIds: ['trips', 'rome'] }),
      doc({ id: 'rome', name: 'Rome 2024', path: ['Trips'], pathIds: ['trips'] }),
    ])
    await mountField()
    await type('rome')
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.results')]) // Rome 2024 holds a chapter, so it is offered as In: too
    expect(suggestionRows()).toEqual([`Rome 2024 Familie · Trips ${t('shell.search.chipIn')}`])
    // by the name alone "Rome 2024" would come first; below the open node, "Rome day one" does
    expect(resultRows()).toEqual(['Rome day one Familie · Trips / Rome 2024', 'Rome 2024 Familie · Trips'])
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
    expect(rows()).toEqual(['Rome 2024 Familie · Trips 2024'])
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
    expect(rows()).toEqual(['Rome 2024 Familie · Trips Anna Schmidt'])
  })

  it('keeps the words a chip did not take as text', async () => {
    await mountField()
    await type('rome schm')
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(field().value).toBe('rome')
    expect(rows()).toEqual(['Rome 2024 Familie · Trips Anna Schmidt'])
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
      expect(headings()).toEqual([t('shell.search.results')]) // the results are headed, always (owner 2026-11-09) // was: the open node's group heading
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

  // was: the loading line showed on focus, at once - now only in an open list, after it has waited 300 ms
  it('names the spaces it could not search at once, and those it is still reading only after 300 ms of the open list waiting', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      Object.assign(searchState, { loading: ['G1', 'P1'], failed: [{ id: 'X', kind: ScopeKind.Group, name: 'Old group' }] })
      await mountField()
      await vi.advanceTimersByTimeAsync(1000) // the focus alone starts no wait: the list is shut
      expect(panelShown()).toBe(false)
      await type('zzz')
      expect(status()).toBe(t('shell.search.notSearched', { spaces: 'Old group' }))
      await vi.advanceTimersByTimeAsync(200)
      expect(status()).toBe(t('shell.search.notSearched', { spaces: 'Old group' })) // no loading line yet
      await vi.advanceTimersByTimeAsync(200)
      expect(status()).toContain(t('shell.search.loading', { count: 2 }, 2))
      expect(status()).toContain(t('shell.search.notSearched', { spaces: 'Old group' }))
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows no loading state at 200 ms and no panel at all while nothing is found yet', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      Object.assign(searchState, { loading: ['G1'] })
      await mountField()
      await type('zzz')
      await vi.advanceTimersByTimeAsync(200)
      expect(status()).toBe('')
      expect(panelShown()).toBe(false)
      await vi.advanceTimersByTimeAsync(200) // 400 ms
      expect(status()).toBe(t('shell.search.loading', { count: 1 }, 1))
      expect(panelShown()).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('never shows the loading state for a load done at 250 ms; the empty state follows it', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      Object.assign(searchState, { loading: ['G1'] })
      await mountField()
      await type('zzz')
      const seen: string[] = []
      for (let ms = 0; ms < 250; ms += 50) {
        await vi.advanceTimersByTimeAsync(50)
        seen.push(status())
      }
      searchState.loading = []
      for (let ms = 250; ms < 1000; ms += 50) {
        await vi.advanceTimersByTimeAsync(50)
        seen.push(status())
      }
      expect(seen.filter((s) => s.includes(t('shell.search.loading', { count: 1 }, 1)))).toEqual([])
      expect(seen.at(-1)).toBe(`${t('shell.search.empty')}${t('shell.search.hint')}`) // control: the status line is read
    } finally {
      vi.useRealTimers()
    }
  })

  // was: a group's first 8 and more on request - now the list's first 10, 10 more on request, the count exact
  it('shows the first 10 results and 10 more on request, the count exact', async () => {
    vi.mocked(indexedDocuments).mockReturnValue(Array.from({ length: 25 }, (_, i) => doc({ id: `n${i}`, name: `Rome ${String(i).padStart(2, '0')}` })))
    await mountField()
    await type('rome')
    expect(rows()).toHaveLength(11)
    expect(rows().slice(0, 2)).toEqual(['Rome 00 Familie', 'Rome 01 Familie'])
    expect(rows().at(-1)).toBe(t('shell.search.showMore', { count: 10 }))
    expect(document.body.querySelector('[data-testid="search-announcement"]')!.textContent!.trim()).toBe(t('shell.search.announceResults', { count: 25 }, 25))
    for (let i = 0; i < 10; i++) await press('ArrowDown')
    await press('Enter')
    expect(rows()).toHaveLength(21)
    expect(rows().at(-1)).toBe(t('shell.search.showMore', { count: 5 }))
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
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.results')]) // was: and the People group's heading
    expect(rows()).toContain('A Anna Schmidt ~anna') // the initial the roster has, the name, the handle
    await type('~anna')
    expect(rows()).toEqual(['A Anna Schmidt ~anna'])
  })

  // was: the People group after every space's group - now a person after content at an equal match, in the one list
  it('puts a person after content at an equal match', async () => {
    vi.mocked(tenantPeopleNow).mockReturnValue([{ userId: 'r', username: 'romeo', realName: null, displayName: 'romeo' }])
    await mountField()
    await type('rom')
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.results')])
    expect(rows()).toEqual([`romeo ${t('shell.search.chipPerson')}`, 'Rome 2024 Familie · Trips', 'Rome garden Family group', 'R romeo ~romeo'])
  })

  it('offers a member as a chip AND lists them as a person: the chip filters, the result opens their profile', async () => {
    await mountField()
    await type('kevin')
    expect(rows()).toEqual([`kevin ${t('shell.search.chipPerson')}`, 'K kevin ~kevin'])
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.results')]) // was: and the People group's heading
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
    expect(headings()).toEqual([t('shell.search.results')]) // the results are headed, always (owner 2026-11-09) // was: the Settings group's heading
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
    expect(rows()).toEqual([`${t('settings.password')} ${t('settings.personal')}`]) // was: the Settings group's heading
  })

  // was: "hides both groups once a chip is set" - the hide rule went; a chip is a predicate over every document, and a
  // person chip asks what the person takes part in, which their own profile is not
  it('a person chip lists what they take part in, not their profile (the predicate, no hide rule)', async () => {
    await mountField()
    await type('anna')
    expect(rows()).toContain('A Anna Schmidt ~anna')
    expectSuggestionHighlighted()
    await press('Enter') // the person chip
    expect(chips()).toEqual(['Anna Schmidt'])
    expect(rows()).toEqual(['Rome 2024 Familie · Trips Anna Schmidt'])
  })

  it('Kind: Person lists only people - with no text, and with text', async () => {
    await mountField()
    await type(t('shell.search.kindPerson'))
    expect(suggestionRows()).toContain(`${t('shell.search.kindPerson')} ${t('shell.search.chipKind')}`)
    expect(highlighted()).toBe(`${t('shell.search.kindPerson')} ${t('shell.search.chipKind')}`)
    await press('Enter')
    expect(chips()).toEqual([t('shell.search.kindPerson')])
    expect(rows()).toEqual(['A Anna Schmidt ~anna', 'K kevin ~kevin'])
    await type('ann')
    expect(rows().filter((r) => !suggestionRows().includes(r))).toEqual(['A Anna Schmidt ~anna'])
  })

  it('Kind: Setting lists only the settings sections the rail offers', async () => {
    await mountField()
    i18n.global.locale.value = 'de'
    try {
      await type('Einstell')
      expect(suggestionRows()).toContain('Einstellung Art')
      await press('Enter')
      expect(chips()).toEqual(['Einstellung'])
      const found = resultRows()
      expect(found.length).toBeGreaterThan(0)
      expect(found).not.toContain('Rome 2024 Familie · Trips')
      expect(found.some((r) => r.includes('Anna') || r.includes('kevin'))).toBe(false)
      expect(found).toContain(`${i18n.global.t('settings.password')} ${i18n.global.t('settings.personal')}`) // in German, the locale on
    } finally {
      i18n.global.locale.value = 'en'
    }
  })

  it('a year chip lists no person and no setting; control: without it a person is listed', async () => {
    vi.mocked(tenantPeopleNow).mockReturnValue([{ userId: 'r', username: 'rome2024', realName: null, displayName: 'Rome 2024 fan' }])
    await mountField()
    await type('rome 2024')
    expect(resultRows()).toContain('R Rome 2024 fan ~rome2024') // control
    await press('Enter') // the year chip, "rome" kept
    expect(chips()).toEqual(['2024'])
    expect(field().value).toBe('rome')
    expect(resultRows()).toEqual(['Rome 2024 Familie · Trips 2024'])
  })

  // was: the hint on focus - now in the empty result state of a typed query only (uc A2)
  it('names what it searches when a query found nothing: members and settings too', async () => {
    await mountField()
    expect(status()).toBe('')
    await type('zzz')
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
    expect(rows()).toEqual(['Rome thing Familie'])
  })
})

// cr-search-chips-for-everything: a node something lies below is an In: chip, with where it lives as its second line
describe('the In: chip', () => {
  const strand = () => doc({ id: 'p1', nodeId: 'rome', kind: 'memories.photo', name: 'strand.jpg', path: ['Trips', 'Rome 2024'], pathIds: ['trips', 'rome'] })

  it('"trip" offers In: Trips with its space, which narrows to its subtree, a photo included; control: the photo without it', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([...indexedDocuments(), strand()])
    await mountField()
    await type('strand')
    expect(rows()).toContain('strand.jpg Familie · Trips / Rome 2024') // control: listed without the chip
    await type('trip')
    expect(suggestionRows()).toEqual([`Trips Familie ${t('shell.search.chipIn')}`])
    await press('Enter')
    expect(chips()).toEqual(['Trips'])
    expect(rows()).toEqual(['Rome 2024 Familie · Trips', 'strand.jpg Familie · Trips / Rome 2024'])
    await type('strand')
    expect(rows()).toEqual(['strand.jpg Familie · Trips / Rome 2024'])
  })

  it('offers two nodes of one name as two suggestions, each with where it lives, each narrowing to its own subtree', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'trips', kind: FOLDER_KIND, name: 'Trips' }),
      doc({ id: 'rome', name: 'Rome 2024', path: ['Trips'], pathIds: ['trips'] }),
      doc({ id: 'work', kind: FOLDER_KIND, name: 'Work' }),
      doc({ id: 'trips2', kind: FOLDER_KIND, name: 'Trips', path: ['Work'], pathIds: ['work'] }),
      doc({ id: 'fair', name: 'Trade fair', path: ['Work', 'Trips'], pathIds: ['work', 'trips2'] }),
      doc({ id: 'g-trips', scopeId: 'G1', kind: FOLDER_KIND, name: 'Trips' }),
      doc({ id: 'camp', scopeId: 'G1', name: 'Camping', path: ['Trips'], pathIds: ['g-trips'] }),
    ])
    await mountField()
    await type('trips')
    expect(suggestionRows()).toEqual([
      `Trips Familie ${t('shell.search.chipIn')}`, `Trips Familie · Work ${t('shell.search.chipIn')}`, `Trips Family group ${t('shell.search.chipIn')}`,
    ])
    await press('ArrowDown')
    await press('Enter')
    expect(chips()).toEqual(['Trips'])
    expect(rows()).toEqual(['Trade fair Familie · Work / Trips'])
  })
})

// uc.platform.search step 4 (owner 2026-11-09): the suggestions stand under their own heading; the first entry of the list is
// highlighted, a suggestion or a result, and Enter takes it; an entry the member moved to stays highlighted while they type
// on and it is listed (the layer's own test holds the drop-out and the pointer).
describe('the suggestions\' heading and the entry Enter takes', () => {
  // was: "Filter by" above the result groups' headings - now the one heading, no other below it
  it('heads the chip suggestions "Filter by" and the results "Results", and no other heading', async () => {
    await mountField()
    await type('2024')
    expect(headings()).toEqual([t('shell.search.filterBy'), t('shell.search.results')])
    expect(suggestionRows()).toEqual([`2024 ${t('shell.search.chipYear')}`])
    expect(rows()).toEqual([`2024 ${t('shell.search.chipYear')}`, 'Rome 2024 Familie · Trips'])
  })

  it('highlights the first entry right after a chip was chosen, and Enter opens it', async () => {
    await mountField()
    await type('2024')
    expectSuggestionHighlighted()
    await press('Enter')
    expect(chips()).toEqual(['2024'])
    expect(highlighted()).toBe('Rome 2024 Familie · Trips 2024')
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
    // was: the two sections found by their word "keys" followed the people for "k" and "ke"; a settings word now counts
    // from the third character on (cr-search-visible-reasons), so neither lists them ("key" does: the next describe)
    await mountField()
    await type('k')
    expect(rows()).toEqual(['K Ken ~ken', 'K Kevin ~kevin'])
    expect(highlighted()).toBe('K Ken ~ken')
    await press('ArrowDown')
    expect(highlighted()).toBe('K Kevin ~kevin')
    await type('ke')
    expect(rows()).toEqual([`Ken ${t('shell.search.chipPerson')}`, `Kevin ${t('shell.search.chipPerson')}`, 'K Ken ~ken', 'K Kevin ~kevin'])
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
    // the member kevin is a result too now, in the one list; the two documents are the ones looked at
    const [first, second] = ['Kevins Geburtstag', '<b>Kev</b> & co'].map((n) => names().find((e) => e.textContent === n)!)
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
    expect(rows()).toEqual(['Roma 2024 Familie May 2024'])
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

  // was: the row read "<label> <heading>"; the word it was found by now follows its heading (cr-search-visible-reasons)
  it('finds the mail section by "E-Mail" for an owner, and for a member nothing - the gate holds', async () => {
    const mail = `${t('mail.navTitle')} ${t('settings.tenant')} · e-mail`
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
    // was: `not.toContain` the bare "<label> <heading>" row, which a row with its word after the heading never equals - the
    // absence now asks for any row of the mail section
    const mailRows = () => rows().filter((r) => r.startsWith(`${t('mail.navTitle')} `))
    await type('mtp')
    expect(mailRows()).toEqual([])
    await type('smtp')
    expect(mailRows()).toEqual([`${t('mail.navTitle')} ${t('settings.tenant')} · SMTP`]) // control
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

  // was: every group named by its heading - now the suggestions by theirs and the one list of results by its own name
  it('names the suggestions by their heading and the results by their name, and the field is a combobox that owns the list', async () => {
    await mountField()
    await type('2024')
    const groups = [...document.body.querySelectorAll<HTMLElement>('[role="listbox"] [role="group"]')]
    expect(groups.length).toBe(2)
    const [suggested, found] = groups
    expect(document.getElementById(suggested.getAttribute('aria-labelledby') ?? '')?.textContent?.trim()).toBe(t('shell.search.filterBy'))
    expect(document.getElementById(found.getAttribute('aria-labelledby') ?? '')?.textContent?.trim()).toBe(t('shell.search.results'))
    expect(found.getAttribute('aria-label')).toBeNull() // was: named for a screen reader only; now its visible heading names it
    const input = field()
    expect(input.getAttribute('role')).toBe('combobox')
    expect(input.getAttribute('aria-expanded')).toBe('true')
    expect(document.getElementById(input.getAttribute('aria-controls') ?? '')?.getAttribute('role')).toBe('listbox')
  })
})

// The owner's item (2026-11-09): the field remembers the queries asked while its list is open, and forgets them when it closes.
describe('the queries remembered while the list is open', () => {
  it('Backspace reads no document while open; after the list closed, the same query reads every document again', async () => {
    let reads = 0
    const base = [doc({ id: 'a', name: 'Rome 2024' }), doc({ id: 'b', name: 'Rome garden' }), doc({ id: 'c', name: 'Paris' })]
    vi.mocked(indexedDocuments).mockReturnValue(base.map((d) => Object.defineProperty({ ...d }, 'foldedName', { get: () => (reads++, d.foldedName) }) as SearchDocument))
    await mountField()
    await type('ro')
    await type('rom')
    const before = reads
    await type('ro') // Backspace
    expect(reads).toBe(before)
    expect(rows()).toContain('Rome 2024 Familie') // the answer is there all the same
    await press('Escape')
    field().focus()
    field().dispatchEvent(new FocusEvent('focus'))
    await flushPromises()
    const reopened = reads
    await type('r')
    await type('ro')
    expect(reads - reopened).toBeGreaterThanOrEqual(base.length * 2) // control: forgotten, so scanned again
  })
})

// cr-search-visible-reasons (owner 2026-11-09): a result never stands there for a reason the member cannot see
describe('a reason the member can see', () => {
  /** The row of a result whose name is `name`, or undefined. */
  const rowOf = (name: string) =>
    [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__results .veyq-tags-input__option')]
      .find((o) => o.querySelector('.veyq-search__result-name')?.textContent === name)
  const names = () => [...document.body.querySelectorAll('.veyq-search__result-name')].map((n) => n.textContent)

  /** Mounted in German: the owner's "Passwort ändern", found by its word "Kennwort". */
  async function inGerman(run: () => Promise<void>) {
    await mountField() // mounting resets the language to English (`realI18n`)
    i18n.global.locale.value = 'de'
    try {
      await flushPromises()
      await run()
    } finally {
      i18n.global.locale.value = 'en'
    }
  }

  it('"Ke" does not list "Passwort ändern"; "Ken" lists it with "Kennwort" on its second line, "Ken" bold', async () => {
    await inGerman(async () => {
      await type('Ke')
      expect(rowOf('Passwort ändern')).toBeUndefined()
      expect(names()).toContain('kevin') // control: the list is there, the member's name matches
      await type('Ken')
      const row = rowOf('Passwort ändern')!
      expect(resultRows()).toContain('Passwort ändern Persönlich · Kennwort')
      const meta = row.querySelector('.veyq-search__result-meta')!
      expect(meta.querySelector('strong')!.textContent).toBe('Ken')
      expect([...meta.querySelector('strong')!.childNodes].map((n) => n.nodeType)).toEqual([Node.TEXT_NODE])
      expect(row.querySelector('.veyq-search__result-name strong')).toBeNull() // the name did not match: nothing bold in it
    })
  })

  it('a section whose label matches shows no word on its second line', async () => {
    await inGerman(async () => {
      await type('Passw') // the label "Passwort ändern" and the word "Passwort" both match: the label is the reason
      const row = rowOf('Passwort ändern')!
      expect(resultRows()).toContain('Passwort ändern Persönlich')
      expect(row.querySelector('.veyq-search__result-meta strong')).toBeNull()
      expect(row.querySelector('.veyq-search__result-name strong')!.textContent).toBe('Passw')
    })
  })

  it('"key" lists the two sections found by "keys", the word after their heading; "ke" neither', async () => {
    await mountField()
    await type('ke')
    expect(resultRows().filter((r) => r.endsWith('· keys'))).toEqual([])
    await type('key')
    expect(resultRows()).toEqual([
      `${t('settings.security')} ${t('settings.personal')} · keys`, `${t('whoIsWho.navTitle')} ${t('settings.tenant')} · keys`,
    ])
  })

  it('"K" lists word starts only; "Ke" lists a match inside a word too', async () => {
    vi.mocked(indexedDocuments).mockReturnValue([
      doc({ id: 'link', name: 'Reiselink' }), doc({ id: 'paket', name: 'Paket' }), doc({ id: 'kiel', name: 'Kiel' }),
      doc({ id: 'trip', name: 'Trip', facets: { places: ['Bad Kissingen'] } }), doc({ id: 'home', name: 'Home', facets: { places: ['Parkweg'] } }),
    ])
    await mountField()
    await type('K')
    expect(names()).toEqual(expect.arrayContaining(['Kiel', 'Trip'])) // a name's and a place's word start
    expect(names()).not.toContain('Reiselink')
    expect(names()).not.toContain('Paket')
    expect(names()).not.toContain('Home') // "Parkweg" holds a k inside a word only
    await type('Ke')
    expect(names()).toContain('Paket') // inside a word, from the second character on
    expect(names()).not.toContain('Reiselink') // control: no "ke" in it
    expect(names()).not.toContain('Kiel')
  })
})
