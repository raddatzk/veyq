<script setup lang="ts">
// An inline message: an error, a warning, a success or a note beside the thing it is about (ADR 0107). It is never
// closed by the user here; it goes when its condition does. A screen reader announces it when it appears
// (`role="alert"`). Sheets reach it by `veyq-message`, `veyq-message--<tone>` and `veyq-message__text` (styled in
// `layer.css`).
export type VeyqMessageTone = 'info' | 'success' | 'warn' | 'error'

defineProps<{ tone: VeyqMessageTone }>()
</script>

<template>
  <div :class="['veyq-message', `veyq-message--${tone}`]" role="alert" aria-live="assertive" aria-atomic="true">
    <div class="veyq-message__wrapper">
      <div class="veyq-message__content">
        <div class="veyq-message__text"><slot /></div>
      </div>
    </div>
  </div>
</template>
