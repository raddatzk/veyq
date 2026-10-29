<script setup lang="ts">
// uc.admin.operator-details / uc.admin.setup-server — what a Server Owner may owe (ADR 0093 § 14), told as information
// and never as a choice: nothing here is ticked or chosen, and nothing is refused or warned about over an empty value.
// The same block at first-run setup and beside the values in Server → Operator, so the two can never say different
// things. The Run docs' Moderation page says the same at more length.
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <div class="operator-duties" data-test="operator-duties">
    <p>{{ t('operatorDuties.exemption') }}</p>
    <p>{{ t('operatorDuties.owes') }}</p>
    <p>{{ t('operatorDuties.everyTenant') }}</p>
    <p>{{ t('operatorDuties.gap') }}</p>
    <p class="operator-duties__assessment">{{ t('operatorDuties.assessment') }}</p>
  </div>
</template>

<style scoped>
.operator-duties p {
  margin: 0 0 0.6rem;
  color: var(--app-muted);
  font-size: 0.92rem;
  line-height: 1.45;
}
.operator-duties p:last-child {
  margin-bottom: 0;
}
.operator-duties__assessment {
  font-weight: 600;
}
</style>
