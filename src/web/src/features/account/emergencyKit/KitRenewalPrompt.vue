<script setup lang="ts">
// The one-time "a clearer Emergency Kit exists" notice (ADR 0157 decision 5, uc.account.renew-emergency-kit step 2) —
// FinishProfilePrompt's pattern: a small dismissible notice in the app shell that links to Settings → Security, where
// `EmergencyKitStatus` offers the new kit. It shows only while the account's last kit is of an older layout than this
// client prints, says that the kit the person has keeps working, never blocks, and never returns once dismissed in this
// browser for this account: "Not now" and following the link both store the dismissal (`kitRenewalDismissed`, the
// layout it spoke of). The shell mounts it only while no other notice is on (`AppShell.vue`, `otherNoticesOn`). It reads
// the layout through the one read of this unlock (`kitRecord.ts`), and not at all once dismissed.
import { computed, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import { account } from '../../../app/account'
import { vaultState } from '../../../app/vault'
import { KIT_LAYOUT } from './emergencyKit'
import { ensureKitLayout, kitLayoutOlder } from './kitRecord'
import { kitRenewalDismissed } from './kitRenewalDismissed'

const { t } = useI18n()
const router = useRouter()

const dismissed = computed(() => Number(kitRenewalDismissed.value.value) >= KIT_LAYOUT)
const show = computed(() => !dismissed.value && kitLayoutOlder.value)

// Read once the account is known (the dismissal is keyed on it) and the vault is open; the read is shared and kept.
watchEffect(() => {
  if (!account.userId || !vaultState.unlocked || dismissed.value) return
  void ensureKitLayout()
})

function dismiss() {
  kitRenewalDismissed.set(String(KIT_LAYOUT))
}
function openKit() {
  dismiss()
  void router.push('/settings/security')
}
</script>

<template>
  <section v-if="show" class="kit-renewal" role="region" :aria-label="t('kit.notice.title')" data-testid="kit-renewal">
    <div class="kit-renewal-text">
      <strong>{{ t('kit.notice.title') }}</strong>
      <span>{{ t(`kit.layoutNews.${KIT_LAYOUT}`) }} {{ t('kit.status.keepsWorking') }}</span>
    </div>
    <div class="kit-renewal-actions">
      <VeyqButton type="button" :label="t('kit.notice.open')" icon="id-card" size="small" data-testid="kit-renewal-open" @click="openKit" />
      <VeyqButton type="button" :label="t('kit.notice.dismiss')" variant="secondary" appearance="text" size="small" data-testid="kit-renewal-dismiss" @click="dismiss" />
    </div>
  </section>
</template>

<style scoped>
/* The in-app notice's look (`FinishProfilePrompt.vue`): a neutral panel, never a status colour — it suggests, it does not warn. */
.kit-renewal {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem 1rem;
  margin-bottom: 1rem;
  padding: 0.7rem 0.9rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
}
.kit-renewal-text {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
  flex: 1 1 18rem;
}
.kit-renewal-text span {
  color: var(--app-muted);
  font-size: 0.9rem;
}
.kit-renewal-actions {
  display: flex;
  gap: 0.4rem;
}
</style>
