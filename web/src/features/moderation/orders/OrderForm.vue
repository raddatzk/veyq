<script setup lang="ts">
// An authority's order, entered by a moderator (uc.moderation.authority-order; ADR 0093 § 12, ADR 0099 § 6) — above the
// queue, folded until asked for. What it keeps honest:
//   - a pasted link leaves as its id alone (`orders.ts`' `parseShareReference`): its fragment is the link's key;
//   - the reference is sealed in this browser to the moderation key, bound to the order's id;
//   - a removal order blocks for everyone at once, so the form asks first, naming that;
//   - what comes out is the server's answer rendered into the Annex text to copy (`orderForms.ts`, a DRAFT until a
//     qualified legal review, which its banner says); the recorded orders below open their references here.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useConfirm } from '../../../shared/state/useConfirm'
import { caseFailureKey } from '../caseFailure'
import { enterOrder, listOrders, openReferenceOf, type AuthorityOrder, type AuthorityOrderKind } from './orders'
import { formTime, renderOrderForm, type OrderFormLang } from './orderForms'

const emit = defineEmits<{ openCase: [caseId: string] }>()

const { t, locale } = useI18n()
const { ask } = useConfirm()

/** `YYYY-MM-DDTHH:MM` in this browser's zone, as a datetime-local input takes it. */
function localInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

const shown = ref(false)
const kind = ref<AuthorityOrderKind>('REMOVAL')
const share = ref('')
const reference = ref('')
const receivedAt = ref(localInput(new Date()))
const holdDays = ref('')
const deferUntil = ref('')
const busy = ref(false)
const error = ref('')
const result = ref<AuthorityOrder | null>(null)
const typedReference = ref('')
const copied = ref(false)
const recorded = ref<AuthorityOrder[] | null>(null)

const KINDS: AuthorityOrderKind[] = ['REMOVAL', 'PRESERVATION', 'INFORMATION']
const lang = computed<OrderFormLang>(() => (locale.value === 'de' ? 'de' : 'en'))

const form = computed(() => {
  const r = result.value
  if (!r) return null
  const which = !r.found ? 'annexIII' : r.blockedAt ? 'annexII' : null
  if (!which) return null
  return {
    which,
    text: renderOrderForm(which, lang.value, {
      reference: typedReference.value, orderId: r.id, shareId: r.shareId, receivedAt: r.receivedAt, blockedAt: r.blockedAt,
      provider: r.providerName, contact: r.contactPoint,
    }),
  } as const
})

const references = computed(() => new Map((recorded.value ?? []).map((o) => [o.id, openReferenceOf(o)])))

async function loadRecorded() {
  try {
    recorded.value = await listOrders()
  } catch (e) {
    error.value = t(await caseFailureKey(e))
  }
}

function toggle() {
  shown.value = !shown.value
  if (shown.value && recorded.value === null) void loadRecorded()
}

async function submit() {
  error.value = ''
  copied.value = false
  const accepted = await ask({
    header: t('moderation.orders.title'),
    message: kind.value === 'REMOVAL' ? t('moderation.orders.confirmRemoval') : t('moderation.orders.confirmHold'),
    acceptLabel: t('moderation.orders.submit'),
    acceptIcon: 'pi pi-ban',
  })
  if (!accepted) return
  busy.value = true
  try {
    const entered = await enterOrder({
      kind: kind.value,
      share: share.value,
      reference: reference.value,
      receivedAt: new Date(receivedAt.value),
      holdDays: holdDays.value.trim() ? Number(holdDays.value) : null,
      deferStatementUntil: deferUntil.value ? new Date(deferUntil.value) : null,
    })
    typedReference.value = reference.value.trim()
    result.value = entered
    share.value = ''
    reference.value = ''
    await loadRecorded()
  } catch (e) {
    error.value = t(await caseFailureKey(e))
  } finally {
    busy.value = false
  }
}

async function copy() {
  if (!form.value) return
  await navigator.clipboard.writeText(form.value.text)
  copied.value = true
}
</script>

