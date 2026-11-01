<script setup lang="ts">
// One split of an entry (uc.expenses.record-entries): an item's or the rest's. The mode — equally, by shares, by percent —
// and, per mode, who is on it: a checkbox per person, or a field per person (empty: not on it). Each mode keeps what was
// typed for it, so switching back loses nothing; `../entryForm.ts` seals the picked mode's values only. Percents are typed
// with at most two decimals and must add up to 100 % (the hint says what they add up to while they do not).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqToggleGroup from '../../../shared/ui/VeyqToggleGroup.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import { percentText, percentTotal, type FormSplit, type SplitMode } from '../entryForm'
import { PERCENT_WHOLE } from '../domain/format'

const split = defineModel<FormSplit>({ required: true })
const props = defineProps<{
  /** The people offered, by participant id. */
  people: readonly string[]
  /** A person's name as the Pot shows it. */
  label: (id: string) => string
  editable: boolean
}>()
const { t, locale } = useI18n()

const modeOptions = computed(() => (['equal', 'shares', 'percent'] as const).map((m) => ({ label: t(`expenses.split.${m}`), value: m })))
const percentSum = computed(() => percentTotal(split.value))

function setMode(mode: SplitMode | undefined) {
  if (mode) split.value = { ...split.value, mode }
}
function setEqual(id: string, on: boolean) {
  const list = split.value.equal
  split.value = { ...split.value, equal: on ? (list.includes(id) ? list : [...list, id]) : list.filter((x) => x !== id) }
}
function setTyped(id: string, typed: string | null | undefined) {
  const key = split.value.mode === 'shares' ? 'shares' : 'percent'
  split.value = { ...split.value, [key]: { ...split.value[key], [id]: typed ?? '' } }
}
const typed = (id: string) => (split.value.mode === 'shares' ? split.value.shares[id] : split.value.percent[id]) ?? ''
</script>

<template>
  <div class="expenses-split">
    <VeyqToggleGroup :model-value="split.mode" :options="modeOptions" :disabled="!props.editable" @update:model-value="setMode" />
    <div v-if="split.mode === 'equal'" class="expenses-split-people">
      <label v-for="id in people" :key="id" class="expenses-check-row">
        <VeyqCheckbox :model-value="split.equal.includes(id)" :disabled="!props.editable" @update:model-value="(on: boolean) => setEqual(id, on)" />
        {{ label(id) }}
      </label>
    </div>
    <template v-else>
      <label v-for="id in people" :key="id" class="expenses-split-row">
        <span>{{ label(id) }}</span>
        <VeyqInput
          :model-value="typed(id)"
          :inputmode="split.mode === 'shares' ? 'numeric' : 'decimal'"
          autocomplete="off"
          :aria-label="t(split.mode === 'shares' ? 'expenses.entry.sharesOf' : 'expenses.entry.percentOf', { name: label(id) })"
          :disabled="!props.editable"
          @update:model-value="(v: string | null | undefined) => setTyped(id, v)"
        />
        <span v-if="split.mode === 'percent'" aria-hidden="true">%</span>
      </label>
      <p v-if="split.mode === 'percent' && percentSum !== PERCENT_WHOLE" class="veyq-form-copy">
        {{ t('expenses.entry.percentTotal', { total: percentText(percentSum, locale) }) }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.expenses-split {
  display: grid;
  gap: 0.5rem;
  min-width: 0;
}
.expenses-split-people {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem 1rem;
}
.expenses-check-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.expenses-split-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(4rem, 7rem) 1rem;
  align-items: center;
  gap: 0.5rem;
}
</style>
