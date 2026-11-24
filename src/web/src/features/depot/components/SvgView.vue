<script setup lang="ts">
// An opened SVG (inbox #1059), zoomable and sharp at every zoom: the picture `svgRaster.ts` decoded, painted onto two
// canvases INSIDE this page - never an `<img>`, never a URL (ADR 0155 § The file facts' slice D11, D14):
// - the base: the whole picture at the size that fits (never larger than its own), at the screen's pixel ratio; zoomed,
//   it is stretched with the picture and is what a scroll shows until the tile below is painted again;
// - the tile, zoomed only: the part of the picture in view, painted at the zoom's actual scale (the zoom x the screen's
//   pixel ratio x a pinch zoom's scale), debounced ([REPAINT_DELAY] after the last zoom, scroll or resize), so the vectors
//   are drawn anew at each scale instead of a bitmap being enlarged. Its pixel count is the view's, not the zoom's, and
//   `planRaster` caps it (SVG_MAX_PIXELS), so a huge SVG or a 16x zoom cannot make a canvas that exhausts memory.
// Zoom out / in / fit are real labelled buttons (keyboard, screen reader), as the PDF's.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import * as core from '@veyq/core-js/depot'
import type { SvgPicture, SvgRaster, SvgRegion } from '../svgRaster'

const props = defineProps<{ picture: SvgPicture; name: string; full: boolean }>()
const emit = defineEmits<{ failed: [] }>()
const { t } = useI18n()

/** Zoom factors relative to the fitted size (1): the buttons step through them. */
/** The zoom steps, ×1 the picture fitted: Depot's Kotlin `SvgView`, one list with the apps (ADR 0155 § Depot's move). */
const ZOOM_STEPS: readonly number[] = core.depotSvgZoomSteps()
/** How long the view must stay still before the tile is painted at its new scale. */
const REPAINT_DELAY = 150
/** The room where nothing can be measured (no layout, e.g. a test). */
const FALLBACK_WIDTH = 800
const FALLBACK_HEIGHT = 600

const scroller = ref<HTMLElement | null>(null)
const sizer = ref<HTMLElement | null>(null)
const base = ref<HTMLCanvasElement | null>(null)
const tile = ref<HTMLCanvasElement | null>(null)
const room = ref({ width: 0, height: 0 })
const zoom = ref<number>(1)
const deviceRatio = ref(readDeviceRatio())

/** CSS px per SVG unit at zoom 1: the picture fitted into the room, never enlarged past its own size. */
const fitScale = computed(() => {
  const w = room.value.width || FALLBACK_WIDTH
  const h = room.value.height || FALLBACK_HEIGHT
  return Math.min(1, w / props.picture.width, h / props.picture.height)
})
const scale = computed(() => fitScale.value * zoom.value)
const laidOut = computed(() => ({ width: props.picture.width * scale.value, height: props.picture.height * scale.value }))
const sizerStyle = computed(() => ({ width: `${laidOut.value.width}px`, height: `${laidOut.value.height}px` }))
const canZoomOut = computed(() => zoom.value > ZOOM_STEPS[0]!)
const canZoomIn = computed(() => zoom.value < ZOOM_STEPS[ZOOM_STEPS.length - 1]!)

/** Device px per CSS px: the screen's ratio (a browser zoom changes it) times a pinch zoom's scale. */
function readDeviceRatio(): number {
  if (typeof window === 'undefined') return 1
  const pinch = window.visualViewport?.scale ?? 1
  return Math.max(1, window.devicePixelRatio || 1) * Math.max(1, pinch || 1)
}

// --- painting ----------------------------------------------------------------------------------------------------------
/** What the tile holds now: its region at the scale it was painted at; the style below stretches it with the zoom. */
const tileDrawn = ref<{ region: SvgRegion; scale: number; raster: SvgRaster } | null>(null)
let alive = true
let timer: ReturnType<typeof setTimeout> | undefined

