<script setup lang="ts">
// One entry of a Pot, opened (uc.expenses.record-entries, uc.expenses.entry-history): a new one, or an existing one to
// read, edit, delete or restore — one dialog for every viewer, its fields read-only and its buttons left out where the
// rights say no (`../rights.ts`, inbox #401: a reader sees the very form a writer edits). What may be saved is
// `../entryForm.ts`'s answer, and the write path asks `entryProblem` again before sealing: an entry that does not add up
// is never saved (owner 2026-10-24). A `409 stale_revision` re-reads the Pot (the session's write does), loads the other
// writer's version into the form and says so (`expenses.entry.stale`); saving again puts this one on top.
//
// Its receipts (uc.expenses.attach-receipts, `EntryReceipts`): what is picked is uploaded with the save, BEFORE the revision
// that lists it (a new entry's id is minted when the dialog opens, so its receipts are completed against it), and the
// revision names the receipts it no longer holds (`receiptsToDrop`). A receipt the link's storage limit or the quota has no
// room for is left out and the entry saves without it, saying so (E2); any other failed upload stops the save, and what
// was already uploaded is kept for the next try. A deleted entry is restorable until its receipts are erased
// (`restoreOffered`, uc.expenses.entry-history E2); past that, no restore is offered.
//
// The day's exchange rate (uc.expenses.exchange-rates, `../exchangeRates.ts`): picking a currency other than the base, in
// an editable form, while the Pot's session names a rate table (`session.rateService`), fills the empty rate from the
// device's table of the day, and says it is computed from the ECB's reference rates of the rows' date (the ECB's terms
// ask its rates be cited as the source). The field stays typeable; a fill that arrives after the person typed a rate,
// picked another currency or left is dropped. The save never waits for it: it seals what the field holds.
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqToggleGroup from '../../../shared/ui/VeyqToggleGroup.vue'
import VeyqDateField from '../../../shared/ui/VeyqDateField.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { Person } from '../../../core/people/directory'
import { DEFAULT_CATEGORIES, isMoney } from '../domain/format'
import { categoryLabel } from '../domain/categories'
import { ownCategoryNames, type PotMeta } from '../domain/potMeta'
import { namedParticipants, type PotLedger } from '../domain/ledger'
import { PotWriteRefused, StaleRevisionError } from '../domain/potApi'
import type { PotSession } from '../potSession'
import { contentOf, emptyForm, emptyItem, formOf, payersTotal, problemKey, restLeft, today, withCurrency, withKind, type EntryForm, type EntryKind, type FormProblem } from '../entryForm'
import { crossRate, dayTable } from '../exchangeRates'
import { formatMoney, knownCurrencies } from '../amounts'
import { offeredPeople, personLabel, type MemberDirectory } from '../people'
import EntryHistory from './EntryHistory.vue'
import EntryReceipts from './EntryReceipts.vue'
import EntrySplit from './EntrySplit.vue'
import { purgeAfterOf, receiptsToDrop, restoreOffered } from '../domain/receiptRules'
import type { HeldReceipt, ReceiptSource } from '../receipts'
import { QUOTA_EXCEEDED } from '../../../core/blob/queue'
import { useToast } from '../../../shared/state/useToast'
import { httpStatus } from '../../../shared/httpError'

const props = defineProps<{
  session: PotSession
  ledger: PotLedger
  meta: PotMeta
  /** The entry opened; null: a new one. */
  entryId: string | null
  canWrite: boolean
  /** Whether the history names each revision's writer (a member's read only). */
  writers: boolean
  people: ReadonlyMap<string, Person> | null
  /** The space's members for a member's view: a member participant reads by their current name. None for a guest. */
  directory?: MemberDirectory
  /** Report this entry (core's report dialog, uc.moderation.report); absent where the viewer reports nothing. */
  onReport?: (entryId: string) => void
  /** Open a listed receipt for whoever looks (`potReceiptSource`). */
  receiptSource: (r: HeldReceipt) => ReceiptSource
  /** Report one receipt on its own; absent where the viewer reports nothing. */
  onReportReceipt?: (r: HeldReceipt) => void
}>()
const emit = defineEmits<{ close: [] }>()
const { t, locale } = useI18n()
const toast = useToast()

