<script setup lang="ts">
// The pages of an opened PDF (inbox #412), drawn by pdf.js onto <canvas> elements INSIDE this page: a vertical scroll of
// every page, each drawn only while it is near the viewport (an IntersectionObserver with a margin of one viewport) and its
// canvas freed again when it is far away, so a 500-page PDF holds a handful of bitmaps, not 500. The page count and the
// current page, zoom out / in / fit-width are real labelled buttons (keyboard, screen reader).
//
// Over each drawn canvas sits pdf.js' text layer (transparent, positioned <span>s): the text can be selected with the mouse or
// the keyboard and copied (Ctrl/Cmd+C), and browser find sees it. It is built with the page's drawing, re-laid at every zoom,
// and emptied when the page is freed - one per drawn page, never more. A page without extractable text (a scan) has none.
//
// What this is NOT, on purpose: no annotation layer (a link in a PDF is ink on a canvas, never an element: a PDF cannot
// navigate this app - the text layer is spans only), no form fields. The document and its worker belong to `useFileViewer` and are destroyed there; this component
// only borrows them and cancels its own drawing when it goes.
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import type { PdfDocument, PdfRenderJob } from '../pdf/pdfEngine'

const props = defineProps<{ doc: PdfDocument; name: string }>()
const { t } = useI18n()

/** Zoom factors relative to "fit width" (1): the buttons step through them. */
const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4] as const
/** A page's height until its real size is known: A4 portrait; refined from the first page and from each page when drawn. */
const FALLBACK_ASPECT = 297 / 210
/** Width used where nothing can be measured (no layout, e.g. a test): pages are still drawn at a sane size. */
const FALLBACK_WIDTH = 800
const MAX_PIXEL_RATIO = 3

const scroller = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const zoom = ref(1)
const current = ref(1)
/** Height / width of each page, by number; unknown until `doc.pageSize` answered. */
const aspects = reactive<Record<number, number>>({})
const failed = reactive<Record<number, boolean>>({})

const pageNumbers = computed(() => Array.from({ length: props.doc.numPages }, (_, i) => i + 1))
const fitWidth = computed(() => Math.max(1, Math.floor((containerWidth.value || FALLBACK_WIDTH) - 2)))
const cssWidth = computed(() => Math.round(fitWidth.value * zoom.value))
const aspectOf = (n: number) => aspects[n] ?? aspects[1] ?? FALLBACK_ASPECT
const pageStyle = (n: number) => ({ width: `${cssWidth.value}px`, height: `${Math.round(cssWidth.value * aspectOf(n))}px` })
const canZoomOut = computed(() => zoom.value > ZOOM_STEPS[0])
const canZoomIn = computed(() => zoom.value < ZOOM_STEPS[ZOOM_STEPS.length - 1])

const canvases = new Map<number, HTMLCanvasElement>()
function setCanvas(n: number, el: unknown): void {
  if (el instanceof HTMLCanvasElement) canvases.set(n, el)
  else canvases.delete(n)
}
/** The element each page's text layer is built in (the engine fills it, `free` empties it). */
const textHosts = new Map<number, HTMLElement>()
function setTextHost(n: number, el: unknown): void {
  if (el instanceof HTMLElement) textHosts.set(n, el)
  else textHosts.delete(n)
}

// While a drag selects text, the layer's end marker covers the page so the drag cannot jump over a gap (pdf.js' own viewer
// does the same with `.selecting`); it is undone by the first pointer-up anywhere or when the window loses focus.
let selectingLayer: HTMLElement | null = null
function stopSelecting(): void {
  selectingLayer?.classList.remove('pdf-text-selecting')
  selectingLayer = null
}
function startSelecting(e: PointerEvent): void {
  const layer = (e.target as Element | null)?.closest?.<HTMLElement>('.pdf-text-layer') ?? null
  if (!layer) return
  stopSelecting()
  selectingLayer = layer
  layer.classList.add('pdf-text-selecting')
}

// --- drawing near the viewport ---------------------------------------------------------------------------------------
const near = new Set<number>()
const jobs = new Map<number, { job: PdfRenderJob; key: string }>()
const drawn = new Map<number, string>()
let alive = true

