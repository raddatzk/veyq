<script setup lang="ts">
// The banner dialog (uc.memory.banner main flow 2-6): choose an image or a video - a picked file, or the gallery medium the
// lightbox hands over - set the point that stays in view, see it framed as a wide screen and as a phone show it, switch a
// portrait medium between fit and fill, describe it, and save; or re-frame or remove the current banner. Presentation only:
// it holds no key and no network - the save is the page's (`useBannerEditor`), awaited here so the dialog can show its
// progress, cancel it, and say what went wrong.
//
// What it refuses before anything is read further (main flow 2, E4, Q5): an image above 40 MB, a file the browser cannot
// read, and a video of which this browser cannot decode a frame - no video banner where nothing could be drawn before it
// plays. A video has no length bound (Q4): it is copied as it is, read by frame while it uploads.
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import ProgressBar from 'primevue/progressbar'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { createBrowserDeriver, type MediaDeriver } from '../../../shared/image/mediaDeriver'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import { inferMediaType } from '../uploadQueue'
import BannerFrame from './BannerFrame.vue'
import { CENTRE, MAX_BANNER_ALT, defaultFrame, isPortrait, type Banner, type BannerFrame as Frame, type FocalPoint } from './bannerModel'
import { BANNER_TOO_LARGE, BANNER_UNREADABLE, BannerStaleError, MAX_BANNER_IMAGE_BYTES } from './bannerWrite'
import type { BannerSaveIntent, BannerSaveOpts } from './useBannerEditor'

const props = defineProps<{
  /** The stored banner (re-framed, or removed, here) - null: the Memory has none. */
  current: Banner | null
  /** The current banner's decrypted picture (its image, or a video's poster). */
  currentSrc: string | null
  /** A gallery medium the lightbox handed over ("Use as banner"): its id, its decrypted preview, whether it is a video. */
  seed?: { mediaId: string; src: string | null; video: boolean } | null
  /** The page's save (see the header). */
  save: (intent: BannerSaveIntent, opts: BannerSaveOpts) => Promise<void>
  /** The pixel stage (a test hands a stub). */
  deriver?: MediaDeriver
}>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

type Pick = { kind: 'file'; file: File; type: string; poster: Uint8Array | null } | { kind: 'medium'; mediaId: string } | { kind: 'keep' }
const pick = ref<Pick | null>(null)
const src = ref<string>('')
const picked = useObjectUrl() // a picked image, or a picked video's decoded first frame (revoked on replace and on close)
const fileInput = ref<HTMLInputElement | null>(null)
const dims = ref<{ w: number; h: number } | null>(null)
const frame = ref<Frame>('fill')
const frameChosen = ref(false) // a stored or a chosen frame stands; a new medium takes its default once its size is known
const focal = ref<FocalPoint>({ ...CENTRE })
const alt = ref('')
const problem = ref<string | null>(null)
const phase = ref<'idle' | 'preparing' | 'saving'>('idle')
const progress = ref<number | null>(null)
let controller: AbortController | null = null

if (props.seed) {
  pick.value = { kind: 'medium', mediaId: props.seed.mediaId }
  src.value = props.seed.src ?? ''
} else if (props.current) {
  pick.value = { kind: 'keep' }
  src.value = props.currentSrc ?? ''
  frame.value = props.current.frame
  frameChosen.value = true
  focal.value = { ...props.current.focal }
}
if (props.current) alt.value = props.current.alt ?? ''

const busy = computed(() => phase.value !== 'idle')
const portrait = computed(() => !!dims.value && isPortrait(dims.value.w, dims.value.h))
const canSave = computed(() => !busy.value && pick.value !== null && (pick.value.kind !== 'keep' || !!props.current))
const isVideo = computed(() =>
  pick.value?.kind === 'file' ? pick.value.type.startsWith('video/') : pick.value?.kind === 'medium' ? !!props.seed?.video : props.current?.medium === 'video',
)

function choose() {
  fileInput.value?.click()
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = '' // allow re-picking the same file
  if (!file) return
  problem.value = null
  const type = inferMediaType(file)
  if (type.startsWith('image/')) {
    if (file.size > MAX_BANNER_IMAGE_BYTES) {
      problem.value = t('memory.banner.tooLarge', { max: '40 MB' })
      return
    }
    use({ kind: 'file', file, type, poster: null }, file)
  } else if (type.startsWith('video/')) {
    phase.value = 'preparing'
    try {
      const poster = await (props.deriver ?? createBrowserDeriver()).deriveVideoPoster(file)
      if (!poster.length) throw new Error(BANNER_UNREADABLE)
      use({ kind: 'file', file, type, poster }, new Blob([poster as BlobPart], { type: 'image/jpeg' }))
    } catch {
      problem.value = t('memory.banner.videoUnreadable') // Q5: no video banner where no frame can be drawn
    } finally {
      phase.value = 'idle'
    }
  } else {
    problem.value = t('memory.banner.unreadable')
  }
}

