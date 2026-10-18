<script setup lang="ts">
// One case (uc.moderation.review-a-case step 2, uc.moderation.take-a-measure): its reports opened in the browser with
// their epoch's key, the hold, the measures, the proposals and the log — whose sealed notes open the same way — and the
// decision panel, each step with its reach stated. Every decision is confirmed, carries the optional note sealed to the
// moderation key, and answers with the case as it now stands, which replaces the one shown. "Review" leaves this page
// for the reported tenant's host, where the share viewer's moderation mode renders the held snapshot. A block carries its
// statement of reasons (uc.moderation.object-to-a-measure): the ground and the facts, required by the instance's full form
// and optional in the reduced one; a reporter who left an address is told the decision. An objection is shown opened, and
// decided here by a moderator other than the one who took the measure while there is one (ADR 0099 § 5).
// An authority (ADR 0093 §§ 7, 12-13): a statement is deferred only on an authority's request, entered with the block
// or renewed once on the statement, and the case shows until when; a case an order opened shows its orders, and every
// case offers the report to an authority and its handover (`orders/AuthoritySection.vue`). On a case of child sexual
// abuse material "Block the content" leads the decision panel as the proposed first step — a human still decides.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import type {
  HoldExtensionReason,
  ModerationCase,
  ModerationGround,
  ModerationProposalStep,
  ModerationTargetLink,
  ModerationTargetUploader,
} from '../../api/generated/moderation'
import { useConfirm } from '../../shared/state/useConfirm'
import {
  barReporter,
  decideObjection,
  dismissCase,
  extendHold,
  getCase,
  liftMeasure,
  openNote,
  openObjectionOf,
  openReportOf,
  proposeStep,
  renewDeferral,
  reporterNoticesOf,
  reviewUrl,
  takeMeasure,
  type MeasureKind,
} from './cases'
import { caseFailureKey } from './caseFailure'
import AuthoritySection from './orders/AuthoritySection.vue'

const props = defineProps<{ caseId: string }>()
const emit = defineEmits<{ back: [] }>()

const { t, locale } = useI18n()
const { ask } = useConfirm()

const current = ref<ModerationCase | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const note = ref('')
const extendUntil = ref<Date | null>(null)
const extendReason = ref('')
// The extension's reason type, in the clear (ADR 0093 § 4): on a child-sexual-abuse case only an authority's request
// or an Art. 18 report in progress extends the hold, and the server refuses any other.
const extendType = ref<HoldExtensionReason | ''>('')
const EXTENSION_TYPES: HoldExtensionReason[] = ['AUTHORITY_REFERENCE', 'ART18_REPORT_IN_PROGRESS', 'OBJECTION', 'LAWYERS_LETTER', 'LAWSUIT']
const CSAM_EXTENSION_TYPES: HoldExtensionReason[] = ['AUTHORITY_REFERENCE', 'ART18_REPORT_IN_PROGRESS']
const GROUNDS: ModerationGround[] = ['CHILD_SEXUAL_ABUSE', 'TERRORIST_CONTENT', 'THREAT_TO_LIFE', 'ILLEGAL_CONTENT', 'TERMS_BREACH']
const ground = ref<ModerationGround | ''>('')
const facts = ref('')
// The provision of the law or the clause of the terms the ground relies on (DSA Art. 17(3)(d), (e)) — required in the full form.
const provision = ref('')
// An authority's deferral of the next block's statement (ADR 0093 § 7): only with its request, at most six weeks.
const deferAsked = ref(false)
const deferUntil = ref<Date | null>(null)
const deferReason = ref('')
// A renewal of a held statement's deferral, per statement.
const renewUntil = ref<Record<string, Date | null>>({})
const renewReason = ref<Record<string, string>>({})
const SIX_WEEKS_DAYS = 42
const deferMax = computed(() => new Date(Date.now() + SIX_WEEKS_DAYS * 24 * 60 * 60 * 1000))
const csam = computed(() => current.value?.category === 'CHILD_SEXUAL_ABUSE')
const extensionTypes = computed(() => (csam.value ? CSAM_EXTENSION_TYPES : EXTENSION_TYPES))

