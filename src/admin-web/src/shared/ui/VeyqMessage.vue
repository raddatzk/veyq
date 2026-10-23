<script setup lang="ts">
// An inline message: an error, a warning, a success, a note or a neutral note (`secondary`) beside the thing it is
// about (ADR 0107). It goes when its condition does; `closable` adds a close button that emits `close` and leaves
// hiding it to the caller (the statement-delivery notice is the one that may be dismissed). A screen reader announces
// it when it appears (`role="alert"`). Sheets reach it by `veyq-message`, `veyq-message--<tone>`, `veyq-message__text`
// and `veyq-message__close` (styled in `layer.css`).
import { useI18n } from 'vue-i18n'
import VeyqIcon from './VeyqIcon.vue'

export type VeyqMessageTone = 'info' | 'success' | 'warn' | 'error' | 'secondary'

defineProps<{ tone: VeyqMessageTone; closable?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
</script>

<template>
  <div :class="['veyq-message', `veyq-message--${tone}`]" role="alert" aria-live="assertive" aria-atomic="true">
    <div class="veyq-message__wrapper">
      <div class="veyq-message__content">
        <div class="veyq-message__text"><slot /></div>
      </div>
      <button v-if="closable" type="button" class="veyq-message__close" :aria-label="t('ui.close')" @click="emit('close')">
        <VeyqIcon name="times" />
      </button>
    </div>
  </div>
</template>
