<script setup lang="ts">
// The settings hub (cr-0078/cr-0087, ADR 0022) — one scope-contextual page reached from the top-bar gear. A
// responsive NavigationSplitView: wide viewports show the section rail + detail; narrow collapses to a list that
// drills into /settings/<section>. The rail mixes two axes (ADR 0022 §3): scope-in-path sections (Persönlich
// always; Tenant members while in the Tenant scope) and **collection categories** — collapsible groups that fan
// out to per-entity detail pages (Gruppen → a group I own → its members). The gear is context-aware: with no
// explicit section it opens the active scope's entity (a group → that group's page).
import { computed, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AppearancePanel from '../features/settings/AppearancePanel.vue'
import DeviceTrustPanel from '../features/account/trustDevice/DeviceTrustPanel.vue'
import SessionsPanel from '../features/account/sessions/SessionsPanel.vue'
import KeyRotationPanel from '../features/account/rotation/KeyRotationPanel.vue'
import ChangePasswordPanel from '../features/account/changePassword/ChangePasswordPanel.vue'
import AddDevicePanel from '../features/account/pairing/AddDevicePanel.vue'
import ProfilePanel from '../features/account/profile/ProfilePanel.vue'
import TenantMembersPanel from '../features/spaces/TenantMembersPanel.vue'
import TenantTermsPanel from '../features/tenant/TenantTermsPanel.vue'
import TenantMailSettingsPanel from '../features/tenant/TenantMailSettingsPanel.vue'
import GroupMembersPanel from '../features/spaces/GroupMembersPanel.vue'
import ServerAdminPanel from '../features/settings/ServerAdminPanel.vue'
import CreateGroupDialog from '../features/spaces/CreateGroupDialog.vue'
import { activeSpace } from '../app/activeSpace'
import { ScopeKind } from '../api/generated/scopes'
import { listMyGroups, type GroupSpace } from '../features/spaces/scopes'
import { serverRole, probeServerRole } from '../app/serverRole'
import { useCapabilities } from '../shared/state/useCapabilities'
import { SPACES_GROUPS } from '../features/platform/capabilities'

type NavItem = { id: string; label: string; icon: string }
type NavCategory = { key: string; label: string; items: NavItem[]; collapsible: boolean; create?: { label: string; run: () => void }; empty?: string }

const { t } = useI18n()
const { hasCapability } = useCapabilities()
const route = useRoute()
const router = useRouter()

// The groups the caller OWNs — the Gruppen collection (ADR 0022 §3). Names decrypted on-device; a member-only
// group isn't listed (nothing to manage), though its detail page stays reachable via a direct/gear link.
const ownedGroups = ref<GroupSpace[]>([])
const createGroupOpen = ref(false)

onMounted(async () => {
  await probeServerRole()
  await loadGroups()
})

async function loadGroups() {
  try {
    ownedGroups.value = (await listMyGroups()).filter((g) => g.role === 'OWNER')
  } catch {
    /* the rail tolerates an empty groups collection */
  }
}


const categories = computed<NavCategory[]>(() => {
  const cats: NavCategory[] = [
    {
      key: 'personal',
      label: t('settings.personal'),
      collapsible: false,
      items: [
        { id: 'appearance', label: t('settings.appearance'), icon: 'pi pi-palette' },
        // Device trust is device-local + always relevant (uc.account.trust-device § Untrust) — no scope needed.
        { id: 'device', label: t('settings.device'), icon: 'pi pi-desktop' },
        // Every active session of the account + the remote sign-out (uc.account.sessions). Account-wide, not
        // scope-bound, and gated on a session rather than an unlock — so it belongs beside device trust.
        { id: 'sessions', label: t('settings.sessions'), icon: 'pi pi-mobile' },
        // Change password (uc.account.change-password) sits beside sessions: it is the credential-adjacent
        // action, account-wide like sessions, gated on a session rather than an unlock (like it, and unlike
        // security below, which rewrites Master-Key-sealed material).
        { id: 'password', label: t('settings.password'), icon: 'pi pi-key' },
        // Adding a device (uc.account.onboard-device) sits beside the sessions it produces. The route's own
        // `requiresUnlock: true` is the gate and it is the correct one here: the granting device is unlocked by
        // premise — it seals the Master Key it is holding.
        { id: 'pairing', label: t('pairing.navTitle'), icon: 'pi pi-qrcode' },
        // Replacing the account's keys after a compromise (uc.account.rotate-keys). It sits below the two
        // sections whose failure it answers — a device trusted by mistake, a session that revoking cannot
        // reach — because that is the order the person arrives in. The route's `requiresUnlock: true` is the
        // right gate: the rotation rewrites Master-Key-sealed material, so a locked vault could not run it at
        // all; its OWN gate is narrower still and lives in the panel (ADR 0060 Decision 2 — a device wrap
        // opened this vault is refused before the form is offered).
        { id: 'security', label: t('settings.security'), icon: 'pi pi-shield' },
        // Per-tenant identity (username + real name) needs any tenant context (ADR 0023).
        ...(activeSpace.tenantId ? [{ id: 'profile', label: t('profile.title'), icon: 'pi pi-user' }] : []),
      ],
    },
  ]
  // Tenant members + terms — scope-in-path: managed while the shared Tenant scope is active (needs K_tenant open).
  // Terms are member-readable (the editor is owner-only, cr-0114); the item is shown to every member.
  if (activeSpace.kind === ScopeKind.Tenant) {
    cats.push({ key: 'tenant', label: t('settings.tenant'), collapsible: false, items: [
      { id: 'tenant-members', label: t('members.title'), icon: 'pi pi-users' },
      { id: 'terms', label: t('terms.navTitle'), icon: 'pi pi-file-edit' },
      // Mail settings are owner-only (the read is owner-gated); the item shows to every member, the panel gates.
      { id: 'mail', label: t('mail.navTitle'), icon: 'pi pi-envelope' },
    ] })
  }
  // Gruppen collection — every group I own + a create affordance (management hub, decoupled from active scope).
  if (activeSpace.tenantId) {
    cats.push({
      key: 'groups',
      label: t('shell.groups'),
      collapsible: true,
      empty: t('groups.noneOwned'),
      // Group creation is gated on the `spaces.groups` capability (ADR 0032): a personal-only instance disables
      // it, so the create affordance is dropped (owned groups stay listed/manageable — only creation is gated).
      ...(hasCapability(SPACES_GROUPS)
        ? { create: { label: t('shell.groupCreate'), run: () => { createGroupOpen.value = true } } }
        : {}),
      // A group in pending-deletion (cr-0090) is badged with a trash icon so the roster shows it's on its way out.
      items: ownedGroups.value.map((g) => ({ id: `group-${g.scopeId}`, label: g.name, icon: g.deletedAt ? 'pi pi-trash' : 'pi pi-users' })),
    })
  }
  // Server → a single signpost. Tenant administration, the release panel and first-run setup are no longer
  // pages of this app: they are `admin-web`, a separate deployable an operator can firewall off the public
  // origin entirely (ADR 0065). This app cannot link to it — the operator chooses where it is reachable
  // (localhost-only by default, or an optional ADMIN_DOMAIN), and a wrong guess is worse than no link — so the
  // entry explains rather than navigates. It stays gated on the Server-Owner probe: telling every member that
  // an admin surface exists would be noise, and to a stranger, a hint.
  if (serverRole.canManage) {
    cats.push({ key: 'instance', label: t('settings.server'), collapsible: false, items: [
      { id: 'server-admin', label: t('serverAdmin.navTitle'), icon: 'pi pi-server' },
    ] })
  }
  return cats
})

// The gear (no param) opens the active scope's entity: a group → its page, the Tenant scope → its members, else
// Persönlich. Narrow with no param shows the list only.
const defaultId = computed(() =>
  activeSpace.kind === ScopeKind.Group && activeSpace.scopeId
    ? `group-${activeSpace.scopeId}`
    : activeSpace.kind === ScopeKind.Tenant
      ? 'tenant-members'
      : 'appearance',
)
const activeId = computed(() => (route.params.section as string | undefined) || defaultId.value)
const hasSection = computed(() => Boolean(route.params.section))

// On a WIDE viewport the gear (no section) already auto-shows a default section's detail — make the URL explicit by
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
  if (id === 'terms') return { component: TenantTermsPanel, props: {} }
  if (id === 'mail') return { component: TenantMailSettingsPanel, props: {} }
  if (id.startsWith('group-')) {
    const scopeId = id.slice('group-'.length)
    return { component: GroupMembersPanel, props: { scopeId, name: ownedGroups.value.find((g) => g.scopeId === scopeId)?.name ?? '' } }
  }
  if (id === 'server-admin') return { component: ServerAdminPanel, props: {} }
  if (id === 'profile') return { component: ProfilePanel, props: {} }
  if (id === 'device') return { component: DeviceTrustPanel, props: {} }
  if (id === 'sessions') return { component: SessionsPanel, props: {} }
  if (id === 'password') return { component: ChangePasswordPanel, props: {} }
  if (id === 'pairing') return { component: AddDevicePanel, props: {} }
  if (id === 'security') return { component: KeyRotationPanel, props: {} }
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
              <i class="pi" :class="isExpanded(c) ? 'pi-chevron-down' : 'pi-chevron-right'" aria-hidden="true" />
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
              <i class="pi pi-plus" aria-hidden="true" />
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
              <i :class="it.icon" aria-hidden="true" />
              <span>{{ it.label }}</span>
            </button>
            <p v-if="c.collapsible && c.items.length === 0 && c.empty" class="settings-nav-empty">{{ c.empty }}</p>
          </template>
        </template>
      </nav>

      <section class="settings-detail">
        <button type="button" class="settings-back" @click="back">
          <i class="pi pi-chevron-left" aria-hidden="true" />
          {{ t('settings.back') }}
        </button>
        <component :is="activeView.component" v-bind="activeView.props" @lifecycle-changed="loadGroups" />
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
.settings-nav-cat-toggle .pi {
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
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
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
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
}
.settings-nav-item.is-active {
  background: var(--p-highlight-background, rgba(127, 127, 127, 0.14));
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
