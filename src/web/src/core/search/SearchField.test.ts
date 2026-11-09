// @vitest-environment happy-dom
// The shell's search field (uc.platform.search) over a stubbed index and the REAL registry (Memories registered, so its
// kind words and its note are the real ones): the first focus asks for the index, a suggestion becomes a chip and Backspace
// takes it away, the results come in their groups with where they live, choosing one opens it, and the field says what it
// searched when it found nothing. Driven by the key events the layer's combobox listens to.
import { describe, it, expect, vi, beforeEach } from 'vitest'

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
vi.mock('../people/directory', async (orig) => ({ ...(await orig<typeof import('../people/directory')>()), loadTenantPeople: vi.fn() }))
vi.mock('../../app/activeSpace', async (orig) => {
  const { reactive } = await import('vue')
  return { ...(await orig<typeof import('../../app/activeSpace')>()), activeSpace: reactive({ scopeId: 'S1', status: 'ready', kind: 'TENANT', name: null }) }
})

import { mount, flushPromises } from '@vue/test-utils'
import SearchField from './SearchField.vue'
import { indexedDocuments, indexedScope, indexedTree, refreshSearchIndex, searchState, type SearchScope } from './searchIndex'
import { loadTenantPeople, type Person } from '../people/directory'
import { searchDocument, type SearchDocument } from './model'
import { setBreadcrumb } from '../../app/breadcrumb'
import { realI18n, t } from '../../test/i18n'
import { treeNode } from '../../test/models'
import { FOLDER_KIND } from '../node/kind'
import { ScopeKind } from '../../api/generated/scopes'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
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
async function mountField() {
  const w = mount(SearchField, { global: { plugins: [realI18n()] }, attachTo: document.body })
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
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map<string, Person>([[ANNA, { userId: ANNA, username: 'anna', realName: 'Anna Schmidt', displayName: 'Anna Schmidt' }]]))
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
