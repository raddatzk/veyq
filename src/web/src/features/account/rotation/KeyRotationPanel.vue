<script setup lang="ts">
// The "Security" section of the settings hub — uc.account.rotate-keys, ADR 0060. Its first section is the account's
// email address (`EmailAddressSection`, uc.account.confirm-email-address); everything below it is rotation, the
// one surface that ends a compromise: someone holds this account's key material (a phished device-link grant),
// and signing that device out is not enough, because a revoke takes back no key.
//
// **Three things this screen must never do**, each of which is a way to make the feature worse than absent:
//
//  - **Imply that rotating undoes anything.** It is forward secrecy, not repair. The copy says what was already
//    read stays read, before any button, and the result screen is a per-scope status rather than a success tick.
//  - **Soften the cold gate.** The rotation runs only against a live proof of password + Secret Key, checked
//    against the stored floor wrap (E1). The attacker this defends against holds a device wrap and nothing
//    colder, so they fail that proof — but a legitimate session opened by a device wrap upgrades *in place*,
//    which is what E1 asks for: turning it away adds nothing over the proof and sends somebody already
//    holding their Emergency Kit back to the sign-in screen for nothing.
//  - **Pre-tick or default the action.** Every other device is signed out and loses its saved key; the person
//    who lands on the consequence is the one holding another device, and they will need their Emergency Kit.
//
// Markup + classes mirror ProfilePanel (veyq-surface + settings-form + settings-section-header, a confirm
// Dialog with veyq-dialog-action-row) and the result list mirrors the veyq-list-row rows of SessionsPanel — the
// built panels are the design source. (It used to name DeviceTrustPanel, which stopped being a panel at all
// when the device rubric was folded into the sessions row — a design-source pointer goes stale silently, since
// nothing compiles it.)
//
// **Phase 5 of cr-secure-the-account takes this panel over** (the ceremony: the entry screen from the plan in place of
// `soleOwnerSpaces`, "Also choose a new password", the steps, the result's links to renew, the copy EN + DE). What
// phase 4 changed here is only what the mechanism needs to stay safe to call: the new Emergency Kit is minted, saved and
// confirmed (`EmergencyKitSave`) before the button can publish — the publish installs that Secret Key — and every state
// and error the mechanism answers renders (`errorUnknownOutcome` included).
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import { account } from '../../../app/account'
import VeyqPassword from '../../../shared/ui/VeyqPassword.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { mintSecretKey, rotateKeys, RotationOutcomeUnknown, RotationRefused, type RotationResult } from './rotateKeys'
import { listRotationStates } from './finishRotation'
import { listMyScopes, listMyTenants } from '../../spaces/scopes'
import { soleOwnerSpaces, type SoleOwnerSpace } from './soleOwnerSpaces'
import { groupNameSeen } from '../../spaces/groupNamesSeen'
import type { RotationScopeState } from '../../../api/generated/account'
import { ScopeKind } from '../../../api/generated/scopes'
import { unlockOrigin } from '../../../app/vault'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import EmailAddressSection from '../email/EmailAddressSection.vue'
import TrustResetLink from '../trustReset/TrustResetLink.vue'
import EmergencyKitSave from '../emergencyKit/EmergencyKitSave.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const { t } = useI18n()

const password = ref('')
const secretKey = ref('')
const confirmOpen = ref(false)
const error = ref('')
/** E8 — the rotation refused because this device refuses a trust document; the error then points at the reset. */
const trustRefused = ref(false)
const result = ref<RotationResult | null>(null)
/** The Secret Key the rotation installs, minted when the panel opens and printed into the new Emergency Kit; the
 *  publish waits until that kit is downloaded AND confirmed (decision 7) — it is the only copy the person will have. */
const newSecretKey = ref(mintSecretKey())
const kitReady = ref(false)

/**
 * Every scope of this account's last rotation, as the SERVER reads it (decision 8) — on every visit, and again right
 * after a rotation, so `waiting` is the server's word from the first render on.
 */
const queued = ref<RotationScopeState[]>([])
/** A tenant root's plaintext name by scope id (scopeId == tenantId). A group's name shows as this page last opened it,
 *  marked as possibly out of date ({@link groupNameSeen}, memory only), or a neutral label — never the raw id
 *  (inbox #280). */
const tenantNames = ref<Record<string, string>>({})
/** The kind of each scope the active tenant lists for this account, by scope id — so the personal space is named as
 *  such (inbox #281). A scope of another tenant is not listed here and reads neutral. */
const scopeKinds = ref<Record<string, ScopeKind>>({})
/** The shared spaces this account owns alone, named before the button (uc.account.rotate-keys E4); empty hides the
 *  line, and so does a read that fails. (Phase 5 replaces it with the entry screen from the plan.) */
