<script setup lang="ts">
// An inline message: an error, a warning, a success or a note beside the thing it is about (ADR 0107). It is never
// closed by the user here; it goes when its condition does. Sheets reach it by `veyq-message`,
// `veyq-message--<tone>` and `veyq-message__text`.
import Message from 'primevue/message'

export type VeyqMessageTone = 'info' | 'success' | 'warn' | 'error'

const props = defineProps<{ tone: VeyqMessageTone }>()

const PT = {
  content: { class: 'veyq-message__content' },
  icon: { class: 'veyq-message__icon' },
  text: { class: 'veyq-message__text' },
}
</script>

<template>
  <Message :severity="props.tone" :closable="false" :class="['veyq-message', `veyq-message--${props.tone}`]" :pt="PT">
    <slot />
  </Message>
</template>
