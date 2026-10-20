<script setup lang="ts">
// uc.account.onboard-device — the terminal screen both receiving cards end on when the grant did not arrive
// (`useGrantReceiver`'s `gone` / `occupied` / `failed`). None of them offers a way back into the ceremony: the
// only way out is back to the sign-in form, where a fresh start is a fresh channel (E2).
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'

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
  <Message v-if="step === 'gone'" severity="warn" :closable="false" class="form-message">
    {{ t('pairing.gone') }}
  </Message>
  <!-- Not folded into the message above, because the remedy is different: this browser is already signed in
       somewhere, and starting the ceremony over would not change that. -->
  <Message v-else-if="step === 'occupied'" severity="warn" :closable="false" class="form-message">
    {{ t('pairing.joinOccupied') }}
  </Message>
  <Message v-else severity="error" :closable="false" class="form-message">{{ error }}</Message>

  <p v-if="step === 'gone'" class="field-hint">{{ goneHint }}</p>

  <Button type="button" :label="t('pairing.joinBack')" icon="pi pi-arrow-left" @click="emit('back')" />
</template>
