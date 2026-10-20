<script setup lang="ts">
// uc.moderation.appoint-moderators (admin-web, ADR 0099) — the "Moderation" rubric of the server settings: turning
// moderation on, the moderator roster, appointing and removing, and rotating the instance moderation key. Every key
// operation is the facade's (`moderation.ts`, the one minter); this panel decides only what to ask and what to say.
// What it keeps honest:
//   - while no epoch exists, reporting is off, and the panel says so before anything else (ADR 0099 § 3);
//   - a rotation that is due (a holder left or replaced their account keys) is said, with what it means for reports;
//   - every server refusal is shown as the panel's error, in words for its code — a refused mint is never swallowed,
//     and the roster is read again after it, so what is shown is what is stored (a removal whose rotation was refused
//     stands, and then shows as a due rotation);
//   - each step that changes who holds the key asks first.
// uc.moderation.take-a-measure step 5 adds the Server Owner's steps below the roster: the moderators' open proposals
// — a member's sign-in block, taken here with its statement of reasons (a proposal is over content, ADR 0093 § 7) after
// a confirm, or the tenant's suspension, taken on the tenant's own page
// (the console opens it; suspending settles the proposal) — each of which may be declined instead, and every sign-in
// block in force, which is lifted here.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import {
  appointModerator, blockSignIn, declineProposal, getModeration, getModerationSteps, liftSignInBlock, ModeratorState,
  ProposalStep, removeModerator, rotateModerationKey, RotationReason,
  type AccountSignInBlock, type InstanceModeration, type InstanceModerator, type ModerationSteps,
  type OpenModerationProposal,
} from './moderation'
import StepStatementFields from './StepStatementFields.vue'
import { stepStatement, StepStatementInvalid, type StepStatementDraft } from './stepStatement'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'

const { t, locale } = useI18n()
const { ask } = useConfirm()
const on401 = handle401(useRouter())
// A tenant-offline proposal is taken on the tenant's own page, where the suspension already lives: the console
// opens it, as it opens the store page for the create dialog.
const emit = defineEmits<{ openTenant: [tenantId: string] }>()

const moderation = ref<InstanceModeration | null>(null)
const steps = ref<ModerationSteps | null>(null)
const address = ref('')
const reason = ref<RotationReason>(RotationReason.ByHand)
const error = ref('')
const busy = ref(false)

// A rotation that is due on arrival was most likely caused by a holder leaving, so the reason starts there; the
// Server Owner changes it when it was an identity rotation (the server says that one is due, not why).
const { pending: loading, error: loadErr } = useAsyncData(
  async () => {
    const [roster, open] = await Promise.all([getModeration(), getModerationSteps()])
    moderation.value = roster
    steps.value = open
    if (roster.rotationDueSince) reason.value = RotationReason.Removal
  },
  { onError: on401 },
)
const loadError = computed(() => loadErrorKind(loadErr.value))

/** The panel's words for each refusal the moderation operations name (instance.openapi.yaml, tag instance-moderation). */
const REFUSALS: Record<string, string> = {
  identity_changed: 'moderation.errors.identityChanged',
  account_not_found: 'moderation.errors.accountNotFound',
  moderation_off: 'moderation.errors.off',
  not_moderator: 'moderation.errors.notModerator',
  moderation_last_holder: 'moderation.errors.lastHolder',
  moderation_grants_incomplete: 'moderation.errors.grantsIncomplete',
  moderation_epoch_stale: 'moderation.errors.epochStale',
}

/** Run one change; show the server's answer, or its refusal in words and the roster as it now stands. */
async function act(change: () => Promise<InstanceModeration>): Promise<boolean> {
  error.value = ''
  busy.value = true
  try {
    moderation.value = await change()
    return true
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return false
    const { status, code } = await decodeHttpError(e)
    error.value = t(
      code && REFUSALS[code] ? REFUSALS[code] : status === HttpStatus.forbidden ? 'moderation.notOwner' : 'moderation.errors.failed',
    )
    // A refused rotation after a removal leaves the removal standing: read the roster again so it shows.
    await getModeration().then((s) => (moderation.value = s)).catch(() => {})
    return false
  } finally {
    busy.value = false
  }
}

async function appoint() {
  const typed = address.value.trim()
  if (!typed) return
  if (await act(() => appointModerator(typed))) address.value = ''
}

