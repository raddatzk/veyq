<script setup lang="ts">
// One split of an entry (uc.expenses.record-entries): an item's or the rest's. While it is everyone equally — a new entry's
// rest, a new item, or one opened equal over everyone offered — it is ONE line ("All 3 people, equally") with "Adjust";
// adjusted, it shows its mode — by shares, by percent, by amount — and its people as rows, a person picked in each (as
// "Paid by" does), each with their value, and "Add another person" (owner 2026-11-03: a list of every participant per
// item grew too long). By shares, "Split equally among all" puts every active person on it at 1 share. Each mode keeps what
// was typed for it, so switching back loses nothing; `../entryForm.ts` seals the picked mode's values only, and shares of
// 1 each as `equal`. Percents must add up to 100 %, amounts to what the split divides ([part]): while they do not, a red
// line under the split says what they add up to (live, as typed); what the save found wrong here is [error], said under it.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqToggleGroup from '../../../shared/ui/VeyqToggleGroup.vue'
import { amountsTotal, everyoneEqually, percentText, percentTotal, splitRow, type FormSplit, type FormSplitRow, type SplitMode } from '../entryForm'
import { PERCENT_WHOLE } from '../domain/format'

const split = defineModel<FormSplit>({ required: true })
const props = defineProps<{
  /** The people offered, by participant id: those a row may pick. */
  people: readonly string[]
  /** The active ones among them: "Split equally among all" puts each on the split. */
  everyone: readonly string[]
  /** A person's name as the Pot shows it. */
  label: (id: string) => string
  editable: boolean
  /** What the split divides, in minor units of the entry's currency: an amounts split adds up to it. */
  part: bigint
  minorUnits: number
  /** An amount of the entry's currency as the Pot shows it. */
  money: (amount: bigint) => string
  /** What the save found wrong with this split (or its item), said under it. */
  error?: string | null
}>()
const { t, locale } = useI18n()

const modes: SplitMode[] = ['shares', 'percent', 'amounts']
const modeOptions = computed(() => modes.map((m) => ({ label: t(`expenses.split.${m}`), value: m })))
const percentSum = computed(() => percentTotal(split.value))
const amountsSum = computed(() => amountsTotal(split.value, props.minorUnits))
const summary = computed(() => {
  const rows = split.value.rows
  return t('expenses.split.everyone', { count: rows.length, name: rows[0] ? props.label(rows[0].participant) : '' }, rows.length)
})

/** A row's choices: every person offered whom no other row has picked. */
const optionsFor = (row: FormSplitRow) => {
  const taken = new Set(split.value.rows.filter((r) => r !== row).map((r) => r.participant))
  return props.people.filter((id) => !taken.has(id)).map((id) => ({ label: props.label(id), value: id }))
}

const update = (next: Partial<FormSplit>) => (split.value = { ...split.value, ...next })
function setMode(mode: SplitMode | undefined) {
  if (mode) update({ mode })
}
function setRow(i: number, change: Partial<FormSplitRow>) {
  update({ rows: split.value.rows.map((r, n) => (n === i ? { ...r, ...change } : r)) })
}
function addPerson() {
  const taken = new Set(split.value.rows.map((r) => r.participant))
  const next = props.people.find((id) => !taken.has(id))
  if (next) update({ rows: [...split.value.rows, splitRow(next)] })
}
function removePerson(i: number) {
  update({ rows: split.value.rows.filter((_, n) => n !== i) })
}

function distribute() {
  split.value = everyoneEqually(split.value, props.everyone)
}

/** The field of a row that the picked mode reads and types. */
const VALUE_KEY = { shares: 'shares', percent: 'percent', amounts: 'amount' } as const
const valueOf = (row: FormSplitRow) => row[VALUE_KEY[split.value.mode]]
const valueLabel = (row: FormSplitRow) =>
  t({ shares: 'expenses.entry.sharesOf', percent: 'expenses.entry.percentOf', amounts: 'expenses.entry.amountOf' }[split.value.mode], { name: props.label(row.participant) })
</script>

<template>
  <div class="expenses-split">
    <div v-if="!split.expanded" class="expenses-split-summary">
      <span>{{ summary }}</span>
      <VeyqButton v-if="editable" :label="t('expenses.split.adjust')" icon="sliders-h" variant="secondary" appearance="text" size="small" @click="update({ expanded: true })" />
    </div>
    <template v-else>
      <VeyqToggleGroup :model-value="split.mode" :options="modeOptions" :disabled="!editable" @update:model-value="setMode" />
      <div v-for="(row, i) in split.rows" :key="i" class="expenses-split-row">
        <VeyqSelect
          :model-value="row.participant"
          :options="optionsFor(row)"
          :disabled="!editable"
          :aria-label="t('expenses.split.person')"
          @update:model-value="(id: string | null | undefined) => setRow(i, { participant: id ?? '' })"
        />
        <VeyqInput
          :model-value="valueOf(row)"
          :inputmode="split.mode === 'shares' ? 'numeric' : 'decimal'"
          autocomplete="off"
          :aria-label="valueLabel(row)"
          :disabled="!editable"
          @update:model-value="(v: string | null | undefined) => setRow(i, { [VALUE_KEY[split.mode]]: v ?? '' })"
        />
        <span class="expenses-split-unit" aria-hidden="true">{{ split.mode === 'percent' ? '%' : '' }}</span>
        <VeyqButton
          v-if="editable && split.rows.length > 1"
          class="veyq-icon-button"
          icon="times"
          variant="secondary"
          appearance="outlined"
          size="small"
          :aria-label="t('expenses.split.removePerson', { name: label(row.participant) })"
          @click="removePerson(i)"
        />
      </div>
      <div v-if="editable" class="expenses-split-actions">
        <VeyqButton v-if="split.rows.length < people.length" :label="t('expenses.split.addPerson')" icon="plus" variant="secondary" appearance="text" size="small" @click="addPerson" />
        <VeyqButton v-if="split.mode === 'shares'" :label="t('expenses.split.everyoneEqually')" icon="users" variant="secondary" appearance="text" size="small" @click="distribute" />
      </div>
      <p v-if="split.mode === 'percent' && percentSum !== PERCENT_WHOLE" class="veyq-form-error">
        {{ t('expenses.entry.percentTotal', { total: percentText(percentSum, locale) }) }}
      </p>
      <p v-else-if="split.mode === 'amounts' && amountsSum !== part" class="veyq-form-error">
        {{ t('expenses.entry.amountsTotal', { total: money(amountsSum), amount: money(part) }) }}
      </p>
    </template>
    <p v-if="error" class="veyq-form-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.expenses-split {
  display: grid;
  gap: 0.5rem;
  min-width: 0;
}
/* "All 3 people, equally" and its "Adjust" in one line; the button wraps under the text on a narrow phone. */
.expenses-split-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.25rem 0.75rem;
}
/* A person, their value and its unit, and the button that takes them off: the person takes what the value leaves. */
.expenses-split-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(4rem, 7rem) 1rem auto;
  align-items: center;
  gap: 0.5rem;
}
.expenses-split-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.75rem;
}
</style>
