<script setup lang="ts" generic="T extends { id: string; aspectRatio: number }">
// The justified media grid — the shared layout engine behind every view over a list of media: the gallery, the trash
// and the contributors' view of a Memory (MemoryDetail.vue, MemoryBuckets.vue), a guest's share of it, and the drop
// page's own tiles (MemoryShareBody.vue), so a share looks exactly like the Memory it mirrors. It owns the hard part —
// measuring the container width, running the justified layout (mediaGridLayout.ts) over the WHOLE list, the
// `.media-grid` geometry — and hands each positioned tile back through the `tile` slot with its own source `item`.
//
// **It renders only what is near the viewport** (ADR 0105 decision 1, inbox #489). The layout stays computed for every
// tile, so the container keeps its true height and the scrollbar its truth; but only the rows that intersect the
// viewport of the grid's scroll container, plus one viewport of overscan each way, are mounted — recomputed at most once
// per animation frame on scroll and on resize — and the DOM no longer grows with the Memory (13,000 nodes at 1,000
// photos, 37,000 at 3,000 when every tile was mounted). A tile's key is its id, so a tile a scroll keeps stays mounted.
// The rows it mounts are announced in nearest-first order (`window`): what the caller loads a thumbnail for.
//
// **One tab stop, the arrow keys move** (ADR 0105 decision 5): a grid whose tiles are not all in the DOM cannot offer a
// tab stop per tile, so it is the WAI-ARIA grid — `role="grid"` with the row count of the WHOLE list, each mounted row
// its `aria-rowindex`, each tile a `gridcell` — with a roving tab stop on the active tile: its controls (the open button
// first, then its mark, checkbox or menu) are the grid's only ones in the tab order, so Tab walks that one tile and leaves
// the grid, and no control of the tile loses its keyboard path. Left/Right go through the grid order, Up/Down to the
// nearest tile of the adjacent row, Home/End to the first and last item, PageUp/PageDown by one viewport; each move
// scrolls its tile into view and focuses it. Enter and Space are the tile's own button's, except where the caller selects
// on Space (`selectOnSpace`: the contributors' view toggles its checkbox as a click does; the trash's button already
// selects). The active tile's row is always mounted, so the tab stop never scrolls out of the DOM.
//
// **A tile that settles above the viewport does not move what the user is looking at** (ADR 0105 decision 4): when the
// layout changes while the grid is scrolled into, the first visible tile keeps its place on screen — anchored by id,
// not by pixel (`overflow-anchor: none` keeps the browser's own anchoring out of it).
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { calculateJustifiedMediaLayout, type MediaGridLayoutTile } from '../mediaGridLayout'

const props = defineProps<{
  /** The tiles in render order — each carries its `id` + display `aspectRatio` (for the layout) plus whatever
   *  content the caller needs back in the slot. */
  items: T[]
  gap?: number
  /** Target row height in px, or a fn of the measured width (default: 150 ≤720px else 200 — the gallery default). */
  targetHeight?: number | ((width: number) => number)
  ariaLabel?: string
  /** Space on a focused tile selects it (the trash, the contributors' view) instead of pressing its button. */
  selectOnSpace?: (id: string) => void
}>()

const emit = defineEmits<{
  /** The ids of the mounted tiles, nearest to the viewport's centre first — re-announced when that set changes. */
  window: [ids: string[]]
}>()

defineSlots<{ tile(props: { tile: MediaGridLayoutTile; item: T }): unknown }>()

/** Rows mounted beyond the viewport, each way, in viewports. */
const OVERSCAN = 1
/** The viewport height assumed while none can be measured (a container not laid out yet). */
const FALLBACK_VIEW = 1000
const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]'

const gridEl = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
/** The visible part of the grid, in the grid's own coordinates (top may be negative: the grid starts below it). */
const view = ref({ top: 0, height: FALLBACK_VIEW })
const activeId = ref<string | null>(null)
/** No box to lay out in (a hidden tab, `display: none`): nothing of it is on screen. */
const hidden = ref(false)
let scroller: HTMLElement | Window | null = null
let resizeObserver: ResizeObserver | null = null
let frame: number | null = null

