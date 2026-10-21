<script setup lang="ts">
// The standing report of a Server Owner's step over content whose statement did not plainly reach everyone (ADR 0093
// § 7, `statementDelivery.ts`): who was not reached — or that the server cannot say, or that nobody was there to tell —
// what to do, and the statement as it was sent, read-only and copyable, so it can be passed on through the instance
// contact. Not a toast: it stays until the Server Owner dismisses it.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { StatementOutcome, type StatementReport } from './statementDelivery'
import { copyText } from '../../shared/inviteLink'

const props = defineProps<{ report: StatementReport }>()
const emit = defineEmits<{ dismiss: [] }>()
const { t } = useI18n()

const outcome = computed(() => props.report.delivery.outcome)
const severity = computed(() => (outcome.value === StatementOutcome.NobodyToTell ? 'info' : 'warn'))
const what = computed(() =>
  outcome.value === StatementOutcome.NotAllReached
    ? t('moderation.steps.delivery.notReached', { addresses: props.report.delivery.notReached.join(', ') })
    : outcome.value === StatementOutcome.NobodyToTell
      ? t('moderation.steps.delivery.nobodyToTell')
      : t('moderation.steps.delivery.unknown'),
)
const ground = computed(() => t(`moderation.steps.categories.${props.report.statement.ground}`))
const provision = computed(() => props.report.statement.provision?.trim() || '')
const facts = computed(() => props.report.statement.facts?.trim() || '')

const copied = ref(false)
async function copy() {
  const lines = [`${t('moderation.steps.statement.ground')}: ${ground.value}`]
  if (provision.value) lines.push(`${t('moderation.steps.statement.provision')}: ${provision.value}`)
  if (facts.value) lines.push(`${t('moderation.steps.statement.facts')}: ${facts.value}`)
  copied.value = await copyText(lines.join('\n'))
}
</script>

<template>
  <Message :severity="severity" :closable="true" class="statement-delivery" :data-outcome="outcome" @close="emit('dismiss')">
    <div class="statement-delivery__body">
      <p class="statement-delivery__what">{{ what }}</p>
      <dl class="statement-delivery__words">
        <dt>{{ t('moderation.steps.statement.ground') }}</dt>
        <dd class="statement-delivery__ground">{{ ground }}</dd>
        <template v-if="provision">
          <dt>{{ t('moderation.steps.statement.provision') }}</dt>
          <dd class="statement-delivery__provision">{{ provision }}</dd>
        </template>
        <template v-if="facts">
          <dt>{{ t('moderation.steps.statement.facts') }}</dt>
          <dd class="statement-delivery__facts">{{ facts }}</dd>
        </template>
      </dl>
      <div>
        <Button
          type="button"
          class="statement-delivery__copy"
          size="small"
          outlined
          :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
          :label="copied ? t('moderation.steps.delivery.copied') : t('moderation.steps.delivery.copy')"
          @click="copy"
        />
      </div>
    </div>
  </Message>
</template>

<style scoped>
.statement-delivery__body {
  display: grid;
  gap: 0.5rem;
}
.statement-delivery__what {
  margin: 0;
}
.statement-delivery__words {
  display: grid;
  gap: 0.15rem;
  margin: 0;
}
.statement-delivery__words dt {
  font-weight: 600;
}
.statement-delivery__words dd {
  margin: 0 0 0.35rem;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
