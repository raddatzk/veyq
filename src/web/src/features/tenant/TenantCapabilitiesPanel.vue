<script setup lang="ts">
// uc.admin.tenant-switch-capabilities (ADR 0088 decisions 2, 12, 13) — the "Capabilities" rubric of the tenant settings,
// beside Terms, Email and Storage. Ported from admin-web's CapabilitiesPanel (the Server Owner's register, which this
// package cannot import) and built on the storage panel's shape: the read is owner-gated, so a non-owner member's 403
// renders a read-only notice and a non-member's 404 the generic load error (ADR 0042).
//
// What it must keep honest, as the Server Owner's panel does:
//   - every word about an entry is the server's, served from the catalog in both languages and picked by the app's
//     locale — never written into this package;
//   - the state shown is the server's `effective` and `serverOff`; "off through" only names the nearest ancestor that is
//     itself off, and "off for the whole server" says the tenant's switch cannot bring it back;
//   - switching off asks first and shows the entry's own `whenOff` (a share switch stops existing links at once; an
//     application's says its content is erased on the day the banner shows); switching on before that day only
//     restores, so it asks nothing;
//   - an application's row names the day its content is erased, the server's `erasureDueAt`, and says who can still
//     cancel it (`erasureNote`, uc.platform.erase-switched-off-application);
//   - a row re-renders from the PUT's answer, and the shell's own set is re-read, so the change shows at once.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import {
  clearTenantCapabilityRequirement,
  erasureNote,
  hasDescendants,
  inLocale,
  listTenantCapabilitySwitches,
  setTenantCapabilityRequirement,
  setTenantCapabilitySwitch,
  tenantCapabilityTree,
  type CapabilityText,
  type ErasureNote,
  type TenantCapabilityRequirement,
  type TenantCapabilityState,
  type TenantCapabilitySwitch,
} from './tenantCapabilities'
import { activeSpace, refreshTenantCapabilities } from '../../app/activeSpace'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'

const { t, locale } = useI18n()
const { ask } = useConfirm()
const on401 = handle401(useRouter())

const entries = ref<TenantCapabilitySwitch[]>([])
const error = ref('')
/** The id whose switch is in flight; one at a time, so a second click cannot race the first one's answer. */
const switching = ref<string | null>(null)

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  if (!activeSpace.tenantId) throw new Error('no-active-tenant') // no HTTP status → classified 'failed' below
  entries.value = await listTenantCapabilitySwitches(activeSpace.tenantId)
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const tree = computed(() => tenantCapabilityTree(entries.value))
const words = (text: CapabilityText) => inLocale(text, locale.value)

const ERASURE_TEXT = { day: 'erasureDay', byServer: 'erasureDayByServer', running: 'erasureRunning' } as const
/** The row's erasure line in the web's day format (as `groups.pendingDeletion`), or '' when it names no day. */
function erasureText(entry: TenantCapabilitySwitch): string {
  const note: ErasureNote | null = erasureNote(entry, new Date())
  if (!note) return ''
  return t(`tenantCapabilities.${ERASURE_TEXT[note.kind]}`, { day: note.day.toLocaleDateString(locale.value) })
}

const STATE_TONE = { on: 'success', off: 'danger', offThrough: 'warning', serverOff: 'warning' } as const
function stateLabel(state: TenantCapabilityState): string {
  if (state.kind === 'offThrough') return t('tenantCapabilities.offThrough', { parent: words(state.through.title) })
  return t(`tenantCapabilities.${state.kind}`)
}

// --- requirements (ADR 0088 decision 8): a flag set or cleared, or a number of days; the value in effect is the
// strictest of the tenant's and the server's, as the server computes and returns it. Setting one asks first and shows
// what it does to what exists (the requirement's own `whenSet`); clearing one only loosens, so it asks nothing.
/** The days typed per requirement name, before they are saved. */
const daysTyped = ref<Record<string, string>>({})
function requirementState(r: TenantCapabilityRequirement): string {
  const value = (v: number) => (r.kind === 'flag' ? t('tenantCapabilities.requirementOn') : t('tenantCapabilities.requirementDays', { days: v }))
  if (r.effective === null || r.effective === undefined) return t('tenantCapabilities.requirementNone')
  if (r.value !== r.effective) return t('tenantCapabilities.requirementByServer', { value: value(r.effective) })
  return value(r.effective)
}

async function changeRequirement(entry: TenantCapabilitySwitch, r: TenantCapabilityRequirement, value: number | null) {
  const tenantId = activeSpace.tenantId
  if (!tenantId) return
  const title = words(r.title)
  if (value !== null) {
    const accepted = await ask({
      header: t('tenantCapabilities.requirementConfirmTitle', { title }),
      message: words(r.whenSet),
      acceptLabel: t('tenantCapabilities.requirementSet'),
      acceptIcon: 'lock',
    })
    if (!accepted) return
  }
  error.value = ''
  switching.value = `${entry.id}:${r.name}`
  try {
    const now = value === null
      ? await clearTenantCapabilityRequirement(tenantId, entry.id, r.name)
      : await setTenantCapabilityRequirement(tenantId, entry.id, r.name, value)
    entries.value = entries.value.map((e) => (e.id === now.id ? now : e))
    await refreshTenantCapabilities(tenantId) // the shell's own values (the share dialogs) follow at once
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    error.value = t('tenantCapabilities.switchFailed', { title })
  } finally {
    switching.value = null
  }
}

