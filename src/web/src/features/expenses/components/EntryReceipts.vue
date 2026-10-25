<script setup lang="ts">
// The receipts of one entry in its dialog (uc.expenses.attach-receipts): the ones its shown revision lists and the ones
// picked since, each opened in place (`ReceiptView`), downloaded, reported on its own and — for whoever may write —
// removed; and the picker that adds images and PDFs of at most 10 MiB. One section for every viewer: a reader sees the
// very list a writer edits, without the add and remove (inbox #401). A receipt a moderator blocked is listed as blocked and
// opens nothing. What is picked is uploaded with the save (`EntryEditor`), never before.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { formatBytes } from '../../../shared/formatBytes'
import { isSaveCancelled } from '../../../shared/saveStream'
import { claimsReceipt } from '../domain/receiptRules'
import { MAX_RECEIPTS } from '../domain/format'
import { RECEIPT_MAX_BYTES, type HeldReceipt, type ReceiptSource } from '../receipts'
import ReceiptView from './ReceiptView.vue'

const props = defineProps<{
  /** The receipt ids the form holds, in order: listed ones and picked ones. */
  ids: readonly string[]
  /** The Pot's receipt listing. */
  listing: readonly HeldReceipt[]
  /** The files picked in this dialog and not uploaded yet, by the receipt id minted for each. */
  pending: ReadonlyMap<string, File>
  /** Whether the viewer may add and remove (the rights' `writeEntries`, and the entry not deleted). */
  editable: boolean
  /** The last receipt read failed. */
  failed: boolean
  source: (r: HeldReceipt) => ReceiptSource
  /** Report one receipt on its own (core's report dialog); absent where the viewer reports nothing. */
  onReport?: (r: HeldReceipt) => void
}>()
const emit = defineEmits<{ add: [files: File[]]; remove: [id: string] }>()
const { t, locale } = useI18n()

type Row =
  | { kind: 'listed'; id: string; receipt: HeldReceipt; source: ReceiptSource }
  | { kind: 'pending'; id: string; file: File }
  | { kind: 'missing'; id: string }

const rows = computed<Row[]>(() =>
  props.ids.map((id): Row => {
    const file = props.pending.get(id)
    if (file) return { kind: 'pending', id, file }
    const receipt = props.listing.find((r) => r.receiptId === id && r.droppedAt === null)
    return receipt ? { kind: 'listed', id, receipt, source: props.source(receipt) } : { kind: 'missing', id }
  }),
)
const size = (bytes: number) => formatBytes(bytes, locale.value)

const opened = ref<string | null>(null)
const busy = ref<string | null>(null)
const note = ref<string | null>(null)

async function download(source: ReceiptSource) {
  note.value = null
  busy.value = source.id
  try {
    await source.download()
  } catch (e) {
    if (!isSaveCancelled(e)) note.value = t('expenses.receipts.downloadFailed')
  } finally {
    busy.value = null
  }
}

// --- adding --------------------------------------------------------------------------------------------------------
const input = ref<HTMLInputElement | null>(null)
const refused = ref<string | null>(null)

/** Keep what may be a receipt and say what was left out: a file over 10 MiB, one that claims to be neither an image nor a
 *  PDF, or one past the 100 an entry may list. */
function picked(files: File[]) {
  const ok: File[] = []
  const tooLarge: string[] = []
  const notReceipt: string[] = []
  const tooMany: string[] = []
  for (const f of files) {
    if (!claimsReceipt(f)) notReceipt.push(f.name)
    else if (f.size > RECEIPT_MAX_BYTES) tooLarge.push(f.name)
    else if (props.ids.length + ok.length >= MAX_RECEIPTS) tooMany.push(f.name)
    else ok.push(f)
  }
  const parts = [
    tooLarge.length ? t('expenses.receipts.tooLarge', { names: tooLarge.join(', ') }) : '',
    notReceipt.length ? t('expenses.receipts.notReceipt', { names: notReceipt.join(', ') }) : '',
    tooMany.length ? t('expenses.receipts.tooMany', { names: tooMany.join(', ') }) : '',
  ].filter(Boolean)
  refused.value = parts.length ? parts.join(' ') : null
  if (ok.length) emit('add', ok)
}
function onChange(e: Event) {
  const el = e.target as HTMLInputElement
  picked([...(el.files ?? [])])
  el.value = ''
}
</script>

