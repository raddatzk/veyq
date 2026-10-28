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
import { refusedDocuments } from '../features/account/trustReset/refusedDocuments'
import ModerationEntryPanel from '../features/moderation/ModerationEntryPanel.vue'
import { myAppointment, type ModeratorAppointment } from '../features/moderation/appointment'
import { ModeratorAppointmentStateEnum } from '../api/generated/moderation'
import { activeSpace } from '../app/activeSpace'
import { ScopeKind } from '../api/generated/scopes'
import { listMyGroups, listMyTenants, type GroupSpace } from '../features/spaces/scopes'
import { useCapabilities } from '../shared/state/useCapabilities'
import { CORE_DEVICE_LINKING, CORE_GROUP_SPACE } from '../features/platform/capabilities'
import VeyqIcon from '../shared/ui/VeyqIcon.vue'
import type { VeyqIconName } from '../shared/ui/icons'

type NavItem = { id: string; label: string; icon: VeyqIconName }
type NavCategory = { key: string; label: string; items: NavItem[]; collapsible: boolean; create?: { label: string; run: () => void }; empty?: string }

const { t } = useI18n()
const { hasCapability } = useCapabilities()
// Adding a device (uc.account.onboard-device) needs the instance's `core.device-linking`: the server refuses every
// /api/pairing/* call without it.
const deviceLinking = hasCapability(CORE_DEVICE_LINKING)
const route = useRoute()
const router = useRouter()

// The groups the caller OWNs — the Gruppen collection (ADR 0022 §3). Names decrypted on-device; a member-only
// group isn't listed (nothing to manage), though its detail page stays reachable by a direct link.
const ownedGroups = ref<GroupSpace[]>([])
const createGroupOpen = ref(false)

// Whether this device refuses its pin store or its device register right now (ADR 0028 Decision 7) — the one
// condition under which the hub offers "Start a list over". A read like every other: it opens through the marks
// and writes nothing; the reset itself is reachable only from that section's confirm dialog.
const trustRefused = ref(false)

// True once every answer a gate below depends on is in (a section is never judged hidden on a guess).
const loaded = ref(false)
onMounted(async () => {
  await Promise.all([loadGroups(), loadTrustRefusals(), loadTenantRole(), loadAppointment()])
  loaded.value = true
})
// A reload or a deep link on a settings page boots with no space open, and the shell enters the tenant while this hub
// mounts (AppShell). Until that entry is through, no tenant is known: a tenant page mounted then reads none and says it
// could not load (the V1 heavy stage at 999 members, 2026-10-23), and the tenant role read for no tenant says "no owner". So nothing is
// judged on a guess: the answers are in once the entry is through AND the role was read for the tenant it opened.
const entering = computed(() => activeSpace.status === 'loading')
const settled = computed(() => loaded.value && !entering.value && roleTenant.value === activeSpace.tenantId)

// The caller's moderator appointment (uc.moderation.serve-as-moderator): the Moderation section — a door to the
// surface, which lives outside this shell (ADR 0099 § 6) — is in the rail for a pending or accepted appointee and for
// nobody else. Null until the answer is in, and on a failed read.
const appointment = ref<ModeratorAppointment | null>(null)
const isAppointed = computed(
  () =>
    appointment.value?.state === ModeratorAppointmentStateEnum.Pending ||
    appointment.value?.state === ModeratorAppointmentStateEnum.Accepted,
)
async function loadAppointment() {
  try {
    appointment.value = await myAppointment()
  } catch {
    appointment.value = null
  }
}
watch(() => activeSpace.tenantId, () => void loadTenantRole())

// Whether the caller owns the active tenant: the owner-only sections (mail, storage) are in the rail for an owner and for
// nobody else - a member is not offered a page that can only refuse them. False until the answer is in, and on a failed read.
const isTenantOwner = ref(false)
/** The tenant `isTenantOwner` answers for (undefined before the first answer): an answer for another tenant is none. */
const roleTenant = ref<string | null | undefined>(undefined)
async function loadTenantRole() {
  const tenantId = activeSpace.tenantId
  let owner = false
  try {
    owner = (await listMyTenants()).find((tn) => tn.tenantId === tenantId)?.role === 'OWNER'
  } catch {
    owner = false
  }
  if (tenantId !== activeSpace.tenantId) return // a later tenant's read owns the answer
  isTenantOwner.value = owner
  roleTenant.value = tenantId
}

