// @vitest-environment happy-dom
// Core's `NodeHeader` (inbox #406): the data it is fed is all it draws. Every action is a real labelled button that calls
// its handler on a real click, an action that is not `shown` is not rendered, a disabled one does not fire, core's own
// actions are merged around the application's, and the leading visual is the icon or the application's slot.
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import NodeHeader from './NodeHeader.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { mergeHeaderActions, standardNodeActions, type NodeHeaderAction } from '../nodeHeader'
import { realI18n, t } from '../../../test/i18n'

const mountHeader = (props: InstanceType<typeof NodeHeader>['$props'], slots: Record<string, string> = {}) =>
  mount(NodeHeader, { props, slots, global: { plugins: [realI18n()] } })
const act = (id: string, over: Partial<NodeHeaderAction> = {}): NodeHeaderAction => ({ id, label: `L-${id}`, icon: 'heart', onClick: () => {}, ...over })
const labels = (w: ReturnType<typeof mountHeader>) => w.findAll('.veyq-node-header-actions button').map((b) => b.attributes('aria-label'))

describe('NodeHeader', () => {
  it('draws the title at the asked heading level, the subtitle and the kind icon', () => {
    const w = mountHeader({ title: 'Trips', subtitle: '3 items', icon: 'folder', level: 1 })
    expect(w.find('h1.veyq-node-header-title').text()).toBe('Trips')
    expect(w.find('.veyq-node-header-subtitle').text()).toBe('3 items')
    expect(w.findComponent(VeyqIcon).classes()).toContain('veyq-node-header-icon')
    expect(w.findComponent(VeyqIcon).props('name')).toBe('folder')
    expect(mountHeader({ title: 'T' }).find('h2.veyq-node-header-title').exists()).toBe(true)
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

  it('marks danger and a toggle\'s pressed state', () => {
    const w = mountHeader({ title: 'T', actions: [act('del', { severity: 'danger' }), act('fav', { pressed: true }), act('off', { pressed: false })] })
    expect(w.find('[data-action="del"]').classes()).toContain('veyq-danger-action')
    expect(w.find('[data-action="fav"]').attributes('aria-pressed')).toBe('true')
    expect(w.find('[data-action="off"]').attributes('aria-pressed')).toBe('false')
    expect(w.find('[data-action="del"]').attributes('aria-pressed')).toBeUndefined()
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
