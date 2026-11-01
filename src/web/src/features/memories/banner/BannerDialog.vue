<script setup lang="ts">
// The banner dialog (uc.memory.banner main flow 2-6): choose an image or a video - a file picked here, the banner's only
// source - see it as a wide screen (16:9) and as a phone (1:1) show it, the whole medium with blur where it does not fill
// the frame, and save; or remove the current banner. There is nothing to frame: the banner never crops (ADR 0102, amended
// 2026-11-01). Presentation only:
// it holds no key and no network - the save is the page's (`useBannerEditor`), awaited here so the dialog can show its
// progress, cancel it, and say what went wrong.
//
// What it refuses before anything is read further (main flow 2, E4, Q5): an image above 40 MB, a file the browser cannot
// read, and a video of which this browser cannot decode a frame - no video banner where nothing could be drawn before it
// plays. A video has no length bound (Q4): it is uploaded as it is, read by frame.
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqProgress from '../../../shared/ui/VeyqProgress.vue'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { createBrowserDeriver, type MediaDeriver } from '../../../shared/image/mediaDeriver'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import { inferMediaType } from '../uploadQueue'
import BannerFrame from './BannerFrame.vue'
import type { Banner } from './bannerModel'
import { BANNER_TOO_LARGE, BANNER_UNREADABLE, BannerStaleError, MAX_BANNER_IMAGE_BYTES } from './bannerWrite'
import type { BannerSaveIntent, BannerSaveOpts } from './useBannerEditor'

const props = defineProps<{
  /** The stored banner (shown in the previews until a new file is picked, or removed here) - null: the Memory has none. */
  current: Banner | null
  /** The current banner's decrypted picture (its image, or a video's poster). */
  currentSrc: string | null
  /** The page's save (see the header). */
  save: (intent: BannerSaveIntent, opts: BannerSaveOpts) => Promise<void>
  /** The pixel stage (a test hands a stub). */
  deriver?: MediaDeriver
}>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

type Pick = { kind: 'file'; file: File; type: string; poster: Uint8Array | null }
const pick = ref<Pick | null>(null)
const src = ref<string>('')
const picked = useObjectUrl() // a picked image, or a picked video's decoded first frame (revoked on replace and on close)
const fileInput = ref<HTMLInputElement | null>(null)
const problem = ref<string | null>(null)
const phase = ref<'idle' | 'preparing' | 'saving'>('idle')
const progress = ref<number | null>(null)
let controller: AbortController | null = null

// The current banner shows in the previews until a new file is picked; saving takes a new file.
if (props.current) src.value = props.currentSrc ?? ''

const busy = computed(() => phase.value !== 'idle')
const canSave = computed(() => !busy.value && pick.value !== null)
const isVideo = computed(() => (pick.value ? pick.value.type.startsWith('video/') : props.current?.medium === 'video'))

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
}

// --- save, remove, cancel --------------------------------------------------------------------------------------------
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
  void run({ kind: 'file', file: p.file, type: p.type, poster: p.poster })
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
  <VeyqDialog
    :visible="true"
    :header="current ? t('memory.banner.change') : t('memory.banner.add')"
    :closable="!busy"
    :close-on-escape="!busy"
    @update:visible="cancel"
  >
    <div class="veyq-form banner-dialog">
      <p class="banner-dialog-copy">{{ t('memory.banner.hint') }}</p>

      <div class="banner-dialog-sources">
        <VeyqButton :label="t('memory.banner.choose')" icon="upload" variant="secondary" appearance="outlined" :disabled="busy" @click="choose" />
        <input ref="fileInput" type="file" accept="image/*,video/*" class="banner-dialog-file" tabindex="-1" aria-hidden="true" @change="onFilePicked" />
      </div>
      <p v-if="phase === 'preparing'" class="banner-dialog-copy" role="status">{{ t('memory.banner.preparing') }}</p>

      <template v-if="src">
        <!-- The banner as it will show: the whole medium in the wide frame and in the phone's, blur where it leaves room. -->
        <div class="banner-dialog-previews">
          <figure class="banner-dialog-preview-figure">
            <div class="banner-dialog-preview is-wide" data-testid="banner-preview-wide">
              <BannerFrame :src="src" />
            </div>
            <figcaption>{{ t('memory.banner.previewWide') }}</figcaption>
          </figure>
          <figure class="banner-dialog-preview-figure is-phone-figure">
            <div class="banner-dialog-preview is-phone" data-testid="banner-preview-phone">
              <BannerFrame :src="src" />
            </div>
            <figcaption>{{ t('memory.banner.previewPhone') }}</figcaption>
          </figure>
        </div>

      </template>

      <p v-if="problem" class="banner-dialog-problem" role="alert">{{ problem }}</p>
      <div v-if="phase === 'saving'" class="banner-dialog-progress" role="status">
        <span>{{ progress === null ? t('memory.banner.preparing') : t('memory.banner.uploading', { percent: progress }) }}</span>
        <VeyqProgress :value="progress ?? 0" :show-value="false" />
      </div>

      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="phase === 'preparing'" @click="cancel" />
        <div class="veyq-action-group">
          <VeyqButton
            v-if="current"
            :label="t('memory.banner.remove')"
            icon="trash"
            variant="danger"
            appearance="outlined"
            :disabled="busy"
            @click="onRemove"
          />
          <VeyqButton :label="t('memory.banner.save')" icon="check" :disabled="!canSave" :loading="phase === 'saving'" @click="onSave" />
        </div>
      </div>
    </div>
  </VeyqDialog>
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
.banner-dialog-previews {
  display: grid;
  grid-template-columns: 16fr 9fr;
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
/* The page's two frames (`MemoryBanner.vue`): 16:9 on a wide screen, 1:1 on a phone. The columns make both one height. */
.banner-dialog-preview.is-wide {
  aspect-ratio: 16 / 9;
}
.banner-dialog-preview.is-phone {
  aspect-ratio: 1 / 1;
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
