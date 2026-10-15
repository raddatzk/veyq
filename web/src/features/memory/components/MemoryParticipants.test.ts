// @vitest-environment happy-dom
// The Memory header's participants row (ADR 0083, uc.memory.participants): overlapping avatars (three at most, past three
// the third slot is "+N"), a popover with every name that links a member to their profile, a greyed "Former member"
// without a link, nothing at all with no participant, the picker (checkboxes, search) over the scope's CURRENT members -
// opened through the exposed `openPicker` the header's "Participants" action calls (`MemoryDetail.rights.test.ts` clicks
// that action) - and - the member / guest difference - nothing at all (not even a request for the members) where the
// right is off.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'

vi.mock('../../../api/client', () => ({ contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../../../core/people/directory', () => ({ loadScopePeople: vi.fn() }))

import MemoryParticipants from './MemoryParticipants.vue'
import { loadScopePeople } from '../../../core/people/directory'
import { contentApi } from '../../../api/client'
import { fakeApi } from '../../../test/apiMock'
import type { ContentApi } from '../../../api/generated/content'
import { memberMemorySource, type NodeMetaDoor } from '../source'
import { sealNodeMeta, unsealNodeMeta } from '../../../core/node/nodeMeta'
import { MAX_PARTICIPANTS } from '../memoryParticipants'
import { treeNode } from '../../../test/models'
import { realI18n } from '../../../test/i18n'

const SCOPE = '11111111-1111-1111-1111-111111111111'
const NODE = '5c09e000-0000-4000-8000-0000000000e1'
const A = 'aaaaaaaa-0000-4000-8000-000000000001'
const B = 'bbbbbbbb-0000-4000-8000-000000000002'
const C = 'cccccccc-0000-4000-8000-000000000003'
const GONE = 'dddddddd-0000-4000-8000-000000000004'
const key = v1.randomBytes(32)
const person = (userId: string, displayName: string) => ({ userId, username: displayName.toLowerCase(), realName: null, displayName })
const D = 'eeeeeeee-0000-4000-8000-000000000005'
const E = 'ffffffff-0000-4000-8000-000000000006'
const people = vi.mocked(loadScopePeople)

const nodeWith = (ids: string[], version = 3) =>
  treeNode({ id: NODE, wrappedMeta: ids.length ? sealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, { v: 1, participants: { userIds: ids } }) : null, metaVersion: version })

const mounted: VueWrapper[] = []
async function mountRow(opts: { ids?: string[]; show?: boolean; write?: NodeMetaDoor | null } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const w = mount(MemoryParticipants, {
    props: { scopeId: SCOPE, node: nodeWith(opts.ids ?? []), memoryKey: key, show: opts.show ?? true, write: opts.write === undefined ? memberMemorySource({ scopeId: () => SCOPE }).nodeMeta! : opts.write },
    global: { plugins: [realI18n(), PrimeVue, router] },
    attachTo: document.body,
  })
  await flushPromises()
  mounted.push(w)
  return w
}

afterEach(() => {
  for (const w of mounted.splice(0)) w.unmount()
  document.body.innerHTML = ''
  vi.useRealTimers()
})

beforeEach(() => {
  vi.clearAllMocks()
  people.mockResolvedValue([person(A, 'Anna'), person(B, 'Ben'), person(C, 'Carl'), person(D, 'Dora'), person(E, 'Élodie')])
})

const group = (w: VueWrapper) => w.get('[data-testid="participants-group"]')
const openPopover = async (w: VueWrapper) => {
  await group(w).trigger('click')
  await flushPromises()
}
const saveButton = () => [...document.body.querySelectorAll('button')].find((b) => b.textContent?.includes('Save'))!
const rows = () => [...document.body.querySelectorAll<HTMLElement>('.memory-participants-option')]
const boxes = () => [...document.body.querySelectorAll<HTMLInputElement>('.memory-participants-option input[type="checkbox"]')]
/** What the header's "Participants" action does (`MemoryDetail`): the row's exposed `openPicker`. */
const openPicker = async (w: VueWrapper) => {
  await (w.vm as unknown as { openPicker: () => Promise<void> }).openPicker()
  await flushPromises()
}
const canEdit = (w: VueWrapper) => (w.vm as unknown as { canEdit: boolean }).canEdit

describe('MemoryParticipants: the avatars in the header', () => {
  it.each([
    [1, 1],
    [2, 2],
    [3, 3],
  ])('%i participant(s) show %i avatar(s) and no "+N"', async (n, avatars) => {
    const w = await mountRow({ ids: [A, B, C].slice(0, n) })
    expect(w.findAll('[data-testid="participant-avatar"]')).toHaveLength(avatars)
    expect(w.find('[data-testid="participant-overflow"]').exists()).toBe(false)
  })

  it.each([
    [4, '+2'],
    [5, '+3'],
  ])('%i participants show two avatars and the third slot as "%s"', async (n, label) => {
    const w = await mountRow({ ids: [A, B, C, D, E].slice(0, n) })
    expect(w.findAll('[data-testid="participant-avatar"]')).toHaveLength(2)
    expect(w.get('[data-testid="participant-overflow"]').text()).toBe(label)
  })

  it('prints no names inline: the names wait in the popover, and the group is a labelled button naming everybody', async () => {
    const w = await mountRow({ ids: [A, B, C, D] })
    expect(w.text()).not.toContain('Anna')
    const g = group(w)
    expect(g.element.tagName).toBe('BUTTON')
    expect(g.attributes('aria-haspopup')).toBe('dialog')
    expect(g.attributes('aria-expanded')).toBe('false')
    expect(g.attributes('aria-label')).toBe('Participants (4): Anna, Ben, Carl, Dora')
  })

  // Owner decision 2026-10-14: the way in is the header's action, so an empty list draws NOTHING here - no "nobody yet"
  // text, no add link, no element at all - while the picker stays reachable through the action.
  it('with no participant the row renders nothing at all, and the picker still opens', async () => {
    const empty = await mountRow()
    expect(empty.find('[data-testid="memory-participants"]').exists(), 'the row').toBe(false)
    expect(empty.find('button').exists(), 'a control').toBe(false)
    expect(empty.text()).toBe('')
    expect(people).not.toHaveBeenCalled() // nobody to name, nothing to look up until the picker opens
    expect(canEdit(empty)).toBe(true)
    await openPicker(empty)
    expect(document.body.querySelector('[data-testid="participants-search"]'), 'the picker').not.toBeNull()
    const filled = await mountRow({ ids: [A] })
    expect(filled.find('[data-testid="memory-participants"]').exists()).toBe(true)
  })

  it('without the right to write the meta (pending deletion) the avatars stay and the picker is not offered', async () => {
    const w = await mountRow({ ids: [A], write: null })
    expect(w.findAll('[data-testid="participant-avatar"]')).toHaveLength(1)
    expect(canEdit(w)).toBe(false)
    await openPicker(w)
    expect(document.body.querySelector('[data-testid="participants-search"]')).toBeNull()
  })
})

describe('MemoryParticipants: the popover', () => {
  it('lists every participant with a link to the profile, and a greyed former member without one', async () => {
    const w = await mountRow({ ids: [A, GONE, B, C, D] }) // five: the popover lists all, not only the avatars
    await openPopover(w)
    expect(group(w).attributes('aria-expanded')).toBe('true')
    const links = w.findAll('[data-testid="participant-link"]')
    expect(links.map((l) => l.get('.memory-participant-name').text())).toEqual(['Anna', 'Ben', 'Carl', 'Dora'])
    expect(links.map((l) => l.attributes('href'))).toEqual([`/#people/${A}`, `/#people/${B}`, `/#people/${C}`, `/#people/${D}`])
    expect(links[0].attributes('aria-label')).toBe('Open the profile of Anna')
    const former = w.findAll('[data-testid="participant-former"]')
    expect(former).toHaveLength(1)
    expect(former[0].get('.memory-participant-name').text()).toBe('Former member')
    expect(former[0].element.tagName).toBe('SPAN') // not an anchor: nothing to open
    expect(former[0].attributes('href')).toBeUndefined()
    expect(former[0].find('a').exists()).toBe(false)
  })

  it('opens on keyboard focus after the intent delay and closes on Escape, handing focus back', async () => {
    vi.useFakeTimers()
    const w = await mountRow({ ids: [A, B] })
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(false)
    ;(group(w).element as HTMLElement).focus()
    await group(w).trigger('focus')
    await vi.advanceTimersByTimeAsync(200)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(true)
    expect(group(w).attributes('aria-expanded')).toBe('true')
    await w.get('[data-testid="participant-link"]').trigger('keydown', { key: 'Escape', code: 'Escape' })
    await vi.advanceTimersByTimeAsync(400)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(false)
    expect(group(w).attributes('aria-expanded')).toBe('false')
  })

  it('opens on hover after the intent delay, not at once, and stays through the close grace period', async () => {
    vi.useFakeTimers()
    const w = await mountRow({ ids: [A, B] })
    await w.get('.memory-participants-anchor').trigger('mouseenter')
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(200)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(true)
    await w.get('.memory-participants-anchor').trigger('mouseleave')
    await vi.advanceTimersByTimeAsync(100)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(true) // inside the grace period: no flicker
    await vi.advanceTimersByTimeAsync(400)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(false)
  })

  it('a click toggles it (touch has no hover)', async () => {
    vi.useFakeTimers()
    const w = await mountRow({ ids: [A] })
    await group(w).trigger('click')
    await vi.advanceTimersByTimeAsync(10)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    await group(w).trigger('click')
    await vi.advanceTimersByTimeAsync(400)
    expect(w.find('[data-testid="participants-list"]').exists()).toBe(false)
  })
})

describe('MemoryParticipants: the picker', () => {
  it('lists the scope\'s current members as PrimeVue checkboxes, writes the choice with the version it read, and stores ids only', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const w = await mountRow({ ids: [A] })
    await openPicker(w)
    expect(document.body.querySelectorAll('.memory-participants-option .p-checkbox')).toHaveLength(5) // not raw inputs
    expect(rows().map((r) => r.querySelector('.memory-participants-name')!.textContent)).toEqual(['Anna', 'Ben', 'Carl', 'Dora', 'Élodie'])
    expect(rows().every((r) => r.querySelector('.p-avatar'))).toBe(true)
    expect(boxes().map((b) => b.checked)).toEqual([true, false, false, false, false])
    boxes()[2].click() // Carl
    boxes()[1].click() // Ben
    await flushPromises()
    saveButton().click()
    await flushPromises()

    expect(updateNode).toHaveBeenCalledTimes(1)
    const sent = updateNode.mock.calls[0][0].updateNodeRequest
    expect(sent.metaVersion).toBe(3)
    const stored = unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, sent.wrappedMeta).participants
    expect(stored).toEqual({ userIds: [A, C, B] }) // opaque ids only, in the order picked - no name anywhere
    expect(Object.keys(stored as object)).toEqual(['userIds'])
    expect(w.emitted('metaUpdated')?.[0]?.[0]).toMatchObject({ nodeId: NODE, metaVersion: 4 })
  })

  it('the search narrows the members by name, ignoring case and diacritics, and says so when nothing matches', async () => {
    const w = await mountRow({ ids: [A] })
    await openPicker(w)
    const search = document.body.querySelector<HTMLInputElement>('[data-testid="participants-search"]')!
    expect(search.getAttribute('aria-label')).toBe('Search members')
    search.value = 'ELODIE'
    search.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(rows().map((r) => r.textContent?.trim())).toEqual(['ÉÉlodie'])
    search.value = 'zzz'
    search.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(rows()).toHaveLength(0)
    expect(document.body.querySelector('[data-testid="participants-no-matches"]')!.textContent).toBe('No member matches your search.')
    void w
  })

  it('a ticked person stays ticked while the filter hides them - the draft is saved whole', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const w = await mountRow({ ids: [A] })
    await openPicker(w)
    const search = document.body.querySelector<HTMLInputElement>('[data-testid="participants-search"]')!
    search.value = 'carl'
    search.dispatchEvent(new Event('input'))
    await flushPromises()
    boxes()[0].click() // the only visible row: Carl
    search.value = 'ben'
    search.dispatchEvent(new Event('input'))
    await flushPromises()
    boxes()[0].click() // Ben - Anna and Carl are hidden, not unticked
    await flushPromises()
    saveButton().click()
    await flushPromises()
    const sent = updateNode.mock.calls[0][0].updateNodeRequest
    expect(unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, sent.wrappedMeta).participants).toEqual({ userIds: [A, C, B] })
    void w
  })

  it('a former member stays listed on top, ticked, and is not filtered out, until the person unticks it', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const w = await mountRow({ ids: [GONE, A] })
    await openPicker(w)
    expect(rows()[0].classList.contains('is-former')).toBe(true)
    const search = document.body.querySelector<HTMLInputElement>('[data-testid="participants-search"]')!
    search.value = 'ben'
    search.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(rows().map((r) => r.classList.contains('is-former'))).toEqual([true, false]) // former + Ben
    const former = boxes()[0]
    expect(former.checked).toBe(true)
    former.click()
    await flushPromises()
    saveButton().click()
    await flushPromises()
    const sent = updateNode.mock.calls[0][0].updateNodeRequest
    expect(unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, sent.wrappedMeta).participants).toEqual({ userIds: [A] })
  })

  it('at the cap the unticked members cannot be added', async () => {
    const many = Array.from({ length: MAX_PARTICIPANTS }, (_, i) => `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`)
    people.mockResolvedValue([...many.map((id, i) => person(id, `P${i}`)), person(C, 'Carl')])
    const w = await mountRow({ ids: many })
    await openPicker(w)
    const carl = rows().find((l) => l.textContent?.includes('Carl'))!
    expect(carl.querySelector('input')!.disabled).toBe(true)
    expect(document.body.textContent).toContain(`up to ${MAX_PARTICIPANTS} people`)
  })

  it('keeps the checkbox at its own size inside the full-width form rule', async () => {
    const w = await mountRow({ ids: [A] })
    await openPicker(w)
    const first = rows()[0]
    expect(first.querySelector('.p-checkbox')).not.toBeNull()
    expect(first.querySelectorAll('input')).toHaveLength(1) // one overlay input in the box, nothing stretching beside it
    void w
  })
})

describe('MemoryParticipants: no right (a share guest)', () => {
  it('renders nothing and reads nothing, even though the sealed meta carries participants', async () => {
    const w = await mountRow({ ids: [A, B], show: false })
    expect(w.element.parentElement?.textContent ?? '').toBe('')
    expect(w.find('[data-testid="memory-participants"]').exists()).toBe(false)
    expect(canEdit(w)).toBe(false)
    expect(people).not.toHaveBeenCalled()
  })
})
