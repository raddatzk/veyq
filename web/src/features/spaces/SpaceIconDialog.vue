<script setup lang="ts">
// The space-icon editor (uc.spaces.set-space-icon). The owner picks one image, frames it 1:1 in the shared
// ImageCropper, and on save the crop is BAKED into the pixels + re-encoded (renderSpaceIcon) → the encoded bytes
// are emitted; the parent seals + POSTs them. Unlike the Memory cover (which stores a reference + crop and
// re-renders), an icon has no source on the server to re-frame against, so the crop is baked at save. Presentation
// + pixels only — no crypto, no network (the parent owns those).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ImageCropper from '../../shared/ui/ImageCropper.vue'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import { createBrowserDeriver } from '../memory/mediaDeriver'
import { renderSpaceIcon } from './spaceIconImage'

const props = defineProps<{ busy?: boolean }>()
const emit = defineEmits<{ save: [Uint8Array]; close: [] }>()
const { t } = useI18n()

const sourceUrl = ref('')
const upload = useObjectUrl() // an owned object URL for the picked file (revoked on replace/close by the primitive)
const fileInput = ref<HTMLInputElement | null>(null)
const cropper = ref<InstanceType<typeof ImageCropper> | null>(null)
const loaded = ref(false)
const rendering = ref(false)
const error = ref('')

const hasSource = computed(() => Boolean(sourceUrl.value))
const canSave = computed(() => loaded.value && hasSource.value && !rendering.value && !props.busy)

function chooseUpload() {
  fileInput.value?.click()
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = '' // allow re-picking the same file
  if (!file || !file.type.startsWith('image/')) return
  error.value = ''
  loaded.value = false
  upload.set(file) // revokes the previous file's URL, mints the new one
  sourceUrl.value = upload.url.value ?? ''
}

async function save() {
  const img = cropper.value?.getImage()
  const crop = cropper.value?.getCrop()
  if (!img || !crop) return
  error.value = ''
  rendering.value = true
  try {
    // Bake the 1:1 crop + re-encode to a padded-fit WebP on-device; the parent seals + POSTs the bytes.
    const bytes = await renderSpaceIcon(createBrowserDeriver(), img, crop)
    emit('save', bytes)
  } catch {
    error.value = t('spaceIcon.failed')
  } finally {
    rendering.value = false
  }
}
</script>

<template>
  <Dialog :visible="true" :header="t('spaceIcon.dialogTitle')" modal @update:visible="emit('close')">
    <div class="veyq-form space-icon-dialog">
      <p class="space-icon-copy">{{ t('spaceIcon.dialogIntro') }}</p>

      <div class="space-icon-sources">
        <Button type="button" :label="t('spaceIcon.upload')" icon="pi pi-upload" severity="secondary" outlined @click="chooseUpload" />
        <input ref="fileInput" type="file" accept="image/*" class="space-icon-file" @change="onFilePicked" />
      </div>

      <ImageCropper v-if="hasSource" ref="cropper" :src="sourceUrl" :aspect="1" @load="loaded = true" />

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="rendering || busy" @click="emit('close')" />
        <Button type="button" :label="t('spaceIcon.save')" icon="pi pi-check" :loading="rendering || busy" :disabled="!canSave" @click="save" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.space-icon-dialog {
  /* A fixed, comfortable width so the copy wraps + the square cropper stays a sensible size. */
  width: min(420px, 92vw);
  gap: 0.85rem;
}
.space-icon-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.space-icon-sources {
  display: flex;
  gap: 0.5rem;
}
.space-icon-file {
  display: none;
}
</style>
