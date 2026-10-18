<script setup lang="ts">
// The statement of reasons' fields for a Server Owner's own step over content (ADR 0093 § 7,
// uc.moderation.object-to-a-measure A7): the ground, the law or clause relied on, and what was found — the sign-in block on a proposal and the
// tenant's suspension both show them. What the fields become is `stepStatement.ts`'s; what the mode requires is the
// server's to refuse, so the hints say when each is required rather than the form guessing the mode. Native controls,
// as web/'s decision panel has them (`CaseDetail.vue`'s statement).
import { useI18n } from 'vue-i18n'
import { GROUNDS, type StepStatementDraft } from './stepStatement'

const draft = defineModel<StepStatementDraft>({ required: true })
defineProps<{ idPrefix: string; disabled?: boolean }>()
const { t } = useI18n()
</script>

<template>
  <div class="veyq-form step-statement">
    <strong>{{ t('moderation.steps.statement.title') }}</strong>
    <p class="settings-muted">{{ t('moderation.steps.statement.copy') }}</p>
    <label :for="`${idPrefix}-ground`">{{ t('moderation.steps.statement.ground') }}</label>
    <select :id="`${idPrefix}-ground`" v-model="draft.ground" class="p-inputtext step-statement__ground" :disabled="disabled">
      <option v-for="g in GROUNDS" :key="g" :value="g">{{ t(`moderation.steps.categories.${g}`) }}</option>
    </select>
    <label :for="`${idPrefix}-provision`">{{ t('moderation.steps.statement.provision') }}</label>
    <input :id="`${idPrefix}-provision`" v-model="draft.provision" type="text" class="p-inputtext step-statement__provision" maxlength="200" :disabled="disabled" />
    <small class="settings-muted">{{ t('moderation.steps.statement.provisionHint') }}</small>
    <label :for="`${idPrefix}-facts`">{{ t('moderation.steps.statement.facts') }}</label>
    <textarea :id="`${idPrefix}-facts`" v-model="draft.facts" class="p-inputtext step-statement__facts" rows="4" maxlength="4000" :disabled="disabled" />
    <small class="settings-muted">{{ t('moderation.steps.statement.factsHint') }}</small>
    <small class="settings-muted step-statement__no-mail">{{ t('moderation.steps.statement.noMail') }}</small>
  </div>
</template>