async function remove(m: InstanceModerator) {
  const accepted = await ask({
    header: t('moderation.removeTitle', { address: m.address }),
    message: t('moderation.removeBody'),
    acceptLabel: t('moderation.remove'),
    acceptIcon: 'pi pi-user-minus',
  })
  if (accepted) await act(() => removeModerator(m.accountId))
}

async function rotate() {
  const accepted = await ask({
    header: t('moderation.rotateTitle'),
    message: t('moderation.rotateBody'),
    acceptLabel: t('moderation.rotate'),
    acceptIcon: 'pi pi-refresh',
  })
  if (accepted) await act(() => rotateModerationKey(reason.value, moderation.value ?? undefined))
}

const reasons = computed(() => [
  { value: RotationReason.ByHand, label: t('moderation.reasonByHand') },
  { value: RotationReason.Compromise, label: t('moderation.reasonCompromise') },
  { value: RotationReason.IdentityRotation, label: t('moderation.reasonIdentityRotation') },
  { value: RotationReason.Removal, label: t('moderation.reasonRemoval') },
])

/** Where one moderator stands: appointed and not yet accepted, or accepted and holding the key or waiting for it. */
function stateOf(m: InstanceModerator): { text: string; tone: 'success' | 'warning' | '' } {
  if (m.state === ModeratorState.Pending) return { text: t('moderation.statePending'), tone: '' }
  return m.holdsCurrentEpoch
    ? { text: t('moderation.stateHolds'), tone: 'success' }
    : { text: t('moderation.stateWaiting'), tone: 'warning' }
}

const dueSince = computed(() => moderation.value?.rotationDueSince?.toLocaleString(locale.value) ?? '')

/** The panel's words for each refusal the Server Owner's step operations name (tag instance-moderation). */
const STEP_REFUSALS: Record<string, string> = {
  account_not_found: 'moderation.steps.errors.accountNotFound',
  case_not_found: 'moderation.steps.errors.caseNotFound',
  sign_in_block_exists: 'moderation.steps.errors.blockExists',
  cannot_block_server_owner: 'moderation.steps.errors.cannotBlockOwner',
  proposal_not_found: 'moderation.steps.errors.proposalNotFound',
  sign_in_block_not_found: 'moderation.steps.errors.blockNotFound',
  invalid_request: 'moderation.steps.errors.invalidRequest',
  statement_required: 'moderation.steps.errors.statementRequired',
  statement_facts_required: 'moderation.steps.errors.factsRequired',
  statement_provision_required: 'moderation.steps.errors.provisionRequired',
  moderation_epoch_changed: 'moderation.steps.errors.epochChanged',
  moderation_off: 'moderation.steps.errors.moderationOff',
}

/** Run one step; show the steps as they now stand, or the refusal in words and the steps read again. */
async function actOnSteps(change: () => Promise<ModerationSteps>): Promise<void> {
  error.value = ''
  busy.value = true
  try {
    steps.value = await change()
  } catch (e) {
    if (e instanceof StepStatementInvalid) {
      error.value = t('moderation.steps.errors.moderationOff')
      return
    }
    on401(e)
    if (isUnauthorized(e)) return
    const { status, code } = await decodeHttpError(e)
    error.value = t(
      code && STEP_REFUSALS[code]
        ? STEP_REFUSALS[code]
        : status === HttpStatus.forbidden ? 'moderation.steps.errors.notOwner' : 'moderation.errors.failed',
    )
    // A refusal is most often a step someone else took meanwhile: read them again so what is shown is what stands.
    await getModerationSteps().then((s) => (steps.value = s)).catch(() => {})
  } finally {
    busy.value = false
  }
}

// The proposal whose block is being written, and its statement (ADR 0093 § 7): a block from a proposal is over content.
const blocking = ref<string | null>(null)
const draft = ref<StepStatementDraft>({ ground: 'TERMS_BREACH', provision: '', facts: '' })

/** Open the statement for [p]'s block, its ground the case's category. */
function startBlock(p: OpenModerationProposal) {
  blocking.value = p.id
  draft.value = { ground: p.category, provision: '', facts: '' }
}

async function block(p: OpenModerationProposal) {
  if (!p.address) return
  const accepted = await ask({
    header: t('moderation.steps.blockTitle', { address: p.address }),
    message: t('moderation.steps.blockBody'),
    acceptLabel: t('moderation.steps.block'),
    acceptIcon: 'pi pi-ban',
  })
  const address = p.address
  if (!accepted) return
  await actOnSteps(async () => blockSignIn(address, p.caseId, await stepStatement(draft.value)))
  if (!error.value) blocking.value = null
}

