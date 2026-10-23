<script setup lang="ts">
// uc.account.erase-account (admin-web, ADR 0101 decisions 1-3 and 9) — Server → Accounts: the Server Owner's door to an
// account erasure, for a request that reached them another way than the member's own settings, and the list of pending
// erasures with a stop per row. What it keeps honest:
//   - the address is typed twice and the button stays disarmed until both match (`addressConfirmed`, the facade's
//     rule): an erasure is final after the purge, and a typo would erase someone else;
//   - what happens is said before it happens — the account stops at once, it is erased after the retention, it can be
//     stopped until then, the Server Owner's own account cannot be erased, a last owner's tenant is left ownerless, and
//     holds and moderation records stay;
//   - each refusal the server can give is named apart, not one generic failure.
// Operator-blind: an address, who asked and two dates — nothing the account holds.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import {
  addressConfirmed,
  AccountErasureRequester,
  eraseAccount,
  listAccountErasures,
  stopAccountErasure,
  type PendingAccountErasure,
} from './accounts'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'

// `changed` = an erasure started or stopped: a last owner's tenant may have become ownerless (or owned again), which
// the console's tenant list marks — it reads the list again.
const emit = defineEmits<{ changed: [] }>()

const { t, locale } = useI18n()
const on401 = handle401(useRouter())

const address = ref('')
const again = ref('')
const actionError = ref('')
const notice = ref('')
const busy = ref(false)

const at = (d: Date) => d.toLocaleString(locale.value)
const armed = computed(() => addressConfirmed(address.value, again.value))

const { data: erasures, pending: loading, error: loadErr, reload } = useAsyncData(() => listAccountErasures(), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const ERASE_REFUSALS: Record<string, string> = {
  account_not_found: 'accounts.notFound',
  server_owner_account: 'accounts.serverOwner',
  account_erasure_pending: 'accounts.alreadyPending',
  moderation_last_holder: 'accounts.lastModerationHolder',
}

async function erase() {
  if (!armed.value || busy.value) return
  actionError.value = ''
  notice.value = ''
  busy.value = true
  try {
    const pending = await eraseAccount(address.value)
    notice.value = t('accounts.erased', { address: pending.address, date: at(pending.dueAt) })
    address.value = ''
    again.value = ''
    emit('changed')
    await reload()
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await decodeHttpError(e)
    actionError.value = t(ERASE_REFUSALS[code ?? ''] ?? 'accounts.eraseFailed')
  } finally {
    busy.value = false
  }
}

// Stopping only ever gives an account back, so it is not confirm-gated (as bringing a tenant back online is not).
async function stop(row: PendingAccountErasure) {
  actionError.value = ''
  notice.value = ''
  try {
    await stopAccountErasure(row.address)
    notice.value = t('accounts.stopped', { address: row.address })
    emit('changed')
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('accounts.stopFailed')
  }
}

const requestedLine = (row: PendingAccountErasure) =>
  row.requestedBy === AccountErasureRequester.Self
    ? t('accounts.bySelf', { at: at(row.requestedAt) })
    : t('accounts.byServerOwner', { at: at(row.requestedAt) })
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('accounts.title') }}</h2>
          <p>{{ t('accounts.sub') }}</p>
        </div>
      </div>

      <VeyqMessage v-if="actionError" tone="error">{{ actionError }}</VeyqMessage>
      <VeyqMessage v-else-if="notice" tone="success">{{ notice }}</VeyqMessage>

      <form class="veyq-form account-erase" @submit.prevent="erase">
        <ul class="settings-muted account-erase__consequences">
          <li>{{ t('accounts.eraseStops') }}</li>
          <li>{{ t('accounts.eraseAfter') }}</li>
          <li>{{ t('accounts.eraseStoppable') }}</li>
          <li>{{ t('accounts.eraseNotServerOwner') }}</li>
          <li>{{ t('accounts.eraseLastOwner') }}</li>
          <li>{{ t('accounts.eraseStays') }}</li>
        </ul>
        <label>
          {{ t('accounts.address') }}
          <VeyqInput v-model="address" class="account-erase__address" type="email" autocomplete="off" spellcheck="false" />
        </label>
        <label>
          {{ t('accounts.addressAgain') }}
          <VeyqInput v-model="again" class="account-erase__again" type="email" autocomplete="off" spellcheck="false" />
        </label>
        <div class="veyq-dialog-action-row">
          <VeyqButton type="submit" :label="t('accounts.erase')" icon="user-minus" variant="danger" :loading="busy" :disabled="!armed" />
        </div>
      </form>

      <h3 class="account-erasures__title">{{ t('accounts.pendingTitle') }}</h3>
      <p v-if="loading" class="settings-muted">{{ t('accounts.loading') }}</p>
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="info">{{ t('accounts.notOwner') }}</VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">{{ t('accounts.loadFailed') }}</VeyqMessage>
      <p v-else-if="!erasures?.length" class="settings-muted">{{ t('accounts.pendingNone') }}</p>
      <ul v-else class="account-erasures">
        <li v-for="row in erasures" :key="row.address" class="account-erasures__row">
          <div>
            <strong>{{ row.address }}</strong>
            <small class="settings-muted">{{ requestedLine(row) }} · {{ t('accounts.due', { date: at(row.dueAt) }) }}</small>
          </div>
          <VeyqButton :label="t('accounts.stop')" icon="undo" variant="secondary" appearance="outlined" @click="stop(row)" />
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.account-erase__consequences {
  margin: 0;
  padding-left: 1.2rem;
  display: grid;
  gap: 0.25rem;
}
.account-erasures__title {
  margin: 0.5rem 0 0;
  font-size: 1rem;
}
.account-erasures {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 0.5rem;
}
.account-erasures__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.account-erasures__row > div {
  display: grid;
}
</style>
