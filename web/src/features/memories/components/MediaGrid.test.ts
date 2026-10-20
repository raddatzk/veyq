// @vitest-environment happy-dom
// The windowed grid (ADR 0105 decisions 1, 2, 5; inbox #489), mounted for real inside a scroll container and scrolled.
// happy-dom lays nothing out, so the boxes are given by hand: the container is 800 px tall and 1000 px wide, and the
// grid's top on screen follows the container's `scrollTop` — which is all the grid reads. 2,000 square tiles at the
// gallery's 200 px target height lay out five to a row (193.6 px rows, an 8 px gap), 400 rows.
// Bites: mount every row (`windowRows` → all) and the DOM-bound cases go red; drop the roving tab stop and the
// keyboard cases go red.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref, type Component } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import MediaGrid from './MediaGrid.vue'

const VIEW = 800
const WIDTH = 1000
type Tile = { id: string; aspectRatio: number }
const tiles = (n: number): Tile[] => Array.from({ length: n }, (_, i) => ({ id: `t${i}`, aspectRatio: 1 }))

let scrollerEl: HTMLElement | null = null
beforeEach(() => {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    const scrolled = scrollerEl?.scrollTop ?? 0
    const box = (top: number, height: number) =>
      ({ top, bottom: top + height, height, left: 0, right: WIDTH, width: WIDTH, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
    if (this === scrollerEl) return box(0, VIEW)
    if (this.classList.contains('media-grid')) return box(-scrolled, 80000)
    return box(0, 0)
  })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => setTimeout(() => cb(0), 0))
})
afterEach(() => {
  vi.unstubAllGlobals()
  scrollerEl = null
})

/** The grid inside an 800 px scroll container, each tile one button; what it announces is kept. */
function mountGrid(items: Tile[], opts: { selectOnSpace?: (id: string) => void } = {}) {
  const windows: string[][] = []
  const list = ref(items)
  const wrapper = mount(
    defineComponent({
      setup(_, { expose }) {
        const grid = ref<{ focusItem: (id: string) => Promise<void> } | null>(null)
        expose({ grid, list })
        return () =>
          h('div', { class: 'scroller', style: 'overflow-y: auto; height: 800px' }, [
            h(
              MediaGrid as unknown as Component, // a generic SFC: its props are checked by the uses below, not here
              {
                ref: grid,
                items: list.value,
                ariaLabel: 'Gallery',
                selectOnSpace: opts.selectOnSpace,
                onWindow: (ids: string[]) => windows.push(ids),
              },
              {
                tile: ({ tile }: { tile: { id: string } }) =>
                  h('div', { class: 'media-tile', 'data-media-id': tile.id }, [
                    h('button', { class: 'open', 'aria-label': `Open ${tile.id}` }),
                    h('button', { class: 'mark' }),
                  ]),
              },
            ),
          ])
      },
    }),
    { attachTo: document.body },
  )
  scrollerEl = wrapper.find('.scroller').element as HTMLElement
  return { wrapper, windows, list, api: wrapper.vm as unknown as { grid: { focusItem: (id: string) => Promise<void> }; list: Tile[] } }
}

const frame = () => new Promise((r) => setTimeout(r, 5))
async function scrollTo(top: number) {
  scrollerEl!.scrollTop = top
  scrollerEl!.dispatchEvent(new Event('scroll'))
  await frame()
  await nextTick()
}
const mounted = (w: VueWrapper) => w.findAll('[role="gridcell"]').map((c) => c.attributes('data-grid-id'))
const rowIndexes = (w: VueWrapper) => w.findAll('[role="row"]').map((r) => Number(r.attributes('aria-rowindex')))
/** The tiles a window of the viewport plus one viewport each way can hold: three viewports of rows, a row more at
 *  each edge, five tiles a row. */
const WINDOW_MAX = (Math.ceil((3 * VIEW) / 201.6) + 2) * 5

describe('the grid mounts only the window (ADR 0105 decision 1)', () => {
  it('at 2,000 tiles it mounts about three viewports of rows, and the grid keeps the whole height', async () => {
    const { wrapper } = mountGrid(tiles(2000))
    await frame()
    await nextTick()
    const cells = mounted(wrapper)
    expect(cells.length).toBeGreaterThan(0)
    expect(cells.length).toBeLessThanOrEqual(WINDOW_MAX)
    expect(cells[0]).toBe('t0')
    const grid = wrapper.find('.media-grid')
    expect(grid.attributes('style')).toMatch(/height: 806\d\dpx|height: 80\d{3}(\.\d+)?px/)
    expect(grid.attributes('aria-rowcount')).toBe('400')
  })

  it('a scroll mounts the new window and lets the old one go, within the same bound', async () => {
    const { wrapper } = mountGrid(tiles(2000))
    await frame()
    await scrollTo(40000) // row 198 is at the top of the viewport
    const cells = mounted(wrapper)
    expect(cells.length).toBeLessThanOrEqual(WINDOW_MAX)
    expect(cells).not.toContain('t0')
    const rows = rowIndexes(wrapper)
    expect(Math.min(...rows)).toBeGreaterThan(180)
    expect(Math.max(...rows)).toBeLessThan(220)
    expect(rows).toContain(Math.floor(40000 / 201.6) + 1) // the row at the viewport's top, 1-based
  })

  it('announces the mounted tiles nearest to the viewport centre first, and the new window after a scroll', async () => {
    const { windows } = mountGrid(tiles(2000))
    await frame()
    await scrollTo(40000)
    const last = windows[windows.length - 1]
    const centreRow = Math.floor((40000 + VIEW / 2) / 201.6)
    expect(Math.floor(Number(last[0].slice(1)) / 5)).toBe(centreRow) // the first asked for is on screen, mid-viewport
    expect(last).not.toContain('t0')
    expect(new Set(last).size).toBe(last.length)
  })

  it('a tile inserted above the viewport keeps the first visible tile where it was on screen', async () => {
    const { wrapper, list } = mountGrid(tiles(2000))
    await frame()
    await scrollTo(40000)
    const visible = (Math.floor(40000 / 201.6) + 1) * 5 - 5 // the first tile of the row the viewport's top crosses
    const onScreen = () => wrapper.find(`[data-grid-id="t${visible}"]`).exists()
    expect(onScreen()).toBe(true)
    // five tiles settle in front of every other: each tile moves one row (201.6 px) down the grid
    list.value = Array.from({ length: 5 }, (_, i) => ({ id: `new-${i}`, aspectRatio: 1 })).concat(list.value)
    await nextTick()
    await frame()
    await nextTick()
    expect(scrollerEl!.scrollTop).toBeCloseTo(40000 + 201.6, 0) // the scroll followed the tile, by id
    expect(onScreen()).toBe(true)
  })

  it('at the top of the grid an insert above is shown, not scrolled past', async () => {
    const { list } = mountGrid(tiles(200))
    await frame()
    list.value = [{ id: 'new', aspectRatio: 1 }, ...list.value]
    await nextTick()
    await frame()
    expect(scrollerEl!.scrollTop).toBe(0)
  })
})

