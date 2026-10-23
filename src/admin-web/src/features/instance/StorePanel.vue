<script setup lang="ts">
// ADR 0089 — the "Object store" rubric of the server settings: the one instance SETTING the console holds today.
// Built on the Config-secret panel's shape (the same kind of screen: a state the operator acts on), plus the form
// the tenant detail panel's quota form is the nearest built one of.
//
// What it must keep honest:
//   - the form shows the WHOLE setting it sends, so a cleared public endpoint clears it on the server;
//   - secrets are write-only: a field left empty means "keep the stored one", never "empty" — except where nothing
//     usable is stored (first entry, a switch from the bundled store, or a value the current config-secret key
//     cannot open), where the field is required and the page says why;
//   - saving changes nothing the instance does: the setting waits as PENDING beside the one in use until its check
//     passes, which puts it in use (decision 5) — so a typo never reaches the media. The form edits what was last
//     saved (the pending setting, else the one in use); only a passed check of the setting in use opens tenant
//     creation, and the check's every step is shown, because "failed" alone does not say what to fix.
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqSelect from '../../shared/ui/VeyqSelect.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import type { VeyqIconName } from '../../shared/ui/icons'
import {
  BUNDLED_STORE_UPDATE,
  checkInstanceStore,
  discardPendingInstanceStore,
  editedSetting,
  getInstanceStore,
  InstanceStoreCheckStepOutcomeEnum,
  InstanceStoreSecret,
  InstanceStoreType,
  ownStoreUpdate,
  setInstanceStore,
  storeError,
} from './store'
import type { InstanceStore, InstanceStoreCheck, InstanceStoreUpdate } from './store'
import StoreProvisioningPanel from './StoreProvisioningPanel.vue'
import StoreReconcilePanel from './StoreReconcilePanel.vue'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'
import { handle401 } from '../../shared/handle401'

const emit = defineEmits<{ changed: [store: InstanceStore] }>()
const { t } = useI18n()
const toast = useToast()
const on401 = handle401(useRouter())

/** What the first field chooses: the store the deployment brings, or one of the two types of a store of one's own. */
type Source = 'BUNDLED' | InstanceStoreType

const store = ref<InstanceStore | null>(null)
const source = ref<Source>(InstanceStoreType.S3)
const form = reactive({
  endpoint: '',
  publicEndpoint: '',
  bucket: '',
  region: '',
  accessKey: '',
  secretKey: '',
  garageAdminEndpoint: '',
  garageAdminToken: '',
})
const error = ref('')

function show(s: InstanceStore) {
  store.value = s
  const setting = editedSetting(s)
  // A fresh install of a bundled recipe starts on the bundled store: the server sets and checks it on its own
  // (ADR 0089 decision 3), so the page usually opens on that result.
  source.value = setting ? (setting.bundled ? 'BUNDLED' : setting.type) : s.bundledAvailable ? 'BUNDLED' : InstanceStoreType.S3
  form.endpoint = setting?.endpoint ?? ''
  form.publicEndpoint = setting?.publicEndpoint ?? ''
  form.bucket = setting?.bucket ?? ''
  form.region = setting?.region ?? ''
  form.accessKey = setting?.accessKey ?? ''
  form.garageAdminEndpoint = setting?.garageAdminEndpoint ?? ''
  form.secretKey = ''
  form.garageAdminToken = ''
  emit('changed', s)
}

const { pending: loading, error: loadErr } = useAsyncData(async () => show(await getInstanceStore()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

/** The setting in use — what the backend signs against. */
const setting = computed(() => store.value?.setting ?? null)
/** The setting the form edits: the pending one, else the one in use. */
const edited = computed(() => editedSetting(store.value))
const pending = computed(() => store.value?.pending ?? null)
const isBundled = computed(() => source.value === 'BUNDLED')
const isGarage = computed(() => source.value === InstanceStoreType.Garage)
/** Once blobs exist the type, endpoint and bucket are fixed (ADR 0089 decision 6); the server refuses a change. */
const fixed = computed(() => store.value?.blobsStored === true)

const sources = computed(() => [
  ...(store.value?.bundledAvailable ? [{ value: 'BUNDLED' as Source, label: t('store.sourceBundled') }] : []),
  { value: InstanceStoreType.S3 as Source, label: t('store.sourceS3') },
  { value: InstanceStoreType.Garage as Source, label: t('store.sourceGarage') },
])

const unreadable = computed(() => edited.value?.unreadable ?? [])
/** A secret has to be typed when nothing usable is stored: none yet, a bundled setting (whose secrets are the
 *  deployment's), or one the current config-secret key cannot open. */
const needsSecretKey = computed(
  () => !edited.value || edited.value.bundled || !edited.value.secretKeySet || unreadable.value.includes(InstanceStoreSecret.SecretKey),
)
const needsGarageToken = computed(
  () =>
    !edited.value ||
    edited.value.bundled ||
    edited.value.type !== InstanceStoreType.Garage ||
    !edited.value.garageAdminTokenSet ||
    unreadable.value.includes(InstanceStoreSecret.GarageAdminToken),
)
const unreadableNames = computed(() => unreadable.value.map((s) => t(`store.secret.${s}`)).join(', '))

const canSave = computed(() => {
  if (isBundled.value) return true
  const f = form
  const filled = [f.endpoint, f.bucket, f.region, f.accessKey].every((v) => v.trim() !== '')
  if (!filled || (needsSecretKey.value && !f.secretKey)) return false
  if (isGarage.value && (f.garageAdminEndpoint.trim() === '' || (needsGarageToken.value && !f.garageAdminToken))) return false
  return true
})

function buildUpdate(): InstanceStoreUpdate {
  if (isBundled.value) return BUNDLED_STORE_UPDATE
  return ownStoreUpdate({ ...form, type: isGarage.value ? InstanceStoreType.Garage : InstanceStoreType.S3 })
}

const SAVE_REFUSALS: Record<string, string> = {
  invalid_request: 'store.saveInvalid',
  no_bundled_store: 'store.saveNoBundled',
  store_in_use: 'store.saveInUse',
  store_secret_unavailable: 'store.saveSecretUnavailable',
}

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    show(await setInstanceStore(buildUpdate()))
    toast.success(t('store.saved'))
  },
  {
    onError: async (e) => {
      on401(e)
      const { status, code } = await storeError(e)
      if (status !== 401) error.value = t((code && SAVE_REFUSALS[code]) || 'store.saveFailed')
    },
  },
)
function save() {
  if (!canSave.value) return
  error.value = ''
  return runSave()
}

