<script setup lang="ts">
// A member's identity-trust badge in the tenant's and groups' member lists (ADR 0028 Addendum 4, uc.spaces.verify-identity
// and uc.spaces.enforce-verified-identities): "Verified" for a contact whose key was compared on their profile and still
// holds, "Key changed" for a directory key that differs from the pin - said louder when the pin was VERIFIED (Addendum 3).
// A `pinned` (trust-on-first-use) or unpinned contact shows nothing: that is the ordinary state of every member.
// One component for both panels, so the two lists cannot drift apart in what they say.
import { useI18n } from 'vue-i18n'
import type { ContactTrust } from '../account/identityPins'

defineProps<{ trust?: ContactTrust }>()
const { t } = useI18n()
</script>

<template>
  <span v-if="trust?.state === 'verified'" class="veyq-badge success" data-testid="member-trust-verified">
    <i class="pi pi-verified" aria-hidden="true"></i>{{ t('safety.stateVerified') }}
  </span>
  <span v-else-if="trust?.state === 'changed'" class="veyq-badge changed" data-testid="member-trust-changed">
    <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ trust.pinVerified ? t('safety.badgeVerifiedChanged') : t('verify.badgeChanged') }}
  </span>
</template>
