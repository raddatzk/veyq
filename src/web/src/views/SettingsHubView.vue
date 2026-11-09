<script setup lang="ts">
// The settings hub (cr-0078/cr-0087, ADR 0022) — one scope-contextual page, reached from the profile menu's
// Settings entry, which is the shell's only door into it. A
// responsive NavigationSplitView: wide viewports show the section rail + detail; narrow collapses to a list that
// drills into /settings/<section>. The rail mixes two axes (ADR 0022 §3): scope-in-path sections (Persönlich
// always; Tenant members while in the Tenant scope) and **collection categories** — collapsible groups that fan
// out to per-entity detail pages (Gruppen → a group I own → its members). That entry is context-aware: with
// no explicit section it opens the active scope's entity (a group → that group's page).
import { computed, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AppearancePanel from '../features/settings/AppearancePanel.vue'
import MapsPanel from '../features/settings/MapsPanel.vue'
import SessionsPanel from '../features/account/sessions/SessionsPanel.vue'
import KeyRotationPanel from '../features/account/rotation/KeyRotationPanel.vue'
import ChangePasswordPanel from '../features/account/changePassword/ChangePasswordPanel.vue'
import AddDevicePanel from '../features/account/pairing/AddDevicePanel.vue'
import ProfilePanel from '../features/account/profile/ProfilePanel.vue'
import TenantMembersPanel from '../features/spaces/TenantMembersPanel.vue'
import PeopleOverviewPanel from '../features/spaces/PeopleOverviewPanel.vue'
import TenantTermsPanel from '../features/tenant/TenantTermsPanel.vue'
import TenantMailSettingsPanel from '../features/tenant/TenantMailSettingsPanel.vue'
import TenantStoragePanel from '../features/tenant/TenantStoragePanel.vue'
import TenantCapabilitiesPanel from '../features/tenant/TenantCapabilitiesPanel.vue'
import GroupMembersPanel from '../features/spaces/GroupMembersPanel.vue'
import CreateGroupDialog from '../features/spaces/CreateGroupDialog.vue'
import TrustResetPanel from '../features/account/trustReset/TrustResetPanel.vue'
import EraseAccountPanel from '../features/account/erasure/EraseAccountPanel.vue'
import ModerationEntryPanel from '../features/moderation/ModerationEntryPanel.vue'
import { activeSpace } from '../app/activeSpace'
import { ScopeKind } from '../api/generated/scopes'
import { useCapabilities } from '../shared/state/useCapabilities'
import { CORE_GROUP_SPACE } from '../features/platform/capabilities'
import { OWNER_ONLY_SECTIONS, useSettingsSections, type SettingsCategory } from '../features/settings/sections'
import VeyqIcon from '../shared/ui/VeyqIcon.vue'

type NavCategory = SettingsCategory & { create?: { label: string; run: () => void } }

const { t } = useI18n()
const { hasCapability } = useCapabilities()
const route = useRoute()
const router = useRouter()

// The sections and their gates are one list, which the search reads too (`features/settings/sections.ts`, ADR 0125
// decision 9): the rail shows its categories, and a section it hides is not offered by the search either.
const { categories: sectionCategories, ready: settled, entering, isHidden, load, loadGroups, loadTrustRefusals, myGroups, appointment, isAppointed, isTenantOwner, trustRefused } =
  useSettingsSections()
onMounted(() => void load())
const createGroupOpen = ref(false)

const categories = computed<NavCategory[]>(() =>
  sectionCategories.value.map((c) =>
    // Group creation is gated on the `core.group-space` capability (ADR 0032, ADR 0088): a personal-only instance
    // disables it, so the create affordance is dropped (owned groups stay listed/manageable — only creation is gated).
    c.key === 'groups' && hasCapability(CORE_GROUP_SPACE)
      ? { ...c, create: { label: t('shell.groupCreate'), run: () => { createGroupOpen.value = true } } }
      : c,
  ),
)

// Entering the hub with no param opens the active scope's entity: a group → its page (owned or not), the Tenant scope →
// its members, else Persönlich. Narrow with no param shows the list only. Once the answers are in, a contextual default
// the caller may not see falls back to Persönlich: the hidden redirect below sends a hidden section to /settings, and a
// default that were itself hidden would send it straight back - on a wide screen the two watchers then replaced the
// route for ever (inbox #814, measured by SettingsHubView.test.ts's settle cases).
const contextualId = computed(() =>
  activeSpace.kind === ScopeKind.Group && activeSpace.scopeId
    ? `group-${activeSpace.scopeId}`
    : activeSpace.kind === ScopeKind.Tenant
      ? 'tenant-members'
      : 'appearance',
)
const defaultId = computed(() => (settled.value && isHidden(contextualId.value) ? 'appearance' : contextualId.value))
const activeId = computed(() => (route.params.section as string | undefined) || defaultId.value)
const hasSection = computed(() => Boolean(route.params.section))

// A section the caller may not see is not offered in the rail, and its address does not open it either: it leads back to
// /settings (which, on a wide screen, opens the default section). Judged only once the answers are in (`isHidden`).
// The page itself is not mounted while it is hidden (it would ask the server for what it may not read) - nor, for the
// pages that depend on an answer still on its way, before that answer is in.
// A tenant page reads the tenant the shell opened, so it is not mounted while the space is still being entered.
const TENANT_PAGES = new Set(['tenant-members', 'tenant-people', 'terms', ...OWNER_ONLY_SECTIONS])
const detailVisible = computed(
  () =>
    !isHidden(activeId.value) &&
    (settled.value || !OWNER_ONLY_SECTIONS.has(activeId.value)) &&
    !(entering.value && TENANT_PAGES.has(activeId.value)),
)
watch(
  [settled, hasSection, activeId, isTenantOwner, trustRefused, myGroups, isAppointed],
  () => {
    if (settled.value && hasSection.value && isHidden(activeId.value)) void router.replace('/settings')
  },
  { deep: true },
)

// On a WIDE viewport entering with no section already auto-shows a default section's detail — make the URL explicit by
// replacing /settings → /settings/<defaultId> (contextual: the Tenant scope → members, a group → its page). On
// NARROW (≤720px) /settings is the drill-down list, so it stays put (redirecting there would break the back path).
function reflectDefaultSection() {
  if (!hasSection.value && defaultId.value && window.matchMedia('(min-width: 721px)').matches) {
    void router.replace(`/settings/${defaultId.value}`)
  }
}
onMounted(reflectDefaultSection)
watch([hasSection, defaultId], reflectDefaultSection)

// Resolve the active id → the detail component + its props (a group id encodes its scope, ADR 0022 §2).
const activeView = computed<{ component: Component; props: Record<string, unknown> }>(() => {
  const id = activeId.value
  if (id === 'tenant-members') return { component: TenantMembersPanel, props: {} }
  if (id === 'tenant-people') return { component: PeopleOverviewPanel, props: {} }
  if (id === 'terms') return { component: TenantTermsPanel, props: {} }
  if (id === 'mail') return { component: TenantMailSettingsPanel, props: {} }
  if (id === 'storage') return { component: TenantStoragePanel, props: {} }
  if (id === 'capabilities') return { component: TenantCapabilitiesPanel, props: {} }
  if (id.startsWith('group-')) {
    const scopeId = id.slice('group-'.length)
    return { component: GroupMembersPanel, props: { scopeId, name: myGroups.value.find((g) => g.scopeId === scopeId)?.name ?? '' } }
  }
  if (id === 'profile') return { component: ProfilePanel, props: {} }
  if (id === 'maps') return { component: MapsPanel, props: {} }
  if (id === 'sessions') return { component: SessionsPanel, props: {} }
  if (id === 'password') return { component: ChangePasswordPanel, props: {} }
  if (id === 'pairing') return { component: AddDevicePanel, props: {} }
  if (id === 'security') return { component: KeyRotationPanel, props: {} }
  if (id === 'trust-reset') return { component: TrustResetPanel, props: {} }
  if (id === 'erase') return { component: EraseAccountPanel, props: {} }
  if (id === 'moderation' && appointment.value) return { component: ModerationEntryPanel, props: { appointment: appointment.value } }
  return { component: AppearancePanel, props: {} }
})

// Collapse state: a category with the active item is open by default; the user can toggle from there.
const manualExpanded = reactive<Record<string, boolean | undefined>>({})
const isExpanded = (c: NavCategory) => manualExpanded[c.key] ?? c.items.some((i) => i.id === activeId.value)
const toggle = (c: NavCategory) => { manualExpanded[c.key] = !isExpanded(c) }

const open = (id: string) => router.push(`/settings/${id}`)
const back = () => router.push('/settings')

async function onGroupCreated(scopeId: string) {
  await loadGroups()
  manualExpanded.groups = true
  await router.push(`/settings/group-${scopeId}`)
}


</script>

<template>
  <div class="veyq-page settings-hub-page">
    <h1 class="settings-hub-title">{{ t('settings.title') }}</h1>
    <div class="settings-hub" :class="{ 'is-index': !hasSection, 'is-detail': hasSection }">
      <nav class="settings-nav" :aria-label="t('settings.title')">
        <template v-for="c in categories" :key="c.key">
          <div class="settings-nav-cat">
            <button
              v-if="c.collapsible"
              type="button"
              class="settings-nav-cat-toggle"
              :aria-expanded="isExpanded(c)"
              @click="toggle(c)"
            >
              <VeyqIcon :name="isExpanded(c) ? 'chevron-down' : 'chevron-right'" />
              <span>{{ c.label }}</span>
            </button>
            <p v-else class="settings-nav-cat-label">{{ c.label }}</p>
            <button
              v-if="c.create"
              type="button"
              class="settings-nav-cat-action"
              :title="c.create.label"
              :aria-label="c.create.label"
              @click="c.create.run()"
            >
              <VeyqIcon name="plus" />
            </button>
          </div>

          <template v-if="!c.collapsible || isExpanded(c)">
            <button
              v-for="it in c.items"
              :key="it.id"
              type="button"
              class="settings-nav-item"
              :class="{ 'is-active': it.id === activeId }"
              @click="open(it.id)"
            >
              <VeyqIcon :name="it.icon" />
              <span>{{ it.label }}</span>
            </button>
            <p v-if="c.collapsible && c.items.length === 0 && c.empty" class="settings-nav-empty">{{ c.empty }}</p>
          </template>
        </template>
      </nav>

      <section class="settings-detail">
        <button type="button" class="settings-back" @click="back">
          <VeyqIcon name="chevron-left" />
          {{ t('settings.back') }}
        </button>
        <component
          :is="activeView.component"
          v-if="detailVisible"
          v-bind="activeView.props"
          @lifecycle-changed="loadGroups"
          @trust-changed="loadTrustRefusals"
        />
      </section>
    </div>

    <CreateGroupDialog v-if="createGroupOpen" @done="onGroupCreated" @close="createGroupOpen = false" />
  </div>
</template>

<style scoped>
.settings-hub-page {
  max-width: 960px;
}
.settings-hub-title {
  margin: 0 0 1rem;
}
.settings-hub {
  display: flex;
  gap: 1.5rem;
  align-items: flex-start;
}
.settings-nav {
  flex: 0 0 220px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.settings-nav-cat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0.75rem 0.25rem 0.25rem 0.5rem;
}
.settings-nav-cat-label,
.settings-nav-cat-toggle {
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--app-muted);
  font: inherit;
  font-size: 0.8rem;
  font-weight: 500;
}
.settings-nav-cat-toggle {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  cursor: pointer;
}
.settings-nav-cat-toggle .veyq-icon {
  font-size: 0.7rem;
}
.settings-nav-cat-action {
  display: grid;
  place-items: center;
  width: 1.4rem;
  height: 1.4rem;
  border: none;
  border-radius: 0.4rem;
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;
}
.settings-nav-cat-action:hover {
  background: var(--app-hover);
  color: inherit;
}
.settings-nav-empty {
  margin: 0.1rem 0.5rem 0.25rem;
  color: var(--app-muted);
  font-size: 0.85rem;
}
.settings-nav-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.6rem;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.settings-nav-item:hover {
  background: var(--app-hover);
}
.settings-nav-item.is-active {
  background: var(--app-success-soft);
  font-weight: 500;
}
.settings-detail {
  flex: 1 1 auto;
  min-width: 0;
}
.settings-back {
  display: none;
  align-items: center;
  gap: 0.35rem;
  margin-bottom: 0.75rem;
  padding: 0.3rem 0.5rem;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

@media (max-width: 720px) {
  .settings-nav {
    flex-basis: 100%;
    width: 100%;
  }
  .settings-hub.is-index .settings-detail {
    display: none;
  }
  .settings-hub.is-detail .settings-nav {
    display: none;
  }
  .settings-hub.is-detail .settings-back {
    display: inline-flex;
  }
}
</style>
