<script setup lang="ts">
// Making a new Emergency Kit when a clearer one exists (uc.account.renew-emergency-kit steps 3-6, ADR 0157 decision 6),
// opened from Settings → Security (`EmergencyKitStatus`). Proven before printed: the password and the Secret Key (typed,
// or the old kit uploaded or scanned into the field) are checked against the stored `PASSWORD` floor
// (`proveColdCredential`: the vault is unlocked here), and only the key that passed is rendered into a kit. A refused
// proof renders nothing and writes nothing (E1). The check seals the kit record with the Master Key it proved and drops
// the key; the record is written once `EmergencyKitSave` says the kit was saved and kept, never at render. A write that
// fails leaves a valid kit and says so (E3).
//
// Markup mirrors the address change's kit offer (`ConfirmEmailChangeView`) inside a dialog: the built screens are the
// design source.
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqPassword from '../../../shared/ui/VeyqPassword.vue'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import { secretKeyReady } from '../../../shared/ui/secretKeyEntry'
import { account } from '../../../app/account'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { ColdCredentialRefused, proveColdCredential, staleAsRejected } from '../coldCredential'
import EmergencyKitSave from './EmergencyKitSave.vue'
import { sealKitRecord, writeKitRecord } from './kitRecord'

const visible = defineModel<boolean>('visible', { required: true })
const { t } = useI18n()

const password = ref('')
const secretKey = ref('')
/** The key the kit prints — set only by a passed proof, and dropped the moment the typed key changes. */
const checkedKey = ref('')
const kitReady = ref(false)
const error = ref('')
/** What became of the record once the kit was kept: written, or not (E3). */
const outcome = ref<'written' | 'notNoted' | null>(null)
/** The record of the kit printed from `checkedKey`, sealed by the proof that passed it. */
let kitRecord: string | null = null

const { run: check, pending: checking } = useAsyncAction(
  async () => {
    error.value = ''
    forgetKit()
    const key = secretKey.value.trim()
    // A vault behind a secured account is "didn't match" here, as on every credential screen but the rotation's.
    const mk = await proveColdCredential(password.value, key).catch(staleAsRejected)
    try {
      kitRecord = sealKitRecord(mk)
    } finally {
      mk.fill(0)
    }
    checkedKey.value = key
    password.value = ''
  },
  {
    onError: (e) => {
      error.value = t(e instanceof ColdCredentialRefused && e.reason === 'credentialRejected' ? 'kit.renew.refused' : 'kit.renew.failed')
    },
  },
)

function onKeyInput(value: string) {
  secretKey.value = value
  if (value.trim() !== checkedKey.value) forgetKit()
}

async function onKept() {
  const sealed = kitRecord
  if (!sealed) return
  try {
    await writeKitRecord(sealed)
    outcome.value = 'written'
  } catch {
    outcome.value = 'notNoted'
  }
}

/** Drop the kit of the last proof: its key, its record, and whether it was kept. */
function forgetKit() {
  checkedKey.value = ''
  kitRecord = null
  kitReady.value = false
  outcome.value = null
}

// Every secret goes when the dialog does; it opens on an empty form.
watch(visible, (open) => {
  if (open) return
  password.value = ''
  secretKey.value = ''
  error.value = ''
  forgetKit()
})
</script>

<template>
  <VeyqDialog v-model:visible="visible" :header="t('kit.renew.title')" :closable="!checking" :close-on-escape="!checking">
    <p class="dialog-copy">{{ t('kit.renew.sub') }}</p>
    <form class="veyq-form" data-testid="kit-renew-check" @submit.prevent="check()">
      <div class="settings-field">
        <label for="kit-renew-password">{{ t('kit.renew.passwordLabel') }}</label>
        <VeyqPassword v-model="password" input-id="kit-renew-password" autocomplete="current-password" :disabled="checking" />
      </div>
      <div class="settings-field">
        <label for="kit-renew-secret-key">{{ t('kit.renew.secretKeyLabel') }}</label>
        <SecretKeyField
          :model-value="secretKey"
          input-id="kit-renew-secret-key"
          :expected-email="account.email"
          :disabled="checking"
          @update:model-value="onKeyInput"
        />
      </div>
      <VeyqMessage v-if="error" tone="error" data-testid="kit-renew-error">{{ error }}</VeyqMessage>
      <VeyqButton
        v-if="!checkedKey"
        type="submit"
        :label="t('kit.renew.check')"
        icon="check"
        :disabled="!password || !secretKeyReady(secretKey) || checking"
        :loading="checking"
      />
    </form>
    <EmergencyKitSave v-if="checkedKey" v-model="kitReady" :email="account.email" :secret-key="checkedKey" @kept="onKept" />
    <VeyqMessage v-if="outcome === 'written'" tone="success" data-testid="kit-renew-done">{{ t('kit.renew.done') }}</VeyqMessage>
    <VeyqMessage v-if="outcome === 'notNoted'" tone="warn" data-testid="kit-renew-not-noted">{{ t('kit.renew.notNoted') }}</VeyqMessage>
    <div class="veyq-dialog-action-row">
      <VeyqButton
        type="button"
        :label="t('common.close')"
        icon="times"
        variant="secondary"
        appearance="outlined"
        :disabled="checking"
        @click="visible = false"
      />
    </div>
  </VeyqDialog>
</template>