const when = (d: Date) => d.toLocaleString(locale.value)
const short = (id: string) => id.slice(0, 8)
const SIX_MONTHS_DAYS = 183

async function load() {
  loading.value = true
  error.value = ''
  try {
    current.value = await getCase(props.caseId)
    if (ground.value === '') ground.value = current.value.category
  } catch (e) {
    error.value = t(await caseFailureKey(e, 'moderation.case.loadFailed'))
  } finally {
    loading.value = false
  }
}

/** Each report opened once per case read: what the reporter wrote, or null when this page holds no key that opens it.
 *  `K_report` opens the report shares — the review's business, not this page's — so it is zeroed at once. */
const openedReports = computed(() => {
  const opened = new Map<string, { reason: string; contact: string; context: string } | null>()
  for (const r of current.value?.reports ?? []) {
    const body = openReportOf(r.epoch, r.sealedReport, r.sealedContact)
    if (body) body.reportKey.fill(0)
    opened.set(r.id, body ? { reason: body.reason, contact: body.contact, context: body.context } : null)
  }
  return opened
})

/** The log's notes, opened: undefined where an event has none, null where it does not open here. */
const openedNotes = computed(() =>
  (current.value?.events ?? []).map((e) => {
    if (e.sealedText == null) return undefined
    return e.epoch == null ? null : openNote(e.epoch, current.value!.id, e.sealedText)
  }),
)

/** The objections, opened: the text, or null where this page holds no key that opens it. */
const openedObjections = computed(() =>
  new Map((current.value?.objections ?? []).map((o) => [o.id, openObjectionOf(o.epoch, o.statementId, o.sealedObjection)])),
)

/** A measure's statement facts, opened from the moderation key's copy: undefined without facts, null where it does not open. */
const openedFacts = computed(() =>
  new Map(
    (current.value?.measures ?? []).map((m) => {
      const s = m.statement
      if (!s || s.sealedFacts == null || s.epoch == null) return [m.id, undefined] as const
      return [m.id, openNote(s.epoch, current.value!.id, s.sealedFacts)] as const
    }),
  ),
)

const fullForm = computed(() => current.value?.statementForm !== 'REDUCED')

const open = computed(() => current.value?.state === 'OPEN')
const closed = computed(() => current.value?.state === 'CLOSED')
const inForce = computed(() => (current.value?.measures ?? []).filter((m) => m.liftedAt === null))
const linksToBlock = computed(() => (current.value?.targets.links ?? []).filter((l) => !l.blocked))
const contentBlockable = computed(() => {
  const c = current.value
  if (!c || (c.itemKind !== 'NODE' && c.itemKind !== 'ITEM')) return false
  return !inForce.value.some((m) => m.kind === 'CONTENT' && m.targetId === c.itemId)
})
const guestsToBlock = computed(() => (current.value?.targets.uploaders ?? []).filter((u) => u.kind === 'GUEST_IDENTITY' && !u.blocked))
const proposalOpen = (step: ModerationProposalStep, targetId: string | null) =>
  (current.value?.proposals ?? []).some((p) => p.step === step && p.targetId === targetId && p.outcome === 'OPEN')
const membersToPropose = computed(() =>
  (current.value?.targets.uploaders ?? []).filter((u) => u.kind === 'ACCOUNT' && !proposalOpen('MEMBER_SIGN_IN', u.id)),
)
const offlineProposable = computed(() => current.value?.tenantId != null && !proposalOpen('TENANT_OFFLINE', current.value.tenantId))
const tenantName = computed(() => current.value?.tenantDomain ?? short(current.value?.tenantId ?? ''))

const extendMin = computed(() => current.value?.hold?.endsAt ?? new Date())
const extendMax = computed(() => new Date(Date.now() + SIX_MONTHS_DAYS * 24 * 60 * 60 * 1000))

/** Confirm a step by its label and reach, then take it; the case it answers with replaces the shown one. */
async function decide(label: string, reach: string, icon: string, action: (note: string) => Promise<ModerationCase>) {
  if (!(await ask({ header: label, message: reach, acceptLabel: label, acceptIcon: icon }))) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    current.value = await action(note.value)
    note.value = ''
  } catch (e) {
    error.value = t(await caseFailureKey(e))
  } finally {
    busy.value = false
  }
}