<template>
  <fieldset class="expenses-entry-fieldset expenses-receipts">
    <legend>{{ t('expenses.receipts.title') }}</legend>
    <p v-if="!rows.length" class="veyq-form-copy">{{ t('expenses.receipts.none') }}</p>
    <ul v-else class="veyq-list expenses-receipt-list">
      <li v-for="row in rows" :key="row.id" class="expenses-receipt" :data-receipt="row.id">
        <div class="expenses-receipt-row">
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ row.kind === 'listed' ? row.source.name : row.kind === 'pending' ? row.file.name : t('expenses.receipts.missing') }}</span>
            <span v-if="row.kind === 'listed'" class="veyq-row-subtitle">{{ size(row.source.size) }}</span>
            <span v-else-if="row.kind === 'pending'" class="veyq-row-subtitle">{{ t('expenses.receipts.pending', { size: size(row.file.size) }) }}</span>
          </span>
          <span class="veyq-action-group">
            <span v-if="row.kind === 'listed' && row.receipt.blockedByOperator" class="veyq-badge warning">{{ t('node.blockedByOperator') }}</span>
            <template v-else-if="row.kind === 'listed'">
              <VeyqButton
                :label="opened === row.id ? t('expenses.receipts.close') : t('expenses.receipts.open')"
                :icon="opened === row.id ? 'eye-slash' : 'eye'"
                variant="secondary"
                appearance="text"
                size="small"
                :aria-label="opened === row.id ? t('expenses.receipts.closeNamed', { name: row.source.name }) : t('expenses.receipts.openNamed', { name: row.source.name })"
                @click="opened = opened === row.id ? null : row.id"
              />
              <VeyqButton
                class="veyq-icon-button"
                icon="download"
                variant="secondary"
                appearance="text"
                size="small"
                :aria-label="t('expenses.receipts.downloadNamed', { name: row.source.name })"
                :loading="busy === row.id"
                @click="download(row.source)"
              />
            </template>
            <VeyqButton
              v-if="row.kind === 'listed' && onReport"
              class="veyq-icon-button"
              icon="flag"
              variant="secondary"
              appearance="text"
              size="small"
              :aria-label="t('expenses.receipts.reportNamed', { name: row.source.name })"
              @click="onReport(row.receipt)"
            />
            <VeyqButton
              v-if="editable"
              class="veyq-icon-button"
              icon="times"
              variant="secondary"
              appearance="outlined"
              size="small"
              :aria-label="t('expenses.receipts.removeNamed', { name: row.kind === 'listed' ? row.source.name : row.kind === 'pending' ? row.file.name : t('expenses.receipts.missing') })"
              @click="emit('remove', row.id)"
            />
          </span>
        </div>
        <ReceiptView v-if="row.kind === 'listed' && opened === row.id" :source="row.source" @download="download(row.source)" />
      </li>
    </ul>
    <VeyqMessage v-if="failed" tone="warn">{{ t('expenses.receipts.loadFailed') }}</VeyqMessage>
    <VeyqMessage v-if="note" tone="error">{{ note }}</VeyqMessage>
    <VeyqMessage v-if="refused" tone="warn">{{ refused }}</VeyqMessage>
    <template v-if="editable">
      <input ref="input" type="file" class="expenses-receipt-input" accept="image/*,application/pdf" multiple :aria-label="t('expenses.receipts.add')" @change="onChange" />
      <VeyqButton :label="t('expenses.receipts.add')" icon="file-plus" variant="secondary" appearance="text" size="small" @click="input?.click()" />
    </template>
  </fieldset>
</template>

<style scoped>
.expenses-receipt-list {
  display: grid;
  gap: 0.5rem;
}
.expenses-receipt {
  display: grid;
  gap: 0.5rem;
  min-width: 0;
}
.expenses-receipt-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.25rem 0.75rem;
  min-width: 0;
}
.expenses-receipt-row .veyq-row-main {
  min-width: 0;
  overflow-wrap: anywhere;
}
.expenses-receipt-input {
  display: none;
}
</style>
