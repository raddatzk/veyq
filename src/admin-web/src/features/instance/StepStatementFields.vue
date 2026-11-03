<script setup lang="ts">
// The statement of reasons' fields for a Server Owner's own step over content (ADR 0093 § 7,
// uc.moderation.object-to-a-measure A7): the ground, the law or clause relied on, and what was found — the sign-in block on a proposal and the
// tenant's suspension both show them. What the fields become is `stepStatement.ts`'s; the facts and the provision are
// required of every statement (ADR 0093 § 7, one form), which the hints say and the server refuses. The layer's
// controls, as src/web/'s decision panel has them (`CaseDetail.vue`'s statement).
// Without instance mail the statement reaches nobody, so the fields say so before the step — read when they open, best
// effort (`statementMailNotSetUp`): a failed read says nothing, and the step's answer still reports what the mail reached.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqSelect from '../../shared/ui/VeyqSelect.vue'
import VeyqTextarea from '../../shared/ui/VeyqTextarea.vue'
import { GROUNDS, type StepStatementDraft } from './stepStatement'
import { statementMailNotSetUp } from './instanceMail'

const draft = defineModel<StepStatementDraft>({ required: true })
defineProps<{ idPrefix: string; disabled?: boolean }>()
const { t } = useI18n()
const noMail = ref(false)
onMounted(async () => { noMail.value = await statementMailNotSetUp() })
const groundOptions = computed(() => GROUNDS.map((g) => ({ label: t(`moderation.steps.categories.${g}`), value: g })))
</script>

<template>
  <div class="veyq-form step-statement">
    <strong>{{ t('moderation.steps.statement.title') }}</strong>
    <p class="settings-muted">{{ t('moderation.steps.statement.copy') }}</p>
    <label :for="`${idPrefix}-ground`">{{ t('moderation.steps.statement.ground') }}</label>
    <VeyqSelect v-model="draft.ground" :input-id="`${idPrefix}-ground`" class="step-statement__ground" :options="groundOptions" :disabled="disabled" />
    <label :for="`${idPrefix}-provision`">{{ t('moderation.steps.statement.provision') }}</label>
    <VeyqInput :id="`${idPrefix}-provision`" v-model="draft.provision" class="step-statement__provision" maxlength="200" :disabled="disabled" />
    <small class="settings-muted">{{ t('moderation.steps.statement.provisionHint') }}</small>
    <label :for="`${idPrefix}-facts`">{{ t('moderation.steps.statement.facts') }}</label>
    <VeyqTextarea :id="`${idPrefix}-facts`" v-model="draft.facts" class="step-statement__facts" rows="4" maxlength="4000" :disabled="disabled" />
    <small class="settings-muted">{{ t('moderation.steps.statement.factsHint') }}</small>
    <VeyqMessage v-if="noMail" tone="warn" class="step-statement__no-mail">{{ t('moderation.steps.statement.noMail') }}</VeyqMessage>
  </div>
</template>