async function loadTrustRefusals() {
  try {
    trustRefused.value = (await refusedDocuments()).length > 0
  } catch {
    trustRefused.value = false
  }
}

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
        { id: 'appearance', label: t('settings.appearance'), icon: 'palette' },
        // Every signed-in device + the remote sign-out (uc.account.sessions), AND how this device unlocks
        // (uc.account.trust-device § Untrust) — one rubric, because both are facts about a device and the list
        // already carries a row for this one. Account-wide, not scope-bound, and gated on a session rather than
        // an unlock. The former separate "This device" section was REPLACED, not redirected (ADR 0022).
        { id: 'sessions', label: t('settings.sessions'), icon: 'mobile' },
        // Change password (uc.account.change-password) sits beside sessions: it is the credential-adjacent
        // action, account-wide like sessions, gated on a session rather than an unlock (like it, and unlike
        // security below, which rewrites Master-Key-sealed material).
        { id: 'password', label: t('settings.password'), icon: 'key' },
        // Adding a device (uc.account.onboard-device) sits beside the sessions it produces. The route's own
        // `requiresUnlock: true` is the gate and it is the correct one here: the granting device is unlocked by
        // premise — it seals the Master Key it is holding. Offered only where the instance links devices at all
        // (`core.device-linking`); off, its address leads back to /settings like any hidden section (`isHidden`).
        ...(deviceLinking ? [{ id: 'pairing', label: t('pairing.navTitle'), icon: 'qrcode' } satisfies NavItem] : []),
        // Replacing the account's keys after a compromise (uc.account.rotate-keys). It sits below the rubric
        // whose failure it answers — a device trusted by mistake, a session that revoking cannot reach — because
        // that is the order the person arrives in. The route's `requiresUnlock: true` is the
        // right gate: the rotation rewrites Master-Key-sealed material, so a locked vault could not run it at
        // all; its OWN gate is narrower still and lives in the panel (ADR 0060 Decision 2 — a device wrap
        // opened this vault is refused before the form is offered).
        { id: 'security', label: t('settings.security'), icon: 'shield' },
        // Starting a refused trust document over (ADR 0028 Decision 7's way back) — beside key rotation and the
        // sessions, and shown ONLY while this device refuses one, so it is never a standing button next to them.
        // Every refusal notice links here; the section's confirm dialog is the one place the reset can run.
        ...(trustRefused.value ? [{ id: 'trust-reset', label: t('trustReset.navTitle'), icon: 'history' } satisfies NavItem] : []),
        // Per-tenant identity (username + real name) needs any tenant context (ADR 0023).
        ...(activeSpace.tenantId ? [{ id: 'profile', label: t('profile.title'), icon: 'user' } satisfies NavItem] : []),
        // The way into moderation, for an appointed account only. Account-wide like an appointment (the instance's, not a
        // space's); the panel only links out, because reported content is never opened beside this shell's vault.
        ...(isAppointed.value ? [{ id: 'moderation', label: t('moderation.entry.navTitle'), icon: 'flag' } satisfies NavItem] : []),
        // Erasing the account (uc.account.erase-account, ADR 0101) — last, below everything it ends. Account-wide like
        // sessions; the hub's `requiresUnlock` gives the panel the account id and address its proof binds. The Server
        // Owner's account is never erased: the panel reads that from the server and says so (A2), as it is the only
        // place that knows.
        { id: 'erase', label: t('erasure.navTitle'), icon: 'user-minus' },
      ],
    },
  ]
  // Tenant members + terms — scope-in-path: managed while the shared Tenant scope is active (needs K_tenant open).
  // Terms are member-readable (the editor is owner-only, cr-0114); the item is shown to every member.
  if (activeSpace.kind === ScopeKind.Tenant) {
    cats.push({ key: 'tenant', label: t('settings.tenant'), collapsible: false, items: [
      { id: 'tenant-members', label: t('members.title'), icon: 'users' },
      // Who's who (inbox #562): every member's key in this space - confirmed or not, verified by whom, a new identity.
      // Every member's, like Members: the records it reads are every member's, so no role changes what it shows.
      { id: 'tenant-people', label: t('whoIsWho.navTitle'), icon: 'verified' },
      { id: 'terms', label: t('terms.navTitle'), icon: 'file-edit' },
      // Mail settings, storage and capabilities are owner-only (their reads are owner-gated): only an owner's rail lists them, so a member is
      // never taken to a page that says it could not load. The panels still gate - the server refuses a member either way.
      ...(isTenantOwner.value
        ? ([
            { id: 'mail', label: t('mail.navTitle'), icon: 'envelope' },
            { id: 'storage', label: t('storage.navTitle'), icon: 'database' },
            { id: 'capabilities', label: t('tenantCapabilities.navTitle'), icon: 'sliders-h' },
          ] satisfies NavItem[])
        : []),
    ] })
  }
  // Gruppen collection — every group I own + a create affordance (management hub, decoupled from active scope).
  if (activeSpace.tenantId) {
    cats.push({
      key: 'groups',
      label: t('shell.groups'),
      collapsible: true,
      empty: t('groups.noneOwned'),
      // Group creation is gated on the `core.group-space` capability (ADR 0032, ADR 0088): a personal-only instance
      // disables it, so the create affordance is dropped (owned groups stay listed/manageable — only creation is gated).
      ...(hasCapability(CORE_GROUP_SPACE)
        ? { create: { label: t('shell.groupCreate'), run: () => { createGroupOpen.value = true } } }
        : {}),
      // A group in pending-deletion (cr-0090) is badged with a trash icon so the roster shows it's on its way out.
      items: ownedGroups.value.map((g): NavItem => ({ id: `group-${g.scopeId}`, label: g.name, icon: g.deletedAt ? 'trash' : 'users' })),
    })
  }
  return cats
})