describe('one tab stop, the arrow keys move (ADR 0105 decision 5)', () => {
  const focused = () => (document.activeElement?.closest('[data-grid-id]') as HTMLElement | null)?.dataset.gridId
  const press = async (_w: VueWrapper, key: string) => {
    ;(document.activeElement as HTMLElement).dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
    await frame()
    await nextTick()
    await frame()
  }

  it('the grid is a WAI-ARIA grid whose tab order holds one tile, its open button first', async () => {
    const { wrapper } = mountGrid(tiles(30))
    await frame()
    await nextTick()
    const grid = wrapper.find('[role="grid"]')
    expect(grid.attributes('aria-label')).toBe('Gallery')
    const tabbable = grid.findAll('button').filter((b) => (b.element as HTMLElement).tabIndex >= 0)
    // Tab enters on the one tile's controls — its open button, then its mark — and the next Tab leaves the grid
    expect(tabbable.map((b) => b.attributes('aria-label') ?? b.classes().join(' '))).toEqual(['Open t0', 'mark'])
  })

  it('arrows, Home and End move the focus and the tab stop with it; Down from a full row goes to the nearest tile of a shorter one', async () => {
    const { wrapper } = mountGrid(tiles(12)) // rows of 5, 5 and a widow row of 2
    await frame()
    await nextTick()
    ;(wrapper.find('[data-grid-id="t0"] button').element as HTMLElement).focus()
    await press(wrapper, 'ArrowRight')
    expect(focused()).toBe('t1')
    await press(wrapper, 'ArrowDown')
    expect(focused()).toBe('t6')
    await press(wrapper, 'ArrowLeft')
    expect(focused()).toBe('t5')
    await press(wrapper, 'ArrowUp')
    expect(focused()).toBe('t0')
    ;(wrapper.find('[data-grid-id="t9"] button').element as HTMLElement).focus()
    await press(wrapper, 'ArrowDown') // the right end of row 2 → the widow row's right tile, the nearest one
    expect(focused()).toBe('t11')
    await press(wrapper, 'Home')
    expect(focused()).toBe('t0')
    await press(wrapper, 'End')
    expect(focused()).toBe('t11')
    const tabbable = wrapper.findAll('button').filter((b) => (b.element as HTMLElement).tabIndex >= 0)
    expect(tabbable.map((b) => b.attributes('aria-label') ?? b.classes().join(' '))).toEqual(['Open t11', 'mark'])
  })

  it('End at 2,000 tiles scrolls the last one in, mounts it and focuses it; PageUp moves one viewport', async () => {
    const { wrapper } = mountGrid(tiles(2000))
    await frame()
    await nextTick()
    ;(wrapper.find('[data-grid-id="t0"] button').element as HTMLElement).focus()
    await press(wrapper, 'End')
    expect(focused()).toBe('t1999')
    expect(scrollerEl!.scrollTop).toBeGreaterThan(79000)
    expect(mounted(wrapper).length).toBeLessThanOrEqual(WINDOW_MAX + 5)
    await press(wrapper, 'PageUp')
    const row = Math.floor(Number(focused()!.slice(1)) / 5)
    expect(399 - row).toBeGreaterThanOrEqual(3) // one 800 px viewport is about four rows
    expect(399 - row).toBeLessThanOrEqual(5)
  })

  it('Space selects where the caller selects on Space, and is the button\'s own otherwise', async () => {
    const select = vi.fn()
    const { wrapper } = mountGrid(tiles(10), { selectOnSpace: select })
    await frame()
    await nextTick()
    ;(wrapper.find('[data-grid-id="t3"] button').element as HTMLElement).focus()
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    document.activeElement!.dispatchEvent(space)
    expect(select).toHaveBeenCalledWith('t3')
    expect(space.defaultPrevented).toBe(true)
  })

  it('focusItem scrolls a far tile into view, mounts it and focuses it (the lightbox\'s close)', async () => {
    const { api, wrapper } = mountGrid(tiles(2000))
    await frame()
    await api.grid.focusItem('t1500')
    await frame()
    await nextTick()
    expect(focused()).toBe('t1500')
    expect(mounted(wrapper)).toContain('t1500')
    expect(scrollerEl!.scrollTop).toBeGreaterThan(50000)
  })
})
