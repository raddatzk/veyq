<script setup lang="ts">
// A moderator's review of a reported medium (uc.moderation.review-a-case A5) — Memories' half of core's item review
// (`registry.claimItemReview`). A medium's report share hands over each of its blobs' `K_blob` (role `BLOB`, by blob id),
// not a node key, so it is drawn on its own: the held row from the review's own listing (`listCaseMedia`, key-proved —
// never the link's), its preview opened with that preview blob's key, and its original on request. Read-only.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { ItemReviewProps } from '../../../core/applications/registry'
import { activeReview } from '../../../core/sharing/review'
import { openBlobWithKey } from '../../../core/blob/transport'
import type { ShareMedia } from '../../../api/generated/share-media'
import { caseMediaApi } from '../api'

const props = defineProps<ItemReviewProps>()
const { t } = useI18n()

const medium = ref<ShareMedia | null>(null)
const previewUrl = ref<string | null>(null)
const failed = ref(false)
const downloading = ref(false)

const keyOf = (blobId: string): Uint8Array | null => props.keys.find((k) => k.role === 'BLOB' && k.ref === blobId)?.key ?? null

/** The original's sealed meta (type, name), opened with its own `K_blob`. */
const meta = computed(() => {
  const m = medium.value
  const key = m ? keyOf(m.blobId) : null
  if (!m?.wrappedBlobMeta || !key) return null
  try {
    return v1.openBlobMeta(key, fromB64std(m.wrappedBlobMeta))
  } catch {
    return null
  }
})

async function open(blobId: string): Promise<Uint8Array | null> {
  const review = activeReview()
  const key = keyOf(blobId)
  if (!review || !key) return null
  return openBlobWithKey(key, () => review.chunkUrls(blobId))
}

onMounted(async () => {
  const review = activeReview()
  if (!review) return
  try {
    const listing = await caseMediaApi().listCaseMedia({ caseId: props.caseId, ...(await review.proof('listCaseMedia')) })
    medium.value = listing.media.find((m) => m.mediaId === props.itemId) ?? null
    const m = medium.value
    if (!m) return
    const tier = m.derivatives?.find((d) => d.kind === 'PREVIEW') ?? m.derivatives?.find((d) => d.kind === 'THUMBNAIL')
    const bytes = tier ? await open(tier.blobId) : null
    if (bytes) previewUrl.value = URL.createObjectURL(new Blob([bytes as BlobPart]))
  } catch {
    failed.value = true
  }
})
onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})

async function downloadOriginal() {
  const m = medium.value
  if (!m || downloading.value) return
  downloading.value = true
  try {
    const bytes = await open(m.blobId)
    if (!bytes) throw new Error('not_opened')
    const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: meta.value?.type || 'application/octet-stream' }))
    const a = document.createElement('a')
    a.href = url
    a.download = meta.value?.name || m.mediaId
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  } catch {
    failed.value = true
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <section v-if="medium" class="veyq-section medium-review">
    <p v-if="meta?.name" class="veyq-form-copy medium-review-name">{{ meta.name }}</p>
    <div class="media-lightbox-stage medium-review-stage">
      <img v-if="previewUrl" :src="previewUrl" :alt="meta?.name ?? ''" />
      <p v-else-if="failed" class="media-lightbox-error">{{ t('media.loadError') }}</p>
      <span v-else class="pi pi-file" aria-hidden="true" />
    </div>
    <Button
      class="medium-review-download"
      severity="secondary"
      icon="pi pi-download"
      :label="t('media.download')"
      :loading="downloading"
      @click="downloadOriginal"
    />
  </section>
</template>

<style scoped>
.medium-review-stage {
  min-height: 240px;
}
.medium-review-stage img {
  max-width: 100%;
  max-height: 70vh;
}
</style>
