<script setup lang="ts">
// "Secure your account" — the settings hub's security rubric (uc.account.rotate-keys, ADR 0060, cr-secure-the-account
// decisions 7, 11, 13). Its first section is the account's email address (`EmailAddressSection`,
// uc.account.confirm-email-address); everything below it is the ceremony that ends a compromise: the identity, the
// Master Key and the Secret Key are replaced together, a new Emergency Kit is issued, and the person stays inside every
// space they were in. The mechanism is `rotateKeys` / `finishRotation`; this file is the ceremony around it.
//
// **The steps** (decision 11): the entry — plain words before any field, from the account rotation's plan; the gate —
// password + current Secret Key, proven before a kit is printed, with "Also choose a new password" pre-selected; the new
// Emergency Kit — downloaded AND confirmed before the action can be pressed (the publish installs that kit's Secret
// Key, so a key nobody saved would strand the account); the confirm dialog; the publish; the result — per space its
// state as the server reads it, the links to renew, and that the old kit no longer works.
//
// **Three things this screen must never do**, each of which makes the feature worse than absent:
//
//  - **Imply that securing undoes anything.** It is forward secrecy, not repair: the entry says what was already seen
//    stays seen, and the result is a per-space state rather than a success tick.
//  - **Say "nothing was changed" without having read it** (the CR's invariant). An unknown outcome says to unlock again
//    with the new kit; a credential the floor refuses says the new kit may be the one that works.
//  - **Keep a minted key.** The new Secret Key (and a new password) live only in this component and are dropped when
//    it is left or once spent. A JS string cannot be zero-filled — `mintSecretKey` zeroes its bytes and hands back the
//    kit's encoding — so dropping the last reference is what this screen can do.
//
// Markup + classes mirror ProfilePanel (veyq-surface + settings-form + settings-section-header, a confirm Dialog with
// veyq-dialog-action-row), the new-password fields ChangePasswordPanel's (`VeyqPassword`, the policy hint, the breach
// notice), the kit step TenantJoinView's (`EmergencyKitSave`), and the result lists the veyq-list-row rows of
// SessionsPanel — the built panels are the design source. Renew opens the node's own share dialog through the
// registry's `shareDialog` slot (`linksToRenew.ts`): there is no second renew UI.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import { account } from '../../../app/account'
import VeyqPassword from '../../../shared/ui/VeyqPassword.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { mintSecretKey, rotateKeys, RotationOutcomeUnknown, RotationRefused, type RotationResult } from './rotateKeys'
import { listRotationStates, readRotationPlan } from './finishRotation'
import { readRotationEntry, type RotationEntry } from './rotationEntry'
import { listLinksToRenew, openRenewDialog, type LinkToRenew, type RenewDialog } from './linksToRenew'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import { listMyTenants } from '../../spaces/scopes'
import { groupNameSeen } from '../../spaces/groupNamesSeen'
import { RotationPlanScopeKindEnum, type RotationPlanScope, type RotationScopeState } from '../../../api/generated/account'
import { unlockOrigin } from '../../../app/vault'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../../../shared/passwordPolicy'
import { useBreachCheck } from '../passwordWarning'
import PasswordBreachNotice from '../PasswordBreachNotice.vue'
import EmailAddressSection from '../email/EmailAddressSection.vue'
import TrustResetLink from '../trustReset/TrustResetLink.vue'
import EmergencyKitSave from '../emergencyKit/EmergencyKitSave.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const { t } = useI18n()

/** The ceremony's step before the publish: the gate (fields), then the new kit. The result replaces both. */
const step = ref<'gate' | 'kit'>('gate')
const password = ref('')
const secretKey = ref('')
/** "Also choose a new password" — pre-selected, not forced (decision 7). */
const choosePassword = ref(true)
const newPassword = ref('')
const confirmPassword = ref('')
const { state: breachState, useAnyway, checkNow: checkBreaches, proceed: breachCheckPasses } = useBreachCheck(newPassword)
/** The Secret Key the publish installs, minted when the panel opens and printed into the new Emergency Kit. */
const newSecretKey = ref(mintSecretKey())
const kitReady = ref(false)
const confirmOpen = ref(false)
const error = ref('')
/** E8 — this device refuses a trust document; the error then points at the reset. */
const trustRefused = ref(false)
const result = ref<RotationResult | null>(null)

