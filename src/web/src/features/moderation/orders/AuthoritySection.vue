<script setup lang="ts">
// A case's dealings with an authority (ADR 0093 §§ 11-13; uc.moderation.authority-order,
// uc.moderation.report-to-an-authority), inside the case:
//   - the orders that opened it, each reference opened here and its Annex II text rendered again from it, to copy;
//   - the report to an authority: the fields the server holds in the clear, fetched on request and laid out as a text
//     block to copy — never a file, never content (`art18Export.ts`);
//   - a handover recorded in the log with its reference sealed; on a child-sexual-abuse case it ends the case's hold
//     (ADR 0093 § 4, § 184b(5) StGB), and the hint and the confirmation say so — on any other it ends nothing.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import type { ModerationCase, ModerationCaseExport, ModerationHandoverKindEnum } from '../../../api/generated/moderation'
import { useConfirm } from '../../../shared/state/useConfirm'
import { getExport, recordHandover } from '../cases'
import { caseFailureKey } from '../caseFailure'
import { renderExport, type ExportLabels } from './art18Export'
import { openReferenceOf } from './orders'
import { formTime, renderOrderForm, type OrderFormLang } from './orderForms'

const props = defineProps<{ current: ModerationCase; busy: boolean }>()
const emit = defineEmits<{ updated: [ModerationCase]; failed: [string]; recorded: [string] }>()

const { t, locale } = useI18n()
const { ask } = useConfirm()

const lang = computed<OrderFormLang>(() => (locale.value === 'de' ? 'de' : 'en'))
/** Whether recording a handover ends this case's hold: a case of child sexual abuse (ADR 0093 § 4). */
const endsHold = computed(() => props.current.category === 'CHILD_SEXUAL_ABUSE')
const exported = ref<ModerationCaseExport | null>(null)
const reference = ref('')
const working = ref(false)
const copied = ref<string | null>(null)

/** Each order of the case with its reference opened, and its Annex II text when it blocked. */
const orders = computed(() =>
  props.current.orders.map((o) => {
    const opened = openReferenceOf(o)
    const annex = o.blockedAt && opened
      ? renderOrderForm('annexII', lang.value, {
        reference: opened, orderId: o.id, shareId: o.shareId, receivedAt: o.receivedAt, blockedAt: o.blockedAt,
        provider: o.providerName, contact: o.contactPoint,
      })
      : null
    return { order: o, reference: opened, annex }
  }),
)

const LABEL_KEYS = [
  'case', 'category', 'origin', 'timeZone', 'reported', 'measure', 'lifted', 'endedWithTarget', 'uploaderAccount',
  'uploaderGuest', 'viaLink', 'guestWrite', 'address', 'port', 'observed', 'object', 'noHeld',
] as const satisfies readonly (keyof ExportLabels)[]

function labels(): ExportLabels {
  return Object.fromEntries(LABEL_KEYS.map((k) => [k, t(`moderation.authority.labels.${k}`)])) as Record<keyof ExportLabels, string>
}

const exportText = computed(() => {
  const e = exported.value
  if (!e) return null
  return renderExport(
    e,
    labels(),
    t(`report.categories.${e.category}`),
    t(`moderation.authority.origins.${e.origin}`),
    (kind) => t(`moderation.case.measureKinds.${kind}`),
  )
})

async function prepare() {
  working.value = true
  try {
    exported.value = await getExport(props.current.id)
  } catch (e) {
    emit('failed', t(await caseFailureKey(e)))
  } finally {
    working.value = false
  }
}

async function copy(key: string, text: string) {
  await navigator.clipboard.writeText(text)
  copied.value = key
}

async function handover(kind: ModerationHandoverKindEnum) {
  const label = kind === 'REPORTED' ? t('moderation.authority.handedOver') : t('moderation.authority.informationGiven')
  if (!(await ask({ header: label, message: t(endsHold.value ? 'moderation.authority.handoverConfirmCsam' : 'moderation.authority.handoverConfirm'), acceptLabel: label, acceptIcon: 'send' }))) return
  working.value = true
  try {
    emit('updated', await recordHandover(props.current.id, kind, reference.value))
    reference.value = ''
    emit('recorded', t('moderation.authority.handoverRecorded'))
  } catch (e) {
    emit('failed', t(await caseFailureKey(e)))
  } finally {
    working.value = false
  }
}
</script>

<template>
  <template v-if="orders.length > 0">
    <h4>{{ t('moderation.orders.title') }}</h4>
    <div class="veyq-list">
      <div v-for="o in orders" :key="o.order.id" class="veyq-list-row moderation-case-order">
        <div class="veyq-row-main">
          <strong class="veyq-row-title">{{ t(`moderation.orders.kinds.${o.order.kind}`) }}</strong>
          <span class="veyq-row-subtitle">{{ o.reference ?? t('moderation.orders.referenceSealed') }} · {{ t('moderation.authority.orderOf', { time: formTime(o.order.receivedAt) }) }}</span>
          <p v-if="o.order.blockedAt" class="veyq-form-copy">{{ t('moderation.orders.blocked', { time: formTime(o.order.blockedAt) }) }}</p>
          <template v-if="o.annex">
            <textarea class="moderation-case-order-text" :value="o.annex" rows="10" readonly />
            <VeyqButton variant="secondary" class="moderation-case-order-copy" icon="copy" :label="copied === o.order.id ? t('moderation.orders.copied') : t('moderation.orders.copyText')" @click="copy(o.order.id, o.annex)" />
          </template>
        </div>
      </div>
    </div>
  </template>

  <h4>{{ t('moderation.authority.title') }}</h4>
  <div class="veyq-form moderation-authority">
    <p class="veyq-form-copy">{{ t('moderation.authority.copy') }}</p>
    <VeyqButton variant="secondary" class="moderation-export-prepare" icon="list" :label="t('moderation.authority.prepare')" :loading="working" :disabled="busy" @click="prepare" />
    <template v-if="exportText">
      <strong>{{ t('moderation.authority.exportTitle') }}</strong>
      <textarea class="moderation-export-text" :value="exportText" rows="12" readonly />
      <VeyqButton variant="secondary" class="moderation-export-copy" icon="copy" :label="copied === 'export' ? t('moderation.authority.copied') : t('moderation.authority.copyText')" @click="copy('export', exportText)" />
    </template>
    <label for="moderation-handover-reference">{{ t('moderation.authority.handoverReference') }}</label>
    <VeyqInput id="moderation-handover-reference" v-model="reference" class="moderation-handover-reference" autocomplete="off" :disabled="busy || working" />
    <span class="veyq-form-copy">{{ t(endsHold ? 'moderation.authority.handoverHintCsam' : 'moderation.authority.handoverHint') }}</span>
    <div class="veyq-action-group">
      <VeyqButton variant="secondary" class="moderation-handover-reported" icon="send" :label="t('moderation.authority.handedOver')" :disabled="busy || working" @click="handover('REPORTED')" />
      <VeyqButton variant="secondary" class="moderation-handover-information" icon="send" :label="t('moderation.authority.informationGiven')" :disabled="busy || working" @click="handover('INFORMATION_GIVEN')" />
    </div>
  </div>
</template>

<style scoped>
.moderation-case-order-text,
.moderation-export-text {
  width: 100%;
  font-family: var(--font-mono, monospace);
}
</style>
