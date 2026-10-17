<script setup lang="ts">
// One case (uc.moderation.review-a-case step 2, uc.moderation.take-a-measure): its reports opened in the browser with
// their epoch's key, the hold, the measures, the proposals and the log — whose sealed notes open the same way — and the
// decision panel, each step with its reach stated. Every decision is confirmed, carries the optional note sealed to the
// moderation key, and answers with the case as it now stands, which replaces the one shown. "Review" leaves this page
// for the reported tenant's host, where the share viewer's moderation mode renders the held snapshot.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import type {
  ModerationCase,
  ModerationProposalStep,
  ModerationTargetLink,
  ModerationTargetUploader,
} from '../../api/generated/moderation'
import { useConfirm } from '../../shared/state/useConfirm'
import {
  barReporter,
  dismissCase,
  extendHold,
  getCase,
  liftMeasure,
  openNote,
  openReportOf,
  proposeStep,
  reviewUrl,
  takeMeasure,
} from './cases'
import { caseFailureKey } from './caseFailure'

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

const when = (d: Date) => d.toLocaleString(locale.value)
const short = (id: string) => id.slice(0, 8)
const SIX_MONTHS_DAYS = 183

async function load() {
  loading.value = true
  error.value = ''
  try {
    current.value = await getCase(props.caseId)
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
    const body = openReportOf(r.epoch, r.sealedReport)
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

const dismiss = () => decide(t('moderation.decide.dismiss'), t('moderation.decide.dismissReach'), 'pi pi-check', (n) => dismissCase(id(), n))

const blockLink = (l: ModerationTargetLink) =>
  decide(t('moderation.decide.blockLink', { id: short(l.shareId) }), t('moderation.decide.blockLinkReach'), 'pi pi-ban', (n) =>
    takeMeasure(id(), 'SHARE', { shareId: l.shareId }, n),
  )

const blockContent = () =>
  decide(t('moderation.decide.blockContent'), t('moderation.decide.blockContentReach'), 'pi pi-ban', (n) =>
    takeMeasure(id(), 'CONTENT', { targetId: current.value!.itemId }, n),
  )

const blockGuest = (u: ModerationTargetUploader) =>
  decide(t('moderation.decide.blockUploader', { id: short(u.id) }), t('moderation.decide.blockUploaderReach'), 'pi pi-ban', (n) =>
    takeMeasure(id(), 'GUEST_IDENTITY', { targetId: u.id }, n),
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
  busy.value = true
  try {
    current.value = await extendHold(id(), extendUntil.value, extendReason.value)
    extendReason.value = ''
    extendUntil.value = null
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
  <div class="veyq-section moderation-case">
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
      </div>
      <p class="veyq-form-copy">{{ t(`moderation.itemKinds.${current.itemKind}`) }} · {{ current.tenantDomain ?? t('moderation.caseRow.noDomain') }}</p>
      <div class="veyq-form">
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
          {{ t('moderation.case.heldUntil', { time: when(current.hold.endsAt) }) }} {{ t(`moderation.case.holdTriggers.${current.hold.trigger}`) }}
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
          <label for="moderation-extend-reason">{{ t('moderation.case.extendReason') }}</label>
          <textarea id="moderation-extend-reason" v-model="extendReason" class="moderation-extend-reason" rows="3" maxlength="2000" required :disabled="busy" />
          <span class="veyq-form-copy">{{ t('moderation.case.extendReasonHint') }}</span>
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
          </div>
          <Button v-if="!m.liftedAt" severity="secondary" class="moderation-lift" icon="pi pi-undo" :label="t('moderation.case.lift')" :disabled="busy" @click="lift(m.id)" />
        </div>
      </div>

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

        <div class="veyq-list">
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

          <div v-if="contentBlockable" class="veyq-list-row">
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