const pixelRatio = () => Math.min(MAX_PIXEL_RATIO, Math.max(1, typeof window === 'undefined' ? 1 : window.devicePixelRatio || 1))

async function draw(n: number): Promise<void> {
  if (!aspects[n]) {
    try {
      const size = await props.doc.pageSize(n)
      aspects[n] = size.height / size.width
    } catch {
      failed[n] = true
      return
    }
  }
  if (!alive || !near.has(n)) return
  const canvas = canvases.get(n)
  if (!canvas) return
  const ratio = pixelRatio()
  const key = `${cssWidth.value}@${ratio}`
  if (drawn.get(n) === key || jobs.get(n)?.key === key) return
  jobs.get(n)?.job.cancel()
  drawn.delete(n) // the engine resizes the canvas as it starts, which clears what was drawn at the old width
  const job = props.doc.render(n, canvas, cssWidth.value, ratio, textHosts.get(n) ?? null)
  jobs.set(n, { job, key })
  try {
    const ok = await job.done
    if (ok && jobs.get(n)?.job === job) {
      drawn.set(n, key)
      failed[n] = false
    }
  } catch {
    if (jobs.get(n)?.job === job) failed[n] = true
  } finally {
    if (jobs.get(n)?.job === job) jobs.delete(n)
  }
}

/** Far from the viewport: stop drawing and give the bitmap back (a 0 x 0 canvas holds none). */
function free(n: number): void {
  jobs.get(n)?.job.cancel()
  jobs.delete(n)
  drawn.delete(n)
  const canvas = canvases.get(n)
  if (canvas) {
    canvas.width = 0
    canvas.height = 0
  }
  textHosts.get(n)?.replaceChildren() // the text layer goes with the bitmap
}

let nearObserver: IntersectionObserver | null = null
let currentObserver: IntersectionObserver | null = null
let resizeObserver: ResizeObserver | null = null
const ratios = new Map<number, number>()

function numberOf(target: Element): number {
  return Number((target as HTMLElement).dataset.page)
}

function observe(): void {
  const root = scroller.value
  if (!root) return
  const pages = root.querySelectorAll<HTMLElement>('.pdf-page')
  if (typeof IntersectionObserver === 'undefined') {
    // No observer: draw the first pages and no more (a browser without it is far outside the supported ones).
    pages.forEach((el) => {
      const n = numberOf(el)
      if (n <= 3) {
        near.add(n)
        void draw(n)
      }
    })
    return
  }
  nearObserver = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        const n = numberOf(e.target)
        if (e.isIntersecting) {
          near.add(n)
          void draw(n)
        } else {
          near.delete(n)
          free(n)
        }
      }
    },
    { root, rootMargin: '100% 0px 100% 0px' },
  )
  currentObserver = new IntersectionObserver(
    (entries) => {
      for (const e of entries) ratios.set(numberOf(e.target), e.isIntersecting ? e.intersectionRatio : 0)
      let best = 0
      let bestRatio = 0
      for (const [n, r] of ratios) if (r > bestRatio || (r === bestRatio && r > 0 && n < best)) { best = n; bestRatio = r }
      if (best > 0) current.value = best
    },
    { root, threshold: [0, 0.25, 0.5, 0.75, 1] },
  )
  pages.forEach((el) => {
    nearObserver!.observe(el)
    currentObserver!.observe(el)
  })
}

function redrawNear(): void {
  for (const n of near) void draw(n)
}

// A new width (the window, full screen) or zoom redraws what is near; pages far away draw when they come near.
watch(cssWidth, () => redrawNear())

onMounted(() => {
  const root = scroller.value
  if (root) {
    containerWidth.value = root.clientWidth
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => {
        containerWidth.value = root.clientWidth
      })
      resizeObserver.observe(root)
    }
  }
  observe()
  document.addEventListener('pointerup', stopSelecting)
  window.addEventListener('blur', stopSelecting)
})
onBeforeUnmount(() => {
  alive = false
  document.removeEventListener('pointerup', stopSelecting)
  window.removeEventListener('blur', stopSelecting)
  stopSelecting()
  nearObserver?.disconnect()
  currentObserver?.disconnect()
  resizeObserver?.disconnect()
  for (const n of [...jobs.keys()]) free(n)
  for (const n of [...canvases.keys()]) free(n)
  canvases.clear()
  textHosts.clear()
})