const soleOwned = ref<SoleOwnerSpace[]>([])

async function loadQueued() {
  try {
    queued.value = await listRotationStates()
  } catch {
    /* the section stays empty; the rotation itself is unaffected */
  }
  await loadLabels()
}
onMounted(loadQueued)
onMounted(async () => {
  soleOwned.value = await soleOwnerSpaces().catch(() => [])
})

/** The device-local labels both lists use instead of a raw scope id; a failed read leaves the neutral label. */
async function loadLabels() {
  if (!queued.value.length) return
  try {
    tenantNames.value = Object.fromEntries((await listMyTenants()).map((tn) => [tn.tenantId, tn.name]))
  } catch {
    /* neutral labels */
  }
  try {
    scopeKinds.value = Object.fromEntries((await listMyScopes()).map((s) => [s.id, s.kind]))
  } catch {
    /* neutral labels */
  }
}

/**
 * Whether this session was *already* opened by a cold credential. It gates nothing — the live proof in
 * `rotateKeys` does that — it decides whether the screen explains why the Secret Key is being asked for here
 * (E1). A device-opened or restored session upgrades in place by supplying it.
 */
const coldUnlock = computed(() => unlockOrigin() === 'cold')
const canSubmit = computed(() => password.value.length > 0 && secretKey.value.trim().length > 0 && kitReady.value)

const { run, pending: busy } = useAsyncAction(
  async () => {
    const outcome = await rotateKeys(password.value, secretKey.value.trim(), { secretKeyB64: newSecretKey.value })
    // The secrets leave this component the moment they are spent — the screen stays mounted afterwards to
    // render the per-scope status, and there is no reason for it to still be holding them.
    password.value = ''
    secretKey.value = ''
    newSecretKey.value = ''
    confirmOpen.value = false
    result.value = outcome
    queued.value = outcome.states
    await loadLabels()
  },
  {
    onError: (e) => {
      trustRefused.value = e instanceof RotationRefused && e.reason === 'trustStateRefused'
      error.value = errorText(e)
    },
  },
)

/** The copy for a failed rotation. An unknown outcome is never "nothing was changed" (decision 12): the vault is
 *  locked, and the person unlocks again with the new kit. */
function errorText(e: unknown): string {
  if (e instanceof RotationOutcomeUnknown) return t('rotation.errorUnknownOutcome')
  if (!(e instanceof RotationRefused)) return t('rotation.errorFailed')
  switch (e.reason) {
    case 'noFloorWrap':
      return t('rotation.errorNoFloor')
    case 'vaultLocked':
      return t('rotation.errorLocked')
    case 'trustStateRefused':
      return t('rotation.errorTrustState')
    case 'requestTooLarge':
      return t('rotation.errorTooLarge')
    default:
      return t('rotation.errorCredential')
  }
}