const id = () => current.value!.id

const dismiss = () =>
  decide(t('moderation.decide.dismiss'), t('moderation.decide.dismissReach'), 'pi pi-check', (n) =>
    dismissCase(id(), n, reporterNoticesOf(current.value!)),
  )

/**
 * Take a block with its statement: refused here already when the full form lacks the facts or the provision, as the
 * server would; the ground defaults to the case's category, and the facts field empties once the block is taken.
 */
function block(label: string, reach: string, kind: MeasureKind, target: { shareId?: string; targetId?: string }) {
  if (fullForm.value && facts.value.trim() === '') {
    error.value = t('moderation.errors.factsRequired')
    return
  }
  if (fullForm.value && provision.value.trim() === '') {
    error.value = t('moderation.errors.provisionRequired')
    return
  }
  if (deferAsked.value && (!deferUntil.value || deferReason.value.trim() === '')) {
    error.value = t('moderation.errors.reasonRequired')
    return
  }
  const deferral = deferAsked.value ? { until: deferUntil.value!, reason: deferReason.value } : null
  const statement = { ground: (ground.value || current.value!.category) as ModerationGround, provision: provision.value, facts: facts.value, deferral }
  return decide(label, reach, 'pi pi-ban', async (n) => {
    const answered = await takeMeasure(id(), kind, target, n, statement, reporterNoticesOf(current.value!))
    facts.value = ''
    deferAsked.value = false
    deferUntil.value = null
    deferReason.value = ''
    return answered
  })
}

/** Renew a held statement's deferral once, with the authority's renewed request (ADR 0093 § 7). */
async function renew(statementId: string) {
  error.value = ''
  notice.value = ''
  const until = renewUntil.value[statementId]
  const reason = renewReason.value[statementId] ?? ''
  if (!until || reason.trim() === '') {
    error.value = t('moderation.errors.reasonRequired')
    return
  }
  busy.value = true
  try {
    current.value = await renewDeferral(id(), statementId, until, reason)
    renewReason.value[statementId] = ''
    renewUntil.value[statementId] = null
    notice.value = t('moderation.deferral.renewedNotice', { time: when(until) })
  } catch (e) {
    error.value = t(await caseFailureKey(e))
  } finally {
    busy.value = false
  }
}

const blockLink = (l: ModerationTargetLink) =>
  block(t('moderation.decide.blockLink', { id: short(l.shareId) }), t('moderation.decide.blockLinkReach'), 'SHARE', { shareId: l.shareId })

const blockContent = () =>
  block(t('moderation.decide.blockContent'), t('moderation.decide.blockContentReach'), 'CONTENT', { targetId: current.value!.itemId })

const blockGuest = (u: ModerationTargetUploader) =>
  block(t('moderation.decide.blockUploader', { id: short(u.id) }), t('moderation.decide.blockUploaderReach'), 'GUEST_IDENTITY', { targetId: u.id })

const uphold = (objectionId: string) =>
  decide(t('moderation.case.uphold'), t('moderation.case.upholdReach'), 'pi pi-check', (n) => decideObjection(id(), objectionId, 'UPHOLD', n))

const liftOnObjection = (objectionId: string) =>
  decide(t('moderation.case.liftOnObjection'), t('moderation.case.liftOnObjectionReach'), 'pi pi-undo', (n) =>
    decideObjection(id(), objectionId, 'LIFT', n),
  )

const proposeSignIn = (u: ModerationTargetUploader) =>
  decide(t('moderation.decide.proposeSignIn', { id: short(u.id) }), t('moderation.decide.proposeSignInReach'), 'pi pi-send', (n) =>
    proposeStep(id(), 'MEMBER_SIGN_IN', u.id, n),
  )

const proposeOffline = () =>
  decide(t('moderation.decide.proposeOffline', { domain: tenantName.value }), t('moderation.decide.proposeOfflineReach'), 'pi pi-send', (n) =>
    proposeStep(id(), 'TENANT_OFFLINE', null, n),
  )

const lift = (measureId: string) =>
  decide(t('moderation.case.lift'), t('moderation.case.liftReach'), 'pi pi-undo', (n) => liftMeasure(id(), measureId, n))