function saveDays(entry: TenantCapabilitySwitch, r: TenantCapabilityRequirement) {
  const days = Number((daysTyped.value[r.name] ?? '').trim())
  if (!Number.isInteger(days) || days < 1 || days > 3650) {
    error.value = t('tenantCapabilities.requirementInvalidDays')
    return
  }
  void changeRequirement(entry, r, days)
}

async function toggle(entry: TenantCapabilitySwitch) {
  const tenantId = activeSpace.tenantId
  if (!tenantId) return
  const off = !entry.off
  const title = words(entry.title)
  if (off) {
    const accepted = await ask({
      header: t('tenantCapabilities.confirmTitle', { title }),
      message: words(entry.whenOff),
      acceptLabel: t('tenantCapabilities.switchOff'),
      acceptIcon: 'power-off',
    })
    if (!accepted) return
  }
  error.value = ''
  switching.value = entry.id
  try {
    const now = await setTenantCapabilitySwitch(tenantId, entry.id, off)
    entries.value = entries.value.map((e) => (e.id === now.id ? now : e))
    // The descendants' `effective` is the server's AND and just changed with this switch; read it, never derive it.
    if (hasDescendants(now.id, entries.value)) entries.value = await listTenantCapabilitySwitches(tenantId)
    await refreshTenantCapabilities(tenantId) // the shell's own set (hasCapability) follows at once
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await decodeHttpError(e)
    error.value = t(code === 'not_tenant_owner' ? 'tenantCapabilities.notOwner' : 'tenantCapabilities.switchFailed', { title })
  } finally {
    switching.value = null
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('tenantCapabilities.navTitle') }}</h2>
          <p>{{ t('tenantCapabilities.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('tenantCapabilities.loading') }}</p>
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="info">{{ t('tenantCapabilities.notOwner') }}</VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">{{ t('tenantCapabilities.loadFailed') }}</VeyqMessage>

      <template v-else>
        <VeyqMessage v-if="error" tone="error" class="capability-error">{{ error }}</VeyqMessage>
        <ul class="capability-tree">
          <li
            v-for="node in tree"
            :key="node.entry.id"
            class="capability"
            :data-id="node.entry.id"
            :style="{ '--capability-depth': node.depth }"
          >
            <div class="capability__text">
              <strong class="capability__title">{{ words(node.entry.title) }}</strong>
              <span class="veyq-badge capability__state" :class="STATE_TONE[node.state.kind]">{{ stateLabel(node.state) }}</span>
              <small class="settings-muted capability__purpose">{{ words(node.entry.purpose) }}</small>
              <small v-if="erasureText(node.entry)" class="capability__erasure">{{ erasureText(node.entry) }}</small>
              <ul v-if="node.entry.requirements.length" class="capability__requirements">
                <li v-for="r in node.entry.requirements" :key="r.name" class="requirement" :data-requirement="r.name">
                  <span class="requirement__title">{{ words(r.title) }}</span>
                  <span class="veyq-badge requirement__state">{{ requirementState(r) }}</span>
                  <span class="requirement__controls">
                    <template v-if="r.kind === 'days'">
                      <VeyqInput
                        v-model="daysTyped[r.name]"
                        class="requirement__days"
                        inputmode="numeric"
                        :placeholder="r.value ? String(r.value) : ''"
                        :aria-label="words(r.title)"
                        :disabled="switching !== null"
                      />
                      <VeyqButton
                        class="requirement__set"
                        :label="t('tenantCapabilities.requirementSet')"
                        variant="secondary"
                        appearance="outlined"
                        size="small"
                        :disabled="switching !== null"
                        @click="saveDays(node.entry, r)"
                      />
                    </template>
                    <VeyqButton
                      v-else-if="r.value === null || r.value === undefined"
                      class="requirement__set"
                      :label="t('tenantCapabilities.requirementSet')"
                      variant="secondary"
                      appearance="outlined"
                      size="small"
                      :disabled="switching !== null"
                      @click="changeRequirement(node.entry, r, 1)"
                    />
                    <VeyqButton
                      v-if="r.value !== null && r.value !== undefined"
                      class="requirement__clear"
                      :label="t('tenantCapabilities.requirementClear')"
                      variant="secondary"
                      appearance="text"
                      size="small"
                      :disabled="switching !== null"
                      @click="changeRequirement(node.entry, r, null)"
                    />
                  </span>
                </li>
              </ul>
            </div>
            <VeyqButton
              :label="node.entry.off ? t('tenantCapabilities.switchOn') : t('tenantCapabilities.switchOff')"
              :icon="node.entry.off ? 'play' : 'power-off'"
              variant="secondary"
              appearance="outlined"
              size="small"
              :loading="switching === node.entry.id"
              :disabled="switching !== null"
              class="capability__toggle"
              @click="toggle(node.entry)"
            />
          </li>
        </ul>
      </template>
    </section>
  </section>
</template>

<style scoped>
.capability-tree {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 0.75rem;
}
.capability {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
  justify-content: space-between;
  /* The tree is the id path: each entry that is itself an ancestor indents its children one step. */
  padding-inline-start: calc(var(--capability-depth, 0) * 1.5rem);
}
.capability__text {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.5rem;
  align-items: baseline;
  min-width: 0;
}
.capability__purpose,
.capability__erasure,
.capability__requirements {
  flex-basis: 100%;
}
.capability__requirements {
  margin: 0.25rem 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 0.5rem;
}
.requirement {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.5rem;
  align-items: center;
}
.requirement__controls {
  display: inline-flex;
  gap: 0.25rem;
  align-items: center;
}
.requirement__days {
  width: 5rem;
}
.capability__toggle {
  flex-shrink: 0;
}
</style>
