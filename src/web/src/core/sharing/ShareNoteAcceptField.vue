<script setup lang="ts">
// The note's acceptance switch (uc.sharing.share-note main flow 1, ADR 0036 point 2): a checkbox under the note editor of
// every share dialog that edits a note — core's folder/file dialog and Memories' — off by default, and enabled only
// while the note has text (a greeting with nothing in it has nothing to accept). Its value is sealed inside the note
// (`./shareNote.ts`), so the operator cannot tell which links ask.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqCheckbox from '../../shared/ui/VeyqCheckbox.vue'
import { hasLocaleText } from './shareNote'
import type { LocaleMap } from '../../shared/ui/localeMap'

const props = defineProps<{ note: LocaleMap; disabled?: boolean }>()
const mustAccept = defineModel<boolean>({ required: true })
const { t } = useI18n()

const hasText = computed(() => hasLocaleText(props.note))
</script>

<template>
  <label class="share-note-accept" :class="{ 'share-note-accept-off': !hasText }">
    <VeyqCheckbox
      v-model="mustAccept"
      input-id="share-note-must-accept"
      data-testid="share-note-must-accept"
      :disabled="disabled || !hasText"
    />
    <span class="share-note-accept-text">
      <span>{{ t('share.noteMustAccept') }}</span>
      <span class="share-note-accept-hint">{{ t('share.noteMustAcceptHint') }}</span>
    </span>
  </label>
</template>

<style scoped>
.share-note-accept {
  display: flex;
  align-items: flex-start;
  gap: 0.55rem;
  cursor: pointer;
}
.share-note-accept :deep(.veyq-checkbox) {
  flex: none;
  margin-top: 0.15rem;
}
.share-note-accept-off {
  cursor: default;
  opacity: 0.6;
}
.share-note-accept-text {
  display: grid;
  gap: 0.12rem;
  font-size: 0.92rem;
  line-height: 1.35;
}
.share-note-accept-hint {
  color: var(--app-muted);
  font-size: 0.82rem;
}
</style>
