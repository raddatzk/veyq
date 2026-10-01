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
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import { account } from '../../../app/account'
import Password from 'primevue/password'
import Message from 'primevue/message'
import { rotateKeys, RotationRefused, type RotationResult } from './rotateKeys'
import { unlockOrigin } from '../../../app/vault'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import EmailAddressSection from '../email/EmailAddressSection.vue'

const { t } = useI18n()

const password = ref('')
const secretKey = ref('')
const confirmOpen = ref(false)
const error = ref('')
const result = ref<RotationResult | null>(null)

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
  },
  {
    onError: (e) => {
      error.value =
        e instanceof RotationRefused
          ? e.reason === 'noFloorWrap'
            ? t('rotation.errorNoFloor')
            : e.reason === 'vaultLocked'
              ? t('rotation.errorLocked')
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

      <!-- Step 1: what this costs, before any button. Not a disclosure to be scrolled past — these are the
           three facts that decide whether the person should be on this screen at all. -->
      <template v-if="!result">
        <ul class="rotation-facts">
          <li><i class="pi pi-history" aria-hidden="true" /><span>{{ t('rotation.factNotRepair') }}</span></li>
          <li><i class="pi pi-sign-out" aria-hidden="true" /><span>{{ t('rotation.factSignsOut') }}</span></li>
          <li><i class="pi pi-key" aria-hidden="true" /><span>{{ t('rotation.factNeedsKit') }}</span></li>
          <li><i class="pi pi-users" aria-hidden="true" /><span>{{ t('rotation.factSharedSpaces') }}</span></li>
        </ul>

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

        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

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

      <!-- Step 9: an honest per-scope status. `waiting` is not a spinner — nothing this account does alone
           moves it, and the copy says so rather than suggesting it will resolve itself (E3). -->
      <template v-else>
        <Message severity="info" :closable="false">{{ t('rotation.doneNotRepair') }}</Message>
        <ul class="rotation-results">
          <li v-for="s in result.scopes" :key="s.scopeId" class="veyq-list-row">
            <span class="rotation-scope-id">{{ s.scopeId }}</span>
            <span class="veyq-badge" :class="`is-${s.status}`">{{ t(`rotation.status.${s.status}`) }}</span>
            <small>{{ t(`rotation.statusHint.${s.status}`) }}</small>
          </li>
        </ul>
      </template>
    </section>

    <Dialog
      v-model:visible="confirmOpen"
      :header="t('rotation.confirmTitle')"
      modal
      :closable="!busy"
      :close-on-escape="!busy"
    >
      <p class="dialog-copy">{{ t('rotation.confirmBody') }}</p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
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
.rotation-facts {
  list-style: none;
  margin: 0.5rem 0 1rem;
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
.rotation-scope-id {
  font-family: var(--app-font-mono, monospace);
  font-size: 0.85em;
  color: var(--app-muted);
}
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
