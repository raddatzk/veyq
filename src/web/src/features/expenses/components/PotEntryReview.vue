<script setup lang="ts">
// A moderator's review of a reported Pot entry (uc.moderation.review-a-case, CR decision 7) — Expenses' half of core's
// item review (`registry.claimItemReview`). The report share hands over each revision's `K_rev` (role `ENTRY_REVISION`,
// `../report.ts`), not the Pot's key, so the entry is drawn on its own: the held revisions from the review's own listing
// (`listCasePotRevisions`, key-proved), each opened with whichever handed key its address takes (a seal binds its address,
// ADR 0072). Read-only; it names no participant (their records were not reported) and no writer (the case read has none).
// Beside them the receipts the hold copied (ADR 0108 decision 7): every receipt of a reported entry, or the one receipt
// reported on its own (its id is the item's) — each opened with the `K_blob` the share handed over for its blob
// ({@link RECEIPT_KEY_ROLE}, the ref its blob id), its bytes from the case's hold (`presignCaseBlobs`, the review's
// `chunkUrls`), drawn by the same `ReceiptView` a member's dialog uses. A receipt whose key the share did not carry (its
// wrap did not open for the reporter) is listed as one that cannot be opened. An item that is no Expenses entry or
// receipt of this case draws nothing.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fromB64std } from '@veyq/crypto/base64'
import type { ItemReviewProps } from '../../../core/applications/registry'
import { activeReview } from '../../../core/sharing/review'
import type { CasePotReceipt, CasePotRevision } from '../../../api/generated/expenses'
import { openBlobWithKey } from '../../../core/blob/transport'
import { openBlobMetaOf } from '../../../core/blob/meta'
import { openSaveTarget } from '../../../shared/saveStream'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import { formatBytes } from '../../../shared/formatBytes'
import { caseExpensesApi } from '../api'
import { openRevisionWithKey, RevisionUnreadable } from '../domain/codec'
import { isMoney, type RevisionBody } from '../domain/format'
import { ENTRY_REVISION_ROLE, RECEIPT_KEY_ROLE } from '../report'
import { receiptName, receiptSize, type ReceiptSource } from '../receipts'
import { formatMoney } from '../amounts'
import ReceiptView from './ReceiptView.vue'

const props = defineProps<ItemReviewProps>()
const { t, locale } = useI18n()

const rows = ref<CasePotRevision[]>([])
const held = ref<CasePotReceipt[]>([])
const opened = ref<string | null>(null)
const when = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

/** Open [row] with the handed revision key at [first] tried before the others: the share lists its revisions' keys newest
 *  first (`../report.ts`), so walking the rows newest first opens each with the next key, and a share of 256 keys costs
 *  256 opens rather than every key on every row. Returns the body and the index of the key that opened it. */
function open(row: CasePotRevision, revisionKeys: readonly Uint8Array[], first: number): { body: RevisionBody; at: number } | null {
  const address = { scopeId: props.scopeId, nodeId: row.nodeId, entryId: row.entryId, revision: row.revision }
  const sealed = fromB64std(row.sealedBody)
  const order = [first, ...revisionKeys.keys()].filter((i, n, all) => i < revisionKeys.length && all.indexOf(i) === n)
  for (const i of order) {
    try {
      return { body: openRevisionWithKey(address, revisionKeys[i]!, sealed), at: i }
    } catch (e) {
      if (!(e instanceof RevisionUnreadable)) throw e
    }
  }
  return null
}

const revisions = computed(() => {
  const revisionKeys = props.keys.filter((k) => k.role === ENTRY_REVISION_ROLE).map((k) => k.key)
  let next = 0
  return [...rows.value]
    .sort((a, b) => b.revision - a.revision)
    .map((row) => {
      const opened = open(row, revisionKeys, next)
      if (opened) next = opened.at + 1
      const body = opened?.body ?? null
      let what = t('expenses.history.unreadable')
      if (body?.kind === 'deleted') what = t('expenses.history.deleted')
      else if (body && isMoney(body)) {
        const money = formatMoney(BigInt(body.amount), body.currency, body.minorUnits, locale.value)
        what = [t(`expenses.kind.${body.kind}`), body.title, money, body.date].filter(Boolean).join(' · ')
      } else if (body) what = t('expenses.history.other')
      return { key: row.revision, what, when: when.value.format(row.createdAt) }
    })
})

/** A held receipt as the review opens it: its handed `K_blob` (null: the share carried none for it), its bytes from the hold. */
const receipts = computed(() =>
  held.value.map((r) => {
    const key = props.keys.find((k) => k.role === RECEIPT_KEY_ROLE && k.ref === r.blobId)?.key ?? null
    const size = receiptSize(r)
    const name = receiptName(key ? openBlobMetaOf(r, key) : null)
    const read = async (signal?: AbortSignal) => {
      const review = activeReview()
      if (!key || !review) throw new Error('receipt_key_missing')
      return openBlobWithKey(key, () => review.chunkUrls(r.blobId), { signal })
    }
    const source: ReceiptSource = {
      id: r.receiptId,
      name,
      size,
      blocked: false,
      read,
      download: async () => {
        const sink = await openSaveTarget(name, size)
        try {
          await sink.write(await read())
          await sink.close()
        } catch (e) {
          await sink.abort(e)
          throw e
        }
      },
    }
    return { id: r.receiptId, size: formatBytes(size, locale.value), source, opens: key !== null }
  }),
)

onMounted(async () => {
  const review = activeReview()
  if (!review || !props.keys.some((k) => k.role === ENTRY_REVISION_ROLE || k.role === RECEIPT_KEY_ROLE)) return
  try {
    const listing = await caseExpensesApi().listCasePotRevisions({ caseId: props.caseId, ...(await review.proof('listCasePotRevisions')) })
    rows.value = listing.revisions.filter((r) => r.entryId === props.itemId)
    // A reported receipt is the item itself; a reported entry brings every receipt kept with it.
    held.value = (listing.receipts ?? []).filter((r) => r.receiptId === props.itemId || r.entryId === props.itemId)
  } catch {
    rows.value = [] // nothing of Expenses held for this case: not ours to draw
    held.value = []
  }
})
</script>

<template>
  <section v-if="rows.length || receipts.length" class="expenses-review">
    <h4>{{ t('expenses.review.title') }}</h4>
    <ol v-if="rows.length" class="veyq-list">
      <li v-for="r in revisions" :key="r.key" class="veyq-list-row expenses-static-row">
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ r.what }}</span>
          <span class="veyq-row-subtitle">{{ r.when }}</span>
        </span>
      </li>
    </ol>
    <template v-if="receipts.length">
      <h5>{{ t('expenses.receipts.title') }}</h5>
      <ul class="veyq-list">
        <li v-for="r in receipts" :key="r.id" class="expenses-review-receipt" :data-receipt="r.id">
          <div class="veyq-list-row expenses-static-row">
            <span class="veyq-row-main">
              <span class="veyq-row-title">{{ r.opens ? r.source.name : t('expenses.receipts.noKey') }}</span>
              <span class="veyq-row-subtitle">{{ r.size }}</span>
            </span>
            <VeyqButton
              v-if="r.opens"
              :label="opened === r.id ? t('expenses.receipts.close') : t('expenses.receipts.open')"
              :icon="opened === r.id ? 'eye-slash' : 'eye'"
              variant="secondary"
              appearance="text"
              size="small"
              @click="opened = opened === r.id ? null : r.id"
            />
          </div>
          <ReceiptView v-if="r.opens && opened === r.id" :source="r.source" @download="r.source.download().catch(() => {})" />
        </li>
      </ul>
    </template>
  </section>
</template>
