<script setup lang="ts">
// A member's identity-trust badge in the tenant's and groups' member lists (ADR 0028 Decision 3, uc.spaces.verify-identity
// and uc.spaces.enforce-verified-identities): "Verified" for a contact whose key was compared on their profile and still
// holds, "Key changed" for a directory key that differs from the pin - said louder when the pin was VERIFIED (ADR 0028 Decision 3).
// A `pinned` (trust-on-first-use) or unpinned contact shows nothing: that is the ordinary state of every member.
// One component for both panels, so the two lists cannot drift apart in what they say.
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
  <span v-else-if="trust?.state === 'changed'" class="veyq-badge changed" data-testid="member-trust-changed">
    <VeyqIcon name="exclamation-triangle" />{{ trust.pinVerified ? t('safety.badgeVerifiedChanged') : t('verify.badgeChanged') }}
  </span>
</template>