const targetHeight = computed(() => {
  const th = props.targetHeight
  if (typeof th === 'function') return th(containerWidth.value)
  return th ?? (containerWidth.value <= 720 ? 150 : 200)
})
const layout = computed(() =>
  calculateJustifiedMediaLayout({
    items: props.items,
    containerWidth: containerWidth.value,
    targetHeight: targetHeight.value,
    gap: props.gap ?? 8,
  }),
)
const byId = computed(() => new Map(props.items.map((i) => [i.id, i])))

type Row = { index: number; top: number; bottom: number; start: number; end: number }
/** The layout's rows (tiles sharing a top), each with its tile range [start, end). */
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const tiles = layout.value.tiles
  for (let i = 0; i < tiles.length; i++) {
    const t = tiles[i]
    const last = out[out.length - 1]
    if (last && last.top === t.top) {
      last.end = i + 1
      last.bottom = Math.max(last.bottom, t.top + t.renderedHeight)
    } else out.push({ index: out.length, top: t.top, bottom: t.top + t.renderedHeight, start: i, end: i + 1 })
  }
  return out
})
/** Each tile's position in the layout and its row, by id. */
const placeOf = computed(() => {
  const m = new Map<string, { at: number; row: number }>()
  for (const row of rows.value) for (let i = row.start; i < row.end; i++) m.set(layout.value.tiles[i].id, { at: i, row: row.index })
  return m
})

