<script setup lang="ts">
// ADR 0088 decision 12 — the "Capabilities" rubric of the server settings (uc.admin.switch-capabilities), beside
// Release, Object store and Config-secret key and built on the store panel's shape: a state the operator acts on.
//
// What it must keep honest:
//   - every word about an entry (title, purpose, what switching it off does) is the server's, served from the
//     catalog in both languages and picked here by the console's locale — never written into this package;
//   - the state shown is the server's `effective`; "off through" only names the nearest ancestor that is itself
//     off, because that is the switch that brings the entry back;
//   - switching off asks first and shows the entry's own `whenOff`, since it can send members to their
//     Emergency Kit (a device switch says so in its own text); switching on only restores, so it asks nothing —
//     the split `TenantDetailPanel`'s suspend / reactivate makes;
//   - a row re-renders from the PUT's answer, not from what was asked for;
//   - an entry a tenant owner may narrow further says so (`tenantSwitchable`, the catalog's levels): "on" here is not
//     "on in every tenant".
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import {
  capabilityError,
  capabilityTree,
  hasDescendants,
  inLocale,
  listInstanceCapabilities,
  setInstanceCapability,
} from './capabilities'
import type { CapabilityLocale, CapabilityState, InstanceCapability, InstanceCapabilityText } from './capabilities'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'

const { t, locale } = useI18n()
const { ask } = useConfirm()
const on401 = handle401(useRouter())

const entries = ref<InstanceCapability[]>([])
const error = ref('')
/** The id whose switch is in flight; one at a time, so a second click cannot race the first one's answer. */
const switching = ref<string | null>(null)

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  entries.value = await listInstanceCapabilities()
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const tree = computed(() => capabilityTree(entries.value))
const words = (text: InstanceCapabilityText) => inLocale(text, locale.value as CapabilityLocale)

const STATE_TONE = { on: 'success', off: 'danger', offThrough: 'warning' } as const
function stateLabel(state: CapabilityState): string {
  if (state.kind === 'offThrough') return t('capabilities.offThrough', { parent: words(state.through.title) })
  return t(state.kind === 'on' ? 'capabilities.on' : 'capabilities.off')
}

async function toggle(entry: InstanceCapability) {
  const off = !entry.off
  const title = words(entry.title)
  if (off) {
    const accepted = await ask({
      header: t('capabilities.confirmTitle', { title }),
      message: words(entry.whenOff),
      acceptLabel: t('capabilities.switchOff'),
      acceptIcon: 'pi pi-power-off',
    })
    if (!accepted) return
  }
  error.value = ''
  switching.value = entry.id
  try {
    const now = await setInstanceCapability(entry.id, off)
    entries.value = entries.value.map((e) => (e.id === now.id ? now : e))
    // The descendants' `effective` is the server's AND and just changed with this switch; read it, never derive it.
    if (hasDescendants(now.id, entries.value)) entries.value = await listInstanceCapabilities()
  } catch (e) {
    on401(e)
    const { status, code } = await capabilityError(e)
    if (status !== 401) error.value = t(code === 'capability_not_found' ? 'capabilities.notFound' : 'capabilities.switchFailed', { title })
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
          <h2>{{ t('capabilities.navTitle') }}</h2>
          <p>{{ t('capabilities.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('capabilities.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('capabilities.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('capabilities.loadFailed') }}</Message>

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
              <small v-if="node.entry.tenantSwitchable" class="settings-muted capability__tenant">{{ t('capabilities.tenantSwitchable') }}</small>
              <code class="capability__id">{{ node.entry.id }}</code>
            </div>
            <Button
              type="button"
              :label="node.entry.off ? t('capabilities.switchOn') : t('capabilities.switchOff')"
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
.capability__purpose,
.capability__tenant,
.capability__id {
  flex-basis: 100%;
}
.capability__id {
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 0.8rem;
  color: var(--app-muted);
}
.capability__toggle {
  flex-shrink: 0;
}
</style>
