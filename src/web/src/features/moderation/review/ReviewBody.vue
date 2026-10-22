<script setup lang="ts">
// The share viewer's moderation mode, behind the gate (uc.moderation.review-a-case steps 4-5; ADR 0093 § 3): the case's
// report shares opened with the moderation keys this page alone holds, and the held snapshot rendered with the GUEST
// viewer, read-only, over the review's own data source (`core/sharing/review.ts`) — a reported node as core's share page
// (`ShareTreePage`, the member's views), a reported item by the application that owns it (`registry.itemReviews`), a
// name reported on its own as that name, an upload link as its name and note — with every report's reason, contact and
// context beside it.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import ShareTreePage from '../../../core/sharing/ShareTreePage.vue'
import { guestAccess } from '../../../core/sharing/guestAccess'
import { beginReview } from '../../../core/sharing/review'
import { pickLocaleText, type PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { registry } from '../../../core/applications/registry'
import { decodeHttpError } from '../../../shared/httpError'
import { fetchReview, linkNoteOf, openReview, reviewHead, reviewSource, rootNameOf, type OpenedReview } from './openReview'

const props = defineProps<{ caseId: string }>()
const { t, locale } = useI18n()

// Key material (the report shares' keys, the root key): shallow refs, never deep reactive proxies.
const opened = shallowRef<OpenedReview | null>(null)
const head = shallowRef<PublicShareHead | null>(null)
const error = ref('')
const loading = ref(true)

const access = computed(() =>
  head.value
    ? guestAccess({ head: head.value, fragmentKey: '', requestGuest: async () => null, reload: async () => {} })
    : null,
)
const itemReviews = registry.itemReviews()
const linkName = computed(() => (opened.value ? rootNameOf(opened.value) : null))
const linkNote = computed(() => {
  const raw = opened.value ? linkNoteOf(opened.value) : null
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw) as { texts?: Record<string, string> }
    return pickLocaleText(parsed.texts ?? {}, locale.value)
  } catch {
    return raw
  }
})

let end: (() => void) | null = null
onMounted(async () => {
  end = beginReview(reviewSource(props.caseId))
  try {
    const review = openReview(await fetchReview(props.caseId))
    opened.value = review
    if (review.review.itemKind === 'NODE') head.value = reviewHead(props.caseId, review, t('moderationReview.unnamed'))
  } catch (e) {
    const { code } = await decodeHttpError(e)
    error.value = t(code === 'nothing_held' ? 'moderationReview.nothingHeld' : code === 'case_not_found' ? 'moderationReview.notFound' : 'moderationReview.failed')
  } finally {
    loading.value = false
  }
})
onBeforeUnmount(() => end?.())

const when = (d: Date) => d.toLocaleString(locale.value)
</script>

<template>
  <div class="moderation-review">
    <h3>{{ t('moderationReview.title') }}</h3>
    <p class="veyq-form-copy">{{ t('moderationReview.readOnly') }}</p>
    <VeyqMessage v-if="error" tone="warn" class="form-message moderation-review-error">{{ error }}</VeyqMessage>
    <p v-else-if="loading" class="veyq-form-copy">{{ t('moderationReview.loading') }}</p>

    <template v-if="opened">
      <section class="veyq-section moderation-review-reports">
        <h4>{{ t('moderationReview.reports') }}</h4>
        <div class="veyq-list">
          <div v-for="r in opened.reports" :key="r.reportId" class="veyq-list-row moderation-review-report">
            <div class="veyq-row-main">
              <span class="veyq-row-subtitle">{{ when(r.receivedAt) }}</span>
              <template v-if="r.body">
                <strong class="veyq-row-title moderation-review-reason">{{ r.body.reason }}</strong>
                <span class="veyq-row-subtitle">{{ r.body.contact || t('moderationReview.noContact') }}</span>
                <span v-if="r.body.context" class="veyq-row-subtitle">{{ r.body.context }}</span>
              </template>
              <span v-else class="veyq-row-subtitle">{{ t('moderationReview.reportUnopened') }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="moderation-review-viewer">
        <p v-if="opened.keys.length === 0" class="veyq-empty">{{ t('moderationReview.keysUnopened') }}</p>
        <ShareTreePage
          v-else-if="opened.review.itemKind === 'NODE' && head && access"
          :share-id="caseId"
          fragment-key=""
          :pw-token="undefined"
          :head="head"
          :reload="async () => {}"
          :access="access"
        />
        <template v-else-if="opened.review.itemKind === 'ITEM' && opened.review.share.scopeId">
          <component
            :is="review"
            v-for="(review, i) in itemReviews"
            :key="i"
            :case-id="caseId"
            :item-id="opened.review.itemId"
            :scope-id="opened.review.share.scopeId"
            :keys="opened.keys"
          />
        </template>
        <!-- A name reported on its own: the report handed over that name's key and nothing else, so its text is all
             there is to show (`rootNameOf`, the held node's name with the report's `NODE_NAME` key). -->
        <div v-else-if="opened.review.itemKind === 'NAME'" class="veyq-section moderation-review-name">
          <span class="veyq-row-subtitle">{{ t('moderationReview.reportedName') }}</span>
          <strong>{{ linkName ?? t('moderationReview.nameUnopened') }}</strong>
        </div>
        <div v-else-if="opened.review.itemKind === 'LINK'" class="veyq-section moderation-review-link">
          <strong>{{ linkName ?? t('moderationReview.unnamed') }}</strong>
          <p v-if="linkNote" class="veyq-form-copy">{{ linkNote }}</p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.moderation-review {
  display: grid;
  gap: 1rem;
}
</style>
