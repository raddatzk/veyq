// @vitest-environment happy-dom
// Core's `NodeHeader` (inbox #406, ADR 0110): the data it is fed is all it draws. An action that may be a button is a real
// labelled icon-only button that calls its handler on a real click, an action that is not `shown` is not rendered, a
// disabled one does not fire, core's own actions are merged around the application's, the kind's `order` comes first, and
// delete and report are never buttons but the ⋯ menu's last entries. Unmeasured (no layout here) every other action is a
// button; a measured row moves what does not fit into the menu (the count itself is `../nodeHeader.test.ts`'s).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import NodeHeader from './NodeHeader.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { mergeHeaderActions, standardNodeActions, type NodeHeaderAction } from '../nodeHeader'
import { realI18n, t } from '../../../test/i18n'
import { chooseHeaderEntry, headerButtonIds, headerMenuIds, openHeaderMenu } from '../../../test/nodeHeader'

const mounted: VueWrapper[] = []
afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
  vi.restoreAllMocks()
})
const mountHeader = (props: InstanceType<typeof NodeHeader>['$props'], slots: Record<string, string> = {}) => {
  const w = mount(NodeHeader, { props, slots, global: { plugins: [realI18n()] }, attachTo: document.body })
  mounted.push(w)
  return w
}
const act = (id: string, over: Partial<NodeHeaderAction> = {}): NodeHeaderAction => ({ id, label: `L-${id}`, icon: 'heart', onClick: () => {}, ...over })
const labels = (w: ReturnType<typeof mountHeader>) => w.findAll('.veyq-node-header-action').map((b) => b.attributes('aria-label'))

