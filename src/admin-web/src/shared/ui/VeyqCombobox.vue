<script setup lang="ts" generic="T extends string | number">
// A drop-down to pick one of many options by typing (ADR 0107), on Reka's Combobox: the field is a text input that
// filters the list as you type (ignoring case and accents), the arrow keys move, Enter picks, Escape closes; a click
// or the chevron opens the whole list. Each option is `{ label, value }`, as `VeyqSelect`'s. `clearable` adds a button
// that empties the pick. The caller's `class` lands on the field's box, every other attribute (`aria-labelledby`, …)
// on the input; `inputId` is the input's id, for a `<label for>`. Sheets reach it by `veyq-combobox`, its input by
// `veyq-combobox__input` and its list by `veyq-combobox__list` (styled in `layer.css`). The input is `size="1"`: it grows
// to the box by flex, so the field fits a narrow grid column as a select's would (a default `<input>` is ~20 characters
// wide at its narrowest).
import { computed, useAttrs } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ComboboxAnchor,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxPortal,
  ComboboxRoot,
  ComboboxTrigger,
  ComboboxViewport,
} from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'
import type { VeyqSelectOption } from './VeyqSelect.vue'

defineOptions({ inheritAttrs: false })
const model = defineModel<T | null | undefined>()
const props = defineProps<{
  options: VeyqSelectOption<T>[]
  placeholder?: string
  inputId?: string
  disabled?: boolean
  clearable?: boolean
}>()
const { t } = useI18n()

const attrs = useAttrs()
const boxClass = computed(() => attrs.class)
const inputAttrs = computed(() => Object.fromEntries(Object.entries(attrs).filter(([key]) => key !== 'class')))

const labelOf = (value: unknown): string => props.options.find((o) => o.value === value)?.label ?? ''
</script>

<template>
  <ComboboxRoot :model-value="model ?? undefined" :disabled="disabled" open-on-click @update:model-value="(v) => (model = v as T)">
    <ComboboxAnchor :class="['veyq-combobox', boxClass]">
      <ComboboxInput
        :id="inputId"
        v-bind="inputAttrs"
        class="veyq-combobox__input"
        :display-value="labelOf"
        :placeholder="placeholder"
        autocomplete="off"
        :size="1"
      />
      <button
        v-if="clearable && model != null"
        type="button"
        class="veyq-combobox__clear"
        :aria-label="t('ui.clear')"
        :disabled="disabled"
        @click="model = null"
      >
        <VeyqIcon name="times" />
      </button>
      <ComboboxTrigger class="veyq-combobox__trigger" :aria-label="t('ui.showOptions')"><VeyqIcon name="chevron-down" /></ComboboxTrigger>
    </ComboboxAnchor>
    <ComboboxPortal>
      <ComboboxContent class="veyq-select__list veyq-combobox__list" position="popper" :side-offset="2" :collision-padding="8">
        <ComboboxViewport class="veyq-select__viewport">
          <ComboboxEmpty class="veyq-combobox__empty">{{ t('ui.noMatches') }}</ComboboxEmpty>
          <ComboboxItem v-for="o in options" :key="String(o.value)" :value="o.value" :text-value="o.label" class="veyq-select__option">
            {{ o.label }}
          </ComboboxItem>
        </ComboboxViewport>
      </ComboboxContent>
    </ComboboxPortal>
  </ComboboxRoot>
</template>