async function decline(p: OpenModerationProposal) {
  const accepted = await ask({
    header: t('moderation.steps.declineTitle'),
    message: t('moderation.steps.declineBody', { target: targetOf(p) }),
    acceptLabel: t('moderation.steps.decline'),
    acceptIcon: 'pi pi-times',
  })
  if (accepted) await actOnSteps(() => declineProposal(p.id))
}

async function lift(b: AccountSignInBlock) {
  await actOnSteps(() => liftSignInBlock(b.id))
}

/** What the proposal would act on: the member's address, or the tenant's domain. */
function targetOf(p: OpenModerationProposal): string {
  if (p.step === ProposalStep.TenantOffline) return p.tenantDomain ?? p.tenantId ?? ''
  return p.address ?? t('moderation.steps.accountGone')
}

const at = (d: Date) => d.toLocaleString(locale.value)
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('moderation.navTitle') }}</h2>
          <p>{{ t('moderation.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('moderation.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('moderation.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('moderation.loadFailed') }}</Message>

      <template v-else-if="moderation">
        <Message v-if="error" severity="error" :closable="false" class="mod-error">{{ error }}</Message>

        <template v-if="!moderation.enabled">
          <Message severity="warn" :closable="false" class="mod-off">{{ t('moderation.off') }}</Message>
        </template>

        <template v-else>
          <template v-if="moderation.rotationDueSince">
            <Message severity="warn" :closable="false" class="mod-due">{{ t('moderation.rotationDue', { since: dueSince }) }}</Message>
            <div>
              <Button type="button" class="mod-due__rotate" :label="t('moderation.rotate')" icon="pi pi-refresh" :loading="busy" @click="rotate" />
            </div>
          </template>
          <p class="settings-muted mod-epoch">{{ t('moderation.epoch', { epoch: moderation.currentEpoch ?? 0 }) }}</p>

          <h3>{{ t('moderation.rosterTitle') }}</h3>
          <ul class="mod-roster">
            <li v-for="m in moderation.moderators" :key="m.accountId" class="mod-row" :data-address="m.address">
              <div class="mod-row__head">
                <strong>{{ m.address }}</strong>
                <span class="veyq-badge mod-row__state" :class="stateOf(m).tone">{{ stateOf(m).text }}</span>
              </div>
              <small class="settings-muted mod-row__address">
                {{ m.addressConfirmed ? t('moderation.addressConfirmed') : t('moderation.addressUnconfirmed') }}
              </small>
              <div>
                <Button
                  type="button"
                  class="mod-remove"
                  severity="danger"
                  text
                  icon="pi pi-user-minus"
                  :label="t('moderation.remove')"
                  :disabled="busy"
                  @click="remove(m)"
                />
              </div>
            </li>
          </ul>

          <form class="mod-appoint" @submit.prevent="appoint">
            <h3>{{ t('moderation.appointTitle') }}</h3>
            <label>
              <span>{{ t('moderation.address') }}</span>
              <InputText v-model="address" class="mod-address" type="email" autocomplete="off" :disabled="busy" />
            </label>
            <small class="settings-muted">{{ t('moderation.appointHint') }}</small>
            <div>
              <Button type="submit" class="mod-appoint__submit" :label="t('moderation.appoint')" icon="pi pi-user-plus" :disabled="busy || !address.trim()" />
            </div>
          </form>

          <div class="mod-rotate">
            <h3>{{ t('moderation.rotateSection') }}</h3>
            <p class="settings-muted">{{ t('moderation.rotateHint') }}</p>
            <label>
              <span>{{ t('moderation.reason') }}</span>
              <Select
                v-model="reason"
                :options="reasons"
                option-label="label"
                option-value="value"
                :disabled="busy"
                class="mod-reason"
              />
            </label>
            <div>
              <Button type="button" class="mod-rotate__submit" :label="t('moderation.rotate')" icon="pi pi-refresh" :loading="busy" @click="rotate" />
            </div>
          </div>
        </template>

        <!-- The Server Owner's steps (uc.moderation.take-a-measure step 5). Shown whether moderation is on or not: a
             sign-in block outlives the key, and a block in force must stay liftable. -->
        <template v-if="steps">
          <div class="mod-steps">
            <h3>{{ t('moderation.steps.proposalsTitle') }}</h3>
            <p class="settings-muted">{{ t('moderation.steps.proposalsHint') }}</p>
            <p v-if="!steps.proposals.length" class="settings-muted mod-proposals-empty">{{ t('moderation.steps.noProposals') }}</p>
            <ul v-else class="mod-roster mod-proposals">
              <li v-for="p in steps.proposals" :key="p.id" class="mod-row mod-proposal" :data-proposal="p.id">
                <div class="mod-row__head">
                  <strong>{{ p.step === ProposalStep.TenantOffline ? t('moderation.steps.stepTenantOffline') : t('moderation.steps.stepMemberSignIn') }}</strong>
                  <span class="veyq-badge warning mod-proposal__category">{{ t(`moderation.steps.categories.${p.category}`) }}</span>
                </div>
                <span class="mod-proposal__target">{{ targetOf(p) }}</span>
                <small class="settings-muted mod-proposal__when">{{ t('moderation.steps.proposedAt', { at: at(p.proposedAt), caseId: p.caseId }) }}</small>
                <div class="veyq-dialog-action-row">
                  <Button
                    v-if="p.step === ProposalStep.MemberSignIn && p.address"
                    type="button"
                    class="mod-proposal__block"
                    severity="danger"
                    outlined
                    icon="pi pi-ban"
                    :label="t('moderation.steps.block')"
                    :disabled="busy || blocking === p.id"
                    @click="startBlock(p)"
                  />
                  <Button
                    v-else-if="p.step === ProposalStep.TenantOffline && p.tenantId"
                    type="button"
                    class="mod-proposal__tenant"
                    outlined
                    icon="pi pi-server"
                    :label="t('moderation.steps.openTenant')"
                    :disabled="busy"
                    @click="emit('openTenant', p.tenantId)"
                  />
                  <Button
                    type="button"
                    class="mod-proposal__decline"
                    severity="secondary"
                    text
                    icon="pi pi-times"
                    :label="t('moderation.steps.decline')"
                    :disabled="busy"
                    @click="decline(p)"
                  />
                </div>
                <template v-if="blocking === p.id">
                  <StepStatementFields v-model="draft" :id-prefix="`mod-statement-${p.id}`" :disabled="busy" />
                  <div class="veyq-dialog-action-row">
                    <Button
                      type="button"
                      class="mod-proposal__send"
                      severity="danger"
                      icon="pi pi-ban"
                      :label="t('moderation.steps.statement.send')"
                      :loading="busy"
                      @click="block(p)"
                    />
                    <Button type="button" severity="secondary" text :label="t('moderation.steps.statement.cancel')" :disabled="busy" @click="blocking = null" />
                  </div>
                </template>
              </li>
            </ul>
          </div>

          <div class="mod-steps">
            <h3>{{ t('moderation.steps.blocksTitle') }}</h3>
            <p v-if="!steps.signInBlocks.length" class="settings-muted mod-blocks-empty">{{ t('moderation.steps.noBlocks') }}</p>
            <ul v-else class="mod-roster mod-blocks">
              <li v-for="b in steps.signInBlocks" :key="b.id" class="mod-row mod-block" :data-block="b.address">
                <div class="mod-row__head">
                  <strong>{{ b.address }}</strong>
                </div>
                <small class="settings-muted mod-block__since">
                  {{ b.caseId
                    ? t('moderation.steps.blockedForCase', { at: at(b.blockedAt), caseId: b.caseId })
                    : t('moderation.steps.blockedNoCase', { at: at(b.blockedAt) }) }}
                </small>
                <div>
                  <Button
                    type="button"
                    class="mod-block__lift"
                    text
                    icon="pi pi-unlock"
                    :label="t('moderation.steps.lift')"
                    :disabled="busy"
                    @click="lift(b)"
                  />
                </div>
              </li>
            </ul>
          </div>
        </template>
      </template>
    </section>
  </section>
</template>

<style scoped>
.mod-roster {
  display: grid;
  gap: 0.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
}
.mod-row {
  display: grid;
  gap: 0.25rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
}
.mod-row__head {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}
.mod-appoint,
.mod-rotate,
.mod-steps {
  display: grid;
  gap: 0.5rem;
}
h3 {
  margin: 0.75rem 0 0;
  font-size: 1rem;
}
</style>
