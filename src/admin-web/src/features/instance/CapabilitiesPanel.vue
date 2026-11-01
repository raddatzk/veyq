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
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import {
  capabilityError,
  capabilityTree,
  clearInstanceCapabilityRequirement,
  setInstanceCapabilityRequirement,
  hasDescendants,
  inLocale,
  listInstanceCapabilities,
  setInstanceCapability,
} from './capabilities'
import type {
  CapabilityLocale, CapabilityState, InstanceCapability, InstanceCapabilityRequirement, InstanceCapabilityText,
} from './capabilities'
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

const STATE_TONE = { on: 'success', off: 'danger', offThrough: 'warning', notConfigured: 'warning' } as const
function stateLabel(state: CapabilityState): string {
  if (state.kind === 'offThrough') return t('capabilities.offThrough', { parent: words(state.through.title) })
  if (state.kind === 'notConfigured') return t('capabilities.notConfigured')
  return t(state.kind === 'on' ? 'capabilities.on' : 'capabilities.off')
}

// --- requirements (ADR 0088 decision 8): the instance's value of each; a tenant may tighten it further for itself.
// Setting one asks first and shows what it does to what exists (`whenSet`); clearing one only loosens, so it asks nothing.
const daysTyped = ref<Record<string, string>>({})
function requirementState(r: InstanceCapabilityRequirement): string {
  if (r.value === null || r.value === undefined) return t('capabilities.requirementNone')
  return r.kind === 'flag' ? t('capabilities.requirementOn') : t('capabilities.requirementDays', { days: r.value })
}

async function changeRequirement(entry: InstanceCapability, r: InstanceCapabilityRequirement, value: number | null) {
  const title = words(r.title)
  if (value !== null) {
    const accepted = await ask({
      header: t('capabilities.requirementConfirmTitle', { title }),
      message: words(r.whenSet),
      acceptLabel: t('capabilities.requirementSet'),
      acceptIcon: 'lock',
    })
    if (!accepted) return
  }
  error.value = ''
  switching.value = `${entry.id}:${r.name}`
  try {
    const now = value === null
      ? await clearInstanceCapabilityRequirement(entry.id, r.name)
      : await setInstanceCapabilityRequirement(entry.id, r.name, value)
    entries.value = entries.value.map((e) => (e.id === now.id ? now : e))
  } catch (e) {
    on401(e)
    const { status } = await capabilityError(e)
    if (status !== 401) error.value = t('capabilities.switchFailed', { title })
  } finally {
    switching.value = null
  }
}

function saveDays(entry: InstanceCapability, r: InstanceCapabilityRequirement) {
  const days = Number((daysTyped.value[r.name] ?? '').trim())
  if (!Number.isInteger(days) || days < 1 || days > 3650) {
    error.value = t('capabilities.requirementInvalidDays')
    return
  }
  void changeRequirement(entry, r, days)
}

async function toggle(entry: InstanceCapability) {
  const off = !entry.off
  const title = words(entry.title)
  if (off) {
    const accepted = await ask({
      header: t('capabilities.confirmTitle', { title }),
      message: words(entry.whenOff),
      acceptLabel: t('capabilities.switchOff'),
      acceptIcon: 'power-off',
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
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="info">{{ t('capabilities.notOwner') }}</VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">{{ t('capabilities.loadFailed') }}</VeyqMessage>

      <template v-else>
        <VeyqMessage v-if="error" tone="error" class="capability-error">{{ error }}</VeyqMessage>
        <ul class="capability-tree">
          <li
            v-for="node in tree"
            :key="node.entry.id"
            class="capability"
            :data-id="node.entry.id"
            :style="{ '--capability-depth': node.depth }"
            :class="{ 'capability--child': node.depth > 0 }"
          >
            <div class="capability__text">
              <strong class="capability__title">{{ words(node.entry.title) }}</strong>
              <span class="veyq-badge capability__state" :class="STATE_TONE[node.state.kind]">{{ stateLabel(node.state) }}</span>
              <small class="settings-muted capability__purpose">{{ words(node.entry.purpose) }}</small>
              <small v-if="node.entry.tenantSwitchable" class="settings-muted capability__tenant">{{ t('capabilities.tenantSwitchable') }}</small>
              <code class="capability__id">{{ node.entry.id }}</code>
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
                        :label="t('capabilities.requirementSet')"
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
                      :label="t('capabilities.requirementSet')"
                      variant="secondary"
                      appearance="outlined"
                      size="small"
                      :disabled="switching !== null"
                      @click="changeRequirement(node.entry, r, 1)"
                    />
                    <VeyqButton
                      v-if="r.value !== null && r.value !== undefined"
                      class="requirement__clear"
                      :label="t('capabilities.requirementClear')"
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
              :label="node.entry.off ? t('capabilities.switchOn') : t('capabilities.switchOff')"
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
  margin-inline-start: calc(var(--capability-depth, 0) * 1.5rem);
}
/* A function of an application (or of another entry) hangs on a line from its parent, so the tree reads as one. */
.capability--child {
  border-inline-start: 2px solid var(--app-line);
  padding-inline-start: 0.75rem;
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
.capability__requirements,
.capability__id {
  flex-basis: 100%;
}
.capability__id {
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 0.8rem;
  color: var(--app-muted);
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
