<script setup lang="ts">
// A Memory's banner on screen (uc.memory.banner, ADR 0102 decisions 6-7, amended 2026-11-01): one image or one video above
// the Memory's header row. The frame is the screen's, not the medium's: 16:9 where the Memory's column is wide, 1:1 where it
// is narrow, never taller than 60 % of the viewport - a container query on `.memory-detail` (already an `inline-size`
// container). Its space is reserved as soon as the sealed meta names a banner, so nothing below it jumps when the bytes
// arrive; a banner whose picture fails draws nothing at all (E5: the header row moves up).
//
// The WHOLE medium, never cropped: it is contained in the frame, and where it does not fill it - left and right for a
// narrower one, above and below for a wider one - a blurred, enlarged copy of the same decrypted picture fills the rest
// (`aria-hidden`; nothing is made or stored for it). A stored `frame` and focal point (an earlier editor's) are read and
// kept, never drawn. A video shows its poster and a play button and NOTHING plays before the click (no `<video>` exists until
// then); the click registers the `BANNER` blob with the media service worker (ADR 0047) and plays it with its controls -
// or, without a service worker, decrypts it whole first, as the lightbox's fallback does. The same component for a member
// and a guest: it offers no action of its own.
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

function stop() {
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
      <img v-if="pictureUrl" class="memory-banner-backdrop" :src="pictureUrl" alt="" aria-hidden="true" />
      <video
        class="memory-banner-media"
        :src="videoSrc"
        :aria-label="t('memory.banner.video')"
        controls
        autoplay
        playsinline
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
@container memory-detail-surface (max-width: 720px) {
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
.memory-banner-play {
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
