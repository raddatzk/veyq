<script setup lang="ts" generic="T extends string | number">
// A row of options of which exactly one is picked, all visible at once (ADR 0107), on Reka's ToggleGroup (single):
// arrow keys move between the options. One is always picked: clicking the picked one keeps it. Sheets reach it by
// `veyq-toggle-group` and `veyq-toggle-group__item` (picked: `[data-state='on']`; styled in `layer.css`).
import { ToggleGroupItem, ToggleGroupRoot } from 'reka-ui'

export interface VeyqToggleOption<V> {
  label: string
  value: V
}

const model = defineModel<T>()
defineProps<{ options: VeyqToggleOption<T>[]; disabled?: boolean }>()

function pick(value: unknown) {
  if (value === undefined || value === null || value === '') return
  model.value = value as T
}
</script>

<template>
  <ToggleGroupRoot type="single" :model-value="model" :disabled="disabled" class="veyq-toggle-group" @update:model-value="pick">
    <ToggleGroupItem v-for="o in options" :key="String(o.value)" :value="o.value" class="veyq-toggle-group__item">
      <span class="veyq-toggle-group__content">{{ o.label }}</span>
    </ToggleGroupItem>
  </ToggleGroupRoot>
</template>
