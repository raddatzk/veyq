<script setup lang="ts">
// A member's identity-trust badge in the tenant's and groups' member lists (ADR 0028 Decision 3, uc.spaces.verify-identity
// and uc.spaces.enforce-verified-identities): "Verified" for a contact whose key was compared on their profile and still
// holds, and — until cr-trust-records-in-the-tenant — the one key state a list shows: an ANOMALY, a served key the
// contact's key chain does not reach from the head this account pinned (cr-secure-the-account decision 9), with the note
// that nothing is shared with it. A `pinned` (trust-on-first-use, or a key the chain reached) or unpinned contact shows
// nothing: that is the ordinary state of every member. One component for both panels, so the two lists cannot drift
// apart in what they say.
import { useI18n } from 'vue-i18n'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import type { ContactTrust } from '../account/identityPins'

defineProps<{ trust?: ContactTrust }>()
const { t } = useI18n()
</script>

<template>
  <span v-if="trust?.state === 'verified'" class="veyq-badge success" data-testid="member-trust-verified">
    <VeyqIcon name="verified" />{{ t('safety.stateVerified') }}
  </span>
  <template v-else-if="trust?.state === 'anomaly'">
    <span class="veyq-badge changed" data-testid="member-trust-anomaly">
      <VeyqIcon name="exclamation-triangle" />{{ t('trust.anomaly') }}
    </span>
    <span class="settings-muted" data-testid="member-trust-anomaly-note">{{ t('trust.anomalyNote') }}</span>
  </template>
</template>
