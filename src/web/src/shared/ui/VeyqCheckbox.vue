<script setup lang="ts">
// A checkbox for one yes/no (ADR 0107). `inputId` is the control's id, for a `<label for>`; without a label, the caller
// passes `aria-label`. Sheets reach it by `veyq-checkbox` and `veyq-checkbox__box`.
import Checkbox from 'primevue/checkbox'

const model = defineModel<boolean>()
defineProps<{ inputId?: string; disabled?: boolean }>()

const PT = { box: { class: 'veyq-checkbox__box' } }
</script>

<template>
  <Checkbox v-model="model" binary class="veyq-checkbox" :input-id="inputId" :disabled="disabled" :pt="PT" />
</template>
