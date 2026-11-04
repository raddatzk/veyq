<script setup lang="ts">
// A Memory's banner on screen (uc.memory.banner, ADR 0102 decisions 6-7): one image or one video above
// the Memory's header row - core draws it there, as `MemoryDetail` contributes it (ADR 0110 decision 10). The frame is the
// screen's, not the medium's: 16:9 where the page is wide, 1:1 where it is narrow, never taller than 60 % of the viewport - a
// container query on core's page (`.veyq-node-page`, an `inline-size` container). Its space is reserved as soon as the sealed meta names a banner, so nothing below it jumps when the bytes
// arrive; a banner whose picture fails draws nothing at all (E5: the header row moves up).
//
// The WHOLE medium, never cropped: it is contained in the frame, and where it does not fill it - left and right for a
// narrower one, above and below for a wider one - a blurred, enlarged copy of the same decrypted picture fills the rest
// (`aria-hidden`; nothing is made or stored for it). A stored `frame` and focal point (an earlier editor's) are read and
// kept, never drawn. While a video plays, that blur follows it (owner 2026-11-01): each frame the browser shows is drawn
// onto a canvas of 32 px across - one tiny draw, no second decode, no second read of the video - which the display enlarges
// and blurs; paused, it holds the last frame, and under `prefers-reduced-motion` it stays the poster. A video shows its poster and a play button and NOTHING plays before the click (no `<video>` exists until
// then); the click registers the `BANNER` blob with the media service worker (ADR 0047) and plays it with its controls -
// or, without a service worker, decrypts it whole first, as the lightbox's fallback does. The same component for a member
// and a guest; where the banner may be changed (`edit`, a member's `setBanner`) a click on it opens its dialog (owner
// 2026-11-04, ADR 0110 decision 11: what is edited at its place has no header action) - the frame itself while nothing plays,
// a button in its corner while a video plays (its controls take the clicks then). A guest's banner offers nothing.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { handleFromUrl, registerPlayback, revokePlayback } from '../../../sw/register'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import type { ChunkProvider } from '../../../sw/protocol'
import type { Banner } from './bannerModel'
import BannerFrame from './BannerFrame.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import type { BannerStatus } from './useMemoryBanner'

const props = defineProps<{
  banner: Banner | null
  /** The decrypted image, or a video's poster (null while it loads, or for a video without a poster). */
  pictureUrl: string | null
  status: BannerStatus
  /** A video's chunk source over its `BANNER` ref (the SW playback). */
  videoChunks: () => ChunkProvider | null
  /** A video decrypted whole (the playback without a service worker). */
  videoBytes: () => Promise<Uint8Array | null>
  /** Open the banner's dialog (change or remove it); null or absent: the banner offers nothing (a guest, a reader). */
  edit?: (() => void) | null
}>()

const { t } = useI18n()

const shown = computed(() => !!props.banner && props.status !== 'failed' && props.status !== 'none')
const isVideo = computed(() => props.banner?.medium === 'video')

// --- playback, only after the click --------------------------------------------------------------------------------
const swUrl = ref<string | null>(null)
let handle: string | null = null
const wholeVideo = useObjectUrl()
const loading = ref(false)
const failed = ref(false)
const videoSrc = computed(() => swUrl.value ?? wholeVideo.url.value)
let run = 0

// --- the backdrop that follows the playing video -------------------------------------------------------------------
/** The canvas's width; its height follows the video's aspect. So few pixels are a blur by themselves when enlarged. */
const BACKDROP_WIDTH = 32
type FrameVideo = HTMLVideoElement & {
  requestVideoFrameCallback?: (cb: () => void) => number
  cancelVideoFrameCallback?: (handle: number) => void
}
const videoEl = ref<FrameVideo | null>(null)
const backdropCanvas = ref<HTMLCanvasElement | null>(null)
/** The canvas holds a frame: it replaces the poster behind the video. */
const live = ref(false)
let frameHandle: { kind: 'video' | 'animation'; id: number } | null = null
const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