describe('NodeHeader', () => {
  it('draws the title as the page\'s one h1, the subtitle and the kind icon', () => {
    const w = mountHeader({ title: 'Trips', subtitle: '3 items', icon: 'folder' })
    // Every header opens its node's page, so its title is that page's h1 (styleguide § Heading levels).
    expect(w.findAll('h1')).toHaveLength(1)
    expect(w.findAll('h2, h3, h4, h5, h6')).toHaveLength(0)
    expect(w.find('h1.veyq-node-header-title').text()).toBe('Trips')
    // The name shows two lines at most (`ui.css`); the whole name stays in the DOM and is the title's tooltip.
    expect(w.find('h1.veyq-node-header-title').attributes('title')).toBe('Trips')
    expect(w.find('.veyq-node-header-subtitle').text()).toBe('3 items')
    expect(w.findComponent(VeyqIcon).classes()).toContain('veyq-node-header-icon')
    expect(w.findComponent(VeyqIcon).props('name')).toBe('folder')
  })

  it('the leading slot replaces the icon (a cover), the meta slot sits under the name', () => {
    const w = mountHeader({ title: 'Rome', icon: 'images' }, { leading: '<img class="cover">', meta: '<i class="chips" />' })
    expect(w.find('.veyq-node-header-leading .cover').exists()).toBe(true)
    expect(w.find('.veyq-node-header-icon').exists()).toBe(false)
    expect(w.find('.veyq-node-header').classes()).toContain('has-visual')
    expect(w.find('.veyq-node-header-body .chips').exists()).toBe(true)
  })

  it('renders only the shown actions, labelled, in order — and no empty action row', () => {
    const w = mountHeader({ title: 'T', actions: [act('a'), act('b', { shown: false }), act('c')] })
    expect(labels(w)).toEqual(['L-a', 'L-c'])
    expect(mountHeader({ title: 'T', actions: [act('a', { shown: false })] }).find('.veyq-node-header-actions').exists()).toBe(false)
  })

  it('a click on each action calls its own handler; a disabled one does not fire', async () => {
    const fns = { a: vi.fn(), b: vi.fn(), c: vi.fn() }
    const w = mountHeader({ title: 'T', actions: [act('a', { onClick: fns.a }), act('b', { onClick: fns.b }), act('c', { onClick: fns.c, disabled: true })] })
    for (const id of ['a', 'b', 'c']) await w.find(`button[data-action="${id}"]`).trigger('click')
    expect(fns.a).toHaveBeenCalledTimes(1)
    expect(fns.b).toHaveBeenCalledTimes(1)
    expect(fns.c).not.toHaveBeenCalled()
  })

  it('every action is a real, focusable button of type button (keyboard: Tab, Enter, Space are the browser\'s)', () => {
    const w = mountHeader({ title: 'T', actions: [act('a'), act('b')] })
    for (const b of w.findAll('.veyq-node-header-actions button')) {
      expect(b.element.tagName).toBe('BUTTON')
      expect(b.attributes('type')).toBe('button')
      expect(b.attributes('tabindex')).toBeUndefined()
    }
  })

  it('marks a toggle\'s pressed state; a button carries no text label (icon-only, one size: the fit\'s premise)', () => {
    const w = mountHeader({ title: 'T', actions: [act('fav', { pressed: true }), act('off', { pressed: false }), act('up', { severity: 'primary' }), act('x')] })
    expect(w.find('[data-action="fav"]').attributes('aria-pressed')).toBe('true')
    expect(w.find('[data-action="off"]').attributes('aria-pressed')).toBe('false')
    expect(w.find('[data-action="x"]').attributes('aria-pressed')).toBeUndefined()
    for (const b of w.findAll('.veyq-node-header-action')) expect(b.text(), b.attributes('data-action')).toBe('')
  })

  it('the kind\'s order puts its listed ids first, in that order; an unlisted action still renders, after them', () => {
    const w = mountHeader({
      title: 'T',
      actions: [act('share'), act('rename'), act('upload')],
      coreActions: [{ ...act('favorite'), placement: 'before' as const }, act('download'), act('later')],
      order: ['favorite', 'upload', 'download', 'share', 'missing'],
    })
    expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'download', 'share', 'rename', 'later'])
  })

  it('delete and report are never buttons: the ⋯ menu\'s last entries, report then delete', async () => {
    const remove = vi.fn()
    const w = mountHeader({
      title: 'Trips',
      actions: [act('delete', { severity: 'danger', onClick: remove }), act('share')],
      coreActions: [{ ...act('report'), menuOnly: true }, act('copy')],
      order: ['share'],
    })
    expect(headerButtonIds(w)).toEqual(['share', 'copy'])
    expect(w.find('button[data-action="delete"]').exists()).toBe(false)
    expect(w.find('button[data-action="report"]').exists()).toBe(false)
    const more = w.find('.veyq-node-header-more')
    expect(more.attributes('aria-label')).toBe(t('node.rowMore', { name: 'Trips' }))
    expect(more.attributes('aria-haspopup')).toBe('menu')
    expect(await headerMenuIds(w)).toEqual(['report', 'delete'])
    const del = await chooseHeaderEntry(w, 'delete')
    expect(del.classList).toContain('is-danger')
    expect(del.textContent?.trim()).toBe('L-delete')
    expect(remove).toHaveBeenCalledTimes(1)
  })

  it('no menu-only action and everything a button: no ⋯ at all', () => {
    expect(mountHeader({ title: 'T', actions: [act('a'), act('b')] }).find('.veyq-node-header-more').exists()).toBe(false)
  })

  it('measured: as many as fit are buttons in order, the rest are entries before the separator; a disabled one stays disabled', async () => {
    // A 200 px row of 36 px buttons and no gap (no stylesheet here): the ⋯ is reserved first, so four buttons beside it.
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(200)
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(36)
    const off = vi.fn()
    const w = mountHeader({
      title: 'T',
      actions: [act('a'), act('b'), act('c'), act('d'), act('e'), act('f', { disabled: true, onClick: off }), act('delete', { severity: 'danger' })],
      order: ['b', 'a'],
    })
    await flushPromises()
    expect(headerButtonIds(w)).toEqual(['b', 'a', 'c', 'd'])
    const entries = await openHeaderMenu(w)
    const menu = document.body.querySelector('.veyq-menu')!
    expect([...menu.children].map((c) => (c.classList.contains('veyq-menu__separator') ? '|' : c.className.match(/veyq-node-header-action-(\w+)/)![1]))).toEqual(['e', 'f', '|', 'delete'])
    expect(entries[1].getAttribute('aria-disabled')).toBe('true')
    entries[1].querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
    expect(off).not.toHaveBeenCalled()
  })

  it('core\'s own actions sit before or after the application\'s, gated like any other', () => {
    const w = mountHeader({
      title: 'T',
      actions: [act('app')],
      coreActions: [act('c-after'), { ...act('c-before'), placement: 'before' as const }, act('c-off', { shown: false })],
    })
    expect(labels(w)).toEqual(['L-c-before', 'L-app', 'L-c-after'])
  })
})

describe('nodeHeader helpers', () => {
  it('mergeHeaderActions puts core before / after the application list', () => {
    const ids = mergeHeaderActions([{ ...act('x'), placement: 'before' }, act('y')], [act('a')]).map((a) => a.id)
    expect(ids).toEqual(['x', 'a', 'y'])
  })

  it('standardNodeActions: only the granted ones, share, rename, move, delete; the kind may relabel', () => {
    const on = { share: vi.fn(), rename: vi.fn(), move: vi.fn(), remove: vi.fn() }
    const all = standardNodeActions(t, { share: true, rename: true, move: true, remove: true, deleteLabel: 'Drop it' }, on)
    expect(all.map((a) => a.id)).toEqual(['share', 'rename', 'move', 'delete'])
    expect(all[3].label).toBe('Drop it')
    expect(all[3].severity).toBe('danger')
    all.forEach((a) => a.onClick())
    expect(Object.values(on).every((f) => f.mock.calls.length === 1)).toBe(true)
    expect(standardNodeActions(t, { rename: true }, on).map((a) => a.id)).toEqual(['rename'])
    expect(standardNodeActions(t, {}, on)).toEqual([])
  })
})
