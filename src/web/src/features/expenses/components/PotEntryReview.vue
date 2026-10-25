<script setup lang="ts">
// A moderator's review of a reported Pot entry (uc.moderation.review-a-case, CR decision 7) — Expenses' half of core's
// item review (`registry.claimItemReview`). The report share hands over each revision's `K_rev` (role `ENTRY_REVISION`,
// `../report.ts`), not the Pot's key, so the entry is drawn on its own: the held revisions from the review's own listing
// (`listCasePotRevisions`, key-proved), each opened with whichever handed key its address takes (a seal binds its address,
// ADR 0072). Read-only; it names no participant (their records were not reported) and no writer (the case read has none).
// An item that is not an Expenses entry of this case draws nothing.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fromB64std } from '@veyq/crypto/base64'
import type { ItemReviewProps } from '../../../core/applications/registry'
import { activeReview } from '../../../core/sharing/review'
import type { CasePotRevision } from '../../../api/generated/expenses'
import { caseExpensesApi } from '../api'
import { openRevisionWithKey, RevisionUnreadable } from '../domain/codec'
import { isMoney, type RevisionBody } from '../domain/format'
import { ENTRY_REVISION_ROLE } from '../report'
import { formatMoney } from '../amounts'

const props = defineProps<ItemReviewProps>()
const { t, locale } = useI18n()

const rows = ref<CasePotRevision[]>([])
const when = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

function open(row: CasePotRevision): RevisionBody | null {
  const address = { scopeId: props.scopeId, nodeId: row.nodeId, entryId: row.entryId, revision: row.revision }
  const sealed = fromB64std(row.sealedBody)
  for (const k of props.keys) {
    if (k.role !== ENTRY_REVISION_ROLE) continue
    try {
      return openRevisionWithKey(address, k.key, sealed)
    } catch (e) {
      if (!(e instanceof RevisionUnreadable)) throw e
    }
  }
  return null
}

const revisions = computed(() =>
  [...rows.value]
    .sort((a, b) => b.revision - a.revision)
    .map((row) => {
      const body = open(row)
      let what = t('expenses.history.unreadable')
      if (body?.kind === 'deleted') what = t('expenses.history.deleted')
      else if (body && isMoney(body)) {
        const money = formatMoney(BigInt(body.amount), body.currency, body.minorUnits, locale.value)
        what = [t(`expenses.kind.${body.kind}`), body.title, money, body.date].filter(Boolean).join(' · ')
      } else if (body) what = t('expenses.history.other')
      return { key: row.revision, what, when: when.value.format(row.createdAt) }
    }),
)

onMounted(async () => {
  const review = activeReview()
  if (!review || !props.keys.some((k) => k.role === ENTRY_REVISION_ROLE)) return
  try {
    const listing = await caseExpensesApi().listCasePotRevisions({ caseId: props.caseId, ...(await review.proof('listCasePotRevisions')) })
    rows.value = listing.revisions.filter((r) => r.entryId === props.itemId)
  } catch {
    rows.value = [] // nothing of Expenses held for this case: not ours to draw
  }
})
</script>

<template>
  <section v-if="rows.length" class="expenses-review">
    <h4>{{ t('expenses.review.title') }}</h4>
    <ol class="veyq-list">
      <li v-for="r in revisions" :key="r.key" class="veyq-list-row expenses-static-row">
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ r.what }}</span>
          <span class="veyq-row-subtitle">{{ r.when }}</span>
        </span>
      </li>
    </ol>
  </section>
</template>