// Entering the hub with no param opens the active scope's entity: a group → its page, the Tenant scope → its
// members, else Persönlich. Narrow with no param shows the list only.
const defaultId = computed(() =>
  activeSpace.kind === ScopeKind.Group && activeSpace.scopeId
    ? `group-${activeSpace.scopeId}`
    : activeSpace.kind === ScopeKind.Tenant
      ? 'tenant-members'
      : 'appearance',
)
const activeId = computed(() => (route.params.section as string | undefined) || defaultId.value)
const hasSection = computed(() => Boolean(route.params.section))

// A section the caller may not see is not offered in the rail, and its address does not open it either: it leads back to
// /settings (which, on a wide screen, opens the default section). Judged only once the answers are in: the owner-only pages
// (mail, storage, capabilities) need the tenant role, a trust reset needs a refused document, a group page needs the group to be the caller's,
// moderation needs an appointment; adding a device needs the instance's `core.device-linking`, known at mount.
const OWNER_ONLY = new Set(['mail', 'storage', 'capabilities'])
const isHidden = (id: string): boolean =>
  id === 'pairing'
    ? !deviceLinking
    : OWNER_ONLY.has(id)
      ? !isTenantOwner.value
      : id === 'trust-reset'
        ? !trustRefused.value
        : id === 'moderation'
          ? !isAppointed.value
          : id.startsWith('group-')
          ? !ownedGroups.value.some((g) => `group-${g.scopeId}` === id)
          : false
// The page itself is not mounted while it is hidden (it would ask the server for what it may not read) - nor, for the
// pages that depend on an answer still on its way, before that answer is in.
// A tenant page reads the tenant the shell opened, so it is not mounted while the space is still being entered.
const TENANT_PAGES = new Set(['tenant-members', 'tenant-people', 'terms', ...OWNER_ONLY])
const detailVisible = computed(
  () =>
    !isHidden(activeId.value) &&
    (settled.value || !OWNER_ONLY.has(activeId.value)) &&
    !(entering.value && TENANT_PAGES.has(activeId.value)),
)
watch(
  [settled, hasSection, activeId, isTenantOwner, trustRefused, ownedGroups, isAppointed],
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
    return { component: GroupMembersPanel, props: { scopeId, name: ownedGroups.value.find((g) => g.scopeId === scopeId)?.name ?? '' } }
  }
  if (id === 'profile') return { component: ProfilePanel, props: {} }
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
