<script setup lang="ts">
// My own safety number on my own profile page (uc.spaces.enforce-verified-identities main flow 0, ADR 0028 Decision 3):
// the 6x5 digits and a QR of my fingerprint, for another member to read aloud or scan on their device. Nothing to edit.
// The key is MY vault's (`myIdentityPublicKeyB64`), never a directory round-trip: the number I show is the one my own
// device holds, which is exactly what the other side needs to compare against what the server served them.
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import { myIdentityPublicKeyB64, safetyNumber } from '../../features/account/identityPins'
import { safetyCodePayload } from '../../features/account/safetyCode'
import PairingCode from '../../features/account/pairing/PairingCode.vue'
import SafetyDigits from './SafetyDigits.vue'

const { t } = useI18n()

function readMine(): { number: string; payload: string } | null {
  try {
    const key = myIdentityPublicKeyB64()
    return { number: safetyNumber(key), payload: safetyCodePayload(key) }
  } catch {
    return null // a locked vault - the profile is a route of the unlocked shell, so this is a defect, said as such
  }
}
const mine = readMine()
</script>

<template>
  <section class="veyq-section my-safety" aria-labelledby="my-safety-title" data-testid="my-safety-number">
    <div class="veyq-section-header">
      <h2 id="my-safety-title">{{ t('safety.mineTitle') }}</h2>
    </div>
    <p class="veyq-form-copy">{{ t('safety.mineIntro') }}</p>
    <template v-if="mine">
      <SafetyDigits :number="mine.number" />
      <PairingCode class="my-safety-code" :payload="mine.payload" :alt="t('safety.qrAlt')" :failed-text="t('safety.qrFailed')" />
    </template>
    <Message v-else severity="error" :closable="false">{{ t('safety.mineUnavailable') }}</Message>
  </section>
</template>

<style scoped>
.my-safety {
  max-width: 32rem;
}
.my-safety-code {
  justify-self: start;
}
</style>
