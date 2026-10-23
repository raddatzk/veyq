<script setup lang="ts">
// An on/off switch for a setting that applies at once (ADR 0107), on Reka's Switch: a `role="switch"` button that
// Space flips. `inputId` is the control's id, for a `<label for>`; without a label, the caller passes `aria-label`.
// Sheets reach it by `veyq-switch` (on: `[data-state='checked']`) and `veyq-switch__track` (styled in `layer.css`).
import { SwitchRoot, SwitchThumb } from 'reka-ui'

const model = defineModel<boolean>({ default: false })
defineProps<{ inputId?: string; disabled?: boolean }>()
</script>

<template>
  <SwitchRoot :id="inputId" v-model="model" :disabled="disabled" class="veyq-switch veyq-switch__track">
    <SwitchThumb class="veyq-switch__handle" />
  </SwitchRoot>
</template>
