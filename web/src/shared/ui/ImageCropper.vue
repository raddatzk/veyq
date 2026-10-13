<script lang="ts">
/** A normalized crop rectangle against the source image — every field a `0..1` fraction of the source dimensions. */
export type CropRect = { x: number; y: number; width: number; height: number }
</script>

<script setup lang="ts">
// A pan + zoom crop viewport with a configurable aspect ratio (extracted from MemoryCoverDialog.vue, cr-space-icon).
// Frames ONE source image into a `aspect`-shaped rectangle and reports the normalized `0..1` crop against the
// source — the Memory cover stores that crop (aspect 16/9) and re-renders; the space icon bakes it (aspect 1).
// Presentation only: no crypto, no network. The crop math (base-scale fill, clamp-so-no-empty-corners, normalize
// to 0..1) is the legacy math, moved verbatim. Class names keep the `memory-cover-*` prefix so the cover's styles
// + tests carry over unchanged.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    src: string
    /** The crop viewport's aspect ratio as width / height (16/9 for the Memory cover, 1 for a space icon). */
    aspect?: number
    /** A stored crop to restore once the image's natural size is known; null centres a fresh crop. */
    initialCrop?: CropRect | null
  }>(),
  { aspect: 16 / 9, initialCrop: null },
)
const emit = defineEmits<{ load: []; error: [] }>()
const { t } = useI18n()

// The crop viewport is **measured** from the rendered cropper (which fills its container width) rather than a fixed
// size, so it scales with the layout + never leaves a gap. The stored crop is normalized 0..1, so the absolute size
// doesn't affect the result — only the editing surface. Defaults (320 × the aspect) until first measured.
const cropperEl = ref<HTMLElement | null>(null)
const cropWidth = ref(320)
const cropHeight = ref(Math.round(320 / (16 / 9)))

/** Measure the cropper's rendered width and derive the height from `aspect` (re-run on open + on resize). */
function measureCropper() {
  const w = cropperEl.value?.clientWidth
  if (w && w > 0) {
    cropWidth.value = w
    cropHeight.value = Math.round(w / props.aspect)
  } else {
    // Keep the default height consistent with the aspect even before a real measurement (happy-dom clientWidth = 0).
    cropHeight.value = Math.round(cropWidth.value / props.aspect)
  }
}

const imageElement = ref<HTMLImageElement | null>(null)
const naturalWidth = ref(0)
const naturalHeight = ref(0)
const zoom = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
/** The crop to restore once the image's natural size is known (set from initialCrop; cleared on a source change). */
const pending = ref<CropRect | null>(props.initialCrop)

const imageLoaded = computed(() => naturalWidth.value > 0 && naturalHeight.value > 0)
const baseScale = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) return 1
  return Math.max(cropWidth.value / naturalWidth.value, cropHeight.value / naturalHeight.value)
})
const baseImageWidth = computed(() => naturalWidth.value * baseScale.value)
const baseImageHeight = computed(() => naturalHeight.value * baseScale.value)
const displayedImageStyle = computed(() => ({
  width: `${baseImageWidth.value * zoom.value}px`,
  height: `${baseImageHeight.value * zoom.value}px`,
  transform: `translate(${offsetX.value}px, ${offsetY.value}px)`,
}))

const dragging = ref(false)
const dragStart = ref({ pointerId: 0, x: 0, y: 0, offsetX: 0, offsetY: 0 })

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

// A new source resets the pan/zoom and re-arms the restore crop; the crop is applied once the image's natural size
// is known (loadImage). Mirrors the dialog's old resetView-on-pick + initialCrop-on-reopen behaviour.
watch(
  () => props.src,
  () => {
    naturalWidth.value = 0
    naturalHeight.value = 0
    zoom.value = 1
    offsetX.value = 0
    offsetY.value = 0
    pending.value = props.initialCrop
  },
)

async function loadImage() {
  const image = imageElement.value
  if (!image) return
  naturalWidth.value = image.naturalWidth
  naturalHeight.value = image.naturalHeight
  await nextTick()
  measureCropper() // the cropper fills its container width by now — measure before computing the crop
  if (pending.value) applyCrop(pending.value)
  else centerImage()
  emit('load')
}

function centerImage() {
  offsetX.value = (cropWidth.value - baseImageWidth.value * zoom.value) / 2
  offsetY.value = (cropHeight.value - baseImageHeight.value * zoom.value) / 2
  clampOffset()
}

function applyCrop(crop: CropRect) {
  const nextZoom = clamp(cropWidth.value / (crop.width * naturalWidth.value * baseScale.value), 1, 3)
  zoom.value = nextZoom
  const scale = baseScale.value * zoom.value
  offsetX.value = -crop.x * naturalWidth.value * scale
  offsetY.value = -crop.y * naturalHeight.value * scale
  clampOffset()
}