function drawFrame() {
  const v = videoEl.value
  const canvas = backdropCanvas.value
  if (!v || !canvas || !v.videoWidth || !v.videoHeight || reducedMotion()) return
  const height = Math.max(1, Math.round((BACKDROP_WIDTH * v.videoHeight) / v.videoWidth))
  if (canvas.width !== BACKDROP_WIDTH || canvas.height !== height) {
    canvas.width = BACKDROP_WIDTH
    canvas.height = height
  }
  const context = canvas.getContext('2d')
  if (!context) return
  context.drawImage(v, 0, 0, BACKDROP_WIDTH, height)
  live.value = true
}

/** One draw per frame the browser presents (`requestVideoFrameCallback`), else per animation frame; none once paused. */
function nextFrame() {
  const v = videoEl.value
  frameHandle = null
  if (!v || v.paused || v.ended || reducedMotion()) return
  if (typeof v.requestVideoFrameCallback === 'function') {
    frameHandle = { kind: 'video', id: v.requestVideoFrameCallback(onFrame) }
  } else if (typeof requestAnimationFrame === 'function') {
    frameHandle = { kind: 'animation', id: requestAnimationFrame(onFrame) }
  }
}
function onFrame() {
  drawFrame()
  nextFrame()
}
function stopFrames() {
  const h = frameHandle
  frameHandle = null
  if (!h) return
  if (h.kind === 'video') videoEl.value?.cancelVideoFrameCallback?.(h.id)
  else if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(h.id)
}
function onPlaying() {
  stopFrames()
  nextFrame()
}

function stop() {
  stopFrames()
  live.value = false
  run++
  if (handle) revokePlayback(handle)
  handle = null
  swUrl.value = null
  wholeVideo.set(null)
  loading.value = false
  failed.value = false
}

async function play() {
  const b = props.banner
  if (!b || b.medium !== 'video' || loading.value) return
  const mine = ++run
  loading.value = true
  failed.value = false
  try {
    const provider = props.videoChunks()
    if (!provider) throw new Error('no_video')
    const type = b.type ?? 'video/mp4'
    const url = await registerPlayback({ chunkCount: provider.chunkCount, byteSize: provider.byteSize, mediaType: type }, provider)
    if (mine !== run) {
      const orphan = url ? handleFromUrl(url) : null
      if (orphan) revokePlayback(orphan)
      return
    }
    if (url) {
      handle = handleFromUrl(url)
      swUrl.value = url
    } else {
      const bytes = await props.videoBytes() // no usable service worker: the whole copy, as the lightbox's fallback
      if (mine !== run) return
      if (!bytes) throw new Error('no_video')
      wholeVideo.set(new Blob([bytes as BlobPart], { type }))
    }
  } catch {
    if (mine === run) failed.value = true
  } finally {
    if (mine === run) loading.value = false
  }
}

// A changed banner (a new one, a removal, another node) stops what played.
watch(() => props.banner?.blob.blobId ?? null, stop)
onBeforeUnmount(stop)
</script>

