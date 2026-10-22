<script setup lang="ts" generic="T extends string | number">
// A drop-down to pick one of several options (ADR 0107). Each option is `{ label, value }`. The `value` slot draws the
// picked option in the closed field, the `option` slot each entry of the list; both get `{ option }` (`value` gets
// `null` while nothing is picked). `inputId` is the control's id, for a `<label for>`. Sheets reach it by
// `veyq-select`, its list by `veyq-select__list` and an entry by `veyq-select__option`.
import { computed } from 'vue'
import Select from 'primevue/select'

export interface VeyqSelectOption<V> {
  label: string
  value: V
}

const model = defineModel<T | null | undefined>()
const props = defineProps<{
  options: VeyqSelectOption<T>[]
  placeholder?: string
  inputId?: string
  disabled?: boolean
}>()
defineSlots<{
  value?: (scope: { option: VeyqSelectOption<T> | null }) => unknown
  option?: (scope: { option: VeyqSelectOption<T> }) => unknown
}>()

const picked = computed(() => props.options.find((o) => o.value === model.value) ?? null)

const PT = {
  overlay: { class: 'veyq-select__list' },
  option: { class: 'veyq-select__option' },
}
</script>

<template>
  <Select
    v-model="model"
    class="veyq-select"
    :options="options"
    option-label="label"
    option-value="value"
    :placeholder="placeholder"
    :input-id="inputId"
    :disabled="disabled"
    :pt="PT"
  >
    <template v-if="$slots.value" #value><slot name="value" :option="picked" /></template>
    <template v-if="$slots.option" #option="{ option }"><slot name="option" :option="option as VeyqSelectOption<T>" /></template>
  </Select>
</template>
