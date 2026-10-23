<script setup lang="ts">
// What a password-choosing dialog shows of the breached-password check (useBreachCheck, passwordWarning.ts): the
// one-sentence note of what leaves the device, always; a hit's warning with its explicit "Use it anyway"; and the
// one line saying a check could not run. One component for the dialogs that choose a password, so the wording and
// the control cannot drift apart between them.
import { useI18n } from 'vue-i18n'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqCheckbox from '../../shared/ui/VeyqCheckbox.vue'
import type { BreachState } from './passwordWarning'

defineProps<{
  state: BreachState
  /** Prefix of the "Use it anyway" checkbox's id — unique per dialog. */
  idPrefix: string
  disabled?: boolean
}>()
const useAnyway = defineModel<boolean>('useAnyway', { required: true })

const { t } = useI18n()
</script>

<template>
  <VeyqMessage v-if="state.kind === 'hit' || state.kind === 'accepted'" tone="warn">
    {{ t('password.pwnedWarning', { count: state.count }) }}
    <span class="checkbox-row breach-use-anyway">
      <VeyqCheckbox v-model="useAnyway" :input-id="`${idPrefix}-use-anyway`" :disabled="disabled" />
      <label :for="`${idPrefix}-use-anyway`">{{ t('password.pwnedAnyway') }}</label>
    </span>
  </VeyqMessage>
  <VeyqMessage v-else-if="state.kind === 'unavailable'" tone="info">{{
    t('password.pwnedUnavailable')
  }}</VeyqMessage>
</template>

<style scoped>
.breach-use-anyway {
  margin-top: 0.5rem;
}
</style>