/** The account rotation's plan (decision 5) — every tenant's rows, so the lists below name a space by its kind. */
const plan = ref<RotationPlanScope[]>([])
/** The entry's facts from the plan (decision 11); null until read, and left null when the read fails. */
const entry = ref<RotationEntry | null>(null)
/** Every scope of this account's last rotation, as the SERVER reads it (decision 8) — on every visit. */
const queued = ref<RotationScopeState[]>([])
/** A tenant root's plaintext name by tenant id (a tenant root's scope id is its tenant id). */
const tenantNames = ref<Record<string, string>>({})
/** The caller's own links that need renewing (decision 13), read after a rotation and on a visit after one. */
const links = ref<LinkToRenew[]>([])
const renewDialog = shallowRef<RenewDialog | null>(null)
const renewError = ref('')

onMounted(async () => {
  await Promise.all([loadEntry(), loadQueued(), loadTenantNames()])
  if (queued.value.length) await loadLinks()
})
onBeforeUnmount(forgetSecrets)

/** The plan first — a cheap read the labels need at once — then the entry's facts, whose dry run opens every grant. */
async function loadEntry() {
  plan.value = await readRotationPlan().catch(() => [])
  entry.value = await readRotationEntry(plan.value).catch(() => null)
}
async function loadQueued() {
  queued.value = await listRotationStates().catch(() => [])
}
async function loadTenantNames() {
  try {
    tenantNames.value = Object.fromEntries((await listMyTenants()).map((tn) => [tn.tenantId, tn.name]))
  } catch {
    /* neutral labels */
  }
}
async function loadLinks() {
  links.value = await listLinksToRenew().catch(() => [])
}

/** What a space is called on this device, never its raw id (inbox #280/#281): a tenant root by its name, the personal
 *  space as such (from the plan's kind, which every tenant's rows carry), a group by the name this device last opened. */
type Label = { text: string; stale: boolean }
function scopeLabel(scopeId: string): Label {
  if (tenantNames.value[scopeId]) return { text: tenantNames.value[scopeId], stale: false }
  if (planRow(scopeId)?.kind === RotationPlanScopeKindEnum.Personal) return { text: t('rotation.personalSpace'), stale: false }
  const seen = groupNameSeen(scopeId)
  return seen ? { text: seen, stale: true } : { text: t('rotation.scopeNameUnavailable'), stale: true }
}
const planRow = (scopeId: string) => plan.value.find((r) => r.scopeId === scopeId)
/** The name of the tenant a link's space lives in, for "open that tenant". */
function tenantOf(scopeId: string): string {
  const tenantId = planRow(scopeId)?.tenantId
  return (tenantId && tenantNames.value[tenantId]) || t('rotation.scopeNameUnavailable')
}

/** E1 — this session was not opened by a cold credential, so the screen says why the Secret Key is asked for. */
const coldUnlock = computed(() => unlockOrigin() === 'cold')
const passwordChosen = computed(
  () => !choosePassword.value || (newPassword.value.length >= MIN_PASSWORD_LENGTH && confirmPassword.value.length > 0),
)
const gateFilled = computed(() => password.value.length > 0 && secretKey.value.trim().length > 0 && passwordChosen.value)
/** The publish waits for the new kit: downloaded AND confirmed (decision 7, `EmergencyKitSave`'s ready). */
const canPublish = computed(() => step.value === 'kit' && kitReady.value && !!newSecretKey.value)

/**
 * The gate: the current credential is proven against the account's floor BEFORE a kit is printed for it — a kit whose
 * credential then fails would be one the person saved for nothing. A floor that opens the account's NEWER Master Key
 * (`vaultStale`: an earlier publish committed) skips the kit: nothing is minted again, the mechanism finishes
 * (decision 12), and the kit printed here would never be published.
 */
const { run: proveGate, pending: proving } = useAsyncAction(
  async () => {
    if (choosePassword.value) {
      if (newPassword.value !== confirmPassword.value) {
        error.value = t('changePassword.mismatch')
        return
      }
      if (!(await breachCheckPasses())) return
    }
    try {
      ;(await proveColdCredential(password.value, secretKey.value.trim())).fill(0)
    } catch (e) {
      if (e instanceof ColdCredentialRefused && e.reason === 'vaultStale') return publish()
      throw e
    }
    step.value = 'kit'
  },
  { onError: (e) => showError(e) },
)