/** The first row whose bottom is below `y` (binary search over the rows' tops, which only grow). */
function rowAt(y: number): number {
  const rs = rows.value
  let lo = 0
  let hi = rs.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (rs[mid].bottom <= y) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** The mounted rows: the viewport's, an overscan each way, and the active tile's. The same range is the same value, so a
 *  scroll inside it re-renders nothing (a computed whose value is unchanged triggers no reader). */
let range: [number, number] = [0, -1]
const windowRows = computed<[number, number]>(() => {
  const rs = rows.value
  if (!rs.length) return (range = [0, -1])
  const { top, height } = view.value
  const from = rowAt(top - OVERSCAN * height)
  const to = Math.min(rs.length - 1, Math.max(from, rowAt(top + height + OVERSCAN * height)))
  if (range[0] !== from || range[1] !== to) range = [from, to]
  return range
})
const mountedRows = computed(() => {
  const rs = rows.value
  const [from, to] = windowRows.value
  const picked: Row[] = rs.slice(from, to + 1)
  const active = activeId.value ? placeOf.value.get(activeId.value) : undefined
  if (active && (active.row < from || active.row > to)) {
    picked.push(rs[active.row])
    picked.sort((a, b) => a.index - b.index)
  }
  return picked.map((row) => ({
    row,
    key: layout.value.tiles[row.start].id,
    cells: layout.value.tiles.slice(row.start, row.end).flatMap((tile, col) => {
      const item = byId.value.get(tile.id)
      return item ? [{ tile, item, col }] : []
    }),
  }))
})

// --- the window the caller loads thumbnails for --------------------------------------------------------------------

/** The mounted tiles' ids, nearest to the viewport's centre first. */
const windowIds = computed(() => {
  if (hidden.value) return [] // a grid not laid out (a hidden tab) wants nothing: its tiles are not on screen
  const centre = view.value.top + view.value.height / 2
  const cells = mountedRows.value.flatMap((r) => r.cells.map((c) => c.tile))
  return cells
    .map((t) => ({ id: t.id, d: Math.abs(t.top + t.renderedHeight / 2 - centre) }))
    .sort((a, b) => a.d - b.d)
    .map((c) => c.id)
})
// Announced when the SET of mounted tiles changes, in the nearest-first order of that moment: a scroll inside the same
// window asks for nothing new.
let announced = ''
watch(
  windowIds,
  (ids) => {
    const key = [...ids].sort().join('\n')
    if (key === announced) return
    announced = key
    emit('window', ids)
  },
  { immediate: true },
)

// --- measuring --------------------------------------------------------------------------------------------------

/** The nearest ancestor that scrolls vertically, else the window. */
function findScroller(el: HTMLElement): HTMLElement | Window {
  for (let n = el.parentElement; n; n = n.parentElement) {
    const overflow = getComputedStyle(n).overflowY
    if (overflow === 'auto' || overflow === 'scroll' || overflow === 'overlay') return n
  }
  return window
}

function measure() {
  const el = gridEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const width = rect.width || el.clientWidth || el.parentElement?.clientWidth || 0
  if (width > 0) containerWidth.value = Math.floor(width)
  hidden.value = width <= 0
  const winHeight = window.innerHeight || FALLBACK_VIEW
  let top = 0
  let bottom = winHeight
  if (scroller && scroller !== window) {
    const box = (scroller as HTMLElement).getBoundingClientRect()
    if (box.height > 0) {
      top = Math.max(box.top, 0)
      bottom = Math.min(box.bottom, winHeight)
    }
  }
  const height = bottom - top > 0 ? bottom - top : FALLBACK_VIEW
  const next = { top: Math.round(top - rect.top), height: Math.round(height) }
  if (next.top !== view.value.top || next.height !== view.value.height) view.value = next
}

function schedule() {
  if (frame !== null) return
  frame = requestAnimationFrame(() => {
    frame = null
    measure()
  })
}

function attach() {
  const el = gridEl.value
  detach()
  if (!el) return
  scroller = findScroller(el)
  scroller.addEventListener('scroll', schedule, { passive: true })
  window.addEventListener('resize', schedule)
  measure()
  if (typeof ResizeObserver === 'undefined') return
  // rAF-debounced, so a width change doesn't trip the ResizeObserver loop guard
  resizeObserver = new ResizeObserver(schedule)
  resizeObserver.observe(el)
  if (scroller !== window) resizeObserver.observe(scroller as HTMLElement)
}
function detach() {
  scroller?.removeEventListener('scroll', schedule)
  window.removeEventListener('resize', schedule)
  resizeObserver?.disconnect()
  resizeObserver = null
  scroller = null
}
onMounted(attach)
watch(gridEl, attach, { flush: 'post' })
onBeforeUnmount(() => {
  detach()
  if (frame !== null) cancelAnimationFrame(frame)
})

function scrollByPx(dy: number) {
  if (!dy || !scroller) return
  if (scroller === window) window.scrollBy(0, dy)
  else (scroller as HTMLElement).scrollTop += dy
}

// --- a settling tile above the viewport keeps the visible one in place --------------------------------------------

let previous: { tops: Map<string, number>; anchor: string | null } | null = null
watch(
  layout,
  (next) => {
    if (previous?.anchor && view.value.top > 0) {
      const before = previous.tops.get(previous.anchor)
      const after = next.tiles.find((t) => t.id === previous!.anchor)?.top
      if (before !== undefined && after !== undefined && after !== before) {
        scrollByPx(after - before)
        measure()
      }
    }
    previous = { tops: new Map(next.tiles.map((t) => [t.id, t.top])), anchor: null }
  },
  { flush: 'post' },
)
// The anchor is the first tile visible at the moment the layout is about to change: kept current as the view moves.
watch(
  [view, layout],
  () => {
    if (!previous) return
    const row = rows.value[rowAt(view.value.top)]
    previous.anchor = row ? layout.value.tiles[row.start].id : null
  },
  { flush: 'post' },
)

// --- keyboard: one tab stop, the arrows move ---------------------------------------------------------------------

const firstId = computed(() => layout.value.tiles[0]?.id ?? null)
/** The tile that carries the tab stop: the last one focused, else the first. */
const stopId = computed(() => (activeId.value && placeOf.value.has(activeId.value) ? activeId.value : firstId.value))

function cellOf(id: string): HTMLElement | null {
  return gridEl.value?.querySelector<HTMLElement>(`[data-grid-id="${CSS.escape(id)}"]`) ?? null
}
/** Take every control inside the grid out of the tab order but the stop tile's own: its open button first, then its
 *  mark, checkbox or menu, so Tab walks that one tile's controls and leaves the grid. */
function syncTabStops() {
  const el = gridEl.value
  if (!el) return
  for (const f of el.querySelectorAll<HTMLElement>(FOCUSABLE)) f.tabIndex = -1
  const cell = stopId.value ? cellOf(stopId.value) : null
  if (cell) for (const f of cell.querySelectorAll<HTMLElement>(FOCUSABLE)) f.tabIndex = 0
}
watch([mountedRows, stopId], () => syncTabStops(), { flush: 'post' })
onMounted(syncTabStops)

/** Scroll `id`'s tile into the viewport (the least distance), mount it, and focus its first control. */
async function moveTo(id: string, focus = true) {
  const place = placeOf.value.get(id)
  if (!place) return
  activeId.value = id
  const tile = layout.value.tiles[place.at]
  const { top, height } = view.value
  if (tile.top < top) scrollByPx(tile.top - top)
  else if (tile.top + tile.renderedHeight > top + height) scrollByPx(tile.top + tile.renderedHeight - (top + height))
  measure()
  await nextTick()
  syncTabStops()
  if (focus) cellOf(id)?.querySelector<HTMLElement>(FOCUSABLE)?.focus({ preventScroll: true })
}

/** The tile of row `row` whose centre is nearest to `x`. */
function nearestIn(row: Row, x: number): string {
  let best = row.start
  let bestD = Infinity
  for (let i = row.start; i < row.end; i++) {
    const t = layout.value.tiles[i]
    const d = Math.abs(t.left + t.renderedWidth / 2 - x)
    if (d < bestD) {
      bestD = d
      best = i
    }
  }
  return layout.value.tiles[best].id
}

function onKeydown(event: KeyboardEvent) {
  if (event.altKey || event.ctrlKey || event.metaKey) return
  const cell = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-grid-id]')
  const id = cell?.dataset.gridId
  const place = id ? placeOf.value.get(id) : undefined
  if (!id || !place) return
  const tiles = layout.value.tiles
  const rs = rows.value
  const tile = tiles[place.at]
  const centre = tile.left + tile.renderedWidth / 2
  let target: string | null = null
  switch (event.key) {
    case 'ArrowRight':
      target = tiles[Math.min(place.at + 1, tiles.length - 1)].id
      break
    case 'ArrowLeft':
      target = tiles[Math.max(place.at - 1, 0)].id
      break
    case 'ArrowDown':
      target = nearestIn(rs[Math.min(place.row + 1, rs.length - 1)], centre)
      break
    case 'ArrowUp':
      target = nearestIn(rs[Math.max(place.row - 1, 0)], centre)
      break
    case 'Home':
      target = tiles[0].id
      break
    case 'End':
      target = tiles[tiles.length - 1].id
      break
    case 'PageDown':
    case 'PageUp': {
      const dy = (event.key === 'PageDown' ? 1 : -1) * view.value.height
      const row = Math.min(rs.length - 1, Math.max(0, rowAt(tile.top + dy)))
      target = nearestIn(rs[event.key === 'PageDown' ? Math.max(row, Math.min(place.row + 1, rs.length - 1)) : Math.min(row, Math.max(place.row - 1, 0))], centre)
      break
    }
    case ' ':
      if (!props.selectOnSpace) return
      event.preventDefault()
      props.selectOnSpace(id)
      return
    default:
      return
  }
  event.preventDefault()
  void moveTo(target)
}

