// @vitest-environment happy-dom
// Core's `NodeHeader` (inbox #406, ADR 0110): the data it is fed is all it draws. An action that may be a button is a real
// labelled icon-only button that calls its handler on a real click, an action that is not `shown` is not rendered, a
// disabled one does not fire, core's own actions - asked of the view around it for the header's `node` - are merged around
// the application's, the kind's `order` comes first, and
// delete and report are never buttons but the ⋯ menu's last entries. Unmeasured (no layout here) every other action is a
// button; a measured row moves what does not fit into the menu (the count itself is `../nodeHeader.test.ts`'s).
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import NodeHeader from './NodeHeader.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import {
  mergeHeaderActions,
  standardActionList,
  standardNodeActions,
  standardNodeRename,
  type CoreHeaderAction,
  type NodeHeaderAction,
  type NodeHeaderFact,
} from '../nodeHeader'
import { NodeNameTaken } from '../nodeName'
import { registry } from '../../applications/registry'
import { coreNodeActionsKey, type CoreNodeActions } from '../coreNodeActions'
import type { UseNodeCover } from '../cover/useNodeCover'
import type { TreeNode } from '../tree'
import { treeNode } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'
import { chooseHeaderEntry, headerButtonIds, headerMenuIds, openHeaderMenu } from '../../../test/nodeHeader'
import { coreActionsStandIn, coverStandIn } from '../../../test/nodePage'
import PersonAvatar from '../../people/PersonAvatar.vue'

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

  describe('the cover is core\'s (inbox #411, owner 2026-11-03)', () => {
    const COVER = { url: 'blob:cover', crop: { x: 0, y: 0, width: 1, height: 1 } }
    const withCover = (opts: Parameters<typeof coverStandIn>[0]) => {
      const w = mount(NodeHeader, {
        props: { node: NODE, title: 'Rome', icon: 'images', cover: true },
        global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn(opts)) },
        attachTo: document.body,
      })
      mounted.push(w)
      return w
    }

    it('a kind that takes a cover shows the node\'s cover; without one, the empty 16:9 frame with the kind\'s icon in it', () => {
      const w = withCover({ covers: new Map([[NODE.id, COVER]]) })
      expect(w.find('.veyq-node-header-leading .veyq-node-header-cover img').attributes('src')).toBe('blob:cover')
      expect(w.find('.veyq-node-header-cover').classes()).not.toContain('is-empty')
      expect(w.find('.veyq-node-header-icon').exists()).toBe(false)
      // A view with nothing resolved for the node: the empty frame - the cover's box, the kind's icon in its middle - and,
      // without the right to set one, a plain frame: no button, no hint.
      const bare = withCover({})
      const frame = bare.find('.veyq-node-header-leading > .veyq-node-header-cover')
      expect(frame.classes()).toContain('is-empty')
      expect(frame.element.tagName).toBe('SPAN')
      expect(frame.attributes('data-testid')).toBe('node-header-cover-frame')
      expect(frame.findComponent(VeyqIcon).props('name')).toBe('images')
      expect(frame.find('.veyq-node-header-cover-hint').exists()).toBe(false)
      expect(bare.find('button').exists()).toBe(false)
      expect(bare.find('.veyq-node-header-icon').exists(), 'no icon tile for a kind that takes a cover').toBe(false)
      // The name is one size, cover or not: no class switches it.
      expect(w.find('.veyq-node-header').classes()).toEqual(bare.find('.veyq-node-header').classes())
    })

    it('the cover button carries its words from three rows on; a lower frame (a folder\'s name alone) is the glyph alone', () => {
      const header = (props: Record<string, unknown>) => {
        const w = mount(NodeHeader, {
          props: { node: NODE, title: 'Rome', icon: 'images', cover: true, ...props },
          global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn({ canSet: true })) },
          attachTo: document.body,
        })
        mounted.push(w)
        return w.find('button[data-testid="node-header-cover"]').classes()
      }
      expect(header({}), 'the name alone: one row').not.toContain('is-roomy')
      expect(header({ subtitle: '3 items' }), 'two rows').not.toContain('is-roomy')
      expect(header({ subtitle: '3 items', facts: [{ kind: 'date', label: 'Spring', empty: true }] })).toContain('is-roomy')
    })

    it('a kind without a cover never shows one, whatever the view resolved', () => {
      const w = mount(NodeHeader, {
        props: { node: NODE, title: 'Rome', icon: 'file' },
        global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn({ covers: new Map([[NODE.id, COVER]]), canSet: true })) },
      })
      mounted.push(w)
      expect(w.find('.veyq-node-header-cover').exists()).toBe(false)
      expect(w.find('[data-testid="node-header-cover"]').exists()).toBe(false)
      expect(w.find('.veyq-node-header-icon').exists()).toBe(true)
    })

    it('where the cover may be set, the visual is a labelled button that opens core\'s dialog for THIS node', async () => {
      const open = vi.fn()
      const empty = withCover({ canSet: true, open })
      const button = empty.find('button[data-testid="node-header-cover"]')
      // Without a cover the button IS the empty frame, the kind's icon in it, named "Set cover".
      expect(button.classes()).toEqual(expect.arrayContaining(['veyq-node-header-cover', 'is-empty']))
      expect(button.findComponent(VeyqIcon).props('name')).toBe('images')
      expect(empty.find('.veyq-node-header-icon').exists()).toBe(false)
      expect(button.attributes('aria-label')).toBe(t('node.cover.set'))
      expect(button.find('.veyq-node-header-cover-hint').exists()).toBe(true)
      await button.trigger('click')
      expect(open).toHaveBeenCalledWith(NODE)
      // With one, it is the cover, named "Change cover".
      const set = withCover({ canSet: true, covers: new Map([[NODE.id, COVER]]) })
      expect(set.find('button[data-testid="node-header-cover"]').classes()).toContain('veyq-node-header-cover')
      expect(set.find('button[data-testid="node-header-cover"]').classes()).not.toContain('is-empty')
      expect(set.find('button[data-testid="node-header-cover"]').attributes('aria-label')).toBe(t('node.cover.change'))
    })

    it('without the right, the visual is no button and opens nothing', () => {
      const open = vi.fn()
      const w = withCover({ canSet: false, open, covers: new Map([[NODE.id, COVER]]) })
      expect(w.find('button').exists()).toBe(false)
      expect(w.find('.veyq-node-header-cover img').exists()).toBe(true)
    })
  })

  describe('the facts under the name', () => {
    it('chips share one row - a button that opens the application\'s editor, greyed where disabled; plain text without a click', async () => {
      const edit = vi.fn()
      const w = mountHeader({
        title: 'Rome',
        facts: [
          { kind: 'date', label: '06/15/2026', actionLabel: 'Edit date', onClick: edit },
          { kind: 'place', label: 'Set place', empty: true, onClick: edit, disabled: true },
          { kind: 'text', text: 'In EUR' },
          { kind: 'date', label: 'Spring', empty: true },
        ],
      })
      const rows = w.findAll('.veyq-node-header-facts')
      expect(rows.map((r) => r.findAll('[data-fact]').map((f) => f.attributes('data-fact')))).toEqual([['date', 'place'], ['text'], ['date']])
      const date = w.find('button[data-fact="date"]')
      expect(date.attributes('aria-label')).toBe('Edit date')
      expect(date.text()).toBe('06/15/2026')
      await date.trigger('click')
      expect(edit).toHaveBeenCalledOnce()
      const place = w.find('button[data-fact="place"]')
      expect(place.classes()).toContain('is-empty')
      expect(place.attributes('disabled')).toBeDefined()
      expect(rows[2]!.find('[data-fact="date"]').element.tagName).toBe('SPAN')
      expect(w.find('[data-fact="text"]').text()).toBe('In EUR')
    })

    it('people link to a profile only on a member\'s page (`linkPeople`); by default - a share page - no link and no picture', async () => {
      const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
      await router.push('/')
      const facts = [{ kind: 'people' as const, label: 'Uploaded by', caption: 'Uploaded by', people: [{ userId: 'u-anna', name: 'Anna' }] }]
      const member = mount(NodeHeader, { props: { node: null, title: 'f', facts, linkPeople: true }, global: { plugins: [realI18n(), router] } })
      const guest = mount(NodeHeader, { props: { node: null, title: 'f', facts }, global: { plugins: [realI18n(), router] } })
      mounted.push(member, guest)
      expect(member.find('[data-testid="person-link"]').attributes('href')).toContain('#people/u-anna')
      expect(member.find('.veyq-person-group-caption').text()).toBe('Uploaded by')
      expect(guest.find('[data-testid="person-link"]').exists()).toBe(false)
      expect(guest.find('a').exists()).toBe(false)
      expect(guest.findComponent(PersonAvatar).props('userId')).toBeNull()
      expect(guest.text()).toContain('Anna')
    })
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
        .veyq-node-header-icon { --veyq-node-header-leading-wide: 180px }`
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
        if (this.classList.contains('veyq-node-header-icon')) return compactNow(this) ? 64 : 180
        return this.matches('.veyq-node-header-action, .veyq-node-header-more') ? 41 : 0
      })
      vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return this.classList.contains('veyq-node-header-title') ? nameWidth(this) : 0
      })
    }
    const memoryActions = () => ['favorite', 'upload', 'share', 'request', 'people', 'download', 'cover', 'banner', 'rename', 'move', 'copy'].map((id) => act(id))
    // The leading visual is the icon tile here, given the 180 px wide width above (a cover's is 244 px in the sheet).
    const mountMemory = (title: string) => mountHeader({ title, icon: 'images', actions: [...memoryActions(), act('delete', { severity: 'danger' })] })

    it('compact below the switch, wide at it: the header\'s class follows the answer', async () => {
      layoutWidths(540)
      const narrow = mountMemory('Rom 2026')
      await flushPromises()
      expect(narrow.find('.veyq-node-header').classes()).toContain('is-compact')
      vi.restoreAllMocks()
      layoutWidths(541)
      const wide = mountMemory('Rom 2026')
      await flushPromises()
      expect(wide.find('.veyq-node-header').classes()).not.toContain('is-compact')
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

describe('NodeHeader: what is editable at its place has no action (ADR 0110 decision 11)', () => {
  const input = (w: VueWrapper) => w.find<HTMLInputElement>('input[data-testid="node-header-name-input"]')
  const mountRenamable = (save: (name: string) => Promise<void>, node: TreeNode = NODE, title = 'Trips') =>
    mountHeader({ title, node, rename: { save } })
  async function startEditing(w: VueWrapper) {
    await w.find('button[data-testid="node-header-rename"]').trigger('click')
    await flushPromises()
    return input(w)
  }

  it('without a rename the name is plain text in the page\'s one h1 - no button, no field', () => {
    const w = mountHeader({ title: 'Trips', node: NODE })
    const h1 = w.find('h1.veyq-node-header-title')
    expect(h1.text()).toBe('Trips')
    expect(h1.attributes('title')).toBe('Trips')
    expect(h1.find('button').exists()).toBe(false)
    expect(w.find('input').exists()).toBe(false)
  })

  it('with one, the h1 holds a real button named "Rename “…”" - still the page\'s one heading', () => {
    const w = mountRenamable(vi.fn())
    expect(w.findAll('h1')).toHaveLength(1)
    const button = w.find('h1.veyq-node-header-title > button.veyq-node-header-rename')
    expect(button.attributes('type')).toBe('button')
    expect(button.attributes('aria-label')).toBe(t('node.renameNamed', { name: 'Trips' }))
    expect(button.text()).toBe('Trips')
    // The heading itself is named by the name, never by its button's "Rename …" (a reader's heading list, a spec's locator).
    expect(w.find('h1').attributes('aria-label')).toBe('Trips')
    // No tooltip on the name (owner 2026-11-04): no layer tooltip trigger (it marks its trigger with `data-state`, as every
    // action button of this header has), no native title on the button or the heading.
    const withAction = mountHeader({ title: 'Trips', node: NODE, rename: { save: vi.fn() }, actions: [{ id: 'x', label: 'X', icon: 'pencil', onClick: vi.fn(), shown: true }] })
    expect(withAction.find('.veyq-node-header-actions button').attributes('data-state'), 'control: a tooltip trigger is marked').toBeDefined()
    expect(withAction.find('button[data-testid="node-header-rename"]').attributes('data-state')).toBeUndefined()
    expect(button.attributes('data-state')).toBeUndefined()
    expect(button.attributes('title')).toBeUndefined()
    expect(w.find('h1').attributes('title')).toBeUndefined()
  })

  it('a click makes it a field holding the name, pre-selected whole; Enter saves the trimmed name through the page\'s write', async () => {
    let done!: () => void
    const save = vi.fn((_name: string) => new Promise<void>((r) => (done = r)))
    const w = mountRenamable(save)
    const field = await startEditing(w)
    expect(field.element.value).toBe('Trips')
    expect(document.activeElement).toBe(field.element)
    expect([field.element.selectionStart, field.element.selectionEnd]).toEqual([0, 5])
    await field.setValue('  Reisen  ')
    await field.trigger('keydown', { key: 'Enter' })
    expect(save).toHaveBeenCalledExactlyOnceWith('Reisen')
    // Open while the write runs; a second Enter sends nothing more.
    await field.trigger('keydown', { key: 'Enter' })
    expect(save).toHaveBeenCalledOnce()
    done()
    await flushPromises()
    expect(input(w).exists()).toBe(false)
    expect(w.find('button[data-testid="node-header-rename"]').exists()).toBe(true)
  })

  it('pre-selects what the kind says a rename changes, as core\'s name dialog does (a file\'s name before its extension)', async () => {
    const real = registry.kind.bind(registry)
    vi.spyOn(registry, 'kind').mockImplementation(((kind: string) =>
      kind === 'FILE' ? { ...real('FOLDER'), supported: true, renameSelectionEnd: (n: string) => n.lastIndexOf('.') } : real(kind)) as typeof registry.kind)
    const file = treeNode({ id: 'f0000000-0000-4000-8000-000000000001', kind: 'FILE', name: 'notes.txt' })
    const field = await startEditing(mountRenamable(vi.fn(), file, 'notes.txt'))
    expect([field.element.selectionStart, field.element.selectionEnd]).toEqual([0, 5])
  })

  it('Esc cancels: nothing is written and the name is back', async () => {
    const save = vi.fn(async () => {})
    const w = mountRenamable(save)
    const field = await startEditing(w)
    await field.setValue('Reisen')
    await field.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(save).not.toHaveBeenCalled()
    expect(input(w).exists()).toBe(false)
    expect(w.find('h1').text()).toBe('Trips')
  })

  it('an unchanged or empty name writes nothing: Enter on the same name closes, an empty one keeps the field', async () => {
    const save = vi.fn(async () => {})
    const w = mountRenamable(save)
    let field = await startEditing(w)
    await field.setValue('   ')
    await field.trigger('keydown', { key: 'Enter' })
    expect(input(w).exists()).toBe(true)
    await field.setValue(' Trips ')
    await field.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(input(w).exists()).toBe(false)
    field = await startEditing(w)
    expect(save).not.toHaveBeenCalled()
  })

  it('a refused name stays in the field with the dialog\'s words under it - a collision names the sibling by its kind', async () => {
    const sibling = treeNode({ id: 'b0000000-0000-4000-8000-000000000002', kind: 'FOLDER', name: 'Reisen' })
    const save = vi.fn(async () => {
      throw new NodeNameTaken(sibling)
    })
    const w = mountRenamable(save)
    const field = await startEditing(w)
    await field.setValue('Reisen')
    await field.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(input(w).exists()).toBe(true)
    const error = w.find('.veyq-node-header-rename-error')
    expect(error.text()).toBe(t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'Reisen' }))
    expect(error.attributes('role')).toBe('alert')
    expect(input(w).attributes('aria-describedby')).toBe(error.attributes('id'))
    // Esc still leaves, the error with it.
    await input(w).trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(w.find('.veyq-node-header-rename-error').exists()).toBe(false)
  })

  it('blur keeps what was typed: a changed name is written; the window losing focus leaves the field open', async () => {
    const save = vi.fn(async () => {})
    const focus = vi.spyOn(document, 'hasFocus').mockReturnValue(false)
    const w = mountRenamable(save)
    const field = await startEditing(w)
    await field.setValue('Reisen')
    await field.trigger('blur')
    expect(save).not.toHaveBeenCalled()
    expect(input(w).exists()).toBe(true)
    focus.mockReturnValue(true)
    await field.trigger('blur')
    await flushPromises()
    expect(save).toHaveBeenCalledExactlyOnceWith('Reisen')
    expect(input(w).exists()).toBe(false)
  })

  it('the rows beside the visual are handed to the sheet as counts: the visual\'s height is computed, never measured', () => {
    const facts: NodeHeaderFact[] = [
      { kind: 'date', label: '2026' },
      { kind: 'place', label: 'Rome' },
      { kind: 'people', label: 'Participants', people: [{ userId: null, name: 'Anna' }] },
    ]
    const style = mountHeader({ title: 'Rome', subtitle: '3 items', facts }).find('.veyq-node-header').attributes('style')
    expect(style).toContain('--veyq-node-header-subtitle-rows: 1')
    expect(style).toContain('--veyq-node-header-chip-rows: 1')
    expect(style).toContain('--veyq-node-header-people-rows: 1')
    expect(style).toContain('--veyq-node-header-text-rows: 0')
    const folder = mountHeader({ title: 'Trips' }).find('.veyq-node-header').attributes('style')
    expect(folder).toContain('--veyq-node-header-subtitle-rows: 0')
    expect(folder).toContain('--veyq-node-header-chip-rows: 0')
    expect(folder).toContain('--veyq-node-header-people-rows: 0')
  })

  it('a people fact with an editor opens it from the fact: a button beside the people, the empty chip with nobody yet', async () => {
    const edit = vi.fn()
    const people = { kind: 'people' as const, label: 'Participants', editLabel: 'Edit participants', emptyLabel: 'Add participants', onEdit: edit }
    const empty = mountHeader({ title: 'Rome', facts: [{ ...people, people: [] }] })
    const chip = empty.find('button[data-fact="people-edit"]')
    expect(chip.text()).toBe('Add participants')
    expect(chip.attributes('aria-label')).toBe('Edit participants')
    expect(empty.find('[data-fact="people"]').exists(), 'nobody to show').toBe(false)
    await chip.trigger('click')
    const some = mountHeader({ title: 'Rome', facts: [{ ...people, people: [{ userId: null, name: 'Anna' }] }] })
    expect(some.find('[data-fact="people"]').text()).toContain('Anna')
    const button = some.find('button[data-fact="people-edit"]')
    expect(button.attributes('aria-label')).toBe('Edit participants')
    await button.trigger('click')
    expect(edit).toHaveBeenCalledTimes(2)
    // Without an editor: the people alone.
    expect(mountHeader({ title: 'Rome', facts: [{ kind: 'people', label: 'P', people: [{ userId: null, name: 'Anna' }] }] }).find('[data-fact="people-edit"]').exists()).toBe(false)
  })

  it('a header\'s move and copy are menu entries, never buttons; share stays a button', async () => {
    const w = mountHeader(
      { title: 'Trips', actions: standardNodeActions(t, { share: true, move: true, delete: true }, {}), order: ['share', 'move', 'copy'] },
      {},
      [{ ...act('copy'), menuOnly: true, placement: { after: 'move' } }],
    )
    expect(headerButtonIds(w)).toEqual(['share'])
    expect(await headerMenuIds(w)).toEqual(['move', 'copy', 'delete'])
  })
})

describe('nodeHeader helpers', () => {
  it('mergeHeaderActions puts core before / after the application list', () => {
    const ids = mergeHeaderActions([{ ...act('x'), placement: 'before' }, act('y')], [act('a')]).map((a) => a.id)
    expect(ids).toEqual(['x', 'a', 'y'])
  })

  it('standardActionList (a row\'s): only the granted ones, share, rename, move, delete; the kind may relabel', () => {
    const on = { share: vi.fn(), rename: vi.fn(), move: vi.fn(), remove: vi.fn() }
    const all = standardActionList(t, { share: true, rename: true, move: true, delete: true, deleteLabel: 'Drop it' }, on)
    expect(all.map((a) => a.id)).toEqual(['share', 'rename', 'move', 'delete'])
    expect(all[3].label).toBe('Drop it')
    expect(all[3].severity).toBe('danger')
    all.forEach((a) => a.onClick())
    expect(Object.values(on).every((f) => f.mock.calls.length === 1)).toBe(true)
    expect(standardActionList(t, { rename: true }, on).map((a) => a.id)).toEqual(['rename'])
    expect(standardActionList(t, {}, on)).toEqual([])
  })

  it('standardNodeActions (a header\'s): no rename entry whatever the right says, the move menu-only (ADR 0110 decision 11)', () => {
    const on = { share: vi.fn(), rename: vi.fn(), move: vi.fn(), remove: vi.fn() }
    const all = standardNodeActions(t, { share: true, rename: true, move: true, delete: true }, on)
    expect(all.map((a) => a.id)).toEqual(['share', 'move', 'delete'])
    expect(all.find((a) => a.id === 'move')?.menuOnly).toBe(true)
    expect(all.find((a) => a.id === 'share')?.menuOnly).toBeUndefined()
  })

  it('standardNodeRename: only with the right AND the page\'s write; it saves through that write', async () => {
    const renameTo = vi.fn(async () => {})
    expect(standardNodeRename({ rename: false }, { renameTo })).toBeNull()
    expect(standardNodeRename({ rename: true }, {})).toBeNull()
    await standardNodeRename({ rename: true }, { renameTo })!.save('Roma')
    expect(renameTo).toHaveBeenCalledWith('Roma')
  })
})
