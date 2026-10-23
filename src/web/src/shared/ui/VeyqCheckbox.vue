<script setup lang="ts">
// A checkbox for one yes/no (ADR 0107), on Reka's Checkbox: a `role="checkbox"` button that Space toggles. `inputId`
// is the control's id, for a `<label for>`; without a label, the caller passes `aria-label`. Sheets reach it by
// `veyq-checkbox` (checked: `[data-state='checked']`) and `veyq-checkbox__box` (styled in `layer.css`).
import { CheckboxIndicator, CheckboxRoot } from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'

const model = defineModel<boolean>({ default: false })
defineProps<{ inputId?: string; disabled?: boolean }>()
</script>

<template>
  <CheckboxRoot
    :id="inputId"
    :model-value="model"
    :disabled="disabled"
    class="veyq-checkbox"
    @update:model-value="(v) => (model = v === true)"
  >
    <span class="veyq-checkbox__box">
      <CheckboxIndicator class="veyq-checkbox__indicator">
        <VeyqIcon name="check" class="veyq-checkbox__icon" />
      </CheckboxIndicator>
    </span>
  </CheckboxRoot>
</template>
