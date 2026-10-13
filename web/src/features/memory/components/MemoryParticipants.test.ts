// @vitest-environment happy-dom
// The Memory header's participants row (ADR 0083, uc.memory.participants): chips that link a member to their profile,
// a greyed "Former member" without a link, the picker over the scope's CURRENT members, and - the member / guest
// difference - nothing at all (not even a request for the members) where the capability is off.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
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
const people = vi.mocked(loadScopePeople)

const nodeWith = (ids: string[], version = 3) =>
  treeNode({ id: NODE, wrappedMeta: ids.length ? sealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, { v: 1, participants: { userIds: ids } }) : null, metaVersion: version })

async function mountRow(opts: { ids?: string[]; show?: boolean; write?: NodeMetaDoor | null } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const w = mount(MemoryParticipants, {
    props: { scopeId: SCOPE, node: nodeWith(opts.ids ?? []), memoryKey: key, show: opts.show ?? true, write: opts.write === undefined ? memberMemorySource({ scopeId: () => SCOPE }).nodeMeta! : opts.write },
    global: { plugins: [realI18n(), PrimeVue, router] },
  })
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  people.mockResolvedValue([person(A, 'Anna'), person(B, 'Ben'), person(C, 'Carl')])
})

describe('MemoryParticipants: a member', () => {
  it('shows a name chip per participant that links to the profile, and a greyed former member without a link', async () => {
    const w = await mountRow({ ids: [A, GONE, B] })
    const links = w.findAll('[data-testid="participant-link"]')
    expect(links.map((l) => l.get('.memory-participant-name').text())).toEqual(['Anna', 'Ben'])
    expect(links.map((l) => l.attributes('href'))).toEqual([`/#people/${A}`, `/#people/${B}`])
    const former = w.findAll('[data-testid="participant-former"]')
    expect(former).toHaveLength(1)
    expect(former[0].get('.memory-participant-name').text()).toBe('Former member')
    expect(former[0].element.tagName).toBe('SPAN') // not an anchor: nothing to open
    expect(former[0].attributes('href')).toBeUndefined()
  })

  it('offers "add" over an empty list and an edit control over a filled one, both labelled', async () => {
    const empty = await mountRow()
    expect(empty.get('[data-testid="participants-edit"]').attributes('aria-label')).toBe('Add participants')
    expect(empty.text()).toContain('Nobody listed yet')
    expect(people).not.toHaveBeenCalled() // nobody to name, nothing to look up until the picker opens
    const filled = await mountRow({ ids: [A] })
    expect(filled.get('[data-testid="participants-edit"]').attributes('aria-label')).toBe('Edit participants')
  })

  it('without the right to write the meta (pending deletion) the chips stay and no control is offered', async () => {
    const w = await mountRow({ ids: [A], write: null })
    expect(w.findAll('[data-testid="participant-link"]')).toHaveLength(1)
    expect(w.find('[data-testid="participants-edit"]').exists()).toBe(false)
  })

  it('the picker lists the scope\'s current members, writes the choice with the version it read, and stores ids only', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const w = await mountRow({ ids: [A] })
    await w.get('[data-testid="participants-edit"]').trigger('click')
    await flushPromises()
    const boxes = document.body.querySelectorAll<HTMLInputElement>('.memory-participants-option input')
    expect([...document.body.querySelectorAll('.memory-participants-option span')].map((s) => s.textContent)).toEqual(['Anna', 'Ben', 'Carl'])
    expect([...boxes].map((b) => b.checked)).toEqual([true, false, false])
    boxes[2].click() // Carl
    boxes[1].click() // Ben
    await flushPromises()
    ;[...document.body.querySelectorAll('button')].find((b) => b.textContent?.includes('Save'))!.click()
    await flushPromises()

    expect(updateNode).toHaveBeenCalledTimes(1)
    const sent = updateNode.mock.calls[0][0].updateNodeRequest
    expect(sent.metaVersion).toBe(3)
    const stored = unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, sent.wrappedMeta).participants
    expect(stored).toEqual({ userIds: [A, C, B] }) // opaque ids only, in the order picked - no name anywhere
    expect(Object.keys(stored as object)).toEqual(['userIds'])
    expect(w.emitted('metaUpdated')?.[0]?.[0]).toMatchObject({ nodeId: NODE, metaVersion: 4 })
  })

  it('a former member stays ticked until the person unticks it', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const w = await mountRow({ ids: [GONE, A] })
    await w.get('[data-testid="participants-edit"]').trigger('click')
    await flushPromises()
    const former = document.body.querySelector<HTMLInputElement>('.memory-participants-option.is-former input')!
    expect(former.checked).toBe(true)
    former.click()
    await flushPromises()
    ;[...document.body.querySelectorAll('button')].find((b) => b.textContent?.includes('Save'))!.click()
    await flushPromises()
    const sent = updateNode.mock.calls[0][0].updateNodeRequest
    expect(unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, sent.wrappedMeta).participants).toEqual({ userIds: [A] })
  })

  it('at the cap the unticked members cannot be added', async () => {
    const many = Array.from({ length: MAX_PARTICIPANTS }, (_, i) => `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`)
    people.mockResolvedValue([...many.map((id, i) => person(id, `P${i}`)), person(C, 'Carl')])
    const w = await mountRow({ ids: many })
    await w.get('[data-testid="participants-edit"]').trigger('click')
    await flushPromises()
    const carl = [...document.body.querySelectorAll<HTMLInputElement>('.memory-participants-option')].find((l) => l.textContent?.includes('Carl'))!
    expect(carl.querySelector('input')!.disabled).toBe(true)
    expect(document.body.textContent).toContain(`up to ${MAX_PARTICIPANTS} people`)
  })
})

describe('MemoryParticipants: no capability (a share guest)', () => {
  it('renders nothing and reads nothing, even though the sealed meta carries participants', async () => {
    const w = await mountRow({ ids: [A, B], show: false })
    expect(w.html()).toBe('<!--v-if-->')
    expect(people).not.toHaveBeenCalled()
  })
})
