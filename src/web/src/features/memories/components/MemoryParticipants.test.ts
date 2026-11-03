// @vitest-environment happy-dom
// A Memory's participants (ADR 0083, uc.memory.participants): the people fact core's header draws (every participant by
// the directory's name, a "Former member" for one who left, the loading and failed states, nothing at all with no
// participant - core's `PersonGroup.test.ts` holds how they are drawn and linked), the picker (checkboxes, search) over the
// scope's CURRENT members - opened through the exposed `openPicker` the header's "Participants" action calls
// (`MemoryDetail.rights.test.ts` clicks that action) - and - the member / guest difference - nothing at all (not even a
// request for the members) where the right is off.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
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
import type { NodeHeaderPeopleFact } from '../../../core/node/nodeHeader'
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
  treeNode({ id: NODE, wrappedMeta: ids.length ? sealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, { v: 1, participants: { userIds: ids } }) : null, metaRevision: version })

const mounted: VueWrapper[] = []
async function mountRow(opts: { ids?: string[]; show?: boolean; write?: NodeMetaDoor | null } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const w = mount(MemoryParticipants, {
    props: { scopeId: SCOPE, node: nodeWith(opts.ids ?? []), memoryKey: key, show: opts.show ?? true, write: opts.write === undefined ? memberMemorySource({ scopeId: () => SCOPE }).nodeMeta! : opts.write },
    global: { plugins: [realI18n(), router] },
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

const saveButton = () => [...document.body.querySelectorAll('button')].find((b) => b.textContent?.includes('Save'))!
const rows = () => [...document.body.querySelectorAll<HTMLElement>('.memory-participants-option')]
/** The layer's checkboxes (ADR 0107): Reka `role="checkbox"` buttons, ticked when `aria-checked` is "true". */
const boxes = () => [...document.body.querySelectorAll<HTMLButtonElement>('.memory-participants-option button[role="checkbox"].veyq-checkbox')]
const ticked = (b: Element) => b.getAttribute('aria-checked') === 'true'
/** What the header's "Participants" action does (`MemoryDetail`): the row's exposed `openPicker`. */
const openPicker = async (w: VueWrapper) => {
  await (w.vm as unknown as { openPicker: () => Promise<void> }).openPicker()
  await flushPromises()
}
const canEdit = (w: VueWrapper) => (w.vm as unknown as { canEdit: boolean }).canEdit

/** The people fact the row hands core's header (`MemoryDetail` contributes it; core's `PersonGroup` draws it). */
const fact = (w: VueWrapper) => (w.vm as unknown as { fact: NodeHeaderPeopleFact | null }).fact

describe('MemoryParticipants: the people fact core\'s header draws', () => {
  it('names every participant by the directory, in the stored order - a member by id, one who left as a former member', async () => {
    const w = await mountRow({ ids: [A, GONE, B] })
    expect(fact(w)).toEqual({
      kind: 'people',
      label: 'Participants',
      people: [
        { userId: A, name: 'Anna', former: false },
        { userId: GONE, name: 'Former member', former: true },
        { userId: B, name: 'Ben', former: false },
      ],
    })
    // The row itself draws nothing: no avatars, no names - core's header does.
    expect(w.text()).toBe('')
  })

  it('says the names are loading while the directory is read, and that it failed when it did', async () => {
    let resolve: (v: Awaited<ReturnType<typeof loadScopePeople>>) => void = () => {}
    people.mockReturnValueOnce(new Promise((r) => (resolve = r)))
    const w = await mountRow({ ids: [A] })
    expect(fact(w)).toMatchObject({ people: [], status: 'Loading…' })
    resolve([person(A, 'Anna')])
    await flushPromises()
    expect(fact(w)).toMatchObject({ people: [{ userId: A, name: 'Anna' }] })
    expect(fact(w)?.status).toBeUndefined()
    people.mockRejectedValueOnce(new Error('offline'))
    const failed = await mountRow({ ids: [B] })
    expect(fact(failed)).toMatchObject({ people: [], status: 'Could not load the members. Please try again.' })
  })

  // Owner decision 2026-10-14: the way in is the header's action, so an empty list offers NO fact - no "nobody yet" text, no
  // add link - while the picker stays reachable through the action.
  it('with no participant there is no fact at all, and the picker still opens', async () => {
    const empty = await mountRow()
    expect(fact(empty)).toBeNull()
    expect(empty.find('button').exists(), 'a control').toBe(false)
    expect(people).not.toHaveBeenCalled() // nobody to name, nothing to look up until the picker opens
    expect(canEdit(empty)).toBe(true)
    await openPicker(empty)
    expect(document.body.querySelector('[data-testid="participants-search"]'), 'the picker').not.toBeNull()
    expect(fact(await mountRow({ ids: [A] }))).not.toBeNull()
  })

  it('without the right to write the meta (pending deletion) the people stay and the picker is not offered', async () => {
    const w = await mountRow({ ids: [A], write: null })
    expect(fact(w)?.people).toHaveLength(1)
    expect(canEdit(w)).toBe(false)
    await openPicker(w)
    expect(document.body.querySelector('[data-testid="participants-search"]')).toBeNull()
  })
})

describe('MemoryParticipants: the picker', () => {
  it('lists the scope\'s current members as the layer\'s checkboxes, writes the choice with the version it read, and stores ids only', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const w = await mountRow({ ids: [A] })
    await openPicker(w)
    expect(document.body.querySelectorAll('.memory-participants-option .veyq-checkbox')).toHaveLength(5) // not raw inputs
    expect(rows().map((r) => r.querySelector('.memory-participants-name')!.textContent)).toEqual(['Anna', 'Ben', 'Carl', 'Dora', 'Élodie'])
    expect(rows().every((r) => r.querySelector('.veyq-avatar'))).toBe(true)
    expect(boxes().map(ticked)).toEqual([true, false, false, false, false])
    boxes()[2].click() // Carl
    boxes()[1].click() // Ben
    await flushPromises()
    saveButton().click()
    await flushPromises()

    expect(updateNode).toHaveBeenCalledTimes(1)
    const sent = updateNode.mock.calls[0][0].updateNodeRequest
    expect(sent.metaRevision).toBe(3)
    const stored = unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, sent.wrappedMeta).participants
    expect(stored).toEqual({ userIds: [A, C, B] }) // opaque ids only, in the order picked - no name anywhere
    expect(Object.keys(stored as object)).toEqual(['userIds'])
    expect(w.emitted('metaUpdated')?.[0]?.[0]).toMatchObject({ nodeId: NODE, metaRevision: 4 })
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
    expect(ticked(former)).toBe(true)
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
    expect(carl.querySelector<HTMLButtonElement>('button[role="checkbox"]')!.disabled).toBe(true)
    expect(document.body.textContent).toContain(`up to ${MAX_PARTICIPANTS} people`)
  })

  it('keeps the checkbox at its own size inside the full-width form rule', async () => {
    const w = await mountRow({ ids: [A] })
    await openPicker(w)
    const first = rows()[0]
    const box = first.querySelector('.veyq-checkbox')
    expect(box).not.toBeNull()
    expect(first.closest('.veyq-form'), 'the picker sits inside the full-width form rule').not.toBeNull()
    // The layer's checkbox is a Reka button with no input of its own, so `.veyq-form input` has nothing to stretch...
    expect(first.querySelectorAll('input')).toHaveLength(0)
    // ...and the box itself is none of what that rule (`styles/ui.css`) makes full width.
    expect(box!.matches(".veyq-form .veyq-input, .veyq-form .veyq-password, .veyq-form .veyq-select, .veyq-form .veyq-date-field, .veyq-form input:not([type='radio']):not([type='checkbox'])")).toBe(false)
    void w
  })
})

describe('MemoryParticipants: no right (a share guest)', () => {
  it('offers no fact and reads nothing, even though the sealed meta carries participants', async () => {
    const w = await mountRow({ ids: [A, B], show: false })
    expect(w.element.parentElement?.textContent ?? '').toBe('')
    expect(fact(w)).toBeNull()
    expect(canEdit(w)).toBe(false)
    expect(people).not.toHaveBeenCalled()
  })
})
