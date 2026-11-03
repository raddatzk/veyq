// @vitest-environment happy-dom
// People as a node header draws them (core's `PersonGroup`, owner 2026-11-03, ADR 0110 decision 10): one person by avatar and
// name, several as overlapping avatars (three at most, past three the third slot is "+N") whose hover card lists everyone and
// links a member to their profile, a greyed former member without a link - and, the guest-safety half, no link and no member
// picture at all unless the page is a member's (`link`). Memories' participants and Depot's uploader are this component's
// facts (`MemoryParticipants.test.ts`, `FileDetail.uploader.test.ts`).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PersonGroup from './PersonGroup.vue'
import PersonAvatar from './PersonAvatar.vue'
import type { NodeHeaderPerson } from '../node/nodeHeader'
import { realI18n } from '../../test/i18n'

const A = 'aaaaaaaa-0000-4000-8000-000000000001'
const B = 'bbbbbbbb-0000-4000-8000-000000000002'
const C = 'cccccccc-0000-4000-8000-000000000003'
const D = 'eeeeeeee-0000-4000-8000-000000000005'
const E = 'ffffffff-0000-4000-8000-000000000006'
const GONE = 'dddddddd-0000-4000-8000-000000000004'
const NAMES: Record<string, string> = { [A]: 'Anna', [B]: 'Ben', [C]: 'Carl', [D]: 'Dora', [E]: 'Élodie' }
const of = (ids: string[]): NodeHeaderPerson[] => ids.map((id) => (NAMES[id] ? { userId: id, name: NAMES[id] } : { userId: id, name: 'Former member', former: true }))