// --- zoom --------------------------------------------------------------------------------------------------------------
/** Change the zoom, keeping the same part of the document in view (the scroll position as a share of the height). */
async function setZoom(next: number): Promise<void> {
  const el = scroller.value
  const share = el && el.scrollHeight > 0 ? el.scrollTop / el.scrollHeight : 0
  zoom.value = next
  await nextTick()
  if (el && el.scrollHeight > 0) el.scrollTop = share * el.scrollHeight
}
function zoomOut(): void {
  const next = [...ZOOM_STEPS].reverse().find((z) => z < zoom.value)
  if (next !== undefined) void setZoom(next)
}
function zoomIn(): void {
  const next = ZOOM_STEPS.find((z) => z > zoom.value)
  if (next !== undefined) void setZoom(next)
}
function fit(): void {
  void setZoom(1)
}
</script>

<template>
  <div class="pdf-viewer">
    <div class="pdf-toolbar" role="toolbar" :aria-label="t('depot.viewer.pdf.toolbar')">
      <span class="pdf-page-indicator">{{ t('depot.viewer.pdf.pageOf', { page: current, total: doc.numPages }) }}</span>
      <span class="pdf-zoom">
        <VeyqButton
          class="pdf-zoom-out"
          icon="search-minus"
          :aria-label="t('depot.viewer.pdf.zoomOut')"
          :title="t('depot.viewer.pdf.zoomOut')"
          :disabled="!canZoomOut"
          size="small"
          variant="secondary"
          appearance="outlined"
          @click="zoomOut"
        />
        <span class="pdf-zoom-level" aria-live="polite">{{ Math.round(zoom * 100) }} %</span>
        <VeyqButton
          class="pdf-zoom-in"
          icon="search-plus"
          :aria-label="t('depot.viewer.pdf.zoomIn')"
          :title="t('depot.viewer.pdf.zoomIn')"
          :disabled="!canZoomIn"
          size="small"
          variant="secondary"
          appearance="outlined"
          @click="zoomIn"
        />
        <VeyqButton
          class="pdf-zoom-fit"
          icon="arrows-h"
          :aria-label="t('depot.viewer.pdf.fitWidth')"
          :title="t('depot.viewer.pdf.fitWidth')"
          :disabled="zoom === 1"
          size="small"
          variant="secondary"
          appearance="outlined"
          @click="fit"
        />
      </span>
    </div>
    <div ref="scroller" class="pdf-scroll" tabindex="0" role="group" :aria-label="t('depot.viewer.pdf.pages', { name })" @pointerdown="startSelecting">
      <div v-for="n in pageNumbers" :key="n" class="pdf-page" :data-page="n" :style="pageStyle(n)">
        <canvas
          :ref="(el) => setCanvas(n, el)"
          class="pdf-canvas"
          role="img"
          :aria-label="t('depot.viewer.pdf.page', { page: n, total: doc.numPages })"
        />
        <div :ref="(el) => setTextHost(n, el)" class="pdf-text-layer" />
        <span v-if="failed[n]" class="pdf-page-failed" role="note">{{ t('depot.viewer.pdf.pageFailed', { page: n }) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pdf-viewer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 100%;
}
.pdf-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  color: var(--app-muted);
}
.pdf-zoom {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}
.pdf-zoom-level {
  min-width: 3.5rem;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.pdf-scroll {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.75rem;
  max-height: 75vh;
  overflow: auto;
  padding: 0.5rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-surface-muted);
}
.pdf-page {
  position: relative;
  flex: none;
  margin: 0 auto;
  /* Paper: a PDF is drawn for a white page in every theme (its own colours assume it), so this is not a themed surface. */
  background: #fff;
  box-shadow: 0 1px 4px var(--app-backdrop-strong);
}
.pdf-canvas {
  display: block;
  width: 100%;
  height: 100%;
}
.pdf-page-failed {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  text-align: center;
  color: #444;
}
:global(.file-viewer-full) .pdf-scroll,
:global(.file-viewer:fullscreen) .pdf-scroll {
  max-height: calc(100vh - 9rem);
}
</style>
