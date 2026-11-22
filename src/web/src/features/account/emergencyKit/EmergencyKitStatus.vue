<script setup lang="ts">
// Which Emergency Kit the account has (uc.account.renew-emergency-kit step 2, ADR 0157 decision 5) — a section of the
// settings hub's Security rubric, mounted by KeyRotationPanel after the address (a `Section`, like EmailAddressSection:
// no `veyq-surface` of its own). Current, or "a clearer kit exists" with the newest layout's sentence, that the kit the
// person has keeps working, and **Make a new kit** (`KitRenewalDialog`). It never blocks and never warns: a kit of an
// older layout opens the account as before. The layout is the one read of this unlock (`kitRecord.ts`), shared with the
// shell's notice; a read that failed says so quietly and offers nothing.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import KitRenewalDialog from './KitRenewalDialog.vue'
import { KIT_LAYOUT } from './emergencyKit'
import { ensureKitLayout, kitLayoutOlder, kitLayoutState } from './kitRecord'

const { t } = useI18n()
const renewing = ref(false)

onMounted(() => void ensureKitLayout())
</script>

<template>
  <section class="settings-form" data-testid="kit-status">
    <div class="settings-section-header">
      <div>
        <h2>{{ t('kit.status.title') }}</h2>
        <p>{{ t('kit.status.sub') }}</p>
      </div>
    </div>

    <template v-if="kitLayoutOlder">
      <VeyqMessage tone="info" data-testid="kit-status-older">
        <strong>{{ t('kit.status.older') }}</strong>
        {{ t(`kit.layoutNews.${KIT_LAYOUT}`) }}
      </VeyqMessage>
      <p class="kit-status-keeps">{{ t('kit.status.keepsWorking') }}</p>
      <VeyqButton type="button" :label="t('kit.status.make')" icon="id-card" data-testid="kit-status-make" @click="renewing = true" />
    </template>
    <VeyqMessage v-else-if="kitLayoutState.layout !== null" tone="success" data-testid="kit-status-current">{{ t('kit.status.current') }}</VeyqMessage>
    <p v-else-if="kitLayoutState.failed" class="settings-muted" data-testid="kit-status-unreadable">{{ t('kit.status.unreadable') }}</p>

    <KitRenewalDialog v-model:visible="renewing" />
  </section>
</template>

<style scoped>
.kit-status-keeps {
  margin: 0.75rem 0;
}
</style>