const mounted: VueWrapper[] = []
async function mountGroup(people: NodeHeaderPerson[], opts: { link?: boolean; caption?: string; status?: string } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const w = mount(PersonGroup, {
    props: { label: 'Participants', people, link: opts.link ?? true, caption: opts.caption, status: opts.status },
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

const trigger = (w: VueWrapper) => w.get('[data-testid="person-group-trigger"]')

describe('one person', () => {
  it('is their avatar and their name, a real link to their profile on a member\'s page', async () => {
    const w = await mountGroup(of([A]), { caption: 'Uploaded by' })
    const link = w.get('[data-testid="person-link"]')
    expect(link.attributes('href')).toBe(`/#people/${A}`)
    expect(link.attributes('aria-label')).toBe('Open the profile of Anna')
    expect(link.get('.veyq-person-name').text()).toBe('Anna')
    expect(w.findComponent(PersonAvatar).props('userId')).toBe(A)
    expect(w.get('.veyq-person-group-caption').text()).toBe('Uploaded by')
    expect(w.attributes('aria-label')).toBe('Participants')
    expect(w.find('[data-testid="person-group-trigger"]').exists()).toBe(false)
  })

  it('a former member: greyed, its hint as the tooltip, no link and no picture', async () => {
    const w = await mountGroup(of([GONE]))
    const former = w.get('[data-testid="person-former"]')
    expect(former.element.tagName).toBe('SPAN')
    expect(former.attributes('title')).toBe('No longer in this space.')
    expect(former.get('.veyq-person-name').text()).toBe('Former member')
    expect(w.find('a').exists()).toBe(false)
    expect(w.findComponent(PersonAvatar).props('userId')).toBeNull()
  })

  it('a person with no member identity (a guest) is their name alone', async () => {
    const w = await mountGroup([{ userId: null, name: 'Ben (guest)' }])
    expect(w.get('[data-testid="person-name"]').text()).toBe('Ben (guest)')
    expect(w.findComponent(PersonAvatar).exists()).toBe(false)
    expect(w.find('a').exists()).toBe(false)
  })
})

describe('several people', () => {
  it.each([
    [2, 2],
    [3, 3],
  ])('%i people show %i avatars and no "+N"', async (n, avatars) => {
    const w = await mountGroup(of([A, B, C].slice(0, n)))
    expect(w.findAll('[data-testid="person-avatar"]')).toHaveLength(avatars)
    expect(w.find('[data-testid="person-overflow"]').exists()).toBe(false)
  })

  it.each([
    [4, '+2'],
    [5, '+3'],
  ])('%i people show two avatars and the third slot as "%s"', async (n, label) => {
    const w = await mountGroup(of([A, B, C, D, E].slice(0, n)))
    expect(w.findAll('[data-testid="person-avatar"]')).toHaveLength(2)
    expect(w.get('[data-testid="person-overflow"]').text()).toBe(label)
  })

  it('prints no names inline: the names wait in the hover card, and the avatars are a labelled button naming everybody', async () => {
    const w = await mountGroup(of([A, B, C, D]))
    expect(w.text()).not.toContain('Anna')
    const g = trigger(w)
    expect(g.element.tagName).toBe('BUTTON')
    expect(g.attributes('aria-haspopup')).toBe('dialog')
    expect(g.attributes('aria-expanded')).toBe('false')
    expect(g.attributes('aria-label')).toBe('Participants (4): Anna, Ben, Carl, Dora')
  })

  it('the card lists everyone with a link to the profile, and a greyed former member without one', async () => {
    const w = await mountGroup(of([A, GONE, B, C, D]))
    await trigger(w).trigger('click')
    await flushPromises()
    expect(trigger(w).attributes('aria-expanded')).toBe('true')
    const links = w.findAll('[data-testid="person-link"]')
    expect(links.map((l) => l.get('.veyq-person-name').text())).toEqual(['Anna', 'Ben', 'Carl', 'Dora'])
    expect(links.map((l) => l.attributes('href'))).toEqual([`/#people/${A}`, `/#people/${B}`, `/#people/${C}`, `/#people/${D}`])
    const former = w.findAll('[data-testid="person-former"]')
    expect(former).toHaveLength(1)
    expect(former[0]!.element.tagName).toBe('SPAN')
    expect(former[0]!.find('a').exists()).toBe(false)
  })

  it('opens on keyboard focus after the intent delay and closes on Escape', async () => {
    vi.useFakeTimers()
    const w = await mountGroup(of([A, B]))
    expect(w.find('[data-testid="person-list"]').exists()).toBe(false)
    ;(trigger(w).element as HTMLElement).focus()
    await trigger(w).trigger('focus')
    await vi.advanceTimersByTimeAsync(200)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(true)
    await w.get('[data-testid="person-link"]').trigger('keydown', { key: 'Escape', code: 'Escape' })
    await vi.advanceTimersByTimeAsync(400)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(false)
    expect(trigger(w).attributes('aria-expanded')).toBe('false')
  })

  it('opens on hover after the intent delay, not at once, and stays through the close grace period', async () => {
    vi.useFakeTimers()
    const w = await mountGroup(of([A, B]))
    await trigger(w).trigger('pointerenter', { pointerType: 'mouse' })
    expect(w.find('[data-testid="person-list"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(200)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(true)
    await trigger(w).trigger('pointerleave', { pointerType: 'mouse' })
    document.body.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerType: 'mouse', clientX: 500, clientY: 500 }))
    await vi.advanceTimersByTimeAsync(100)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(true) // inside the grace period: no flicker
    await vi.advanceTimersByTimeAsync(400)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(false)
  })

  it('a click toggles it (touch has no hover)', async () => {
    vi.useFakeTimers()
    const w = await mountGroup(of([A, B]))
    await trigger(w).trigger('click')
    await vi.advanceTimersByTimeAsync(10)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    await trigger(w).trigger('click')
    await vi.advanceTimersByTimeAsync(400)
    expect(w.find('[data-testid="person-list"]').exists()).toBe(false)
  })
})

describe('a share page (no `link`)', () => {
  it('links nobody and asks for no member picture, one person or several', async () => {
    const one = await mountGroup(of([A]), { link: false })
    expect(one.find('a').exists()).toBe(false)
    expect(one.findComponent(PersonAvatar).props('userId')).toBeNull()
    expect(one.text()).toContain('Anna')
    const many = await mountGroup(of([A, B, C]), { link: false })
    await trigger(many).trigger('click')
    await flushPromises()
    expect(many.find('a').exists()).toBe(false)
    expect(many.findAllComponents(PersonAvatar).every((a) => a.props('userId') === null)).toBe(true)
    // A positive control on the same predicate: the very group on a member's page does link and ask.
    const member = await mountGroup(of([A, B, C]))
    await trigger(member).trigger('click')
    await flushPromises()
    expect(member.findAll('a')).toHaveLength(3)
    expect(member.findAllComponents(PersonAvatar).some((a) => a.props('userId') === A)).toBe(true)
  })

  it('a status says what is still missing (the names loading, a read that failed)', async () => {
    const w = await mountGroup([], { status: 'Loading…' })
    expect(w.get('[role="status"]').text()).toBe('Loading…')
  })
})
