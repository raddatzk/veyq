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
//   - switching off asks first and shows the entry's own `whenOff` (a share switch stops existing links at once);
//     switching on only restores, so it asks nothing;
//   - a row re-renders from the PUT's answer, and the shell's own set is re-read, so the change shows at once.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import {
  hasDescendants,
  inLocale,
  listTenantCapabilitySwitches,
  setTenantCapabilitySwitch,
  tenantCapabilityTree,
  type CapabilityText,
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

const STATE_TONE = { on: 'success', off: 'danger', offThrough: 'warning', serverOff: 'warning' } as const
function stateLabel(state: TenantCapabilityState): string {
  if (state.kind === 'offThrough') return t('tenantCapabilities.offThrough', { parent: words(state.through.title) })
  return t(`tenantCapabilities.${state.kind}`)
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
      acceptIcon: 'pi pi-power-off',
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
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('tenantCapabilities.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('tenantCapabilities.loadFailed') }}</Message>

      <template v-else>
        <Message v-if="error" severity="error" :closable="false" class="capability-error">{{ error }}</Message>
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
            </div>
            <Button
              type="button"
              :label="node.entry.off ? t('tenantCapabilities.switchOn') : t('tenantCapabilities.switchOff')"
              :icon="node.entry.off ? 'pi pi-play' : 'pi pi-power-off'"
              severity="secondary"
              outlined
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
.capability__purpose {
  flex-basis: 100%;
}
.capability__toggle {
  flex-shrink: 0;
}
</style>
