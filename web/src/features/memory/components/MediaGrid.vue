<script setup lang="ts" generic="T extends { id: string; aspectRatio: number }">
// The justified media grid — the shared layout engine behind BOTH the internal Memory gallery
// (MemoryDetail.vue) and the anonymous public-share viewer (ShareView.vue), so a share looks exactly like the
// Memory it mirrors. It owns the hard part — measuring the container width, running the Google-Photos justified
// layout (mediaGridLayout.ts, ported from the design source), and the `.media-grid` container geometry — and
// hands each **positioned** tile back to the caller via the `tile` scoped slot together with its own source
// `item`, so each surface keeps its own tile content (the internal grid its clickable media + optimistic-upload
// tiles; the viewer its read-only tiles) while the geometry + `.media-grid` classes stay identical. Generic over
// the item type so callers get their fully-typed item back with no map lookups. Pure presentation, no data layer.
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { calculateJustifiedMediaLayout, type MediaGridLayoutTile } from '../mediaGridLayout'

const props = defineProps<{
  /** The tiles in render order — each carries its `id` + display `aspectRatio` (for the layout) plus whatever
   *  content the caller needs back in the slot. */
  items: T[]
  gap?: number
  /** Target row height in px, or a fn of the measured width (default: 150 ≤720px else 200 — the gallery default). */
  targetHeight?: number | ((width: number) => number)
  ariaLabel?: string
}>()

defineSlots<{ tile(props: { tile: MediaGridLayoutTile; item: T }): unknown }>()

const gridEl = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
let observer: ResizeObserver | null = null
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
// Zip each laid-out tile back to its source item (layout ids are a subset of item ids), so the slot gets both
// the geometry and the fully-typed item — no map lookup / non-null assertion in the caller's template.
const byId = computed(() => new Map(props.items.map((i) => [i.id, i])))
const positioned = computed(() =>
  layout.value.tiles.flatMap((tile) => {
    const item = byId.value.get(tile.id)
    return item ? [{ tile, item }] : []
  }),
)

function measure() {
  const el = gridEl.value
  if (!el) return
  const width = el.getBoundingClientRect().width || el.clientWidth || el.parentElement?.clientWidth || 0
  if (width > 0) containerWidth.value = Math.floor(width)
}
function attach() {
  const el = gridEl.value
  observer?.disconnect()
  observer = null
  if (!el) return
  measure()
  if (typeof ResizeObserver === 'undefined') return
  // rAF-debounce so a width change doesn't trip the ResizeObserver loop guard
  observer = new ResizeObserver(() => {
    if (frame !== null) return
    frame = requestAnimationFrame(() => {
      frame = null
      measure()
    })
  })
  observer.observe(el)
}
onMounted(attach)
watch(gridEl, attach, { flush: 'post' })
onBeforeUnmount(() => {
  observer?.disconnect()
  if (frame !== null) cancelAnimationFrame(frame)
})
</script>

<template>
  <div ref="gridEl" class="media-grid" :style="{ height: `${layout.height}px` }" :aria-label="ariaLabel">
    <template v-for="p in positioned" :key="p.tile.id">
      <slot name="tile" :tile="p.tile" :item="p.item" />
    </template>
  </div>
</template>
