<script setup lang="ts">
// The statement of reasons on a blocked item, node or link (ADR 0093 § 7, uc.moderation.object-to-a-measure steps 5, 8,
// 10): shown only to a member it names — everyone else keeps the bare "Blocked by the operator" — rendered from the one
// template in its form, the facts opened with the member's identity key, and the objection: its form once, then its
// state and answer. It names the host, never the moderator.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { loadStatements, objectionFailure, objectTo, openFacts, statementFor } from './statements'
import { renderStatement } from './template'

const props = defineProps<{ targetId?: string | null; shareId?: string | null }>()

const { t, locale } = useI18n()

const text = ref('')
const busy = ref(false)
const error = ref('')
const sent = ref(false)

const statement = computed(() => statementFor({ targetId: props.targetId, shareId: props.shareId }))
const lang = computed(() => (locale.value.startsWith('de') ? 'de' : 'en'))
const day = (d: Date) => d.toLocaleDateString(locale.value, { year: 'numeric', month: 'long', day: 'numeric' })

const facts = computed(() => (statement.value ? openFacts(statement.value) : null))

const passages = computed(() => {
  const s = statement.value
  if (!s) return []
  return renderStatement(lang.value, {
    measureKind: s.measureKind,
    roles: s.roles,
    basis: s.basis,
    ground: s.ground,
    form: s.form,
    date: day(s.sentAt),
    contact: s.contactAddress ?? null,
    hasFacts: s.sealedFacts != null,
    provision: s.provision,
  })
})

const ERRORS: Record<string, string> = {
  objection_exists: 'statement.errors.objectionExists',
  measure_not_in_force: 'statement.errors.measureNotInForce',
}

async function submit() {
  error.value = ''
  if (text.value.trim() === '') {
    error.value = t('statement.objectEmpty')
    return
  }
  busy.value = true
  try {
    await objectTo(statement.value!, text.value)
    text.value = ''
    sent.value = true
  } catch (e) {
    error.value = t(ERRORS[(await objectionFailure(e)) ?? ''] ?? 'statement.errors.failed')
  } finally {
    busy.value = false
  }
}

onMounted(() => loadStatements())
</script>

<template>
  <section v-if="statement" class="veyq-form statement-notice">
    <strong class="statement-title">{{ t('statement.title') }}</strong>
    <template v-for="p in passages" :key="p.id">
      <p class="veyq-form-copy statement-passage" :data-passage="p.id">{{ p.text }}</p>
      <p v-if="p.id === 'facts'" class="veyq-form-copy statement-facts">{{ facts ?? t('statement.factsUnopened') }}</p>
    </template>

    <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    <Message v-if="sent" severity="success" :closable="false" class="form-message">{{ t('statement.objectSent') }}</Message>

    <template v-if="statement.objection">
      <p v-if="!statement.objection.outcome" class="veyq-form-copy statement-objection-state">
        {{ statement.objection.mine ? t('statement.waitingMine', { time: day(statement.objection.receivedAt) }) : t('statement.waitingOther', { time: day(statement.objection.receivedAt) }) }}
      </p>
      <template v-else>
        <p class="veyq-form-copy statement-objection-state">
          {{ statement.objection.outcome === 'LIFTED' ? t('statement.lifted', { time: day(statement.objection.decidedAt!) }) : t('statement.upheld', { time: day(statement.objection.decidedAt!) }) }}
        </p>
        <p class="veyq-form-copy statement-objection-review">{{ statement.objection.ownReview ? t('statement.ownReview') : t('statement.otherReview') }}</p>
      </template>
    </template>
    <form v-else-if="statement.canObject" class="veyq-form statement-objection" @submit.prevent="submit">
      <label :for="`statement-objection-${statement.id}`">{{ t('statement.objectLabel') }}</label>
      <textarea :id="`statement-objection-${statement.id}`" v-model="text" class="statement-objection-text" rows="4" maxlength="2000" :disabled="busy" />
      <span class="veyq-form-copy">{{ t('statement.objectHint') }}</span>
      <Button type="submit" class="statement-objection-submit" icon="pi pi-send" :label="t('statement.objectSubmit')" :loading="busy" />
    </form>
    <p v-else-if="statement.liftedAt" class="veyq-form-copy">{{ t('statement.liftedAt', { time: day(statement.liftedAt) }) }}</p>
  </section>
</template>
