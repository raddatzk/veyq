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
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import { account } from '../../../app/account'
import Password from 'primevue/password'
import Message from 'primevue/message'
import { listQueuedRotations, requeueQueuedRotation, rotateKeys, RotationRefused, type RotationResult } from './rotateKeys'
import { listMyScopes, listMyTenants } from '../../spaces/scopes'
import { groupNameSeen } from '../../spaces/groupNamesSeen'
import type { RotationScopeState } from '../../../api/generated/account'
import { ScopeKind } from '../../../api/generated/scopes'
import { unlockOrigin } from '../../../app/vault'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import EmailAddressSection from '../email/EmailAddressSection.vue'
import TrustResetLink from '../trustReset/TrustResetLink.vue'

const { t } = useI18n()

const password = ref('')
const secretKey = ref('')
const confirmOpen = ref(false)
const error = ref('')
/** E8 — the rotation refused because this device refuses a trust document; the error then points at the reset. */
const trustRefused = ref(false)
const result = ref<RotationResult | null>(null)

/**
 * Every rotation this account's rotation queued, as the SERVER reads it (uc.account.rotate-keys §9, ADR 0060
 * Decision 6) — on every visit, and again right after a rotation, so `waiting` is the server's word from the first
 * render on, and a rotation that dead-lettered reads `failed` instead of a `waiting` nothing would ever update.
 */
const queued = ref<RotationScopeState[]>([])
/** A tenant root's plaintext name by scope id (scopeId == tenantId). A group's name is sealed under a key this
 *  account cannot open until it is re-admitted, so its row shows the name this page last opened, marked as
 *  possibly out of date ({@link groupNameSeen}, memory only), or a neutral label — never the raw id (inbox #280). */
const tenantNames = ref<Record<string, string>>({})
/** The kind of each scope the active tenant lists for this account, by scope id — so the result list (the scopes
 *  this device re-keyed alone, whose ids the rotation answers without a kind) can name the personal space as such
 *  (inbox #281). A sole-member scope of another tenant is not listed here and reads neutral. */
const scopeKinds = ref<Record<string, ScopeKind>>({})
const requeueing = ref<string | null>(null)
const requeueError = ref('')

async function loadQueued() {
  try {
    queued.value = await listQueuedRotations()
  } catch {
    /* the section stays empty; the rotation itself is unaffected */
  }
  await loadLabels()
}
onMounted(loadQueued)

/** The device-local labels both lists use instead of a raw scope id; a failed read leaves the neutral label. */
async function loadLabels() {
  const alone = result.value?.scopes.length ?? 0
  if (!queued.value.length && !alone) return
  try {
    tenantNames.value = Object.fromEntries((await listMyTenants()).map((tn) => [tn.tenantId, tn.name]))
  } catch {
    /* neutral labels */
  }
  if (!alone) return
  try {
    scopeKinds.value = Object.fromEntries((await listMyScopes()).map((s) => [s.id, s.kind]))
  } catch {
    /* neutral labels */
  }
}

/** `failed` only: queue it again, this account still excluded; the row shows the state the server answers. */
async function requeue(scopeId: string) {
  requeueError.value = ''
  requeueing.value = scopeId
  try {
    const now = await requeueQueuedRotation(scopeId)
    queued.value = queued.value.map((q) => (q.scopeId === scopeId ? now : q))
  } catch {
    requeueError.value = t('rotation.requeueFailed')
  } finally {
    requeueing.value = null
  }
}

/**
 * Whether this session was *already* opened by a cold credential. It gates nothing — the live proof in
 * `rotateKeys` does that — it decides whether the screen explains why the Secret Key is being asked for here
 * (E1). A device-opened or restored session upgrades in place by supplying it.
 */
const coldUnlock = computed(() => unlockOrigin() === 'cold')
const canSubmit = computed(() => password.value.length > 0 && secretKey.value.trim().length > 0)

