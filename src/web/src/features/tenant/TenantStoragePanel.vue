<script setup lang="ts">
// uc.sharing.share-link-storage-limit (web, ADR 0073 §4, §6) — the "Storage" rubric of the tenant settings. The
// tenant OWNER sees what the tenant occupies in the store and sets the one per-link limit every upload link
// (READ_WRITE / WRITE_ONLY) stores at most. The mail-settings panel's shape: the read is owner-gated, so a non-owner
// member's 403 renders a read-only notice and a non-member's 404 the generic load error (ADR 0042). Operator-blind:
// only byte counts the server already sees, and only the tenant's total — never a figure per member or link.
// The limit is entered in GiB and sent in bytes; `0` switches link uploads off, and there is no "unlimited".
// Beside the usage sits the tenant's quota when the Server Owner set one (uc.admin.tenant-storage-quota): read-only
// here — no tenant-owner operation writes it, so the owner distributes below it and cannot raise it (ADR 0073 §1).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import { getTenantStorage, updateTenantStorage, formatGib, GIB, type TenantStorage } from './tenantStorage'
import { activeSpace } from '../../app/activeSpace'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'
import { formatBytes } from '../../shared/formatBytes'

const { t, locale } = useI18n()
const toast = useToast()
const on401 = handle401(useRouter())

const usedBytes = ref(0)
const quotaBytes = ref<number | null>(null)
// The limit as typed, in GiB (a decimal is allowed: 0.5 GiB is a real limit).
const limitGib = ref('')
// The limit as loaded: a field nobody edited saves these bytes, never the rounded GiB it shows — otherwise a page
// saved unchanged moves the limit, and one under 0.01 GiB, shown as "0", switched link uploads off.
const loaded = ref({ text: '', bytes: 0 })
const actionError = ref('')

function applyView(s: TenantStorage) {
  usedBytes.value = s.usedBytes
  quotaBytes.value = s.quotaBytes ?? null
  limitGib.value = formatGib(s.shareLinkLimitBytes)
  loaded.value = { text: limitGib.value, bytes: s.shareLinkLimitBytes }
}

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  if (!activeSpace.tenantId) throw new Error('no-active-tenant') // no HTTP status → classified 'failed' below
  applyView(await getTenantStorage(activeSpace.tenantId))
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const used = computed(() => formatBytes(usedBytes.value, locale.value, 'binary'))
const quota = computed(() => (quotaBytes.value === null ? null : formatBytes(quotaBytes.value, locale.value, 'binary')))
// Accepts "2", "2.5" and the German "2,5"; anything else (empty, negative, text) is not a limit.
const limitBytes = computed<number | null>(() => {
  if (limitGib.value.trim() === loaded.value.text) return loaded.value.bytes
  const n = Number(limitGib.value.trim().replace(',', '.'))
  return limitGib.value.trim() !== '' && Number.isFinite(n) && n >= 0 ? Math.round(n * GIB) : null
})

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    applyView(await updateTenantStorage(activeSpace.tenantId!, limitBytes.value!))
    toast.success(t('storage.saved'))
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { status } = await decodeHttpError(e)
      actionError.value = status === HttpStatus.forbidden ? t('storage.notOwner') : t('storage.saveFailed')
    },
  },
)

async function save() {
  actionError.value = ''
  if (limitBytes.value === null) {
    actionError.value = t('storage.invalidLimit')
    return
  }
  await runSave()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('storage.navTitle') }}</h2>
          <p>{{ t('storage.editorSub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('storage.loading') }}</p>
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="info">{{ t('storage.notOwner') }}</VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">{{ t('storage.loadFailed') }}</VeyqMessage>

      <template v-else>
        <VeyqMessage v-if="actionError" tone="error">{{ actionError }}</VeyqMessage>

        <p class="storage-used">
          <span>{{ t('storage.used') }}</span>
          <strong>{{ used }}</strong>
        </p>
        <div v-if="quota !== null" class="storage-quota">
          <p class="storage-used">
            <span>{{ t('storage.quota') }}</span>
            <strong>{{ quota }}</strong>
          </p>
          <small class="field-hint">{{ t('storage.quotaHint') }}</small>
        </div>

        <label>
          <span>{{ t('storage.linkLimit') }}</span>
          <VeyqInput v-model="limitGib" inputmode="decimal" autocomplete="off" />
          <small class="field-hint">{{ t('storage.linkLimitHint') }}</small>
        </label>

        <div class="storage-actions">
          <VeyqButton :label="t('storage.save')" icon="save" :loading="saving" @click="save" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.storage-used {
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  margin: 0;
}
.storage-quota {
  display: grid;
  gap: 0.25rem;
}
.storage-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
</style>