const bar = (reportId: string) => decide(t('moderation.decide.bar'), t('moderation.decide.barReach'), 'pi pi-ban', (n) => barReporter(id(), reportId, n))

async function extend() {
  error.value = ''
  notice.value = ''
  if (!extendUntil.value || extendReason.value.trim() === '') {
    error.value = t('moderation.errors.reasonRequired')
    return
  }
  if (csam.value && !extendType.value) {
    error.value = t('moderation.errors.extendTypeRequired')
    return
  }
  busy.value = true
  try {
    current.value = await extendHold(id(), extendUntil.value, extendReason.value, extendType.value || null)
    extendReason.value = ''
    extendUntil.value = null
    extendType.value = ''
    if (current.value.hold) notice.value = t('moderation.case.extended', { time: when(current.value.hold.endsAt) })
  } catch (e) {
    error.value = t(await caseFailureKey(e))
  } finally {
    busy.value = false
  }
}

/** A full navigation to the reported tenant's host: the review unlocks there on its own. */
function review() {
  window.location.assign(reviewUrl(current.value!.id, current.value!.tenantDomain))
}

onMounted(load)
</script>

<template>
  <div class="veyq-section moderation-case" :data-case-id="caseId">
    <div class="veyq-section-header">
      <Button severity="secondary" class="moderation-case-back" icon="pi pi-arrow-left" :label="t('moderation.case.back')" :disabled="busy" @click="emit('back')" />
    </div>
    <Message v-if="error" severity="error" :closable="false" class="form-message moderation-case-error">{{ error }}</Message>
    <Message v-if="notice" severity="success" :closable="false" class="form-message">{{ notice }}</Message>
    <p v-if="loading" class="veyq-form-copy">{{ t('moderation.case.loading') }}</p>

    <template v-else-if="current">
      <div class="veyq-section-header">
        <h3>{{ t(`report.categories.${current.category}`) }}</h3>
        <span class="veyq-badge" :class="{ warning: open, muted: closed }">{{ t(`moderation.states.${current.state}`) }}</span>
        <span v-if="current.origin === 'ORDER'" class="veyq-badge moderation-case-order-badge">{{ t('moderation.caseRow.order') }}</span>
      </div>
      <p class="veyq-form-copy">{{ t(`moderation.itemKinds.${current.itemKind}`) }} · {{ current.tenantDomain ?? t('moderation.caseRow.noDomain') }}</p>
      <!-- An order's case has no report and so no report share to review: the order acted on ids. -->
      <div v-if="current.origin !== 'ORDER'" class="veyq-form">
        <Button class="moderation-case-review" icon="pi pi-eye" :label="t('moderation.case.review')" :disabled="busy" @click="review" />
        <p class="veyq-form-copy">{{ t('moderation.case.reviewHint') }}</p>
      </div>

      <!-- The reports, opened here with their epoch's key. -->
      <h4>{{ t('moderation.case.reports') }}</h4>
      <div class="veyq-list">
        <div v-for="r in current.reports" :key="r.id" class="veyq-list-row moderation-report">
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ t(`report.categories.${r.category}`) }}</strong>
            <span class="veyq-row-subtitle">{{ t(`moderation.case.reporter.${r.reporter}`) }} · {{ t('moderation.case.received', { time: when(r.receivedAt) }) }}</span>
            <template v-if="openedReports.get(r.id)">
              <p class="veyq-form-copy">{{ t('moderation.case.reason') }}: {{ openedReports.get(r.id)!.reason }}</p>
              <p class="veyq-form-copy">{{ t('moderation.case.contact') }}: {{ openedReports.get(r.id)!.contact || t('moderation.case.noContact') }}</p>
              <p v-if="openedReports.get(r.id)!.context" class="veyq-form-copy">{{ t('moderation.case.context') }}: {{ openedReports.get(r.id)!.context }}</p>
            </template>
            <p v-else class="veyq-form-copy moderation-report-sealed">{{ t('moderation.case.cannotOpen') }}</p>
            <div v-if="r.barred" class="veyq-row-meta"><span class="veyq-badge warning">{{ t('moderation.case.barred') }}</span></div>
          </div>
          <Button v-if="!r.barred" severity="danger" outlined class="moderation-bar" :label="t('moderation.decide.bar')" :disabled="busy" @click="bar(r.id)" />
        </div>
      </div>

      <!-- The hold, and its extension with a reason (ADR 0093 § 4). -->
      <h4>{{ t('moderation.case.hold') }}</h4>
      <template v-if="current.hold">
        <p class="veyq-form-copy">
          {{ t('moderation.case.heldUntil', { time: when(current.hold.endsAt) }) }}
          {{ t(`moderation.case.holdTriggers.${current.hold.trigger}`, { time: when(current.hold.floorUntil ?? current.hold.endsAt) }) }}
        </p>
        <form class="veyq-form moderation-extend" @submit.prevent="extend">
          <label for="moderation-extend-until">{{ t('moderation.case.extendUntil') }}</label>
          <DatePicker
            v-model="extendUntil"
            input-id="moderation-extend-until"
            :min-date="extendMin"
            :max-date="extendMax"
            date-format="yy-mm-dd"
            show-icon
            icon-display="input"
            :disabled="busy"
            fluid
          />
          <span class="veyq-form-copy">{{ t('moderation.case.extendUntilHint') }}</span>
          <label for="moderation-extend-type">{{ csam ? t('moderation.case.extendType') : t('moderation.case.extendTypeOptional') }}</label>
          <select id="moderation-extend-type" v-model="extendType" class="moderation-extend-type" :disabled="busy">
            <option value="" disabled>{{ t('moderation.case.extendTypePick') }}</option>
            <option v-for="r in extensionTypes" :key="r" :value="r">{{ t(`moderation.case.extendTypes.${r}`) }}</option>
          </select>
          <label for="moderation-extend-reason">{{ t('moderation.case.extendReason') }}</label>
          <textarea id="moderation-extend-reason" v-model="extendReason" class="moderation-extend-reason" rows="3" maxlength="2000" required :disabled="busy" />
          <span class="veyq-form-copy">{{ t(csam ? 'moderation.case.extendReasonHintCsam' : 'moderation.case.extendReasonHint') }}</span>
          <Button type="submit" severity="secondary" class="moderation-extend-submit" icon="pi pi-clock" :label="t('moderation.case.extend')" :loading="busy" />
        </form>
      </template>
      <p v-else class="veyq-form-copy">{{ t('moderation.case.noHold') }}</p>

      <!-- The measures; any moderator lifts one in force. -->
      <h4>{{ t('moderation.case.measures') }}</h4>
      <p v-if="current.measures.length === 0" class="veyq-form-copy">{{ t('moderation.case.noMeasures') }}</p>
      <div v-else class="veyq-list">
        <div v-for="m in current.measures" :key="m.id" class="veyq-list-row moderation-measure">
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ t(`moderation.case.measureKinds.${m.kind}`) }}</strong>
            <span class="veyq-row-subtitle" :title="m.targetId">{{ t('moderation.case.target', { id: short(m.targetId) }) }} · {{ t('moderation.case.takenAt', { time: when(m.takenAt) }) }}</span>
            <div class="veyq-row-meta">
              <span v-if="m.liftedAt" class="veyq-badge muted">{{ t('moderation.case.liftedAt', { time: when(m.liftedAt) }) }}</span>
              <span v-else class="veyq-badge warning">{{ t('moderation.case.inForce') }}</span>
            </div>
            <template v-if="m.statement">
              <p class="veyq-form-copy moderation-measure-statement">
                {{ t('moderation.case.statementGround', { ground: t(`report.categories.${m.statement.ground}`) }) }}
                <template v-if="m.statement.sentAt">{{ t('moderation.case.statementSent', { time: when(m.statement.sentAt), count: m.statement.recipients }, m.statement.recipients) }}</template>
                <template v-else-if="m.statement.deferredUntil">{{ t('moderation.deferral.deferredUntil', { time: when(m.statement.deferredUntil) }) }}</template>
                <template v-else>{{ t('moderation.case.statementNobody') }}</template>
                <template v-if="m.statement.renewed"> {{ t('moderation.deferral.renewed') }}</template>
              </p>
              <!-- The authority's renewal, once, while the statement is still held (TCO Art. 11(3)). -->
              <form
                v-if="m.statement.deferredUntil && !m.statement.renewed"
                class="veyq-form moderation-renew"
                @submit.prevent="renew(m.statement.id)"
              >
                <label :for="`moderation-renew-until-${m.statement.id}`">{{ t('moderation.deferral.renewUntil') }}</label>
                <DatePicker
                  v-model="renewUntil[m.statement.id]"
                  :input-id="`moderation-renew-until-${m.statement.id}`"
                  :min-date="m.statement.deferredUntil"
                  :max-date="deferMax"
                  date-format="yy-mm-dd"
                  show-icon
                  icon-display="input"
                  :disabled="busy"
                  fluid
                />
                <label :for="`moderation-renew-reason-${m.statement.id}`">{{ t('moderation.deferral.renewReason') }}</label>
                <textarea :id="`moderation-renew-reason-${m.statement.id}`" v-model="renewReason[m.statement.id]" class="moderation-renew-reason" rows="2" maxlength="2000" :disabled="busy" />
                <Button type="submit" severity="secondary" class="moderation-renew-submit" icon="pi pi-clock" :label="t('moderation.deferral.renew')" :loading="busy" />
              </form>
              <p v-if="openedFacts.get(m.id) !== undefined" class="veyq-form-copy moderation-measure-facts">
                {{ t('moderation.case.statementFacts') }}: {{ openedFacts.get(m.id) ?? t('moderation.case.noteSealed') }}
              </p>
            </template>
          </div>
          <Button v-if="!m.liftedAt" severity="secondary" class="moderation-lift" icon="pi pi-undo" :label="t('moderation.case.lift')" :disabled="busy" @click="lift(m.id)" />
        </div>
      </div>

      <!-- The objections (ADR 0093 § 7, ADR 0099 § 5): opened here, decided by another moderator while there is one. -->
      <template v-if="current.objections.length > 0">
        <h4>{{ t('moderation.case.objections') }}</h4>
        <div class="veyq-list">
          <div v-for="o in current.objections" :key="o.id" class="veyq-list-row moderation-objection">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ t('moderation.case.objectionReceived', { time: when(o.receivedAt) }) }}</strong>
              <p class="veyq-form-copy moderation-objection-text">{{ openedObjections.get(o.id) ?? t('moderation.case.objectionSealed') }}</p>
              <div class="veyq-row-meta">
                <span v-if="o.outcome" class="veyq-badge" :class="{ success: o.outcome === 'LIFTED', muted: o.outcome === 'UPHELD' }">
                  {{ t(`moderation.case.objectionOutcomes.${o.outcome}`) }}
                </span>
                <span v-else class="veyq-badge warning">{{ t('moderation.case.objectionOpen') }}</span>
              </div>
              <p v-if="o.outcome && o.ownReview" class="veyq-form-copy">{{ t('moderation.case.ownReview') }}</p>
              <p v-if="!o.outcome && !o.mayDecide" class="veyq-form-copy moderation-objection-another">{{ t('moderation.case.anotherDecides') }}</p>
            </div>
            <template v-if="!o.outcome && o.mayDecide">
              <Button severity="secondary" class="moderation-objection-uphold" icon="pi pi-check" :label="t('moderation.case.uphold')" :disabled="busy" @click="uphold(o.id)" />
              <Button severity="danger" outlined class="moderation-objection-lift" icon="pi pi-undo" :label="t('moderation.case.liftOnObjection')" :disabled="busy" @click="liftOnObjection(o.id)" />
            </template>
          </div>
        </div>
      </template>

      <!-- The proposals, as such: the Server Owner takes or declines them. -->
      <h4>{{ t('moderation.case.proposals') }}</h4>
      <p v-if="current.proposals.length === 0" class="veyq-form-copy">{{ t('moderation.case.noProposals') }}</p>
      <div v-else class="veyq-list">
        <div v-for="p in current.proposals" :key="p.id" class="veyq-list-row moderation-proposal">
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ t(`moderation.case.proposalSteps.${p.step}`) }}</strong>
            <span class="veyq-row-subtitle" :title="p.targetId">{{ t('moderation.case.target', { id: short(p.targetId) }) }} · {{ t('moderation.case.proposedAt', { time: when(p.proposedAt) }) }}</span>
            <div class="veyq-row-meta">
              <span class="veyq-badge" :class="{ warning: p.outcome === 'OPEN', success: p.outcome === 'TAKEN', muted: p.outcome === 'DECLINED' }">
                {{ t(`moderation.case.proposalOutcomes.${p.outcome}`) }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- The decision panel: each step with its reach. -->
      <h4>{{ t('moderation.decide.title') }}</h4>
      <p v-if="closed" class="veyq-form-copy moderation-decide-closed">{{ t('moderation.decide.closed') }}</p>
      <div v-else class="veyq-form moderation-decide">
        <label for="moderation-decision-note">{{ t('moderation.decide.note') }}</label>
        <textarea id="moderation-decision-note" v-model="note" class="moderation-decision-note" rows="3" maxlength="2000" :disabled="busy" />
        <span class="veyq-form-copy">{{ t('moderation.decide.noteHint') }}</span>

        <strong>{{ t('moderation.decide.statement') }}</strong>
        <p class="veyq-form-copy">{{ t('moderation.decide.statementCopy') }}</p>
        <label for="moderation-statement-ground">{{ t('moderation.decide.ground') }}</label>
        <select id="moderation-statement-ground" v-model="ground" class="moderation-statement-ground" :disabled="busy">
          <option v-for="g in GROUNDS" :key="g" :value="g">{{ t(`report.categories.${g}`) }}</option>
        </select>
        <label for="moderation-statement-provision">{{ fullForm ? t('moderation.decide.provision') : t('moderation.decide.provisionOptional') }}</label>
        <input id="moderation-statement-provision" v-model="provision" class="moderation-statement-provision" type="text" maxlength="200" :disabled="busy" />
        <span class="veyq-form-copy">{{ t('moderation.decide.provisionHint') }}</span>
        <label for="moderation-statement-facts">{{ fullForm ? t('moderation.decide.facts') : t('moderation.decide.factsOptional') }}</label>
        <textarea id="moderation-statement-facts" v-model="facts" class="moderation-statement-facts" rows="4" maxlength="2000" :disabled="busy" />
        <span class="veyq-form-copy">{{ fullForm ? t('moderation.decide.factsHint') : t('moderation.decide.factsHintReduced') }}</span>
        <label class="moderation-defer-ask">
          <input v-model="deferAsked" type="checkbox" class="moderation-defer-toggle" :disabled="busy" />
          {{ t('moderation.deferral.ask') }}
        </label>
        <template v-if="deferAsked">
          <label for="moderation-defer-until">{{ t('moderation.deferral.until') }}</label>
          <DatePicker
            v-model="deferUntil"
            input-id="moderation-defer-until"
            :min-date="new Date()"
            :max-date="deferMax"
            date-format="yy-mm-dd"
            show-icon
            icon-display="input"
            :disabled="busy"
            fluid
          />
          <label for="moderation-defer-reason">{{ t('moderation.deferral.reason') }}</label>
          <textarea id="moderation-defer-reason" v-model="deferReason" class="moderation-defer-reason" rows="2" maxlength="2000" :disabled="busy" />
          <span class="veyq-form-copy">{{ t('moderation.deferral.reasonHint') }}</span>
        </template>

        <div class="veyq-list">
          <!-- Child sexual abuse material: blocking the content is proposed as the first step (ADR 0093 § 13). -->
          <div v-if="csam && contentBlockable" class="veyq-list-row moderation-proposed-first">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ t('moderation.decide.blockContent') }}</strong>
              <div class="veyq-row-meta"><span class="veyq-badge warning">{{ t('moderation.csam.proposed') }}</span></div>
              <p class="veyq-form-copy">{{ t('moderation.decide.blockContentReach') }}</p>
            </div>
            <Button severity="danger" class="moderation-block-content" icon="pi pi-ban" :label="t('moderation.decide.blockContent')" :disabled="busy" @click="blockContent" />
          </div>

          <div v-if="open" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ t('moderation.decide.dismiss') }}</strong>
              <p class="veyq-form-copy">{{ t('moderation.decide.dismissReach') }}</p>
            </div>
            <Button class="moderation-dismiss" icon="pi pi-check" :label="t('moderation.decide.dismiss')" :disabled="busy" @click="dismiss" />
          </div>

          <div v-for="l in linksToBlock" :key="l.shareId" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title" :title="l.shareId">{{ t('moderation.decide.blockLink', { id: short(l.shareId) }) }}</strong>
              <span class="veyq-row-subtitle">{{ t(`moderation.decide.linkRelations.${l.relation}`) }} · {{ t(`moderation.decide.linkRoles.${l.role}`) }}</span>
              <p class="veyq-form-copy">{{ t('moderation.decide.blockLinkReach') }}</p>
            </div>
            <Button severity="danger" class="moderation-block-link" icon="pi pi-ban" :label="t('moderation.decide.blockLink', { id: short(l.shareId) })" :disabled="busy" @click="blockLink(l)" />
          </div>

          <div v-if="contentBlockable && !csam" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ t('moderation.decide.blockContent') }}</strong>
              <p class="veyq-form-copy">{{ t('moderation.decide.blockContentReach') }}</p>
            </div>
            <Button severity="danger" class="moderation-block-content" icon="pi pi-ban" :label="t('moderation.decide.blockContent')" :disabled="busy" @click="blockContent" />
          </div>

          <div v-for="u in guestsToBlock" :key="u.id" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title" :title="u.id">{{ t('moderation.decide.blockUploader', { id: short(u.id) }) }}</strong>
              <p class="veyq-form-copy">{{ t('moderation.decide.blockUploaderReach') }}</p>
            </div>
            <Button severity="danger" class="moderation-block-guest" icon="pi pi-ban" :label="t('moderation.decide.blockUploader', { id: short(u.id) })" :disabled="busy" @click="blockGuest(u)" />
          </div>
        </div>

        <strong>{{ t('moderation.decide.ownerSteps') }}</strong>
        <p class="veyq-form-copy">{{ t('moderation.decide.ownerStepsCopy') }}</p>
        <div class="veyq-list">
          <div v-for="u in membersToPropose" :key="u.id" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title" :title="u.id">{{ t('moderation.decide.proposeSignIn', { id: short(u.id) }) }}</strong>
              <p class="veyq-form-copy">{{ t('moderation.decide.proposeSignInReach') }}</p>
            </div>
            <Button severity="secondary" class="moderation-propose-sign-in" icon="pi pi-send" :label="t('moderation.decide.proposeSignIn', { id: short(u.id) })" :disabled="busy" @click="proposeSignIn(u)" />
          </div>
          <div v-if="offlineProposable" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ t('moderation.decide.proposeOffline', { domain: tenantName }) }}</strong>
              <p class="veyq-form-copy">{{ t('moderation.decide.proposeOfflineReach') }}</p>
            </div>
            <Button severity="secondary" class="moderation-propose-offline" icon="pi pi-send" :label="t('moderation.decide.proposeOffline', { domain: tenantName })" :disabled="busy" @click="proposeOffline" />
          </div>
        </div>
      </div>

      <!-- An authority: the orders that opened the case, the report to an authority and its handover (ADR 0093 §§ 12-13). -->
      <AuthoritySection
        :current="current"
        :busy="busy"
        @updated="current = $event"
        @failed="error = $event"
        @recorded="notice = $event"
      />

      <!-- The log (ADR 0093 § 11): clear fields, the notes opened here. -->
      <h4>{{ t('moderation.case.log') }}</h4>
      <div class="veyq-list">
        <div v-for="(e, i) in current.events" :key="i" class="veyq-list-row moderation-event">
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ t(`moderation.case.events.${e.event}`) }}</strong>
            <span class="veyq-row-subtitle">
              {{ when(e.at) }}<template v-if="e.detail"> · {{ e.detail }}</template><template v-if="e.until"> · {{ t('moderation.case.eventUntil', { time: when(e.until) }) }}</template>
            </span>
            <p v-if="openedNotes[i] !== undefined" class="veyq-form-copy moderation-event-note">{{ openedNotes[i] ?? t('moderation.case.noteSealed') }}</p>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