const tileStyle = computed(() => {
  const d = tileDrawn.value
  if (!d) return { display: 'none' }
  const k = scale.value / d.scale
  return {
    left: `${d.region.x * k}px`,
    top: `${d.region.y * k}px`,
    width: `${d.region.width * k}px`,
    height: `${d.region.height * k}px`,
  }
})

function fail(): void {
  if (!alive) return
  freeCanvases()
  emit('failed')
}

/** The fit and the screen ratio the base was painted for. */
let baseKey = ''
function paintBase(): void {
  const canvas = base.value
  if (!canvas) return
  const s = fitScale.value
  baseKey = `${s}@${deviceRatio.value}`
  try {
    props.picture.paint(canvas, { x: 0, y: 0, width: props.picture.width * s, height: props.picture.height * s }, s, deviceRatio.value)
  } catch {
    fail()
  }
}

/** The part of the laid-out picture inside the scroller's view, in the picture's CSS px. */
function visibleRegion(): SvgRegion {
  const el = scroller.value
  const box = sizer.value
  const { width, height } = laidOut.value
  if (!el || !box || !el.clientWidth || !el.clientHeight) return { x: 0, y: 0, width: Math.min(width, FALLBACK_WIDTH), height: Math.min(height, FALLBACK_HEIGHT) }
  const left = el.scrollLeft - box.offsetLeft
  const top = el.scrollTop - box.offsetTop
  const x = Math.max(0, left)
  const y = Math.max(0, top)
  return {
    x,
    y,
    width: Math.max(1, Math.min(width, left + el.clientWidth) - x),
    height: Math.max(1, Math.min(height, top + el.clientHeight) - y),
  }
}

function paintTile(): void {
  const canvas = tile.value
  if (!canvas) return
  if (zoom.value === 1) {
    // At the fitted size the base is the picture at full sharpness: no tile, no second bitmap.
    canvas.width = 0
    canvas.height = 0
    tileDrawn.value = null
    return
  }
  const region = visibleRegion()
  try {
    const raster = props.picture.paint(canvas, region, scale.value, deviceRatio.value)
    tileDrawn.value = { region, scale: scale.value, raster }
  } catch {
    fail()
  }
}

/** Paint again once the view has been still for [REPAINT_DELAY]; the base only where the fit or the screen moved. */
function schedule(): void {
  clearTimeout(timer)
  timer = setTimeout(() => {
    if (!alive) return
    if (baseKey !== `${fitScale.value}@${deviceRatio.value}`) paintBase()
    paintTile()
  }, REPAINT_DELAY)
}

watch([fitScale, zoom, deviceRatio], () => schedule())

function freeCanvases(): void {
  for (const c of [base.value, tile.value]) {
    if (c) {
      c.width = 0
      c.height = 0
    }
  }
  tileDrawn.value = null
  baseKey = ''
}

// --- the room, the screen ------------------------------------------------------------------------------------------------
/**
 * The room the picture fits: the scroller's width and its maximum height (70vh, or the full screen's). The border box,
 * not `clientWidth`: a zoomed picture's scroll bar narrows the client box, and fitting to that would rescale the picture
 * the bar belongs to.
 */
function measure(): void {
  const el = scroller.value
  if (!el) return
  const maxHeight = parseFloat(getComputedStyle(el).maxHeight)
  const next = { width: el.offsetWidth, height: Number.isFinite(maxHeight) ? maxHeight : 0 }
  if (next.width !== room.value.width || next.height !== room.value.height) room.value = next
}

