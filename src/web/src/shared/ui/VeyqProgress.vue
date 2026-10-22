<script setup lang="ts">
// A progress bar from 0 to 100 (ADR 0107). `showValue` prints the percentage inside the bar. Sheets reach it by
// `veyq-progress` and `veyq-progress__value`.
import ProgressBar from 'primevue/progressbar'

defineProps<{ value: number; showValue?: boolean }>()

const PT = { value: { class: 'veyq-progress__value' } }
</script>

<template>
  <ProgressBar class="veyq-progress" :value="value" :show-value="showValue" :pt="PT" />
</template>