async function confirm() {
  error.value = ''
  await run()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <!-- The address a reset would mail, and whether it is confirmed (uc.account.confirm-email-address) — the
         Security rubric's first section, above the rotation that ends a compromise. -->
    <EmailAddressSection />
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('rotation.title') }}</h2>
          <p>{{ t('rotation.sub') }}</p>
        </div>
      </div>

      <!-- Step 1: which keys, why, when, and what this costs, before any button. Not a disclosure to be scrolled
           past — this is what decides whether the person should be on this screen at all. -->
      <template v-if="!result">
        <div class="rotation-explain">
          <div>
            <h3>{{ t('rotation.whatTitle') }}</h3>
            <p>{{ t('rotation.whatBody') }}</p>
          </div>
          <div>
            <h3>{{ t('rotation.whyTitle') }}</h3>
            <p>{{ t('rotation.whyBody') }}</p>
          </div>
          <div>
            <h3>{{ t('rotation.whenTitle') }}</h3>
            <p>{{ t('rotation.whenBody') }}</p>
          </div>
          <div>
            <h3>{{ t('rotation.afterTitle') }}</h3>
            <ul class="rotation-facts">
              <li><VeyqIcon name="id-card" /><span>{{ t('rotation.factIdentity') }}</span></li>
              <li><VeyqIcon name="users" /><span>{{ t('rotation.factSharedSpaces') }}</span></li>
              <li><VeyqIcon name="sign-out" /><span>{{ t('rotation.factSignsOut') }}</span></li>
              <li><VeyqIcon name="envelope" /><span>{{ t('rotation.factInvites') }}</span></li>
              <li><VeyqIcon name="history" /><span>{{ t('rotation.factNotRepair') }}</span></li>
              <li><VeyqIcon name="link" /><span>{{ t('rotation.factLinks') }}</span></li>
            </ul>
          </div>
          <ul class="rotation-facts">
            <li v-if="soleOwned.length" data-testid="rotation-sole-owner">
              <VeyqIcon name="crown" />
              <div>
                <span>{{ t('rotation.soleOwner') }}</span>
                <ul class="rotation-sole-owner">
                  <li v-for="s in soleOwned" :key="s.scopeId">{{ s.name ?? t('rotation.scopeNameUnavailable') }}</li>
                </ul>
              </div>
            </li>
            <li><VeyqIcon name="key" /><span>{{ t('rotation.factNeedsKit') }}</span></li>
          </ul>
        </div>

        <!-- E1. This session did not prove the Secret Key when it unlocked, so say why it is being asked for
             now — rather than letting the field read as an unexplained extra hurdle. -->
        <VeyqMessage v-if="!coldUnlock" tone="info">{{ t('rotation.gateNotCold') }}</VeyqMessage>

        <!-- `veyq-form` for the field widths and spacing its siblings get (ChangePasswordPanel), on a div: a
             <form> would let Enter submit past the confirm dialog. -->
        <div class="veyq-form">
          <div class="settings-field">
            <label for="rotation-password">{{ t('rotation.passwordLabel') }}</label>
            <VeyqPassword
              input-id="rotation-password"
              v-model="password"
              autocomplete="current-password"
              :disabled="busy"
            />
          </div>
          <div class="settings-field">
            <label for="rotation-secret-key">{{ t('rotation.secretKeyLabel') }}</label>
            <SecretKeyField v-model="secretKey" input-id="rotation-secret-key"
                            :expected-email="account.email" :disabled="busy" />
            <small>{{ t('rotation.secretKeyHint') }}</small>
          </div>
        </div>

        <!-- The new Emergency Kit: the publish installs its Secret Key, so it is saved and confirmed first (decision 7). -->
        <EmergencyKitSave v-if="newSecretKey" v-model="kitReady" :email="account.email" :secret-key="newSecretKey" />

        <VeyqMessage v-if="error" tone="error">
          {{ error }}
          <TrustResetLink v-if="trustRefused" />
        </VeyqMessage>

        <div class="rotation-action">
          <VeyqButton
            type="button"
            :label="t('rotation.action')"
            icon="refresh"
            variant="danger"
            appearance="outlined"
            :disabled="!canSubmit || busy"
            @click="confirmOpen = true"
          />
        </div>
      </template>

      <!-- Step 9: the rotation is published; how each space stands is the server's word, below. -->
      <template v-else>
        <VeyqMessage tone="info">{{ t('rotation.doneNotRepair') }}</VeyqMessage>
      </template>

      <!-- Every space of this account's last rotation, as the server reads it now — on every visit, not only right after
           the rotation. A `waiting` space gets its new lock the next time one of this account's devices opens veyq. -->
      <section v-if="queued.length" class="rotation-queued">
        <h3>{{ t('rotation.queuedTitle') }}</h3>
        <ul class="rotation-results">
          <li v-for="q in queued" :key="q.scopeId" class="veyq-list-row" :data-queued-scope="q.scopeId">
            <span v-if="tenantNames[q.scopeId]">{{ tenantNames[q.scopeId] }}</span>
            <span v-else-if="scopeKinds[q.scopeId] === ScopeKind.Personal">{{ t('rotation.personalSpace') }}</span>
            <span v-else-if="groupNameSeen(q.scopeId)">
              {{ groupNameSeen(q.scopeId) }}
              <small class="rotation-name-stale">{{ t('rotation.groupNameLastSeen') }}</small>
            </span>
            <span v-else class="rotation-name-stale">{{ t('rotation.scopeNameUnavailable') }}</span>
            <span class="veyq-badge" :class="`is-${q.state}`">{{ t(`rotation.status.${q.state}`) }}</span>
            <small v-if="q.state === 'blocked'">{{ q.reason ? t(`rotation.blocked.${q.reason}`) : '' }}</small>
            <small v-else>{{ t(`rotation.queuedHint.${q.state}`) }}</small>
          </li>
        </ul>
      </section>
    </section>

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
.rotation-sole-owner {
  margin: 0.25rem 0 0;
  padding-left: 1.1rem;
}
.rotation-facts .rotation-sole-owner li {
  display: list-item;
}
.rotation-facts .veyq-icon {
  color: var(--app-muted);
  margin-top: 0.15rem;
}
.rotation-action {
  display: flex;
  margin-top: 1rem;
}
.rotation-results {
  list-style: none;
  margin: 1rem 0 0;
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
