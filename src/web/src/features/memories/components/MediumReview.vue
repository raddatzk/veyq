<script setup lang="ts">
// A moderator's review of a reported medium (uc.moderation.review-a-case A5) — Memories' half of core's item review
// (`app.itemReview`). A medium's report share hands over each of its blobs' `K_blob` (role `BLOB`, by blob id),
// not a node key, so it is drawn on its own: the held row from the review's own listing (`listCaseMedia`, key-proved —
// never the link's), its preview opened with that preview blob's key, and its original on request. Read-only.
// A music track (ADR 0135 decision 10) has to be heard to be judged: its original is decrypted once and played by an
// `<audio controls>` over a `blob:` of it - the bytes "Download original" saves too. A review is rare and short, so it
// needs no stream; its trim is not applied (the listed tier is not in the report, ADR 0093 § 1).
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { ItemReviewProps } from '../../../core/applications/registry'
import { activeReview } from '../../../core/sharing/review'
import { openBlobWithKey } from '../../../core/blob/transport'
import type { ShareMedia } from '../../../api/generated/share-media'
import { caseMediaApi } from '../api'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const props = defineProps<ItemReviewProps>()
const { t } = useI18n()

const medium = ref<ShareMedia | null>(null)
const previewUrl = ref<string | null>(null)
/** An audio original's `blob:`, played in place. */
const audioUrl = ref<string | null>(null)
/** The original's bytes once opened: an audio track's for its player, kept for the download. */
let original: Uint8Array | null = null
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
    const type = meta.value?.type ?? ''
    if (type.startsWith('audio/')) {
      original = await open(m.blobId)
      if (original) audioUrl.value = URL.createObjectURL(new Blob([original as BlobPart], { type }))
      else failed.value = true
      return
    }
    const tier = m.derivatives?.find((d) => d.kind === 'PREVIEW') ?? m.derivatives?.find((d) => d.kind === 'THUMBNAIL')
    const bytes = tier ? await open(tier.blobId) : null
    if (bytes) previewUrl.value = URL.createObjectURL(new Blob([bytes as BlobPart]))
  } catch {
    failed.value = true
  }
})
onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  original = null
})

async function downloadOriginal() {
  const m = medium.value
  if (!m || downloading.value) return
  downloading.value = true
  try {
    const bytes = original ?? (await open(m.blobId))
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
      <audio v-if="audioUrl" :src="audioUrl" controls preload="metadata" class="medium-review-audio" data-testid="medium-review-audio" />
      <img v-else-if="previewUrl" :src="previewUrl" :alt="meta?.name ?? ''" />
      <p v-else-if="failed" class="media-lightbox-error">{{ t('media.loadError') }}</p>
      <VeyqIcon v-else name="file" />
    </div>
    <VeyqButton
      class="medium-review-download"
      variant="secondary"
      icon="download"
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
.medium-review-audio {
  width: min(100%, 32rem);
}
.medium-review-stage img {
  max-width: 100%;
  max-height: 70vh;
}
</style>