/** The record this dialog writes: the opened entry's, or one minted now for a new entry — its receipts are completed
 *  against it before its first revision. */
const recordId = props.entryId ?? crypto.randomUUID()

const entry = computed(() => (props.entryId ? (props.ledger.entries.find((e) => e.id === props.entryId) ?? null) : null))
const base = computed(() => props.meta.base)
const deleted = computed(() => entry.value?.status === 'deleted')

function initialForm(): EntryForm {
  const content = entry.value?.content
  const offered = offeredPeople(props.ledger).map((p) => p.id)
  return content ? formOf(content, base.value, offered, locale.value) : emptyForm(base.value, offered)
}
const form = ref<EntryForm>(initialForm())
/** The revision the form shows. Every write of this dialog is made on it (`basedOn`), not on what the live poll brought in
 *  since: a version saved elsewhere meanwhile is then a `409 stale_revision` the dialog shows, never one written over unseen. */
const shownRevision = ref<number | undefined>(entry.value?.revision)
/** Files picked in this dialog and not uploaded yet, by the receipt id minted for each. */
const pending = reactive(new Map<string, File>())
/** Receipts this dialog uploaded (the drop rule reads them: one added and removed again is dropped). */
const uploaded = new Set<string>()
/** Show the entry as held now. After a stale save the receipts this dialog added stay in the form, so the save on top
 *  keeps them. */
function showCurrent(carryOwn = false) {
  const own = carryOwn ? form.value.receipts.filter((id) => pending.has(id) || uploaded.has(id)) : []
  form.value = initialForm()
  for (const id of own) if (!form.value.receipts.includes(id)) form.value.receipts.push(id)
  shownRevision.value = entry.value?.revision
}
const busy = ref(false)
const error = ref<string | null>(null)
const stale = ref(false)
const showHistory = ref(false)
const editable = computed(() => props.canWrite && !deleted.value && !busy.value)
const purgeAfter = computed(() => (deleted.value ? purgeAfterOf(props.session.held.value, recordId) : null))
const restorable = computed(() => restoreOffered(purgeAfter.value))
const purgeDate = computed(() =>
  purgeAfter.value ? new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(purgeAfter.value) : '',
)

const offered = computed(() => {
  const content = entry.value?.content
  return offeredPeople(props.ledger, content ? namedParticipants(content) : [])
})
const personOptions = computed(() => offered.value.map((p) => ({ label: personLabel(props.ledger, p.id, t, props.directory ?? null), value: p.id })))
const label = (id: string) => personLabel(props.ledger, id, t, props.directory ?? null)

const offeredIds = computed(() => offered.value.map((p) => p.id))
const kindOptions = computed(() => (['expense', 'refund', 'transfer'] as const).map((k) => ({ label: t(`expenses.kind.${k}`), value: k })))
const own = computed(() => ownCategoryNames(props.meta))
const categoryOptions = computed(() => {
  const ids = [...DEFAULT_CATEGORIES, ...props.meta.categories.map((c) => c.id)]
  if (!ids.includes(form.value.category)) ids.push(form.value.category)
  return ids.map((id) => {
    const l = categoryLabel(id, own.value)
    return { value: id, label: 'key' in l ? t(l.key) : 'name' in l ? l.name : t('expenses.unknownCategory') }
  })
})
const currencyOptions = computed(() => {
  const list = knownCurrencies()
  for (const c of [base.value.currency, form.value.currency]) if (!list.includes(c)) list.push(c)
  return list.map((c) => ({ value: c, label: c }))
})
const foreign = computed(() => form.value.currency !== base.value.currency)
const header = computed(() => {
  if (!entry.value) return t('expenses.entry.newTitle')
  return props.canWrite && !deleted.value ? t('expenses.entry.editTitle') : t('expenses.entry.viewTitle')
})

