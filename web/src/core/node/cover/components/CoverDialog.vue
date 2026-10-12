<script setup lang="ts">
// The cover dialog every node that takes a cover shares (inbox #411, follow-up 2026-10-12): a folder, a drop box, a Memory.
// It crops ONE source with the shared 16:9 `ImageCropper` and emits a `CoverSaveIntent` - the view seals it. Sources: a NEW
// image the member picks here (`upload`), the CURRENT cover re-cropped (`keep`, only the crop moves), and - where the kind adds
// one - the application's own (`source`, e.g. a photo of the Memory chosen in its gallery -> `media`). Remove drops the cover.
// Presentation only: no crypto, no network. The crop is the cover's stored one, so a cover framed here reopens as it was framed.
//
// The application's extra source arrives as DATA (`source`, `hint`), never as a component: core owns the dialog, the application
// only says what else can be cropped and how to describe it (`KindCover.extraSource`, `registry.ts`).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { useObjectUrl } from '../../../../shared/state/useObjectUrls'
import ImageCropper from '../../../../shared/ui/ImageCropper.vue'
import type { CoverCrop, CoverSaveIntent } from '../coverModel'
import { coverFileProblem } from '../pickImage'

const props = defineProps<{
  /** The current cover's crop (restored when re-cropping it) - null: the node has none (nothing to remove). */
  cover: { crop: CoverCrop } | null
  /** The current cover's decrypted image URL: the crop source when re-cropping it. */
  existingSrc: string | null
  /** The application's own source to crop at once (a photo picked in its gallery); `crop` is restored only when it already is the cover. */
  source?: { id: string; src: string; crop: CoverCrop | null } | null
  /** The application's one line pointing at its source, already translated. */
  hint?: string | null
}>()
const emit = defineEmits<{ save: [CoverSaveIntent]; close: [] }>()
const { t } = useI18n()

type Pick = { kind: 'upload'; file: File } | { kind: 'keep' } | { kind: 'media'; mediaId: string }
const pick = ref<Pick | null>(null)
const sourceUrl = ref('')
const upload = useObjectUrl() // an owned object URL for a freshly picked file (revoked on replace/close by the primitive)
const fileInput = ref<HTMLInputElement | null>(null)
// The crop to restore once the picked image's natural size is known (only when reopening a stored cover).
const restoreCrop = ref<CoverCrop | null>(props.cover?.crop ?? null)
const cropper = ref<InstanceType<typeof ImageCropper> | null>(null)
const loaded = ref(false)
const problem = ref<string | null>(null)

const canSave = computed(() => loaded.value && pick.value !== null)

// Seed the crop source: the application's own (it takes precedence - the member just chose it), else the current cover
// re-cropped, else empty (upload only). The crop is applied once the image's natural size is known (the cropper's @load).
if (props.source) {
  pick.value = { kind: 'media', mediaId: props.source.id }
  sourceUrl.value = props.source.src
  restoreCrop.value = props.source.crop
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
  if (!file) return
  const why = coverFileProblem(file)
  if (why) {
    problem.value = t(why, { max: '40 MB' })
    return
  }
  problem.value = null
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
  <Dialog :visible="true" :header="t('node.cover.dialogTitle')" modal @update:visible="emit('close')">
    <div class="veyq-form veyq-cover-dialog">
      <p class="veyq-cover-copy">{{ hint ? `${t('node.cover.copy')} ${hint}` : t('node.cover.copy') }}</p>

      <div class="veyq-cover-sources">
        <Button type="button" :label="t('node.cover.choose')" icon="pi pi-upload" severity="secondary" outlined @click="chooseUpload" />
        <input ref="fileInput" type="file" accept="image/*" class="veyq-cover-file" tabindex="-1" aria-hidden="true" @change="onFilePicked" />
      </div>
      <p v-if="problem" class="veyq-cover-problem" role="alert">{{ problem }}</p>

      <ImageCropper
        v-if="sourceUrl"
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
            v-if="cover"
            type="button"
            :label="t('node.cover.remove')"
            icon="pi pi-trash"
            severity="danger"
            outlined
            @click="remove"
          />
          <Button type="button" :label="t('node.cover.save')" icon="pi pi-check" :disabled="!canSave" @click="save" />
        </div>
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.veyq-cover-dialog {
  /* A fixed, comfortable width so the copy wraps and the full-width cropper stays a sensible size in every state. */
  width: min(500px, 92vw);
  gap: 0.85rem;
}
.veyq-cover-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.veyq-cover-sources {
  display: flex;
  gap: 0.5rem;
}
.veyq-cover-file {
  display: none;
}
.veyq-cover-problem {
  margin: 0;
  color: var(--app-danger);
  font-size: 0.9rem;
}
</style>
