<script setup lang="ts">
// An owner's notice that a compromise rotation queued on this space needs an owner
// (uc.spaces.complete-queued-rotation E2/E8, ADR 0060 Decision 6): a member re-secured their account, and the rotation
// that shuts their old key out of this space is stuck. The value comes from the owner's own scope row (`compromiseRotation`, owner-only on
// the server), so a plain member never sees this.
//
//  - `failed` — it dead-lettered: offer the re-queue. The server excludes the rotated account again and not this
//    caller, so this device's own worker can claim it on its next pass.
//  - `stalled` — no owner may complete it: the remedy is promoting a co-owner (E4), which the member list below
//    already offers; no action of its own here.
//
// Both say in plain words that until the re-key lands the old key still reads what is new in this space —
// forward secrecy, not repair. Markup mirrors the panels' own warn `Message` with an action inside (the trust-refusal
// notice + its `TrustResetLink`), so this adds no new UI.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
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
  <Message v-if="state" severity="warn" :closable="false" class="compromise-rotation-notice">
    {{ t(`members.compromiseRotation.${state}`) }}
    <Button
      v-if="state === 'failed'"
      type="button"
      :label="t('members.compromiseRotationRequeue')"
      icon="pi pi-replay"
      size="small"
      outlined
      :loading="busy"
      @click="requeue"
    />
    <small v-if="error">{{ error }}</small>
  </Message>
</template>