/** Where the fill of the rate stands for the currency picked last: asked, filled (with the rows' date), or not found. */
type RateFill = { currency: string } & ({ state: 'loading' } | { state: 'filled'; rate: string; date: string } | { state: 'missing' })
const rateFill = ref<RateFill | null>(null)
/** Counts the fills asked; an answer to any but the last is dropped. A counter, not the object put in [rateFill]: a ref
 *  hands back a reactive proxy of what it was given, so `rateFill.value !== asked` holds for the very fill asked and every
 *  answer would be dropped (found by EntryEditor.rates.test.ts). */
let fillAsked = 0
const rateNote = computed(() => {
  const f = rateFill.value
  if (!f || f.currency !== form.value.currency) return null
  if (f.state === 'loading') return t('expenses.entry.rateLoading')
  if (f.state === 'missing') return t('expenses.entry.rateMissing', { currency: f.currency })
  return form.value.rate === f.rate ? t('expenses.entry.rateFilled', { date: dayText(f.date) }) : null
})
function dayText(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(new Date(y!, m! - 1, d!))
}

/** Fill the rate of [currency] from the day's table, unless the person moved on meanwhile. */
async function fillRate(currency: string) {
  const template = props.session.rateService.value
  if (!template) return
  const asked = ++fillAsked
  rateFill.value = { currency, state: 'loading' }
  const table = await dayTable(template, today())
  if (asked !== fillAsked || form.value.currency !== currency) return // another currency picked since
  const filled = table ? crossRate(table, currency, base.value.currency) : null
  if (!filled) {
    rateFill.value = { currency, state: 'missing' }
    return
  }
  if (form.value.rate.trim() !== '' || !editable.value) {
    rateFill.value = null // typed meanwhile, or saving: what the person wrote stands
    return
  }
  form.value.rate = filled.rate
  rateFill.value = { currency, state: 'filled', rate: filled.rate, date: filled.date }
}

function setCurrency(currency: string | null | undefined) {
  if (!currency || currency === form.value.currency) return
  form.value = withCurrency(form.value, currency, base.value)
  rateFill.value = null
  fillAsked++
  if (editable.value && foreign.value) void fillRate(currency)
}
function setKind(kind: EntryKind | undefined) {
  if (kind) form.value = withKind(form.value, kind, locale.value)
}
function addPayer() {
  const taken = new Set(form.value.payers.map((p) => p.participant))
  form.value.payers.push({ participant: offered.value.find((p) => !taken.has(p.id))?.id ?? '', amount: '' })
}
function addItem() {
  form.value.items.push(emptyItem(offeredIds.value))
}
/** The amount: what the payers (receivers) typed, summed — never typed itself. */
const total = computed(() => payersTotal(form.value))
/** The amount less the items: the rest item's amount (negative: the items are above the amount, and nothing saves). */
const rest = computed(() => restLeft(form.value))
/** The rest item shows while there is a rest, and on an entry with no items, whose whole amount is the rest. */
const showRest = computed(() => rest.value > 0n || form.value.items.length === 0)
const money = (amount: bigint) => formatMoney(amount, form.value.currency, form.value.minorUnits, locale.value)

const problemText = (p: FormProblem | PotWriteRefused['reason']) => t(`expenses.entry.problem.${problemKey(p)}`)

/** The other writer's version, after a stale save: the form shows it, and the next save goes on top. */
watch(
  () => entry.value?.revision,
  (now, before) => {
    if (stale.value && now !== before) showCurrent(true)
  },
)

/** `451 blocked_by_operator`: the Pot, a node above it, or this entry is blocked by a moderator. */
const BLOCKED = 451

async function run(write: () => Promise<void>) {
  error.value = null
  busy.value = true
  try {
    await write()
    emit('close')
  } catch (e) {
    if (e instanceof StaleRevisionError) {
      stale.value = true
      showCurrent(true)
    } else if (e instanceof ReceiptUploadFailed) {
      error.value = t(e.tooLarge ? 'expenses.receipts.tooLarge' : 'expenses.receipts.uploadFailed', { names: e.fileName })
    } else if (e instanceof PotWriteRefused) {
      error.value = problemText(e.reason)
    } else if (httpStatus(e) === BLOCKED) {
      // a moderator blocked the Pot or this entry since the page read it (inbox #542): nothing was stored
      error.value = t('expenses.entry.blocked')
    } else if (e instanceof Error && e.name === 'GuestDeclinedError') {
      error.value = null
    } else {
      error.value = t('expenses.entry.saveFailed')
    }
  } finally {
    busy.value = false
  }
}

