<script setup lang="ts">
// An owner's notice that a rotation queued on this space needs an owner (uc.spaces.complete-queued-rotation E2/E8): a
// tenant member's removal cut their membership here, and the rotation that shuts their key out of this space is stuck.
// The value comes from the owner's own scope row (`compromiseRotation`, owner-only on the server), so a plain member
// never sees this.
//
//  - `failed` — it dead-lettered: offer the re-queue. The server re-originates it excluding nobody, so this device's
//    own worker can claim it on its next pass.
//  - `stalled` — no owner may complete it: the remedy is promoting a co-owner (E2), which the member list below
//    already offers; no action of its own here.
//
// Both say in plain words that until the re-key lands the old key still reads what is new in this space —
// forward secrecy, not repair. Markup mirrors the panels' own warn `Message` with an action inside (the trust-refusal
// notice + its `TrustResetLink`), so this adds no new UI.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { requeueScopeRotation } from './scopes'
import type { CompromiseRotation } from '../../api/generated/scopes'

const props = defineProps<{ scopeId: string; state: CompromiseRotation | null }>()
const emit = defineEmits<{ requeued: [state: CompromiseRotation | null] }>()

const { t } = useI18n()
const busy = ref(false)
const error = ref('')

async function requeue() {
  error.value = ''
  busy.value = true
  try {
    emit('requeued', await requeueScopeRotation(props.scopeId))
  } catch {
    error.value = t('members.compromiseRotationRequeueFailed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <VeyqMessage v-if="state" tone="warn" class="compromise-rotation-notice">
    {{ t(`members.compromiseRotation.${state}`) }}
    <VeyqButton
      v-if="state === 'failed'"
      :label="t('members.compromiseRotationRequeue')"
      icon="replay"
      size="small"
      appearance="outlined"
      :loading="busy"
      @click="requeue"
    />
    <small v-if="error">{{ error }}</small>
  </VeyqMessage>
</template>
