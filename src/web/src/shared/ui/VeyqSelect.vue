<script setup lang="ts" generic="T extends string | number">
// A drop-down to pick one of several options (ADR 0107), on Reka's Select: the field is a button that opens the list,
// arrow keys move, typing jumps to an option, Enter picks, Escape closes and focus returns to the field. Each option
// is `{ label, value }`. The `value` slot draws the picked option in the closed field, the `option` slot each entry of
// the list; both get `{ option }` (`value` gets `null` while nothing is picked). `inputId` is the field's id, for a
// `<label for>`. Sheets reach it by `veyq-select`, its list by `veyq-select__list` and an entry by
// `veyq-select__option` (styled in `layer.css`).
import { computed } from 'vue'
import { SelectContent, SelectIcon, SelectItem, SelectItemText, SelectPortal, SelectRoot, SelectTrigger, SelectValue, SelectViewport } from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'

export interface VeyqSelectOption<V> {
  label: string
  value: V
}

defineOptions({ inheritAttrs: false })
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
</script>

<template>
  <SelectRoot :model-value="model ?? undefined" :disabled="disabled" @update:model-value="(v) => (model = v as T)">
    <SelectTrigger :id="inputId" v-bind="$attrs" class="veyq-select">
      <span :class="['veyq-select__label', { 'veyq-select__label--placeholder': !picked }]">
        <slot v-if="$slots.value && picked" name="value" :option="picked" />
        <SelectValue v-else>{{ picked ? picked.label : placeholder }}</SelectValue>
      </span>
      <SelectIcon class="veyq-select__dropdown"><VeyqIcon name="chevron-down" /></SelectIcon>
    </SelectTrigger>
    <SelectPortal>
      <SelectContent class="veyq-select__list" position="popper" :side-offset="2" :collision-padding="8">
        <SelectViewport class="veyq-select__viewport">
          <SelectItem v-for="o in options" :key="String(o.value)" :value="o.value" class="veyq-select__option">
            <slot v-if="$slots.option" name="option" :option="o" />
            <SelectItemText v-else>{{ o.label }}</SelectItemText>
          </SelectItem>
        </SelectViewport>
      </SelectContent>
    </SelectPortal>
  </SelectRoot>
</template>