const { run: publish, pending: busy } = useAsyncAction(
  async () => {
    const outcome = await rotateKeys(password.value, secretKey.value.trim(), {
      secretKeyB64: newSecretKey.value,
      password: choosePassword.value ? newPassword.value : undefined,
    })
    // Spent: the secrets leave this component now. The screen stays mounted to render the result.
    forgetSecrets()
    confirmOpen.value = false
    result.value = outcome
    queued.value = outcome.states
    await Promise.all([loadEntry(), loadLinks()])
  },
  { onError: (e) => showError(e) },
)

function showError(e: unknown) {
  trustRefused.value = e instanceof RotationRefused && e.reason === 'trustStateRefused'
  error.value = errorText(e)
}

/** The copy for a refused or failed step. An unknown outcome is never "nothing was changed" (decision 12): the vault
 *  is locked, and the person unlocks again with the new kit. A credential the floor refuses names the new kit too: if
 *  the account was secured meanwhile, the old kit is the one that no longer opens it. */
function errorText(e: unknown): string {
  if (e instanceof RotationOutcomeUnknown) return t('rotation.errorUnknownOutcome')
  const reason = e instanceof RotationRefused || e instanceof ColdCredentialRefused ? e.reason : null
  switch (reason) {
    case 'credentialRejected':
    case 'vaultStale':
      return t('rotation.errorCredential')
    case 'noFloorWrap':
      return t('rotation.errorNoFloor')
    case 'vaultLocked':
      return t('rotation.errorLocked')
    case 'trustStateRefused':
      return t('rotation.errorTrustState')
    case 'requestTooLarge':
      return t('rotation.errorTooLarge')
    default:
      return t('rotation.errorFailed')
  }
}

async function toKit() {
  error.value = ''
  await proveGate()
}
async function confirm() {
  error.value = ''
  await publish()
}
function back() {
  error.value = ''
  step.value = 'gate'
}

/** Drop every secret this panel holds — once spent, and when the panel is left (decision 11). */
function forgetSecrets() {
  password.value = ''
  secretKey.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  newSecretKey.value = ''
  closeRenew()
}

