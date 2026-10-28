<script setup lang="ts">
// A member's identity-trust badge in the tenant's and groups' member lists (ADR 0111 decision 15, uc.spaces.verify-identity
// and uc.spaces.enforce-verified-identities), from `tenantBadges` of the active tenant (`tenantTrust.ts`): "Verified" when
// someone's verification of the member's current head counts in this tenant, "New" while this account first saw them less
// than two weeks ago, and an ANOMALY - a key this tenant does not anchor (no join record for a non-owner, a record that
// does not verify, a key the chain does not reach) - with the note that nothing is shared with it. "Not verified" is the
// ordinary state of every member and shows nothing in a list; the profile says it. Display only: nothing here blocks
// anybody. One component for both panels, so the two lists cannot drift apart in what they say.
import { useI18n } from 'vue-i18n'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import type { MemberBadge } from '../account/tenantTrust'

defineProps<{ badge?: MemberBadge }>()
const { t } = useI18n()
</script>

<template>
  <span v-if="badge === 'verified'" class="veyq-badge success" data-testid="member-trust-verified">
    <VeyqIcon name="verified" />{{ t('safety.stateVerified') }}
  </span>
  <span v-else-if="badge === 'new'" class="veyq-badge" data-testid="member-trust-new">{{ t('safety.stateNew') }}</span>
  <template v-else-if="badge === 'anomaly'">
    <span class="veyq-badge changed" data-testid="member-trust-anomaly">
      <VeyqIcon name="exclamation-triangle" />{{ t('trust.anomaly') }}
    </span>
    <span class="settings-muted" data-testid="member-trust-anomaly-note">{{ t('trust.anomalyNote') }}</span>
  </template>
</template>
