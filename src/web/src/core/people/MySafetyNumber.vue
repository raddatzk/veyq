<script setup lang="ts">
// My own safety number on my own profile page (uc.spaces.enforce-verified-identities main flow 0, ADR 0028 Decision 3,
// ADR 0111 decision 14): the 6x5 digits, my chain code (two groups, from the head of my key chain) and a QR of both, for
// another member to read aloud or scan on their device. Nothing to edit.
// The key is MY vault's (`myIdentityPublicKeyB64`), never a directory round-trip: the number I show is the one my own
// device holds, which is exactly what the other side needs to compare against what the server served them. The head is
// my chain as served, walked and required to end at that same key (`myChainHead`) - a served chain that withholds my
// newest step is refused here rather than shown, so a compare cannot match a peer who was served the same short chain.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { myIdentityPublicKeyB64, safetyNumber } from '../../features/account/identityPins'
import { myChainHead } from '../../features/account/tenantTrust'
import { chainCode, safetyCodePayload } from '../../features/account/safetyCode'
import PairingCode from '../../features/account/pairing/PairingCode.vue'
import SafetyDigits from './SafetyDigits.vue'

const { t } = useI18n()

type Mine = { kind: 'loading' } | { kind: 'unavailable' } | { kind: 'ready'; number: string; chain: string; payload: string }
const mine = ref<Mine>({ kind: 'loading' })

onMounted(async () => {
  try {
    const key = myIdentityPublicKeyB64()
    const { head } = await myChainHead()
    mine.value = { kind: 'ready', number: safetyNumber(key), chain: chainCode(head), payload: safetyCodePayload(key, head) }
  } catch {
    // a locked vault, or a served chain that does not end at my key: said as such, nothing half-shown
    mine.value = { kind: 'unavailable' }
  }
})
</script>

<template>
  <section class="veyq-section my-safety" aria-labelledby="my-safety-title" data-testid="my-safety-number">
    <div class="veyq-section-header">
      <h2 id="my-safety-title">{{ t('safety.mineTitle') }}</h2>
    </div>
    <p class="veyq-form-copy">{{ t('safety.mineIntro') }}</p>
    <p v-if="mine.kind === 'loading'" class="veyq-form-copy" role="status">{{ t('people.loading') }}</p>
    <template v-else-if="mine.kind === 'ready'">
      <SafetyDigits :number="mine.number" />
      <div class="my-safety-chain" data-testid="my-chain-code">
        <div class="veyq-section-header"><h3>{{ t('safety.chainCode') }}</h3></div>
        <SafetyDigits :number="mine.chain" />
        <p class="veyq-form-copy">{{ t('safety.mineChainCodeHint') }}</p>
      </div>
      <PairingCode class="my-safety-code" :payload="mine.payload" :alt="t('safety.qrAlt')" :failed-text="t('safety.qrFailed')" />
    </template>
    <VeyqMessage v-else tone="error">{{ t('safety.mineUnavailable') }}</VeyqMessage>
  </section>
</template>

<style scoped>
.my-safety {
  max-width: 32rem;
}
.my-safety-code {
  justify-self: start;
}
.my-safety-chain {
  display: grid;
  gap: 0.25rem;
}
</style>