function use(p: Pick, picture: Blob) {
  pick.value = p
  picked.set(picture)
  src.value = picked.url.value ?? ''
  dims.value = null
  frameChosen.value = false
  focal.value = { ...CENTRE }
}

function onLoad(event: Event) {
  const img = event.target as HTMLImageElement
  if (!img.naturalWidth || !img.naturalHeight) return
  dims.value = { w: img.naturalWidth, h: img.naturalHeight }
  if (!frameChosen.value) frame.value = defaultFrame(img.naturalWidth, img.naturalHeight)
}

// --- the focal point: a click or a drag on the picture, the arrow keys on the handle -------------------------------
const stage = ref<HTMLElement | null>(null)
const clamp = (v: number) => Math.min(1, Math.max(0, v))
function focusAt(e: PointerEvent) {
  const box = stage.value?.getBoundingClientRect()
  if (!box || !box.width || !box.height) return
  focal.value = { x: clamp((e.clientX - box.left) / box.width), y: clamp((e.clientY - box.top) / box.height) }
}
let dragging = false
function onPointerDown(e: PointerEvent) {
  dragging = true
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
  focusAt(e)
}
function onPointerMove(e: PointerEvent) {
  if (dragging) focusAt(e)
}
function onPointerUp() {
  dragging = false
}
function onHandleKey(e: KeyboardEvent) {
  const step = e.shiftKey ? 0.1 : 0.02
  const moves: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }
  const m = moves[e.key]
  if (!m) return
  e.preventDefault()
  focal.value = { x: clamp(focal.value.x + m[0]), y: clamp(focal.value.y + m[1]) }
}
const handleStyle = computed(() => ({ left: `${focal.value.x * 100}%`, top: `${focal.value.y * 100}%` }))

function setFrame(f: Frame) {
  frame.value = f
  frameChosen.value = true
}

// --- save, remove, cancel --------------------------------------------------------------------------------------------
const framing = () => ({ frame: frame.value, focal: { ...focal.value }, alt: alt.value })

async function run(intent: BannerSaveIntent) {
  controller = new AbortController()
  phase.value = 'saving'
  progress.value = null
  problem.value = null
  try {
    await props.save(intent, { signal: controller.signal, onProgress: (f) => (progress.value = Math.round(f * 100)) })
    emit('close')
  } catch (e) {
    if (controller?.signal.aborted) return // the member cancelled: the old banner stands, nothing to say
    problem.value = wording(e)
  } finally {
    phase.value = 'idle'
    progress.value = null
    controller = null
  }
}

function wording(e: unknown): string {
  const code = e instanceof Error ? e.message : ''
  if (e instanceof BannerStaleError || e instanceof MetaConflictError) return t('memory.banner.stale')
  if (code === BANNER_TOO_LARGE) return t('memory.banner.tooLarge', { max: '40 MB' })
  if (code === BANNER_UNREADABLE) return isVideo.value ? t('memory.banner.noFrame') : t('memory.banner.unreadable')
  return t('memory.banner.failed')
}

function onSave() {
  const p = pick.value
  if (!p || !canSave.value) return
  if (p.kind === 'file') void run({ kind: 'file', file: p.file, type: p.type, poster: p.poster, framing: framing() })
  else if (p.kind === 'medium') void run({ kind: 'medium', mediaId: p.mediaId, framing: framing() })
  else void run({ kind: 'keep', framing: framing() })
}

function onRemove() {
  void run({ kind: 'remove' })
}