const { run: renew, pending: opening } = useAsyncAction(
  async (link: LinkToRenew) => {
    renewError.value = ''
    const dialog = await openRenewDialog(link)
    if (!dialog) renewError.value = t('rotation.renewFailed')
    renewDialog.value = dialog
  },
  {
    onError: () => {
      renewError.value = t('rotation.renewFailed')
    },
  },
)
function closeRenew() {
  renewDialog.value?.release()
  renewDialog.value = null
}
async function renewClosed() {
  closeRenew()
  await loadLinks()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <!-- The address a reset would mail, and whether it is confirmed (uc.account.confirm-email-address) — the
         rubric's first section, above the ceremony that ends a compromise. -->
    <EmailAddressSection />
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('rotation.title') }}</h2>
          <p>{{ t('rotation.sub') }}</p>
        </div>
      </div>

      <template v-if="!result">
        <!-- The entry: what happens, in plain words, before any field (decision 11). -->
        <div v-if="step === 'gate'" class="rotation-explain">
          <div>
            <h3>{{ t('rotation.afterTitle') }}</h3>
            <ul class="rotation-facts">
              <li><VeyqIcon name="users" /><span>{{ t('rotation.factSharedSpaces') }}</span></li>
              <li><VeyqIcon name="sign-out" /><span>{{ t('rotation.factSignsOut') }}</span></li>
              <li><VeyqIcon name="id-card" /><span>{{ t('rotation.factNewKit') }}</span></li>
              <li><VeyqIcon name="key" /><span>{{ t('rotation.factPassword') }}</span></li>
              <li v-if="entry?.signedOpenInvites" data-testid="rotation-fact-invites">
                <VeyqIcon name="envelope" /><span>{{ t('rotation.factInvites') }}</span>
              </li>
              <li><VeyqIcon name="history" /><span>{{ t('rotation.factNotRepair') }}</span></li>
              <li><VeyqIcon name="link" /><span>{{ t('rotation.factLinks') }}</span></li>
            </ul>
          </div>
          <div v-if="entry?.blocked.length" data-testid="rotation-blocked">
            <h3>{{ t('rotation.blockedTitle') }}</h3>
            <ul class="rotation-results">
              <li v-for="b in entry.blocked" :key="b.scopeId" class="veyq-list-row" :data-blocked-scope="b.scopeId">
                <span :class="{ 'rotation-name-stale': scopeLabel(b.scopeId).stale }">{{ scopeLabel(b.scopeId).text }}</span>
                <span class="veyq-badge is-blocked">{{ t('rotation.status.blocked') }}</span>
                <small>{{ t(`rotation.blocked.${b.reason}`) }}</small>
              </li>
            </ul>
          </div>
        </div>

        <!-- The gate: password + current Secret Key, and the new password (pre-selected). -->
        <template v-if="step === 'gate'">
          <!-- E1. This session did not prove the Secret Key when it unlocked, so say why it is asked for now. -->
          <VeyqMessage v-if="!coldUnlock" tone="info">{{ t('rotation.gateNotCold') }}</VeyqMessage>

          <!-- `veyq-form` on a div, not a <form>: Enter must not run past the kit and the confirm dialog. -->
          <div class="veyq-form">
            <div class="settings-field">
              <label for="rotation-password">{{ t('rotation.passwordLabel') }}</label>
              <VeyqPassword input-id="rotation-password" v-model="password" autocomplete="current-password" :disabled="proving || busy" />
            </div>
            <div class="settings-field">
              <label for="rotation-secret-key">{{ t('rotation.secretKeyLabel') }}</label>
              <SecretKeyField v-model="secretKey" input-id="rotation-secret-key" :expected-email="account.email" :disabled="proving || busy" />
              <small>{{ t('rotation.secretKeyHint') }}</small>
            </div>
            <label class="rotation-choice">
              <VeyqCheckbox v-model="choosePassword" input-id="rotation-new-password-choice" :disabled="proving || busy" />
              <span>{{ t('rotation.newPassword') }}</span>
            </label>
            <template v-if="choosePassword">
              <div class="settings-field">
                <label for="rotation-new-password">{{ t('changePassword.newPasswordLabel') }}</label>
                <VeyqPassword
                  input-id="rotation-new-password"
                  v-model="newPassword"
                  autocomplete="new-password"
                  :disabled="proving || busy"
                  @blur="checkBreaches"
                />
                <small v-if="passwordHintKey(newPassword)" class="field-hint">{{ t(passwordHintKey(newPassword)!) }}</small>
              </div>
              <PasswordBreachNotice v-model:use-anyway="useAnyway" :state="breachState" id-prefix="rotation" :disabled="proving || busy" />
              <div class="settings-field">
                <label for="rotation-new-password-confirm">{{ t('changePassword.confirmPasswordLabel') }}</label>
                <VeyqPassword input-id="rotation-new-password-confirm" v-model="confirmPassword" autocomplete="new-password" :disabled="proving || busy" />
              </div>
            </template>
          </div>

          <VeyqMessage v-if="error" tone="error">
            {{ error }}
            <TrustResetLink v-if="trustRefused" />
          </VeyqMessage>

          <div class="rotation-action">
            <VeyqButton
              type="button"
              :label="t('rotation.next')"
              icon="arrow-right"
              :disabled="!gateFilled || proving || busy"
              :loading="proving || busy"
              @click="toKit"
            />
          </div>
        </template>

        <!-- The new Emergency Kit: the publish installs its Secret Key, so it is saved and confirmed first (decision 7). -->
        <template v-else>
          <h3>{{ t('rotation.kitTitle') }}</h3>
          <EmergencyKitSave v-if="newSecretKey" v-model="kitReady" :email="account.email" :secret-key="newSecretKey">
            <p>{{ t('rotation.kitSub') }}</p>
            <VeyqMessage tone="info">{{ t('rotation.kitKeepOld') }}</VeyqMessage>
          </EmergencyKitSave>

          <VeyqMessage v-if="error" tone="error">
            {{ error }}
            <TrustResetLink v-if="trustRefused" />
          </VeyqMessage>

          <div class="rotation-action veyq-action-group">
            <VeyqButton type="button" :label="t('rotation.back')" icon="arrow-left" variant="secondary" appearance="outlined" :disabled="busy" @click="back" />
            <VeyqButton
              type="button"
              :label="t('rotation.action')"
              icon="refresh"
              variant="danger"
              appearance="outlined"
              :disabled="!canPublish || busy"
              @click="confirmOpen = true"
            />
          </div>
        </template>
      </template>

      <!-- The result: the account is published; how each space stands is the server's word, below. -->
      <template v-else>
        <VeyqMessage tone="info" data-testid="rotation-done">{{ t('rotation.doneOldKit') }} {{ t('rotation.doneSpaces') }}</VeyqMessage>
      </template>

      <!-- Every space of this account's last rotation, as the server reads it now — on every visit, not only right after
           it. A `waiting` space gets its new lock the next time one of this account's devices opens veyq. -->
      <section v-if="queued.length" class="rotation-queued">
        <h3>{{ t('rotation.queuedTitle') }}</h3>
        <ul class="rotation-results">
          <li v-for="q in queued" :key="q.scopeId" class="veyq-list-row" :data-queued-scope="q.scopeId">
            <span>
              {{ scopeLabel(q.scopeId).text }}
              <small v-if="scopeLabel(q.scopeId).stale && groupNameSeen(q.scopeId)" class="rotation-name-stale">{{ t('rotation.groupNameLastSeen') }}</small>
            </span>
            <span class="veyq-badge" :class="`is-${q.state}`">{{ t(`rotation.status.${q.state}`) }}</span>
            <small v-if="q.state === 'blocked'">{{ q.reason ? t(`rotation.blocked.${q.reason}`) : '' }}</small>
            <small v-else>{{ t(`rotation.queuedHint.${q.state}`) }}</small>
          </li>
        </ul>
      </section>

      <!-- The links made before, which stay open until renewed (decision 13): the caller's own, by space. -->
      <section v-if="links.length" class="rotation-queued" data-testid="rotation-links">
        <h3>{{ t('rotation.linksTitle') }}</h3>
        <VeyqMessage v-if="renewError" tone="error">{{ renewError }}</VeyqMessage>
        <ul class="rotation-results">
          <li v-for="l in links" :key="l.share.shareId" class="veyq-list-row" :data-renew-share="l.share.shareId">
            <span>
              {{ l.name ?? t('rotation.linkNameUnavailable') }}
              <small class="rotation-name-stale">{{ scopeLabel(l.share.scopeId).text }}</small>
            </span>
            <VeyqButton
              v-if="l.here"
              type="button"
              :label="l.upload ? t('rotation.linkOpen') : t('rotation.renew')"
              icon="replay"
              variant="secondary"
              appearance="outlined"
              size="small"
              :disabled="opening"
              @click="renew(l)"
            />
            <small v-if="!l.here">{{ t('rotation.linkElsewhere', { tenant: tenantOf(l.share.scopeId) }) }}</small>
            <small v-else-if="l.upload">{{ t('rotation.linkUpload') }}</small>
          </li>
        </ul>
      </section>
    </section>

    <!-- The node's own share dialog — the kind's, through the registry (`linksToRenew.ts`). -->
    <component :is="renewDialog.component" v-if="renewDialog" v-bind="renewDialog.props" @close="renewClosed" />

    <VeyqDialog
      v-model:visible="confirmOpen"
      :header="t('rotation.confirmTitle')"
      :closable="!busy"
      :close-on-escape="!busy"
    >
      <p class="dialog-copy">{{ t('rotation.confirmBody') }}</p>
      <VeyqMessage v-if="error" tone="error">
        {{ error }}
        <TrustResetLink v-if="trustRefused" />
      </VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton
          type="button"
          :label="t('common.cancel')"
          icon="times"
          variant="secondary"
          appearance="outlined"
          :disabled="busy"
          @click="confirmOpen = false"
        />
        <VeyqButton
          type="button"
          :label="t('rotation.action')"
          icon="refresh"
          variant="danger"
          :disabled="!canPublish"
          :loading="busy"
          @click="confirm"
        />
      </div>
    </VeyqDialog>
  </section>
</template>

<style scoped>
.rotation-explain {
  display: grid;
  gap: 1rem;
  margin-bottom: 1rem;
}
.rotation-explain h3 {
  margin-bottom: 0.35rem;
}
.rotation-facts {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}
.rotation-facts li {
  display: flex;
  align-items: start;
  gap: 0.5rem;
}
.rotation-facts .veyq-icon {
  color: var(--app-muted);
  margin-top: 0.15rem;
}
.rotation-choice {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.rotation-action {
  display: flex;
  gap: 0.5rem;
  margin-top: 1rem;
}
.rotation-results {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
}
/* A state's hint is a sentence: in the list row's `auto` column it squeezed the space's name under the badge. Name and
   badge share the first line; the hint takes the whole width below. */
.rotation-results .veyq-list-row {
  grid-template-columns: minmax(0, 1fr) auto;
}
.rotation-results .veyq-list-row > small {
  grid-column: 1 / -1;
}
.rotation-queued {
  margin-top: 1.5rem;
}
.rotation-queued h3 {
  margin: 0 0 0.5rem;
  font-size: 1rem;
}
.rotation-name-stale {
  color: var(--app-muted);
}
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
