<script setup lang="ts">
// A progress bar from 0 to 100 (ADR 0107), on Reka's Progress (the progressbar role and its value). The percentage is
// printed inside the bar unless `showValue` is false. Sheets reach it by `veyq-progress` and `veyq-progress__value`
// (styled in `layer.css`).
import { ProgressIndicator, ProgressRoot } from 'reka-ui'

withDefaults(defineProps<{ value: number; showValue?: boolean }>(), { showValue: true })
</script>

<template>
  <ProgressRoot :model-value="value" :max="100" class="veyq-progress">
    <ProgressIndicator class="veyq-progress__value" :style="{ width: `${value}%` }">
      <span v-if="showValue && value > 0" class="veyq-progress__label">{{ value }}%</span>
    </ProgressIndicator>
  </ProgressRoot>
</template>