function onFocusin(event: FocusEvent) {
  const id = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-grid-id]')?.dataset.gridId
  if (id && id !== activeId.value) activeId.value = id
}

defineExpose({
  /** Scroll `id`'s tile into view and focus it (the lightbox's close returns focus here, ADR 0105 decision 5). */
  focusItem: (id: string) => moveTo(id),
})
</script>

<template>
  <div
    ref="gridEl"
    class="media-grid"
    role="grid"
    :aria-label="ariaLabel"
    :aria-rowcount="rows.length"
    :style="{ height: `${layout.height}px` }"
    @keydown="onKeydown"
    @focusin="onFocusin"
  >
    <div v-for="r in mountedRows" :key="r.key" class="media-grid-row" role="row" :aria-rowindex="r.row.index + 1">
      <div
        v-for="c in r.cells"
        :key="c.tile.id"
        class="media-grid-cell"
        role="gridcell"
        :aria-colindex="c.col + 1"
        :data-grid-id="c.tile.id"
      >
        <slot name="tile" :tile="c.tile" :item="c.item" />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* A row is the positioning origin of its tiles, laid over the grid's own: a tile's translate(left, top) is in the grid's
   coordinates as before. It has no box of its own, so a row of absolutely placed tiles takes no space. */
.media-grid {
  overflow-anchor: none;
}
.media-grid-row {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 0;
}
</style>