const isParticipant = (id: string) => props.ledger.participants.some((p) => p.id === id && !p.erased)

/** A picked file's upload failed in a way the save cannot go past (`budget_exceeded`: over 10 MiB; anything else but no
 *  room). What was uploaded before it stays uploaded. */
class ReceiptUploadFailed extends Error {
  constructor(
    readonly fileName: string,
    readonly tooLarge: boolean,
  ) {
    super('receipt_upload_failed')
  }
}

/** Upload every picked file the form still holds; answer the names of those there was no room for (left out of the form). */
async function uploadPicked(): Promise<string[]> {
  const noRoom: string[] = []
  for (const id of [...form.value.receipts]) {
    const file = pending.get(id)
    if (!file) continue
    try {
      await props.session.addReceipt(recordId, id, file)
      uploaded.add(id)
      pending.delete(id)
    } catch (e) {
      if (e instanceof Error && e.message === QUOTA_EXCEEDED) {
        noRoom.push(file.name)
        pending.delete(id)
        form.value.receipts = form.value.receipts.filter((x) => x !== id)
      } else if ((e instanceof Error && (e.name === 'GuestDeclinedError' || e.name === 'PotKeyUnavailableError')) || httpStatus(e) === BLOCKED) {
        throw e
      } else {
        throw new ReceiptUploadFailed(file.name, e instanceof Error && e.message === 'budget_exceeded')
      }
    }
  }
  return noRoom
}

/** The revision the form shows, as the drop rule reads it: the receipts it lists and when it was stored. */
function shownRevisionFacts(): { receipts: readonly string[]; createdAt: Date } | null {
  if (shownRevision.value === undefined) return null
  const r = props.session.held.value.find((x) => x.entryId === recordId && x.revision === shownRevision.value)
  if (!r) return null
  return { receipts: r.body && isMoney(r.body) ? r.body.receipts : [], createdAt: r.createdAt }
}

function save() {
  const result = contentOf(form.value, isParticipant)
  if ('problem' in result) {
    error.value = problemText(result.problem)
    return
  }
  stale.value = false
  void run(async () => {
    const noRoom = await uploadPicked()
    const receipts = [...form.value.receipts]
    const content = { ...result.content, receipts }
    const droppedReceipts = receiptsToDrop(props.session.receipts.value, recordId, receipts, shownRevisionFacts(), uploaded)
    await props.session.write([{ entryId: recordId, content, basedOn: shownRevision.value, droppedReceipts }])
    if (noRoom.length) toast.error(t('expenses.receipts.noRoom', { names: noRoom.join(', ') }))
  })
}
function addReceipts(files: File[]) {
  for (const f of files) {
    const id = crypto.randomUUID()
    pending.set(id, f)
    form.value.receipts.push(id)
  }
}
function removeReceipt(id: string) {
  form.value.receipts = form.value.receipts.filter((x) => x !== id)
  pending.delete(id)
}
function remove() {
  if (!props.entryId) return
  void run(() => props.session.write([{ entryId: props.entryId!, content: { kind: 'deleted' }, basedOn: shownRevision.value }]))
}
function restore() {
  const content = entry.value?.content
  if (!props.entryId || !content || !restorable.value) return
  void run(() => props.session.write([{ entryId: props.entryId!, content, basedOn: shownRevision.value }]))
}
</script>