const { run: runCheck, pending: checking } = useAsyncAction(
  async () => {
    await checkInstanceStore()
    // Read the store again rather than patching the result in: a passing check of a pending setting put it in use.
    adoptCheck(await getInstanceStore())
  },
  {
    onError: async (e) => {
      on401(e)
      const { status, code } = await storeError(e)
      if (status !== 401) error.value = t(code === 'store_not_configured' ? 'store.checkNotConfigured' : 'store.checkFailed')
    },
  },
)
/** A check changes nothing the form shows, so it must not reset what the operator is typing. */
function adoptCheck(s: InstanceStore) {
  store.value = s
  emit('changed', s)
}
function check() {
  error.value = ''
  return runCheck()
}

const { run: runDiscard, pending: discarding } = useAsyncAction(
  async () => {
    show(await discardPendingInstanceStore())
    toast.success(t('store.discarded'))
  },
  {
    onError: async (e) => {
      on401(e)
      const { status } = await storeError(e)
      if (status !== 401) error.value = t('store.discardFailed')
    },
  },
)
function discard() {
  error.value = ''
  return runDiscard()
}

/** The check shown: the pending setting's while one waits, else the setting in use's. */
const lastCheck = computed<InstanceStoreCheck | null>(() => (pending.value ? pending.value.lastCheck : store.value?.lastCheck) ?? null)
const busy = computed(() => saving.value || checking.value || discarding.value)

