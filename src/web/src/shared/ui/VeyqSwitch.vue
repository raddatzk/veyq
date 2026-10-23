<script setup lang="ts">
// An on/off switch for a setting that applies at once (ADR 0107). `inputId` is the control's id, for a `<label for>`;
// without a label, the caller passes `aria-label`. Sheets reach it by `veyq-switch` and `veyq-switch__track`.
import ToggleSwitch from 'primevue/toggleswitch'

const model = defineModel<boolean>()
defineProps<{ inputId?: string; disabled?: boolean }>()

const PT = { slider: { class: 'veyq-switch__track' } }
</script>

<template>
  <ToggleSwitch v-model="model" class="veyq-switch" :input-id="inputId" :disabled="disabled" :pt="PT" />
</template>
