<script setup lang="ts">
// The profile-picture editor (uc.spaces.set-profile-picture, ADR 0084), modelled on the space-icon dialog: choose one image,
// frame it 1:1 in the shared ImageCropper, and on "use" the crop is baked into a 256 px WebP that fits the constant pad
// (`renderSpaceIcon` with the avatar's edge and byte budget). The bytes are EMITTED - the picture is sealed and sent with
// the profile's Save, so this is presentation + pixels only (no crypto, no network). `remove` is offered while the member
// has a picture. A file the browser cannot decode is refused here with a message and nothing is kept (E1).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import ImageCropper from '../../../shared/ui/ImageCropper.vue'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { createBrowserDeriver } from '../../../shared/image/mediaDeriver'
import { renderSpaceIcon } from '../../spaces/spaceIconImage'
import { AVATAR_EDGE, AVATAR_ENCODED_BUDGET } from './memberAvatar'

/** Cap on the picked file, so a huge photo is refused before it is decoded (the cover's cap, `MAX_COVER_SOURCE_BYTES`). */
const MAX_SOURCE_BYTES = 40 * 1024 * 1024

const props = defineProps<{ hasPicture?: boolean }>()
const emit = defineEmits<{ use: [Uint8Array]; remove: []; close: [] }>()
const { t } = useI18n()

const sourceUrl = ref('')
const upload = useObjectUrl() // the picked file's object URL, revoked on replace / close by the primitive
const fileInput = ref<HTMLInputElement | null>(null)
const cropper = ref<InstanceType<typeof ImageCropper> | null>(null)
const loaded = ref(false)
const rendering = ref(false)
const error = ref('')

const canUse = computed(() => loaded.value && Boolean(sourceUrl.value) && !rendering.value)

function chooseUpload() {
  fileInput.value?.click()
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = '' // allow re-picking the same file
  if (!file) return
  error.value = ''
  if (!file.type.startsWith('image/') || file.size === 0 || file.size > MAX_SOURCE_BYTES) {
    error.value = t('profilePicture.notImage')
    return
  }
  loaded.value = false
  upload.set(file) // revokes the previous file's URL, mints the new one
  sourceUrl.value = upload.url.value ?? ''
}

function onUndecodable() {
  loaded.value = false
  sourceUrl.value = ''
  upload.clear()
  error.value = t('profilePicture.notImage')
}

async function use() {
  const img = cropper.value?.getImage()
  const crop = cropper.value?.getCrop()
  if (!img || !crop) return
  error.value = ''
  rendering.value = true
  try {
    emit('use', await renderSpaceIcon(createBrowserDeriver(), img, crop, { edge: AVATAR_EDGE, budget: AVATAR_ENCODED_BUDGET }))
  } catch {
    error.value = t('profilePicture.failed')
  } finally {
    rendering.value = false
  }
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('profilePicture.dialogTitle')" @update:visible="emit('close')">
    <div class="veyq-form profile-picture-dialog" data-testid="profile-picture-dialog">
      <p class="profile-picture-copy">{{ t('profilePicture.dialogIntro') }}</p>

      <div class="profile-picture-sources">
        <VeyqButton type="button" :label="t('profilePicture.choose')" icon="upload" variant="secondary" appearance="outlined" @click="chooseUpload" />
        <VeyqButton v-if="props.hasPicture" type="button" :label="t('profilePicture.remove')" icon="trash" variant="danger" appearance="text" data-testid="profile-picture-remove" @click="emit('remove')" />
        <input ref="fileInput" type="file" accept="image/*" class="profile-picture-file" :aria-label="t('profilePicture.choose')" data-testid="profile-picture-file" @change="onFilePicked" />
      </div>

      <ImageCropper v-if="sourceUrl" ref="cropper" :src="sourceUrl" :aspect="1" @load="loaded = true" @error="onUndecodable" />

      <VeyqMessage v-if="error" tone="error" role="alert">{{ error }}</VeyqMessage>

      <div class="veyq-dialog-action-row">
        <VeyqButton type="button" :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="rendering" @click="emit('close')" />
        <VeyqButton type="button" :label="t('profilePicture.use')" icon="check" :loading="rendering" :disabled="!canUse" data-testid="profile-picture-use" @click="use" />
      </div>
    </div>
  </VeyqDialog>
</template>

<style scoped>
.profile-picture-dialog {
  /* A fixed, comfortable width so the copy wraps and the square cropper stays a sensible size. */
  width: min(420px, 92vw);
  gap: 0.85rem;
}
.profile-picture-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.profile-picture-sources {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.profile-picture-file {
  display: none;
}
</style>