const { run, pending: busy } = useAsyncAction(
  async () => {
    const outcome = await rotateKeys(password.value, secretKey.value.trim())
    // The secrets leave this component the moment they are spent — the screen stays mounted afterwards to
    // render the per-scope status, and there is no reason for it to still be holding them.
    password.value = ''
    secretKey.value = ''
    confirmOpen.value = false
    result.value = outcome
    await loadQueued()
  },
  {
    onError: (e) => {
      trustRefused.value = e instanceof RotationRefused && e.reason === 'trustStateRefused'
      error.value =
        e instanceof RotationRefused
          ? e.reason === 'noFloorWrap'
            ? t('rotation.errorNoFloor')
            : e.reason === 'vaultLocked'
              ? t('rotation.errorLocked')
              : e.reason === 'trustStateRefused'
                ? t('rotation.errorTrustState')
                : t('rotation.errorCredential')
          : t('rotation.errorFailed')
    },
  },
)

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
              <li><i class="pi pi-id-card" aria-hidden="true" /><span>{{ t('rotation.factIdentity') }}</span></li>
              <li><i class="pi pi-users" aria-hidden="true" /><span>{{ t('rotation.factSharedSpaces') }}</span></li>
              <li><i class="pi pi-sign-out" aria-hidden="true" /><span>{{ t('rotation.factSignsOut') }}</span></li>
              <li><i class="pi pi-envelope" aria-hidden="true" /><span>{{ t('rotation.factInvites') }}</span></li>
              <li><i class="pi pi-history" aria-hidden="true" /><span>{{ t('rotation.factNotRepair') }}</span></li>
            </ul>
          </div>
          <ul class="rotation-facts">
            <li><i class="pi pi-crown" aria-hidden="true" /><span>{{ t('rotation.soleOwner') }}</span></li>
            <li><i class="pi pi-key" aria-hidden="true" /><span>{{ t('rotation.factNeedsKit') }}</span></li>
          </ul>
        </div>

        <!-- E1. This session did not prove the Secret Key when it unlocked, so say why it is being asked for
             now — rather than letting the field read as an unexplained extra hurdle. -->
        <Message v-if="!coldUnlock" severity="info" :closable="false">{{ t('rotation.gateNotCold') }}</Message>

        <div class="settings-field">
          <label for="rotation-password">{{ t('rotation.passwordLabel') }}</label>
          <Password
            id="rotation-password"
            v-model="password"
            :feedback="false"
            toggle-mask
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

        <Message v-if="error" severity="error" :closable="false">
          {{ error }}
          <TrustResetLink v-if="trustRefused" />
        </Message>

        <div class="rotation-action">
          <Button
            type="button"
            :label="t('rotation.action')"
            icon="pi pi-refresh"
            severity="danger"
            outlined
            :disabled="!canSubmit || busy"
            @click="confirmOpen = true"
          />
        </div>
      </template>

      <!-- Step 9: an honest per-scope status for what this device re-keyed alone (step 6). -->
      <template v-else>
        <Message severity="info" :closable="false">{{ t('rotation.doneNotRepair') }}</Message>
        <ul class="rotation-results">
          <li v-for="s in result.scopes" :key="s.scopeId" class="veyq-list-row" :data-alone-scope="s.scopeId">
            <span v-if="tenantNames[s.scopeId]">{{ tenantNames[s.scopeId] }}</span>
            <span v-else-if="scopeKinds[s.scopeId] === ScopeKind.Personal">{{ t('rotation.personalSpace') }}</span>
            <span v-else-if="groupNameSeen(s.scopeId)">
              {{ groupNameSeen(s.scopeId) }}
              <small class="rotation-name-stale">{{ t('rotation.groupNameLastSeen') }}</small>
            </span>
            <span v-else class="rotation-name-stale">{{ t('rotation.scopeNameUnavailable') }}</span>
            <span class="veyq-badge" :class="`is-${s.status}`">{{ t(`rotation.status.${s.status}`) }}</span>
            <small>{{ t(`rotation.statusHint.${s.status}`) }}</small>
          </li>
        </ul>
      </template>

      <!-- The shared spaces a rotation of this account queued, as the server reads them now — on every visit, not
           only after the rotation that queued them. Until a row reads secured, the old key still reads what is new
           in that space; the copy says so (forward secrecy, not repair). The re-queue is offered on failed only,
           the one state it can move; stalled carries the promote-a-co-owner hint instead (E4). -->
      <section v-if="queued.length" class="rotation-queued">
        <h3>{{ t('rotation.queuedTitle') }}</h3>
        <ul class="rotation-results">
          <li v-for="q in queued" :key="q.scopeId" class="veyq-list-row" :data-queued-scope="q.scopeId">
            <span v-if="tenantNames[q.scopeId]">{{ tenantNames[q.scopeId] }}</span>
            <span v-else-if="groupNameSeen(q.scopeId)">
              {{ groupNameSeen(q.scopeId) }}
              <small class="rotation-name-stale">{{ t('rotation.groupNameLastSeen') }}</small>
            </span>
            <span v-else class="rotation-name-stale">{{ t('rotation.groupNameUnavailable') }}</span>
            <span class="veyq-badge" :class="`is-${q.state}`">{{ t(`rotation.status.${q.state}`) }}</span>
            <small>{{ t(`rotation.queuedHint.${q.state}`) }}</small>
            <Button
              v-if="q.state === 'failed'"
              type="button"
              :label="t('rotation.requeue')"
              icon="pi pi-replay"
              size="small"
              outlined
              :loading="requeueing === q.scopeId"
              @click="requeue(q.scopeId)"
            />
          </li>
        </ul>
        <Message v-if="requeueError" severity="error" :closable="false">{{ requeueError }}</Message>
      </section>
    </section>

    <Dialog
      v-model:visible="confirmOpen"
      :header="t('rotation.confirmTitle')"
      modal
      :closable="!busy"
      :close-on-escape="!busy"
    >
      <p class="dialog-copy">{{ t('rotation.confirmBody') }}</p>
      <Message v-if="error" severity="error" :closable="false">
        {{ error }}
        <TrustResetLink v-if="trustRefused" />
      </Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="busy"
          @click="confirmOpen = false"
        />
        <Button
          type="button"
          :label="t('rotation.action')"
          icon="pi pi-refresh"
          severity="danger"
          :loading="busy"
          @click="confirm"
        />
      </div>
    </Dialog>
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
.rotation-facts .pi {
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