function updateZoom(event: Event) {
  const target = event.target as HTMLInputElement
  const nextZoom = Number(target.value)
  const previousZoom = zoom.value
  if (!Number.isFinite(nextZoom) || nextZoom <= 0 || previousZoom <= 0) return
  const centerX = cropWidth.value / 2
  const centerY = cropHeight.value / 2
  offsetX.value = centerX - ((centerX - offsetX.value) / previousZoom) * nextZoom
  offsetY.value = centerY - ((centerY - offsetY.value) / previousZoom) * nextZoom
  zoom.value = nextZoom
  clampOffset()
}

function startDrag(event: PointerEvent) {
  if (!imageLoaded.value) return
  dragging.value = true
  dragStart.value = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, offsetX: offsetX.value, offsetY: offsetY.value }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function drag(event: PointerEvent) {
  if (!dragging.value || event.pointerId !== dragStart.value.pointerId) return
  offsetX.value = dragStart.value.offsetX + event.clientX - dragStart.value.x
  offsetY.value = dragStart.value.offsetY + event.clientY - dragStart.value.y
  clampOffset()
}

function stopDrag(event: PointerEvent) {
  if (!dragging.value || event.pointerId !== dragStart.value.pointerId) return
  dragging.value = false
  ;(event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId)
}

// Clamp the pan so the (base-scale-filled) image never leaves an empty corner inside the crop frame.
function clampOffset() {
  const width = baseImageWidth.value * zoom.value
  const height = baseImageHeight.value * zoom.value
  offsetX.value = clamp(offsetX.value, Math.min(0, cropWidth.value - width), 0)
  offsetY.value = clamp(offsetY.value, Math.min(0, cropHeight.value - height), 0)
}

// Normalize the current pan/zoom back to a `0..1` crop rectangle against the source image (the reported shape).
function currentCrop(): CropRect {
  const scale = baseScale.value * zoom.value
  return {
    x: clamp(-offsetX.value / scale / naturalWidth.value, 0, 1),
    y: clamp(-offsetY.value / scale / naturalHeight.value, 0, 1),
    width: clamp(cropWidth.value / scale / naturalWidth.value, 0, 1),
    height: clamp(cropHeight.value / scale / naturalHeight.value, 0, 1),
  }
}

// Keep the crop viewport in sync with the container width (re-measure + re-clamp the pan so no empty corner shows).
function onResize() {
  measureCropper()
  clampOffset()
}
onMounted(() => {
  window.addEventListener('resize', onResize)
  // A reopened source shows an already-cached image whose `@load` may not refire — measure the laid-out cropper and
  // compute the crop here so it fills the width on open, not only after a resize.
  void nextTick(() => {
    measureCropper()
    const img = imageElement.value
    if (img?.complete && img.naturalWidth > 0) void loadImage()
  })
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})

defineExpose({
  /** The current normalized crop, or null before the image has loaded. */
  getCrop: (): CropRect | null => (imageLoaded.value ? currentCrop() : null),
  /** Whether the source image has decoded (drives the consumer's Save gate). */
  isLoaded: () => imageLoaded.value,
  /** The loaded source `<img>` — a consumer that bakes pixels (the space icon) draws its crop from this. */
  getImage: (): HTMLImageElement | null => imageElement.value,
})
</script>

<template>
  <div class="memory-cover-editor">
    <div
      ref="cropperEl"
      class="memory-cover-cropper"
      :class="{ 'memory-cover-cropper-dragging': dragging }"
      :style="{ aspectRatio: String(aspect) }"
      @pointerdown="startDrag"
      @pointermove="drag"
      @pointerup="stopDrag"
      @pointercancel="stopDrag"
    >
      <img ref="imageElement" :src="src" :style="displayedImageStyle" draggable="false" alt="" @load="loadImage" @error="emit('error')" />
    </div>
    <label class="memory-cover-zoom">
      <span>{{ t('common.zoom') }}</span>
      <input type="range" min="1" max="3" step="0.01" :value="zoom" :disabled="!imageLoaded" @input="updateZoom" />
    </label>
  </div>
</template>

<style scoped>
.memory-cover-editor {
  display: grid;
  justify-items: stretch;
  gap: 0.85rem;
}
.memory-cover-cropper {
  position: relative;
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-backdrop-strong);
  cursor: grab;
  touch-action: none;
}
.memory-cover-cropper::after {
  position: absolute;
  inset: 0;
  border: 2px solid rgb(255 255 255 / 82%);
  box-shadow: inset 0 0 0 999px rgb(15 23 42 / 8%);
  content: '';
  pointer-events: none;
}
.memory-cover-cropper-dragging {
  cursor: grabbing;
}
.memory-cover-cropper img {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  user-select: none;
  will-change: transform;
}
.memory-cover-zoom {
  display: grid;
  grid-template-columns: auto minmax(180px, 1fr);
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  color: var(--app-control-text);
  font-size: 0.9rem;
}
.memory-cover-zoom input {
  width: 100%;
}
</style>