function cancel() {
  if (phase.value === 'saving') controller?.abort()
  else if (!busy.value) emit('close')
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <Dialog
    :visible="true"
    :header="current ? t('memory.banner.change') : t('memory.banner.add')"
    modal
    :closable="!busy"
    :close-on-escape="!busy"
    @update:visible="cancel"
  >
    <div class="veyq-form banner-dialog">
      <p class="banner-dialog-copy">{{ t('memory.banner.hint') }} {{ t('memory.banner.galleryHint') }}</p>

      <div class="banner-dialog-sources">
        <Button type="button" :label="t('memory.banner.choose')" icon="pi pi-upload" severity="secondary" outlined :disabled="busy" @click="choose" />
        <input ref="fileInput" type="file" accept="image/*,video/*" class="banner-dialog-file" tabindex="-1" aria-hidden="true" @change="onFilePicked" />
      </div>
      <p v-if="phase === 'preparing'" class="banner-dialog-copy" role="status">{{ t('memory.banner.preparing') }}</p>

      <template v-if="src">
        <!-- The whole medium, the point that stays in view on it: a click or a drag moves it, so do the arrow keys. -->
        <div
          ref="stage"
          class="banner-dialog-stage"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        >
          <img class="banner-dialog-image" :src="src" alt="" draggable="false" @load="onLoad" />
          <button
            type="button"
            class="banner-focal-handle"
            :style="handleStyle"
            :aria-label="t('memory.banner.focalPoint')"
            :title="t('memory.banner.focalPoint')"
            @keydown="onHandleKey"
          />
        </div>

        <div class="banner-dialog-previews">
          <figure class="banner-dialog-preview-figure">
            <div class="banner-dialog-preview is-wide" data-testid="banner-preview-wide">
              <BannerFrame :src="src" :frame="frame" :focal="focal" />
            </div>
            <figcaption>{{ t('memory.banner.previewWide') }}</figcaption>
          </figure>
          <figure class="banner-dialog-preview-figure is-phone-figure">
            <div class="banner-dialog-preview is-phone" data-testid="banner-preview-phone">
              <BannerFrame :src="src" :frame="frame" :focal="focal" />
            </div>
            <figcaption>{{ t('memory.banner.previewPhone') }}</figcaption>
          </figure>
        </div>

        <div v-if="portrait" class="banner-dialog-frame" role="group" :aria-label="t('memory.banner.frame')">
          <button
            v-for="f in (['fit', 'fill'] as const)"
            :key="f"
            type="button"
            class="banner-dialog-frame-option"
            :class="{ 'is-active': frame === f }"
            :aria-pressed="frame === f"
            :disabled="busy"
            @click="setFrame(f)"
          >
            {{ t(`memory.banner.${f}`) }}
          </button>
        </div>

        <label>
          {{ t('memory.banner.description') }}
          <InputText v-model="alt" :maxlength="MAX_BANNER_ALT" autocomplete="off" :disabled="busy" />
          <small class="banner-dialog-copy">{{ t('memory.banner.descriptionHint') }}</small>
        </label>
      </template>

      <p v-if="problem" class="banner-dialog-problem" role="alert">{{ problem }}</p>
      <div v-if="phase === 'saving'" class="banner-dialog-progress" role="status">
        <span>{{ progress === null ? t('memory.banner.preparing') : t('memory.banner.uploading', { percent: progress }) }}</span>
        <ProgressBar :value="progress ?? 0" :show-value="false" />
      </div>

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="phase === 'preparing'" @click="cancel" />
        <div class="veyq-action-group">
          <Button
            v-if="current"
            type="button"
            :label="t('memory.banner.remove')"
            icon="pi pi-trash"
            severity="danger"
            outlined
            :disabled="busy"
            @click="onRemove"
          />
          <Button type="button" :label="t('memory.banner.save')" icon="pi pi-check" :disabled="!canSave" :loading="phase === 'saving'" @click="onSave" />
        </div>
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.banner-dialog {
  width: min(640px, 92vw);
  gap: 0.85rem;
}
.banner-dialog-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.banner-dialog-sources {
  display: flex;
  gap: 0.5rem;
}
.banner-dialog-file {
  display: none;
}
/* The whole medium at its own aspect, the handle over it. */
.banner-dialog-stage {
  position: relative;
  align-self: center;
  max-width: 100%;
  touch-action: none;
  cursor: crosshair;
  user-select: none;
}
.banner-dialog-image {
  display: block;
  max-width: 100%;
  max-height: 34vh;
  border-radius: var(--app-radius-sm);
}
.banner-focal-handle {
  position: absolute;
  width: 1.6rem;
  height: 1.6rem;
  padding: 0;
  transform: translate(-50%, -50%);
  border: 3px solid #fff;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-accent) 55%, transparent);
  box-shadow: 0 0 0 1px rgb(0 0 0 / 0.45);
  cursor: grab;
}
.banner-focal-handle:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.banner-dialog-previews {
  display: grid;
  grid-template-columns: 3fr 1.3fr;
  gap: 0.75rem;
  align-items: end;
}
.banner-dialog-preview-figure {
  margin: 0;
  display: grid;
  gap: 0.3rem;
}
.banner-dialog-preview-figure figcaption {
  color: var(--app-muted);
  font-size: 0.8rem;
}
.banner-dialog-preview {
  position: relative;
  overflow: hidden;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
}
.banner-dialog-preview.is-wide {
  aspect-ratio: 3 / 1;
}
.banner-dialog-preview.is-phone {
  aspect-ratio: 16 / 9;
}
/* The fit/fill switch: the gallery's mark filter's look. */
.banner-dialog-frame {
  display: inline-flex;
  align-self: start;
  gap: 2px;
  padding: 2px;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
}
.banner-dialog-frame-option {
  padding: 4px 10px;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-muted);
  font-size: 0.8125rem;
  cursor: pointer;
}
.banner-dialog-frame-option.is-active {
  background: var(--app-surface);
  color: var(--app-text);
}
.banner-dialog-problem {
  margin: 0;
  color: var(--app-danger);
  font-size: 0.9rem;
}
.banner-dialog-progress {
  display: grid;
  gap: 0.35rem;
  font-size: 0.85rem;
  color: var(--app-muted);
}
</style>
