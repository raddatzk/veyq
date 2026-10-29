<script setup lang="ts">
// uc.moderation.authority-order (admin-web, ADR 0093 § 12, ADR 0099 § 6) — Server → Moderation: the Server Owner's door
// to an authority's order, without a key. What it keeps honest:
//   - a pasted link leaves as its id alone (`orders.ts`' `parseShareReference`): its fragment is the link's key;
//   - the reference is sealed in this browser to the moderation key's public half; the server never reads it;
//   - a removal order blocks for everyone at once, so the panel asks first, naming that;
//   - what comes out is the server's answer, rendered into the Annex text to copy (`orderForms.ts`).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqSelect from '../../shared/ui/VeyqSelect.vue'
import VeyqTextarea from '../../shared/ui/VeyqTextarea.vue'
import { enterOrder, OrderInvalid, type AuthorityOrder, type OrderKind } from './orders'
import { formTime, renderOrderForm, type OrderFormLang } from './orderForms'
import { useConfirm } from '../../shared/state/useConfirm'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'

const { t, locale } = useI18n()
const on401 = handle401(useRouter())
const { ask } = useConfirm()

/** `YYYY-MM-DDTHH:MM` in this browser's zone, as a datetime-local input takes it. */
function localInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

const kind = ref<OrderKind>('REMOVAL')
const share = ref('')
const reference = ref('')
const receivedAt = ref(localInput(new Date()))
const holdDays = ref('')
const deferUntil = ref('')
const error = ref('')
const result = ref<AuthorityOrder | null>(null)
// The reference as typed, for the form text: the server holds it sealed and answers it sealed.
const typedReference = ref('')
const copied = ref<'annexII' | 'annexIII' | null>(null)

const kinds = computed(() =>
  (['REMOVAL', 'PRESERVATION', 'INFORMATION'] as const).map((k) => ({ value: k, label: t(`orders.kinds.${k}`) })),
)

const lang = computed<OrderFormLang>(() => (locale.value === 'de' ? 'de' : 'en'))

const form = computed(() => {
  const r = result.value
  if (!r) return null
  const which = r.found ? (r.blockedAt ? 'annexII' : null) : 'annexIII'
  if (!which) return null
  return {
    which,
    text: renderOrderForm(which, lang.value, {
      reference: typedReference.value, orderId: r.id, shareId: r.shareId, receivedAt: r.receivedAt, blockedAt: r.blockedAt,
      provider: r.providerName, contact: r.contactPoint,
    }),
  } as const
})

const { run, pending } = useAsyncAction(
  async () => {
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
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      if (e instanceof OrderInvalid) {
        error.value = t(`orders.${e.reason === 'share' ? 'invalidShare' : e.reason === 'reference' ? 'invalidReference' : 'moderationOff'}`)
        return
      }
      const { code } = await decodeHttpError(e)
      error.value =
        code === 'order_exists' ? t('orders.exists')
        : code === 'deferral_out_of_bounds' ? t('orders.deferralOutOfBounds')
        : code === 'moderation_off' ? t('orders.moderationOff')
        : t('orders.failed')
    },
  },
)

async function submit() {
  error.value = ''
  copied.value = null
  const accepted = await ask({
    header: t('orders.confirmTitle'),
    message: kind.value === 'REMOVAL' ? t('orders.confirmRemoval') : t('orders.confirmHold'),
    acceptLabel: t('orders.submit'),
    acceptIcon: 'ban',
  })
  if (accepted) await run()
}

async function copy() {
  if (!form.value) return
  await navigator.clipboard.writeText(form.value.text)
  copied.value = form.value.which
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('orders.title') }}</h2>
          <p>{{ t('orders.sub') }}</p>
        </div>
      </div>

      <VeyqMessage v-if="error" tone="error" class="order-error">{{ error }}</VeyqMessage>

      <form class="veyq-form order-form" @submit.prevent="submit">
        <label>
          <span>{{ t('orders.kind') }}</span>
          <VeyqSelect v-model="kind" :options="kinds" class="order-kind" :disabled="pending" />
        </label>
        <label>
          <span>{{ t('orders.share') }}</span>
          <VeyqInput v-model="share" class="order-share" autocomplete="off" spellcheck="false" />
          <small class="settings-muted">{{ t('orders.shareHint') }}</small>
        </label>
        <label>
          <span>{{ t('orders.reference') }}</span>
          <VeyqInput v-model="reference" class="order-reference" autocomplete="off" />
          <small class="settings-muted">{{ t('orders.referenceHint') }}</small>
        </label>
        <label>
          <span>{{ t('orders.receivedAt') }}</span>
          <input v-model="receivedAt" type="datetime-local" class="veyq-input order-received" />
        </label>
        <label v-if="kind === 'INFORMATION'">
          <span>{{ t('orders.holdDays') }}</span>
          <VeyqInput v-model="holdDays" class="order-hold-days" inputmode="numeric" autocomplete="off" />
        </label>
        <label v-if="kind === 'REMOVAL'">
          <span>{{ t('orders.deferUntil') }}</span>
          <input v-model="deferUntil" type="datetime-local" class="veyq-input order-defer" />
        </label>
        <div>
          <VeyqButton type="submit" class="order-submit" :label="t('orders.submit')" icon="ban" :loading="pending" />
        </div>
      </form>

      <section v-if="result" class="veyq-form order-result">
        <VeyqMessage v-if="!result.found" tone="warn" class="order-not-found">{{ t('orders.notFound') }}</VeyqMessage>
        <template v-else>
          <VeyqMessage v-if="result.blockedAt" tone="success" class="order-blocked">
            {{ t('orders.blocked', { time: formTime(result.blockedAt) }) }}
          </VeyqMessage>
          <p v-if="result.holdUntil" class="settings-muted order-held">{{ t('orders.held', { time: formTime(result.holdUntil) }) }}</p>
          <p v-if="result.statementDeferredUntil" class="settings-muted order-deferred">
            {{ t('orders.deferred', { time: formTime(result.statementDeferredUntil) }) }}
          </p>
        </template>
        <template v-if="form">
          <h3>{{ t(`orders.${form.which}`) }}</h3>
          <VeyqTextarea :model-value="form.text" readonly rows="12" auto-resize class="order-form-text" />
          <div>
            <VeyqButton class="order-copy" :label="copied ? t('orders.copied') : t('orders.copy')" icon="copy" appearance="text" @click="copy" />
          </div>
        </template>
      </section>
    </section>
  </section>
</template>

<style scoped>
.order-form-text {
  width: 100%;
  font-family: var(--font-mono, monospace);
}
</style>
