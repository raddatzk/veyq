<script setup lang="ts">
// The Memory cover editor (cr-0072 §1–2 + cr-0073). Crops **one** source and emits a [CoverSaveIntent]
// (`upload` | `media` | `keep` | `remove`) — the parent seals it. Sources: a **brand-new uploaded image** (an
// `<input type=file accept=image/*>`), an **existing Memory photo** chosen in the gallery lightbox ("Set as
// cover" → `mediaSource`, so its 16:9 crop is framed right here instead of defaulted to the full frame), or the
// **current cover re-cropped** (`existingSrc`). Presentation only: no crypto, no network. The pan/zoom crop
// viewport is the shared [ImageCropper] (aspect 16/9); this dialog only picks the source + emits the intent.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import type { CoverCrop, CoverSaveIntent, MemoryCover } from '../memoryCover'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import ImageCropper from '../../../components/ImageCropper.vue'

const props = defineProps<{
  cover: MemoryCover | null // the current stored cover (restore its crop + enable Remove), or null
  existingSrc: string | null // the current cover's decrypted image URL — the crop source when re-cropping it
  hasMedia: boolean // whether the Memory has photos — drives the copy's "…or open a photo in the gallery" hint
  mediaSource?: { mediaId: string; src: string } | null // a Memory photo picked in the lightbox to crop as the cover (its decrypted thumbnail = the crop source)
}>()
const emit = defineEmits<{ save: [CoverSaveIntent]; close: [] }>()
const { t } = useI18n()

// The active crop source: a freshly uploaded file, an existing Memory photo (lightbox pick), or the current cover
// kept (only the crop moves).
type Pick = { kind: 'upload'; file: File } | { kind: 'keep' } | { kind: 'media'; mediaId: string }
const pick = ref<Pick | null>(null)
const sourceUrl = ref('')
const upload = useObjectUrl() // an owned object URL for a freshly picked file (revoked on replace/close by the primitive)
const fileInput = ref<HTMLInputElement | null>(null)
// The crop to restore once the picked image's natural size is known (set only when reopening an existing cover).
const restoreCrop = ref<CoverCrop | null>(props.cover?.crop ?? null)
const cropper = ref<InstanceType<typeof ImageCropper> | null>(null)
const loaded = ref(false)

const hasSource = computed(() => Boolean(sourceUrl.value))
const canSave = computed(() => loaded.value && pick.value !== null)

// Seed the crop source. A Memory photo picked in the lightbox (`mediaSource`) takes precedence — crop THAT photo,
// restoring its stored crop only when it is already the cover, else a fresh centre crop. Otherwise re-crop the
// current cover (the header "edit cover" flow), or open empty (upload only). The crop is applied once the image's
// natural size is known (the cropper's @load).
if (props.mediaSource) {
  pick.value = { kind: 'media', mediaId: props.mediaSource.mediaId }
  sourceUrl.value = props.mediaSource.src
  restoreCrop.value =
    props.cover?.source.kind === 'media' && props.cover.source.mediaId === props.mediaSource.mediaId
      ? props.cover.crop
      : null
} else if (props.cover && props.existingSrc) {
  pick.value = { kind: 'keep' }
  sourceUrl.value = props.existingSrc
}

function chooseUpload() {
  fileInput.value?.click()
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = '' // allow re-picking the same file
  if (!file || !file.type.startsWith('image/')) return
  pick.value = { kind: 'upload', file }
  restoreCrop.value = null // a fresh upload centres a new crop
  loaded.value = false
  upload.set(file) // revokes the previously picked file's URL, mints the new one
  sourceUrl.value = upload.url.value ?? ''
}

function save() {
  const p = pick.value
  const crop = cropper.value?.getCrop()
  if (!p || !crop) return
  if (p.kind === 'upload') emit('save', { kind: 'upload', file: p.file, crop })
  else if (p.kind === 'media') emit('save', { kind: 'media', mediaId: p.mediaId, crop })
  else emit('save', { kind: 'keep', crop })
  emit('close')
}

function remove() {
  emit('save', { kind: 'remove' })
  emit('close')
}
</script>

<template>
  <Dialog :visible="true" :header="t('memory.cover.choose')" modal @update:visible="emit('close')">
    <div class="veyq-form memory-cover-dialog">
      <p class="memory-cover-copy">{{ hasMedia ? t('memory.cover.copy') : t('memory.cover.copyNoMedia') }}</p>

      <div class="memory-cover-sources">
        <Button type="button" :label="t('memory.cover.upload')" icon="pi pi-upload" severity="secondary" outlined @click="chooseUpload" />
        <input ref="fileInput" type="file" accept="image/*" class="memory-cover-file" @change="onFilePicked" />
      </div>

      <ImageCropper
        v-if="hasSource"
        ref="cropper"
        :src="sourceUrl"
        :aspect="16 / 9"
        :initial-crop="restoreCrop"
        @load="loaded = true"
      />

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="emit('close')" />
        <div class="veyq-action-group">
          <Button
            type="button"
            :label="t('memory.cover.remove')"
            icon="pi pi-trash"
            severity="danger"
            outlined
            :disabled="!cover"
            @click="remove"
          />
          <Button type="button" :label="t('memory.cover.save')" icon="pi pi-check" :disabled="!canSave" @click="save" />
        </div>
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.memory-cover-dialog {
  /* A fixed, comfortable width so the copy wraps + the full-width cropper stays a sensible size in every state
     (with/without the media picker) — without it the long copy line + fluid cropper balloon the dialog. */
  width: min(500px, 92vw);
  gap: 0.85rem;
}
.memory-cover-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.memory-cover-sources {
  display: flex;
  gap: 0.5rem;
}
.memory-cover-file {
  display: none;
}
</style>