<template>
  <div class="veyq-section moderation-orders">
    <div class="veyq-section-header">
      <h3>{{ t('moderation.orders.title') }}</h3>
      <Button
        severity="secondary"
        class="moderation-orders-toggle"
        icon="pi pi-file-edit"
        :label="shown ? t('moderation.orders.close') : t('moderation.orders.open')"
        @click="toggle"
      />
    </div>
    <template v-if="shown">
      <p class="veyq-form-copy">{{ t('moderation.orders.copy') }}</p>
      <Message v-if="error" severity="error" :closable="false" class="form-message moderation-orders-error">{{ error }}</Message>
      <form class="veyq-form moderation-order-form" @submit.prevent="submit">
        <label for="moderation-order-kind">{{ t('moderation.orders.kind') }}</label>
        <select id="moderation-order-kind" v-model="kind" class="moderation-order-kind" :disabled="busy">
          <option v-for="k in KINDS" :key="k" :value="k">{{ t(`moderation.orders.kinds.${k}`) }}</option>
        </select>
        <label for="moderation-order-share">{{ t('moderation.orders.share') }}</label>
        <input id="moderation-order-share" v-model="share" class="p-inputtext moderation-order-share" autocomplete="off" spellcheck="false" :disabled="busy" />
        <span class="veyq-form-copy">{{ t('moderation.orders.shareHint') }}</span>
        <label for="moderation-order-reference">{{ t('moderation.orders.reference') }}</label>
        <input id="moderation-order-reference" v-model="reference" class="p-inputtext moderation-order-reference" autocomplete="off" :disabled="busy" />
        <span class="veyq-form-copy">{{ t('moderation.orders.referenceHint') }}</span>
        <label for="moderation-order-received">{{ t('moderation.orders.receivedAt') }}</label>
        <input id="moderation-order-received" v-model="receivedAt" type="datetime-local" class="p-inputtext moderation-order-received" :disabled="busy" />
        <template v-if="kind === 'INFORMATION'">
          <label for="moderation-order-hold-days">{{ t('moderation.orders.holdDays') }}</label>
          <input id="moderation-order-hold-days" v-model="holdDays" class="p-inputtext moderation-order-hold-days" inputmode="numeric" :disabled="busy" />
        </template>
        <template v-if="kind === 'REMOVAL'">
          <label for="moderation-order-defer">{{ t('moderation.orders.deferUntil') }}</label>
          <input id="moderation-order-defer" v-model="deferUntil" type="datetime-local" class="p-inputtext moderation-order-defer" :disabled="busy" />
        </template>
        <Button type="submit" severity="danger" class="moderation-order-submit" icon="pi pi-ban" :label="t('moderation.orders.submit')" :loading="busy" />
      </form>

      <div v-if="result" class="moderation-order-result">
        <Message v-if="!result.found" severity="warn" :closable="false" class="form-message moderation-order-not-found">{{ t('moderation.orders.notFound') }}</Message>
        <template v-else>
          <Message v-if="result.blockedAt" severity="success" :closable="false" class="form-message moderation-order-blocked">
            {{ t('moderation.orders.blocked', { time: formTime(result.blockedAt) }) }}
          </Message>
          <p v-if="result.holdUntil" class="veyq-form-copy">{{ t('moderation.orders.held', { time: formTime(result.holdUntil) }) }}</p>
          <p v-if="result.statementDeferredUntil" class="veyq-form-copy">{{ t('moderation.orders.deferred', { time: formTime(result.statementDeferredUntil) }) }}</p>
        </template>
        <template v-if="form">
          <strong>{{ t(`moderation.orders.${form.which}`) }}</strong>
          <textarea class="moderation-order-text" :value="form.text" rows="12" readonly />
          <Button severity="secondary" class="moderation-order-copy" icon="pi pi-copy" :label="copied ? t('moderation.orders.copied') : t('moderation.orders.copyText')" @click="copy" />
        </template>
        <Button v-if="result.caseId" severity="secondary" class="moderation-order-case" icon="pi pi-folder-open" :label="t('moderation.orders.openCase')" @click="emit('openCase', result.caseId)" />
      </div>

      <h4>{{ t('moderation.orders.recorded') }}</h4>
      <p v-if="recorded && recorded.length === 0" class="veyq-form-copy">{{ t('moderation.orders.none') }}</p>
      <div v-else-if="recorded" class="veyq-list">
        <div v-for="o in recorded" :key="o.id" class="veyq-list-row moderation-order-row">
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ t(`moderation.orders.kinds.${o.kind}`) }}</strong>
            <span class="veyq-row-subtitle">{{ references.get(o.id) ?? t('moderation.orders.referenceSealed') }} · {{ formTime(o.receivedAt) }}</span>
            <div class="veyq-row-meta">
              <span class="veyq-badge" :class="{ muted: !o.found }">{{ o.found ? t('moderation.orders.found') : t('moderation.orders.notFoundShort') }}</span>
            </div>
          </div>
          <Button v-if="o.caseId" severity="secondary" class="moderation-order-row-case" icon="pi pi-folder-open" :label="t('moderation.orders.openCase')" @click="emit('openCase', o.caseId)" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.moderation-order-text {
  width: 100%;
  font-family: var(--font-mono, monospace);
}
</style>