const OUTCOME_ICON: Record<InstanceStoreCheckStepOutcomeEnum, VeyqIconName> = {
  [InstanceStoreCheckStepOutcomeEnum.Passed]: 'check-circle',
  [InstanceStoreCheckStepOutcomeEnum.Failed]: 'times-circle',
  [InstanceStoreCheckStepOutcomeEnum.Skipped]: 'minus-circle',
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('store.navTitle') }}</h2>
          <p>{{ t('store.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('store.loading') }}</p>
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="info">{{ t('store.notOwner') }}</VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">{{ t('store.loadFailed') }}</VeyqMessage>

      <form v-else-if="store" class="veyq-form" @submit.prevent="save">
        <VeyqMessage v-if="unreadable.length" tone="warn" class="store-unreadable">
          {{ t('store.unreadable', { secrets: unreadableNames }) }}
        </VeyqMessage>
        <VeyqMessage v-if="fixed" tone="info">{{ t('store.fixed') }}</VeyqMessage>
        <VeyqMessage v-if="pending" tone="warn" class="store-pending">
          {{ setting ? t('store.pendingNote') : t('store.pendingFirst') }}
        </VeyqMessage>

        <label>
          {{ t('store.source') }}
          <VeyqSelect
            v-model="source"
            :options="sources"
            :disabled="busy || fixed"
            input-id="store-source"
            class="store-source"
          />
        </label>

        <VeyqMessage v-if="isBundled" tone="secondary">{{ t('store.bundledNote') }}</VeyqMessage>

        <template v-else>
          <label>
            {{ t('store.endpoint') }}
            <VeyqInput v-model="form.endpoint" autocomplete="off" name="endpoint" :disabled="busy || fixed" />
            <small class="settings-muted">{{ t('store.endpointHint') }}</small>
          </label>
          <label>
            {{ t('store.publicEndpoint') }}
            <VeyqInput v-model="form.publicEndpoint" autocomplete="off" name="publicEndpoint" :disabled="busy" />
            <small class="settings-muted">{{ t('store.publicEndpointHint') }}</small>
          </label>
          <label>
            {{ t('store.bucket') }}
            <VeyqInput v-model="form.bucket" autocomplete="off" name="bucket" :disabled="busy || fixed" />
            <small class="settings-muted">{{ isGarage ? t('store.bucketHintGarage') : t('store.bucketHintS3') }}</small>
          </label>
          <label>
            {{ t('store.region') }}
            <VeyqInput v-model="form.region" autocomplete="off" name="region" :disabled="busy" />
          </label>
          <label>
            {{ t('store.accessKey') }}
            <VeyqInput v-model="form.accessKey" autocomplete="off" name="accessKey" :disabled="busy" />
          </label>
          <label>
            {{ t('store.secretKey') }}
            <VeyqInput
              v-model="form.secretKey"
              type="password"
              autocomplete="new-password"
              name="secretKey"
              :required="needsSecretKey"
              :placeholder="needsSecretKey ? '' : t('store.secretKept')"
              :disabled="busy"
            />
            <small class="settings-muted">{{ t('store.secretHint') }}</small>
          </label>
          <template v-if="isGarage">
            <label>
              {{ t('store.garageAdminEndpoint') }}
              <VeyqInput v-model="form.garageAdminEndpoint" autocomplete="off" name="garageAdminEndpoint" :disabled="busy" />
            </label>
            <label>
              {{ t('store.garageAdminToken') }}
              <VeyqInput
                v-model="form.garageAdminToken"
                type="password"
                autocomplete="new-password"
                name="garageAdminToken"
                :required="needsGarageToken"
                :placeholder="needsGarageToken ? '' : t('store.secretKept')"
                :disabled="busy"
              />
            </label>
          </template>
        </template>

        <VeyqMessage v-if="error" tone="error" class="store-error">{{ error }}</VeyqMessage>
        <div class="veyq-dialog-action-row">
          <VeyqButton
            :label="t('store.check')"
            icon="check-circle"
            variant="secondary"
            appearance="outlined"
            :loading="checking"
            :disabled="(!setting && !pending) || saving || discarding"
            class="store-check"
            @click="check"
          />
          <VeyqButton
            v-if="pending"
            :label="t('store.discard')"
            icon="undo"
            variant="secondary"
            appearance="text"
            :loading="discarding"
            :disabled="saving || checking"
            class="store-discard"
            @click="discard"
          />
          <VeyqButton type="submit" :label="t('store.save')" icon="save" :loading="saving" :disabled="!canSave || checking || discarding" class="store-save" />
        </div>
      </form>
    </section>

    <section v-if="store && !loading" class="settings-form store-result">
      <h3>{{ pending ? t('store.checkTitlePending') : t('store.checkTitle') }}</h3>
      <!-- With the hint and nothing saved, the server is about to set the bundled store itself: only the seconds before
           its boot worker adopts it (ADR 0089 decision 3). -->
      <VeyqMessage v-if="!setting && !pending" tone="info" class="store-not-set">
        {{ store.bundledAvailable ? t('store.adopting') : t('store.notSet') }}
      </VeyqMessage>
      <!-- A first setting has no store in use beside it, so its texts say what a first one means. -->
      <VeyqMessage v-else-if="!lastCheck" tone="warn" class="store-unchecked">
        {{ pending ? (setting ? t('store.pendingUnchecked') : t('store.pendingFirstUnchecked')) : t('store.unchecked') }}
      </VeyqMessage>
      <template v-else>
        <VeyqMessage :tone="lastCheck.passed ? 'success' : 'error'" class="store-verdict">
          {{
            pending
              ? setting
                ? t('store.pendingCheckFailedAt')
                : t('store.pendingFirstCheckFailedAt')
              : lastCheck.passed
                ? t('store.checkPassed')
                : t('store.checkFailedAt')
          }}
        </VeyqMessage>
        <ol class="store-steps">
          <li v-for="s in lastCheck.steps" :key="s.step" :class="`store-step store-step--${s.outcome.toLowerCase()}`" :data-step="s.step">
            <VeyqIcon :name="OUTCOME_ICON[s.outcome]" />
            <span>
              <strong>{{ t(`store.step.${s.step}`) }}</strong> — {{ t(`store.outcome.${s.outcome}`) }}
              <small v-if="s.detail" class="settings-muted store-step__detail">{{ s.detail }}</small>
            </span>
          </li>
        </ol>
        <p class="settings-muted">{{ t('store.checkedAt', { at: lastCheck.checkedAt.toLocaleString() }) }}</p>
      </template>
    </section>

    <!-- What the Garage provisioning run did after the last start (ADR 0067) — only a Garage store has one. -->
    <StoreProvisioningPanel v-if="store && setting && setting.type === InstanceStoreType.Garage" />
    <!-- What the bucket reconcile found in the store in use, and its switches (uc.admin.configure-object-store A7). -->
    <StoreReconcilePanel v-if="store && setting && !loading" />
  </section>
</template>

<style scoped>
.store-source {
  max-width: 24rem;
}
.store-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 0.4rem;
}
.store-step {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;
}
.store-step__detail {
  display: block;
}
.store-step--passed .veyq-icon {
  color: var(--app-success, #2e7d32);
}
.store-step--failed .veyq-icon {
  color: var(--app-danger, #c62828);
}
.store-step--skipped .veyq-icon {
  color: var(--app-muted);
}
</style>