<template>
  <section
    v-if="shown"
    class="memory-banner"
    :class="{ 'is-video': isVideo, 'is-loading': !pictureUrl && !videoSrc }"
    data-testid="memory-banner"
  >
    <template v-if="videoSrc">
      <img v-if="pictureUrl && !live" class="memory-banner-backdrop" :src="pictureUrl" alt="" aria-hidden="true" />
      <canvas v-show="live" ref="backdropCanvas" class="memory-banner-backdrop" aria-hidden="true" />
      <video
        ref="videoEl"
        class="memory-banner-media"
        :src="videoSrc"
        :aria-label="t('memory.banner.video')"
        controls
        autoplay
        playsinline
        @play="onPlaying"
        @seeked="drawFrame"
        @loadeddata="drawFrame"
        @pause="stopFrames"
        @ended="stopFrames"
        @error="failed = true"
      />
    </template>
    <template v-else>
      <!-- Decorative: the banner has no description, and the Memory's title follows it. -->
      <BannerFrame v-if="pictureUrl && banner" :src="pictureUrl" />
      <button
        v-if="isVideo"
        type="button"
        class="memory-banner-play"
        :disabled="loading"
        :aria-label="t('memory.banner.play')"
        :title="t('memory.banner.play')"
        @click="play"
      >
        <VeyqIcon :name="loading ? 'spinner' : 'play'" :spin="loading" />
      </button>
    </template>
    <!-- Its way to its dialog: the whole frame while nothing plays (beneath the play button), a corner button while a video
         plays. -->
    <button
      v-if="edit"
      type="button"
      class="memory-banner-edit"
      :class="{ 'is-corner': !!videoSrc }"
      :aria-label="t('memory.banner.change')"
      :title="t('memory.banner.change')"
      data-testid="memory-banner-edit"
      @click="edit()"
    >
      <span class="memory-banner-edit-hint">
        <VeyqIcon name="objects-column" />
        <span class="memory-banner-edit-label">{{ t('memory.banner.change') }}</span>
      </span>
    </button>
    <p v-if="failed" class="memory-banner-error" role="status">{{ t('memory.banner.playFailed') }}</p>
  </section>
</template>

<style scoped>
/* The frame (owner 2026-11-01): 16:9 on a wide column, 1:1 on a narrow one, at most 60 % of the viewport's height - there
   it is lower, and the whole medium in it narrower, the blur at its sides wider. The space is held while the bytes load
   (`is-loading`), so the header row below never jumps. */
.memory-banner {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  max-height: 60vh;
  overflow: hidden;
  border-radius: var(--app-radius);
  background: var(--app-control-bg);
}
@container veyq-node-page (max-width: 720px) {
  .memory-banner {
    aspect-ratio: 1 / 1;
  }
}
.memory-banner-media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
/* Where the whole medium leaves the frame: the same picture, enlarged and blurred, behind it. A static picture. */
.memory-banner-backdrop {
  position: absolute;
  inset: -8%;
  width: 116%;
  height: 116%;
  object-fit: cover;
  filter: blur(28px) saturate(1.1);
  opacity: 0.85;
}
/* The way to the banner's dialog: a transparent button over the whole frame, its hint at the foot on hover and keyboard focus
   (the cover's look); while a video plays, a small round button in the frame's corner instead, always shown. */
.memory-banner-edit {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  padding: 0;
  border: none;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
}
.memory-banner-edit:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: -2px;
}
.memory-banner-edit-hint {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.35rem;
  background: color-mix(in srgb, var(--app-backdrop-strong) 62%, transparent);
  color: #fff;
  font-size: 0.85rem;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.memory-banner-edit:hover .memory-banner-edit-hint,
.memory-banner-edit:focus-visible .memory-banner-edit-hint {
  opacity: 1;
}
.memory-banner-edit.is-corner {
  inset: 0.6rem 0.6rem auto auto;
  width: 2.4rem;
  height: 2.4rem;
  overflow: hidden;
  border-radius: 999px;
}
.memory-banner-edit.is-corner .memory-banner-edit-hint {
  height: 100%;
  padding: 0;
  opacity: 1;
}
.memory-banner-edit.is-corner .memory-banner-edit-label {
  display: none;
}
.memory-banner-play {
  z-index: 1;
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: grid;
  place-items: center;
  width: 4rem;
  height: 4rem;
  border: none;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-backdrop-strong) 60%, transparent);
  color: #fff;
  font-size: 1.5rem;
  cursor: pointer;
  transition: background 0.15s ease;
}
.memory-banner-play:hover:not(:disabled) {
  background: var(--app-accent);
  color: var(--app-on-fill);
}
.memory-banner-play:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.memory-banner-play .veyq-icon {
  margin-left: 0.2rem; /* optically center the play triangle */
}
.memory-banner-error {
  position: absolute;
  inset-inline: 0;
  bottom: 0;
  margin: 0;
  padding: 0.4rem 0.75rem;
  background: color-mix(in srgb, var(--app-backdrop-strong) 62%, transparent);
  color: #fff;
  font-size: 0.85rem;
}
</style>
