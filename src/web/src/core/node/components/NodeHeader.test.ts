// @vitest-environment happy-dom
// Core's `NodeHeader` (inbox #406, ADR 0110): the data it is fed is all it draws. An action that may be a button is a real
// labelled icon-only button that calls its handler on a real click, an action that is not `shown` is not rendered, a
// disabled one does not fire, core's own actions - asked of the view around it for the header's `node` - are merged around
// the application's, the kind's `order` comes first, and
// delete and report are never buttons but the ⋯ menu's last entries. Unmeasured (no layout here) every other action is a
// button; a measured row moves what does not fit into the menu (the count itself is `../nodeHeader.test.ts`'s).
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import NodeHeader from './NodeHeader.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { mergeHeaderActions, standardNodeActions, type CoreHeaderAction, type NodeHeaderAction } from '../nodeHeader'
import { coreNodeActionsKey, type CoreNodeActions } from '../coreNodeActions'
import type { UseNodeCover } from '../cover/useNodeCover'
import type { TreeNode } from '../tree'
import { treeNode } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'
import { chooseHeaderEntry, headerButtonIds, headerMenuIds, openHeaderMenu } from '../../../test/nodeHeader'

const mounted: VueWrapper[] = []
afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
  vi.restoreAllMocks()
})
type HeaderProps = Omit<InstanceType<typeof NodeHeader>['$props'], 'node'> & { node?: TreeNode | null }
/** The node a header heads in these tests, and a view around it that supplies `core` as core's actions of a node. */
const NODE = treeNode({ id: 'b0000000-0000-4000-8000-000000000001', kind: 'FOLDER', name: 'T' })
const asked: (TreeNode | null)[] = []
const coreView = (core: readonly CoreHeaderAction[]): CoreNodeActions => ({
  cover: {} as UseNodeCover,
  header: (node) => (asked.push(node), node ? [...core] : []),
  row: () => [],
})
const mountHeader = (props: HeaderProps, slots: Record<string, string> = {}, core: readonly CoreHeaderAction[] | null = null) => {
  const provide = core ? { [coreNodeActionsKey as symbol]: coreView(core) } : {}
  const w = mount(NodeHeader, { props: { node: core ? NODE : null, ...props }, slots, global: { plugins: [realI18n()], provide }, attachTo: document.body })
  mounted.push(w)
  return w
}
const act = (id: string, over: Partial<NodeHeaderAction> = {}): NodeHeaderAction => ({ id, label: `L-${id}`, icon: 'heart', shown: true, onClick: () => {}, ...over })
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
    const w = mountHeader(
      { title: 'T', actions: [act('share'), act('rename'), act('upload')], order: ['favorite', 'upload', 'download', 'share', 'missing'] },
      {},
      [{ ...act('favorite'), placement: 'before' as const }, act('download'), act('later')],
    )
    expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'download', 'share', 'rename', 'later'])
  })

  it('delete and report are never buttons: the ⋯ menu\'s last entries, report then delete', async () => {
    const remove = vi.fn()
    const w = mountHeader(
      { title: 'Trips', actions: [act('delete', { severity: 'danger', onClick: remove }), act('share')], order: ['share'] },
      {},
      [{ ...act('report'), menuOnly: true }, act('copy')],
    )
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

  describe('the layout, by the header\'s own width (ADR 0110 decision 8)', () => {
    // The sizes the answer reads, as the sheet gives them (no stylesheet is loaded here): a 192 px name minimum, 16 px gaps
    // beside the name, 7 px between buttons, a Memory-like 180 px leading visual. 41 px buttons: the floor's two and the ⋯
    // need 16 + 82 + 41 + 14 = 153 px, so the switch is at 180 + 16 + 192 + 153 = 541 px.
    let sheet: HTMLStyleElement
    beforeEach(() => {
      sheet = document.createElement('style')
      sheet.textContent = `
        .veyq-node-header { --veyq-node-header-name-min: 192px; column-gap: 16px }
        .veyq-node-header-titlerow { column-gap: 16px }
        .veyq-node-header-actions { column-gap: 7px }
        .lead { --veyq-node-header-leading-wide: 180px }`
      document.head.appendChild(sheet)
    })
    afterEach(() => sheet.remove())

    /** Layout widths as a browser would give them: the header `width` wide, its title row the rest beside the leading
     *  visual, the name `nameWidth` long, and the leading visual 64 px wide while the header is compact, 180 px wide. */
    function layoutWidths(width: number, nameWidth: (title: HTMLElement) => number = () => 80) {
      const compactNow = (el: HTMLElement) => !!el.closest('.veyq-node-header')?.classList.contains('is-compact')
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
        if (this.classList.contains('veyq-node-header')) return width
        if (this.classList.contains('veyq-node-header-titlerow')) return width - 180 - 16
        if (this.classList.contains('veyq-node-header-actions')) return width
        return 0
      })
      vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
        if (this.classList.contains('lead')) return compactNow(this) ? 64 : 180
        return this.matches('.veyq-node-header-action, .veyq-node-header-more') ? 41 : 0
      })
      vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return this.classList.contains('veyq-node-header-title') ? nameWidth(this) : 0
      })
    }
    const memoryActions = () => ['favorite', 'upload', 'share', 'request', 'people', 'download', 'cover', 'banner', 'rename', 'move', 'copy'].map((id) => act(id))
    const leading = { leading: '<span class="lead" :class="{ small: params.compact }" :data-compact="String(params.compact)"></span>' }
    const mountMemory = (title: string) => mountHeader({ title, actions: [...memoryActions(), act('delete', { severity: 'danger' })] }, leading)

    it('compact below the switch, wide at it: the header\'s class and the leading slot\'s `compact` follow the answer', async () => {
      layoutWidths(540)
      const narrow = mountMemory('Rom 2026')
      await flushPromises()
      expect(narrow.find('.veyq-node-header').classes()).toContain('is-compact')
      expect(narrow.find('.lead').attributes('data-compact')).toBe('true')
      vi.restoreAllMocks()
      layoutWidths(541)
      const wide = mountMemory('Rom 2026')
      await flushPromises()
      expect(wide.find('.veyq-node-header').classes()).not.toContain('is-compact')
      expect(wide.find('.lead').attributes('data-compact')).toBe('false')
    })

    it('a short and a very long name at the same width get the same layout', async () => {
      for (const width of [540, 541, 900]) {
        layoutWidths(width, (t) => (t.textContent!.length > 20 ? 2000 : 80))
        const short = mountMemory('Rom 2026')
        const long = mountMemory('Sommerurlaub an der Algarve mit der ganzen Familie und den Nachbarn 2025')
        await flushPromises()
        expect(long.find('.veyq-node-header').classes().includes('is-compact'), `${width} px`).toBe(short.find('.veyq-node-header').classes().includes('is-compact'))
        vi.restoreAllMocks()
      }
    })

    it('wide, the name comes first: a long one leaves the floor\'s two buttons, a short one as many as fit', async () => {
      layoutWidths(900, (t) => (t.textContent!.length > 20 ? 2000 : 80))
      const long = mountMemory('Sommerurlaub an der Algarve mit der ganzen Familie und den Nachbarn 2025')
      const short = mountMemory('Rom 2026')
      await flushPromises()
      expect(headerButtonIds(long)).toEqual(['favorite', 'upload'])
      // The title row is 900 - 196 = 704 px, less the name (80 + 1) and the gap: 607 px for k buttons and the ⋯ (48k + 41).
      expect(headerButtonIds(short)).toHaveLength(11)
      expect(await headerMenuIds(long)).toEqual(['share', 'request', 'people', 'download', 'cover', 'banner', 'rename', 'move', 'copy', 'delete'])
    })

    it('compact, the actions\' own row holds as many as fit; no floor there', async () => {
      layoutWidths(300)
      const w = mountMemory('Rom 2026')
      await flushPromises()
      // A 300 px row: the ⋯ (41) and five 48 px buttons.
      expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'share', 'request', 'people'])
    })

    it('never flips: every re-measure keeps the answer although the leading visual is 64 px while compact', async () => {
      // A width where a 64 px leading visual would fit wide and the 180 px one does not: a measure that read the leading's
      // current box would answer wide, then compact, then wide…
      let resized: (() => void) | null = null
      vi.stubGlobal('ResizeObserver', class {
        constructor(cb: () => void) {
          resized = cb
        }
        observe() {}
        disconnect() {}
      })
      vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
        cb(0)
        return 0
      })
      layoutWidths(480)
      const w = mountMemory('Rom 2026')
      const seen: boolean[] = []
      for (let i = 0; i < 4; i++) {
        await flushPromises()
        seen.push(w.find('.veyq-node-header').classes().includes('is-compact'))
        resized!()
      }
      vi.unstubAllGlobals()
      expect(seen).toEqual([true, true, true, true])
    })

    it('names the width its drawn layout belongs to: the old one until the resize\'s measurement has run', async () => {
      // A browser spec picks a button or the ⋯ from the drawn layout; right after a resize that layout is the old width's
      // until the ResizeObserver's frame has measured. `data-layout-width` is how it tells the two apart.
      let resized: (() => void) | null = null
      vi.stubGlobal('ResizeObserver', class {
        constructor(cb: () => void) {
          resized = cb
        }
        observe() {}
        disconnect() {}
      })
      vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
        cb(0)
        return 0
      })
      layoutWidths(300)
      const w = mountMemory('Rom 2026')
      await flushPromises()
      const head = () => w.find('.veyq-node-header')
      expect(head().attributes('data-layout-width')).toBe('300')
      expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'share', 'request', 'people'])

      // The header is 900 px now, its observer's frame not yet run: still the 300 px layout, and it says so.
      vi.restoreAllMocks()
      layoutWidths(900)
      await flushPromises()
      expect(head().attributes('data-layout-width')).toBe('300')
      expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'share', 'request', 'people'])

      resized!()
      await flushPromises()
      vi.unstubAllGlobals()
      expect(head().attributes('data-layout-width')).toBe('900')
      expect(headerButtonIds(w)).toHaveLength(11)
    })
  })

  it('core\'s own actions sit before or after the application\'s, gated like any other', () => {
    const w = mountHeader({ title: 'T', actions: [act('app')] }, {}, [act('c-after'), { ...act('c-before'), placement: 'before' as const }, act('c-off', { shown: false })])
    expect(labels(w)).toEqual(['L-c-before', 'L-app', 'L-c-after'])
  })

  // Core's actions come centrally (ADR 0110): the header asks the view around it for ITS node; the page builds none.
  it('asks the view around it for core\'s actions of the node it heads - none for the space\'s top level, none without a view', () => {
    asked.length = 0
    const w = mountHeader({ title: 'T', actions: [act('app')] }, {}, [{ ...act('report'), menuOnly: true }])
    expect(asked.map((n) => n?.id)).toContain(NODE.id)
    expect(w.find('.veyq-node-header-more').exists(), 'core\'s report in the menu').toBe(true)
    asked.length = 0
    const top = mountHeader({ title: 'Space', node: null }, {}, [act('favorite')])
    expect(asked).toEqual([null])
    expect(top.find('.veyq-node-header-actions').exists()).toBe(false)
    expect(mountHeader({ title: 'T', node: NODE, actions: [act('app')] }).findAll('.veyq-node-header-action').map((b) => b.attributes('data-action'))).toEqual(['app'])
  })
})

describe('nodeHeader helpers', () => {
  it('mergeHeaderActions puts core before / after the application list', () => {
    const ids = mergeHeaderActions([{ ...act('x'), placement: 'before' }, act('y')], [act('a')]).map((a) => a.id)
    expect(ids).toEqual(['x', 'a', 'y'])
  })

  it('standardNodeActions: only the granted ones, share, rename, move, delete; the kind may relabel', () => {
    const on = { share: vi.fn(), rename: vi.fn(), move: vi.fn(), remove: vi.fn() }
    const all = standardNodeActions(t, { share: true, rename: true, move: true, delete: true, deleteLabel: 'Drop it' }, on)
    expect(all.map((a) => a.id)).toEqual(['share', 'rename', 'move', 'delete'])
    expect(all[3].label).toBe('Drop it')
    expect(all[3].severity).toBe('danger')
    all.forEach((a) => a.onClick())
    expect(Object.values(on).every((f) => f.mock.calls.length === 1)).toBe(true)
    expect(standardNodeActions(t, { rename: true }, on).map((a) => a.id)).toEqual(['rename'])
    expect(standardNodeActions(t, {}, on)).toEqual([])
  })
})
