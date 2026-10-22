<script setup lang="ts" generic="T extends string | number | boolean">
// A row of options of which exactly one is picked, all visible at once (ADR 0107). One is always picked: clicking the
// picked one keeps it. Sheets reach it by `veyq-toggle-group` and `veyq-toggle-group__item`.
import SelectButton from 'primevue/selectbutton'

export interface VeyqToggleOption<V> {
  label: string
  value: V
}

const model = defineModel<T>()
defineProps<{ options: VeyqToggleOption<T>[]; disabled?: boolean }>()

const PT = { pcToggleButton: { root: { class: 'veyq-toggle-group__item' } } }
</script>

<template>
  <SelectButton
    v-model="model"
    class="veyq-toggle-group"
    :options="options"
    option-label="label"
    option-value="value"
    :allow-empty="false"
    :disabled="disabled"
    :pt="PT"
  />
</template>
