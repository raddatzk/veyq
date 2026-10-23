<script setup lang="ts">
// uc.account.onboard-device — the terminal screen both receiving cards end on when the grant did not arrive
// (`useGrantReceiver`'s `gone` / `occupied` / `failed`). None of them offers a way back into the ceremony: the
// only way out is back to the sign-in form, where a fresh start is a fresh channel (E2).
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'

defineProps<{
  step: string
  error: string
  /** What `gone` tells the person to do next — the one line the two directions word differently. */
  goneHint: string
}>()

const emit = defineEmits<{ (e: 'back'): void }>()

const { t } = useI18n()
</script>

<template>
  <!-- `gone` is E1's single message. It is also what an adopt `401` reaches, because the grant is spent by then,
       and — signing in with a QR code — where a REFUSED code lands, since this device cannot be told it was wrong. -->
  <VeyqMessage v-if="step === 'gone'" tone="warn" class="form-message">
    {{ t('pairing.gone') }}
  </VeyqMessage>
  <!-- Not folded into the message above, because the remedy is different: this browser is already signed in
       somewhere, and starting the ceremony over would not change that. -->
  <VeyqMessage v-else-if="step === 'occupied'" tone="warn" class="form-message">
    {{ t('pairing.joinOccupied') }}
  </VeyqMessage>
  <VeyqMessage v-else tone="error" class="form-message">{{ error }}</VeyqMessage>

  <p v-if="step === 'gone'" class="field-hint">{{ goneHint }}</p>

  <VeyqButton type="button" :label="t('pairing.joinBack')" icon="arrow-left" @click="emit('back')" />
</template>