let resizeObserver: ResizeObserver | null = null
let ratioQuery: MediaQueryList | null = null
function onRatio(): void {
  deviceRatio.value = readDeviceRatio()
  watchRatio()
}
/** A browser zoom or a move to another screen changes `devicePixelRatio`: one query per ratio, renewed on each change. */
function watchRatio(): void {
  ratioQuery?.removeEventListener('change', onRatio)
  ratioQuery = typeof window.matchMedia === 'function' ? window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`) : null
  ratioQuery?.addEventListener('change', onRatio)
}
function onViewport(): void {
  deviceRatio.value = readDeviceRatio()
}

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined' && scroller.value) {
    resizeObserver = new ResizeObserver(() => measure())
    resizeObserver.observe(scroller.value)
  }
  watchRatio()
  window.visualViewport?.addEventListener('resize', onViewport)
  // a new canvas holds a 300 x 150 bitmap of its own: the tile has none until a zoom asks for one
  if (tile.value) {
    tile.value.width = 0
    tile.value.height = 0
  }
  paintBase()
})
watch(
  () => props.full,
  () => void nextTick(measure),
)
onBeforeUnmount(() => {
  alive = false
  clearTimeout(timer)
  resizeObserver?.disconnect()
  ratioQuery?.removeEventListener('change', onRatio)
  window.visualViewport?.removeEventListener('resize', onViewport)
  freeCanvases()
})

// --- zoom ----------------------------------------------------------------------------------------------------------------
/** Change the zoom, keeping the point at the view's centre where it was. */
async function setZoom(next: number): Promise<void> {
  const el = scroller.value
  const before = zoom.value
  const cx = el ? el.scrollLeft + el.clientWidth / 2 : 0
  const cy = el ? el.scrollTop + el.clientHeight / 2 : 0
  zoom.value = next
  await nextTick()
  if (el) {
    el.scrollLeft = cx * (next / before) - el.clientWidth / 2
    el.scrollTop = cy * (next / before) - el.clientHeight / 2
  }
}
function zoomOut(): void {
  const next = core.depotSvgZoomOut(zoom.value)
  if (next !== zoom.value) void setZoom(next)
}
function zoomIn(): void {
  const next = core.depotSvgZoomIn(zoom.value)
  if (next !== zoom.value) void setZoom(next)
}
function fit(): void {
  void setZoom(1)
}
</script>

<template>
  <div class="svg-viewer">
    <div class="svg-toolbar" role="toolbar" :aria-label="t('depot.viewer.image.toolbar')">
      <VeyqButton
        class="svg-zoom-out"
        icon="search-minus"
        :aria-label="t('depot.viewer.image.zoomOut')"
        :title="t('depot.viewer.image.zoomOut')"
        :disabled="!canZoomOut"
        size="small"
        variant="secondary"
        appearance="outlined"
        @click="zoomOut"
      />
      <span class="svg-zoom-level" aria-live="polite">{{ Math.round(zoom * 100) }} %</span>
      <VeyqButton
        class="svg-zoom-in"
        icon="search-plus"
        :aria-label="t('depot.viewer.image.zoomIn')"
        :title="t('depot.viewer.image.zoomIn')"
        :disabled="!canZoomIn"
        size="small"
        variant="secondary"
        appearance="outlined"
        @click="zoomIn"
      />
      <VeyqButton
        class="svg-zoom-fit"
        icon="image"
        :aria-label="t('depot.viewer.image.fit')"
        :title="t('depot.viewer.image.fit')"
        :disabled="zoom === 1"
        size="small"
        variant="secondary"
        appearance="outlined"
        @click="fit"
      />
    </div>
    <div ref="scroller" class="svg-scroll" :class="{ 'svg-scroll-full': full }" tabindex="0" @scroll.passive="schedule">
      <div ref="sizer" class="svg-sizer" :style="sizerStyle">
        <canvas ref="base" class="svg-base" role="img" :aria-label="name" />
        <canvas ref="tile" class="svg-tile" aria-hidden="true" :style="tileStyle" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.svg-viewer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 100%;
}
.svg-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 0.25rem;
  color: var(--app-muted);
}
.svg-zoom-level {
  min-width: 3.5rem;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.svg-scroll {
  /* Its width comes from the page around it, never from the picture in it (the PDF's F12): the room is measured here. */
  contain: inline-size;
  position: relative;
  max-height: 70vh;
  overflow: auto;
}
.svg-scroll-full {
  max-height: calc(100vh - 9rem);
}
.svg-sizer {
  position: relative;
  margin: 0 auto;
}
.svg-base {
  display: block;
  width: 100%;
  height: 100%;
}
.svg-tile {
  position: absolute;
}
</style>
