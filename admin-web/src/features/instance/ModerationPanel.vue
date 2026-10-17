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
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import {
  appointModerator, getModeration, ModeratorState, removeModerator, rotateModerationKey, RotationReason,
  type InstanceModeration, type InstanceModerator,
} from './moderation'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'

const { t, locale } = useI18n()
const { ask } = useConfirm()
const on401 = handle401(useRouter())

const moderation = ref<InstanceModeration | null>(null)
const address = ref('')
const reason = ref<RotationReason>(RotationReason.ByHand)
const error = ref('')
const busy = ref(false)

// A rotation that is due on arrival was most likely caused by a holder leaving, so the reason starts there; the
// Server Owner changes it when it was an identity rotation (the server says that one is due, not why).
const { pending: loading, error: loadErr } = useAsyncData(
  async () => {
    moderation.value = await getModeration()
    if (moderation.value.rotationDueSince) reason.value = RotationReason.Removal
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
.mod-rotate {
  display: grid;
  gap: 0.5rem;
}
h3 {
  margin: 0.75rem 0 0;
  font-size: 1rem;
}
</style>