<template>
  <VeyqDialog :visible="true" :header="header" :closable="!busy" :close-on-escape="!busy" class="expenses-entry-dialog" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="save">
      <VeyqMessage v-if="stale" tone="warn">{{ t('expenses.entry.stale') }}</VeyqMessage>
      <VeyqMessage v-if="deleted" tone="info">
        {{ t('expenses.entry.deletedHint') }}
        <template v-if="purgeAfter && restorable">{{ t('expenses.entry.restorableUntil', { date: purgeDate }) }}</template>
        <template v-else-if="purgeAfter">{{ t('expenses.entry.notRestorable') }}</template>
      </VeyqMessage>
      <VeyqMessage v-if="entry?.status === 'invalid'" tone="warn">{{ t('expenses.entry.invalid') }}</VeyqMessage>

      <VeyqToggleGroup :model-value="form.kind" :options="kindOptions" :disabled="!editable" @update:model-value="setKind" />

      <label>
        {{ form.kind === 'transfer' ? t('expenses.entry.noteLabel') : t('expenses.entry.titleLabel') }}
        <VeyqInput v-model="form.title" autocomplete="off" :disabled="!editable" />
      </label>
      <div class="expenses-entry-row">
        <label v-if="form.kind === 'transfer'">
          {{ t('expenses.entry.amountLabel') }}
          <VeyqInput v-model="form.amount" inputmode="decimal" autocomplete="off" :disabled="!editable" />
        </label>
        <label>
          {{ t('expenses.entry.currencyLabel') }}
          <VeyqSelect :model-value="form.currency" :options="currencyOptions" :disabled="!editable" @update:model-value="setCurrency" />
        </label>
      </div>
      <label v-if="foreign">
        {{ t('expenses.entry.rateLabel', { from: form.currency, to: base.currency }) }}
        <VeyqInput v-model="form.rate" inputmode="decimal" autocomplete="off" :disabled="!editable" />
        <span v-if="rateNote" class="veyq-form-copy">{{ rateNote }}</span>
        <span class="veyq-form-copy">{{ t('expenses.entry.rateHint') }}</span>
      </label>
      <div class="expenses-entry-row">
        <label>
          {{ t('expenses.entry.dateLabel') }}
          <VeyqDateField v-model="form.date" :disabled="!editable" />
        </label>
        <label v-if="form.kind !== 'transfer'">
          {{ t('expenses.entry.categoryLabel') }}
          <VeyqSelect v-model="form.category" :options="categoryOptions" :disabled="!editable" />
        </label>
      </div>

      <template v-if="form.kind === 'transfer'">
        <div class="expenses-entry-row">
          <label>
            {{ t('expenses.entry.fromLabel') }}
            <VeyqSelect v-model="form.from" :options="personOptions" :disabled="!editable" />
          </label>
          <label>
            {{ t('expenses.entry.toLabel') }}
            <VeyqSelect v-model="form.to" :options="personOptions" :disabled="!editable" />
          </label>
        </div>
      </template>

      <div v-else class="expenses-entry-columns">
        <fieldset class="expenses-entry-fieldset">
          <legend>{{ form.kind === 'expense' ? t('expenses.entry.paidBy') : t('expenses.entry.receivedBy') }}</legend>
          <div v-for="(payer, i) in form.payers" :key="i" class="expenses-entry-row">
            <VeyqSelect v-model="payer.participant" :options="personOptions" :disabled="!editable" :aria-label="t('expenses.entry.personLabel')" />
            <VeyqInput
              v-model="payer.amount"
              inputmode="decimal"
              autocomplete="off"
              :aria-label="t(form.kind === 'expense' ? 'expenses.entry.payerAmount' : 'expenses.entry.receiverAmount', { name: label(payer.participant) })"
              :disabled="!editable"
            />
            <VeyqButton
              v-if="editable && form.payers.length > 1"
              class="veyq-icon-button"
              icon="times"
              variant="secondary"
              appearance="outlined"
              size="small"
              :aria-label="t('expenses.entry.removePayer', { name: label(payer.participant) })"
              @click="form.payers.splice(i, 1)"
            />
          </div>
          <VeyqButton v-if="editable && form.payers.length < offered.length" :label="t('expenses.entry.addPayer')" icon="plus" variant="secondary" appearance="text" size="small" @click="addPayer" />
          <p class="expenses-entry-total">
            <span>{{ t('expenses.entry.amountLabel') }}</span>
            <output>{{ money(total) }}</output>
          </p>
        </fieldset>

        <fieldset class="expenses-entry-fieldset">
          <legend>{{ t('expenses.entry.splitLabel') }}</legend>
          <div v-for="(item, i) in form.items" :key="i" class="expenses-item">
            <div class="expenses-entry-row">
              <VeyqInput v-model="item.label" autocomplete="off" :aria-label="t('expenses.entry.itemLabel')" :placeholder="t('expenses.entry.itemLabel')" :disabled="!editable" />
              <VeyqInput v-model="item.amount" inputmode="decimal" autocomplete="off" :aria-label="t('expenses.entry.itemAmount')" :placeholder="t('expenses.entry.itemAmount')" :disabled="!editable" />
              <VeyqButton
                v-if="editable"
                class="veyq-icon-button"
                icon="times"
                variant="secondary"
                appearance="outlined"
                size="small"
                :aria-label="t('expenses.entry.removeItem')"
                @click="form.items.splice(i, 1)"
              />
            </div>
            <EntrySplit v-model="item.split" :people="offeredIds" :label="label" :editable="editable" />
          </div>
          <VeyqButton v-if="editable" :label="t('expenses.entry.addItem')" icon="plus" variant="secondary" appearance="text" size="small" @click="addItem" />
          <div v-if="showRest" class="expenses-item expenses-rest">
            <p class="expenses-entry-total">
              <span>{{ form.items.length ? t('expenses.entry.restLabel') : t('expenses.entry.wholeLabel') }}</span>
              <output>{{ money(rest > 0n ? rest : 0n) }}</output>
            </p>
            <EntrySplit v-model="form.rest" :people="offeredIds" :label="label" :editable="editable" />
          </div>
          <VeyqMessage v-if="rest < 0n" tone="warn">{{ t('expenses.entry.itemsOver', { amount: money(-rest) }) }}</VeyqMessage>
        </fieldset>
      </div>

      <EntryReceipts
        :ids="form.receipts"
        :listing="session.receipts.value"
        :pending="pending"
        :editable="editable"
        :failed="session.receiptsFailed.value"
        :source="receiptSource"
        :on-report="onReportReceipt"
        @add="addReceipts"
        @remove="removeReceipt"
      />

      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>

      <EntryHistory v-if="entry && showHistory" :session="session" :meta="meta" :entry-id="entry.id" :writers="writers" :people="people" />

      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="busy" @click="emit('close')" />
        <span class="veyq-action-group">
          <VeyqButton v-if="entry" :label="showHistory ? t('expenses.history.hide') : t('expenses.history.show')" icon="history" variant="secondary" appearance="text" @click="showHistory = !showHistory" />
          <VeyqButton v-if="entry && onReport" :label="t('report.action')" icon="flag" variant="secondary" appearance="text" :disabled="busy" @click="onReport(entry.id)" />
          <VeyqButton v-if="entry && canWrite && !deleted" :label="t('expenses.entry.delete')" icon="trash" variant="danger" appearance="outlined" :disabled="busy" @click="remove" />
          <VeyqButton v-if="entry && canWrite && deleted && restorable" type="button" :label="t('expenses.entry.restore')" icon="undo" :loading="busy" @click="restore" />
          <VeyqButton v-if="canWrite && !deleted" type="submit" :label="t('common.save')" icon="check" :loading="busy" />
        </span>
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.expenses-entry-row {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 0.75rem;
}
.expenses-entry-row > * {
  flex: 1 1 8rem;
  min-width: 0;
}
.expenses-entry-row > .veyq-icon-button {
  flex: 0 0 auto;
}
.expenses-entry-fieldset {
  display: grid;
  gap: 0.6rem;
  min-width: 0;
  margin: 0;
  padding: 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
}
.expenses-entry-fieldset legend {
  padding: 0 0.25rem;
  font-weight: 700;
}
/* Payers on one side, the items and the rest on the other, where the dialog has room for both; stacked on a phone. */
.expenses-entry-columns {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr));
  align-items: start;
  gap: 0.75rem;
  min-width: 0;
}
.expenses-entry-total {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  margin: 0;
  font-weight: 700;
}
.expenses-item {
  display: grid;
  gap: 0.4rem;
  padding-bottom: 0.6rem;
  border-bottom: 1px solid var(--app-line);
}
.expenses-rest {
  border-bottom: 0;
  padding-bottom: 0;
}
</style>
