<script setup lang="ts">
// The shell's left rail (cr-0030/0031). Markup + classes are the design source's TenantSidebar verbatim (brand,
// the space switcher, the Personal-space nav, the ProfileMenu) so layout.css styles it identically; only the
// data is ours — the tenants come from listMyTenants() (E2EE: the server only knows membership), switching is a
// full navigation to that tenant's domain, and the bottom is the ProfileMenu (theme + language + settings +
// sign-out — the shell's one door into the hub).
//
// The rail also carries the **groups** section and the **favorites** section — each pinned Memory with its own
// chosen cover (uc.memory.cover's fourth surface, after the browse card, the detail header and the map pin).
// The covers come from the shared `coverStore`: this component asks it WHICH nodes are on screen and reads the
// result, and imports **no key API at all** — the epoch chain that opens a node's `K_mem` has no business in an
// always-mounted rail, and keeping it out is a property worth preserving, not an accident of one diff.
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import Dialog from 'primevue/dialog'
import Popover from 'primevue/popover'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import TenantAvatar from './TenantAvatar.vue'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { listMyTenants, listMyGroups, openMyTenantIcon, type GroupSpace } from '../../features/spaces/scopes'
import { spaceIconVersion } from '../../features/spaces/spaceIconSignal'
import { useObjectUrls } from '../../shared/state/useObjectUrls'
import { activeSpace } from '../../app/activeSpace'
import CreateGroupDialog from '../../features/spaces/CreateGroupDialog.vue'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { SPACES_GROUPS } from '../../features/platform/capabilities'
import { favoritesStore } from '../../app/favoritesStore'
import { useThemeState } from '../../shared/state/useThemeState'
import { treeStore } from '../../features/memory/treeStore'
import { covers, ensureCovers } from '../../features/memory/coverStore'
import MemoryCoverImage from '../../features/memory/components/MemoryCoverImage.vue'
import { nodeById } from '../../features/memory/browse'
import { hashForNode, parseHash, TENANT_SCOPE, isPersonalScope, personalScope } from '../../features/memory/location'
import { getMyProfile } from '../../features/account/profile/profile'
import { NodeKind } from '../../api/generated/content'
import { type TreeNode } from '../../features/memory/tree'
import { slugify } from '../../shared/naming'
import type { MyTenant } from '../../api/generated/scopes'
import ProfileMenu from './ProfileMenu.vue'

const { t } = useI18n()
const { hasCapability } = useCapabilities()
// Group creation is gated on the `spaces.groups` capability (ADR 0032): a personal-only instance disables it,
// so the "New group" affordance is hidden (and the group-create endpoint rejects with 422). Existing groups
// stay listed/manageable — only creation is gated, matching the server.
const canCreateGroup = computed(() => hasCapability(SPACES_GROUPS))
const route = useRoute()
const router = useRouter()
const { resolvedTheme } = useThemeState()
const tenants = ref<MyTenant[]>([])
// Each tenant's decrypted icon as an owned object URL keyed by tenantId (uc.spaces.set-space-icon,
// en.spaces.tenant-scope) — opened on-device against the caller's own per-tenant grant. Absent → the initial.
const tenantIcons = useObjectUrls()
function tenantIcon(tenantId: string): string | undefined {
  return tenantIcons.get(tenantId) ?? undefined
}

const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))

// The active in-tenant space, from the URL's leading fragment token (token-less = the shared tenant space,
// `~me` = personal — ADR 0019). Drives both which sidebar entry is highlighted and how favorite hashes build.
const activeScope = computed(() => parseHash(route.hash).scope)
const tenantActive = computed(() => activeScope.value === TENANT_SCOPE)
const personalActive = computed(() => isPersonalScope(activeScope.value))

// The member's groups (en.spaces.group-scope, cr-0032): names decrypted on-device (the server only ever stored
// ciphertext). A group is entered via the `#@<id>` fragment; the active one is highlighted from the leading token.
const groups = ref<GroupSpace[]>([])
// Each group's decrypted icon as an owned object URL keyed by scopeId (uc.spaces.set-space-icon, GROUP slice) —
// opened on-device by listMyGroups (peeling the epoch chain). Absent → the pi-users glyph.
const groupIcons = useObjectUrls()
function groupIcon(scopeId: string): string | undefined {
  return groupIcons.get(scopeId) ?? undefined
}
// A group's fragment token is its readable slug (`slugify(name)`, e.g. `#@familie-sued`) when that slug is unique
// among the caller's groups, else its opaque scopeId — groups have no server-enforced slug uniqueness (unlike
// content nodes), so a name collision falls back to the unambiguous id (cr-0120). Old `#@<id>` links keep working:
// openGroupByToken resolves both forms.
const groupTokens = computed<Record<string, string>>(() => {
  const counts = new Map<string, number>()
  for (const g of groups.value) {
    const slug = slugify(g.name)
    if (slug) counts.set(slug, (counts.get(slug) ?? 0) + 1)
  }
  const tokens: Record<string, string> = {}
  for (const g of groups.value) {
    const slug = slugify(g.name)
    tokens[g.scopeId] = slug && counts.get(slug) === 1 ? slug : g.scopeId
  }
  return tokens
})
function groupHash(group: GroupSpace): string {
  return `#@${groupTokens.value[group.scopeId] ?? group.scopeId}`
}
// Highlight by the *resolved* scope, not the URL token: a group reached via slug, id, or an old link all open the
// same scope, so the active row stays correct regardless of which fragment form addressed it.
function groupActive(scopeId: string): boolean {
  return activeSpace.scopeId === scopeId
}
async function loadGroups() {
  try {
    groups.value = await listMyGroups()
    for (const g of groups.value) {
      if (g.icon) groupIcons.set(g.scopeId, new Blob([g.icon as BlobPart], { type: 'image/webp' }))
      else groupIcons.drop(g.scopeId)
    }
  } catch {
    /* the switcher tolerates an empty groups section */
  }
}

// Fetch the switcher's tenants + decrypt each one's icon on-device (open our own grant → K_icon → the image bytes).
// A tenant with no icon / no grant / an unopenable icon just drops from the map → the initial shows. Re-run when a
// space icon changes anywhere (bumpSpaceIcon) so the owner's edit reaches this always-mounted rail without a reload.
async function loadTenants() {
  try {
    tenants.value = await listMyTenants()
  } catch {
    return // the sidebar tolerates an empty switcher
  }
  await Promise.all(
    tenants.value.map(async (tn) => {
      const bytes = await openMyTenantIcon(tn)
      if (bytes) tenantIcons.set(tn.tenantId, new Blob([bytes as BlobPart], { type: 'image/webp' }))
      else tenantIcons.drop(tn.tenantId)
    }),
  )
}
watch(spaceIconVersion, () => { void loadTenants(); void loadGroups() })

// Create-group dialog. On success: refresh the list + switch into the new group (`#@<id>`).
const createGroupOpen = ref(false)
async function onGroupCreated(scopeId: string) {
  await loadGroups()
  const created = groups.value.find((g) => g.scopeId === scopeId)
  void router.push({ name: 'home', hash: created ? groupHash(created) : `#@${scopeId}` })
}

// --- compact top bar: measured group overflow (cr-0120 follow-up, ported from the design source's TenantSidebar).
// At the compact width the groups become a horizontal strip; only as many links as physically fit are shown, and
// the rest collapse into a "＋N" chevron menu (with a trailing "create group"). Widths are measured off-screen via
// a hidden clone (`.compact-group-measure`), re-measured on resize + when the group list changes.
const groupMenu = ref<InstanceType<typeof Menu> | null>(null)
const compactGroupLinks = ref<HTMLElement | null>(null)
const compactGroupMeasure = ref<HTMLElement | null>(null)
const compactGroupLinksWidth = ref(0)
const compactGroupOverflowWidth = ref(48)
const compactGroupItemWidths = ref<Record<string, number>>({})

const visibleCompactGroups = computed<GroupSpace[]>(() => {
  // Reserve the chevron's width so it never has to share the strip with a link it can't fit beside. When not
  // even the first link fits (a very narrow strip), show none inline — the chevron alone holds every group.
  // (Forcing a first link here is what clipped both the link and the chevron off the `overflow:hidden` strip.)
  const available = compactGroupLinksWidth.value - compactGroupOverflowWidth.value - 8
  const visible: GroupSpace[] = []
  let used = 0
  for (const g of groups.value) {
    const width = compactGroupItemWidths.value[g.scopeId] ?? 126
    const next = used + width + (visible.length > 0 ? 8 : 0)
    if (next > available) break
    visible.push(g)
    used = next
  }
  return visible
})
const overflowCompactGroups = computed<GroupSpace[]>(() => {
  const seen = new Set(visibleCompactGroups.value.map((g) => g.scopeId))
  return groups.value.filter((g) => !seen.has(g.scopeId))
})
// The "＋N" menu lists only the groups that didn't fit; creation stays on the always-present "＋" in the heading
// (so it isn't duplicated here). The chevron shows only when something actually overflowed.
const groupMenuItems = computed<MenuItem[]>(() =>
  overflowCompactGroups.value.map((g) => ({
    label: g.name,
    icon: 'pi pi-users',
    command: () => void router.push({ name: 'home', hash: groupHash(g) }),
  })),
)
function toggleGroupMenu(event: Event) {
  groupMenu.value?.toggle(event)
}

/** Measure the visible strip + each group's rendered width (from the off-screen clone) so the greedy packer knows
 *  how many fit. Runs after the DOM settles (nextTick), on group changes, and on resize. */
async function measureCompactGroupNav() {
  await nextTick()
  if (compactGroupLinks.value) compactGroupLinksWidth.value = compactGroupLinks.value.getBoundingClientRect().width
  const root = compactGroupMeasure.value
  if (!root) return
  const widths: Record<string, number> = {}
  for (const el of Array.from(root.querySelectorAll<HTMLElement>('[data-compact-group-id]'))) {
    const id = el.dataset.compactGroupId
    if (id) widths[id] = Math.ceil(el.getBoundingClientRect().width)
  }
  compactGroupItemWidths.value = widths
  const overflowBtn = root.querySelector<HTMLElement>('[data-compact-group-overflow]')
  if (overflowBtn) compactGroupOverflowWidth.value = Math.ceil(overflowBtn.getBoundingClientRect().width)
}

watch(groups, () => void measureCompactGroupNav())

// A RAF-debounced ResizeObserver (veyq has no shared helper) re-packs the strip as the top bar width changes.
let groupResizeObserver: ResizeObserver | null = null
onBeforeUnmount(() => groupResizeObserver?.disconnect())

// The caller's own per-tenant username builds the Personal link `#~<username>` (ADR 0023); until it resolves (or
// if there's no profile in this tenant), the `~me` fallback still routes to the personal scope.
const myUsername = ref('')
const personalHash = computed(() => `#${personalScope(myUsername.value)}`)

// The active tenant drives the merged switcher row (cr-0108): its avatar + name + domain, and its body enters
// the shared Tenant home. Falls back to the first known tenant until the active id resolves.
const activeTenant = computed(
  () => tenants.value.find((tn) => tn.tenantId === activeSpace.tenantId) ?? tenants.value[0] ?? null,
)

// The all-tenants overview (cr-0108): a chevron on the active row toggles this popup; it lists every tenant the
// member belongs to and switches on select. Shown only when there is more than one tenant to switch between.
const tenantOverview = ref<InstanceType<typeof Popover> | null>(null)

// The pinned Memories, resolved against the local decrypted tree (a favorite whose node is gone just drops).
const favoriteMemories = computed(() =>
  [...favoritesStore.ids]
    .map((id) => nodeById(treeStore.nodes, id))
    .filter((n): n is TreeNode => !!n && n.kind === NodeKind.Memory)
    .map((n) => ({ id: n.id, name: n.name, hash: hashForNode(treeStore.nodes, n.id, activeScope.value) })),
)
/** The pinned Memories' covers, resolved by the shared store. A favorite without one keeps the placeholder —
 *  uc.memory.cover E1 stands: there is no auto-cover, and the rail adds no branch to say so. */
const favoriteCover = (id: string) => covers.value.get(id)
watch(
  // Keyed on each favorite's **sealed meta**, not merely on which ids are pinned — the same key the store uses
  // for staleness, because the store only re-resolves what it is asked about. `patchNodeMeta` mutates
  // `wrappedMeta` IN PLACE, so a watch over the ids, the node count or the array identity never fires and a
  // cover set in the detail header leaves the rail showing the old one. The epoch is here for the other half:
  // a rotation swaps the keys a cover was decrypted under and the store drops them, so the rail must ask again
  // rather than sit on an empty map.
  () => favoriteMemories.value.map((m) => `${m.id}:${nodeById(treeStore.nodes, m.id)?.wrappedMeta ?? ''}`).join('|') + `#${activeSpace.epoch}`,
  () => ensureCovers(favoriteMemories.value.map((m) => m.id)),
  { immediate: true },
)

onMounted(async () => {
  await loadTenants()
  try {
    myUsername.value = (await getMyProfile()).username
  } catch {
    /* no profile in this tenant yet → the ~me fallback still routes to the personal scope */
  }
  await loadGroups()
  // measure once + observe the strip and the hidden clone so the pack re-runs as the compact bar reflows.
  void measureCompactGroupNav()
  if (typeof ResizeObserver !== 'undefined') {
    let raf = 0
    groupResizeObserver = new ResizeObserver(() => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => void measureCompactGroupNav())
    })
    if (compactGroupLinks.value) groupResizeObserver.observe(compactGroupLinks.value)
    if (compactGroupMeasure.value) groupResizeObserver.observe(compactGroupMeasure.value)
  }
})

function isActive(tenant: MyTenant): boolean {
  return tenant.tenantId === activeSpace.tenantId
}

function switchTenant(tenant: MyTenant) {
  if (isActive(tenant)) return
  // Each tenant is reached at its own domain (ADR 0002); switching is a full navigation there.
  window.location.assign(`https://${tenant.domain}`)
}

// Mobile navigation (cr-0084): below the compact threshold the inline nav is hidden, so a hamburger opens a
// bottom-sheet with the full navigation (tenant switch + Shared/Personal + favorites) — ported from the legacy
// design source. Groups join it when the groups epic lands.
const mobileMenuOpen = ref(false)
function closeMobileMenu() {
  mobileMenuOpen.value = false
}
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink :to="{ name: 'home', hash: '' }" class="sidebar-brand" aria-label="veyq Home">
      <img class="sidebar-brand-mark" :src="brandLogoUrl" alt="" aria-hidden="true" />
      <span>veyq</span>
    </RouterLink>

    <section class="sidebar-section sidebar-tenant-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('shell.spaces') }}</p>
      </div>
      <!-- The tenant switcher and the shared-Tenant-space entry are one row (cr-0108): the body enters the
           token-less Tenant home; a chevron opens the all-tenants overview. Both are labelled with the active
           tenant, so a single row replaces the old inline switcher list + the separate Tenant-space nav link. -->
      <div v-if="activeTenant" class="sidebar-tenant-switcher">
        <div class="sidebar-tenant-active">
          <RouterLink v-slot="{ href, navigate }" :to="{ name: 'home', hash: '' }" custom>
            <a
              :href="href"
              class="sidebar-tenant-link sidebar-tenant-space-link"
              :class="{ 'router-link-active': tenantActive }"
              :title="activeTenant.name"
              @click="navigate"
            >
              <TenantAvatar :name="activeTenant.name" :icon="tenantIcon(activeTenant.tenantId)" active />
              <span class="sidebar-tenant-text">
                <strong>{{ activeTenant.name }}</strong>
                <!-- Awaiting access: a grant-less member is on this tenant but has no key yet (cr-0077). The
                     token-less home fell back to Personal (activeSpace.tenantUnavailable); a key-holder delivers
                     the grant automatically (cr-0076), then this clears on the next entry. -->
                <small v-if="activeSpace.tenantUnavailable" class="sidebar-tenant-awaiting">
                  {{ t('members.awaitingAccess') }}
                </small>
                <small v-else>{{ activeTenant.domain }}</small>
              </span>
            </a>
          </RouterLink>
          <!-- Only when there is somewhere to switch to (self-hosted is usually a single tenant). -->
          <button
            v-if="tenants.length > 1"
            class="sidebar-tenant-switch"
            type="button"
            :title="t('shell.tenantSwitch')"
            :aria-label="t('shell.tenantSwitch')"
            aria-haspopup="menu"
            @click="tenantOverview?.toggle($event)"
          >
            <span class="pi pi-chevron-down" />
          </button>
        </div>
        <Popover ref="tenantOverview" class="sidebar-tenant-overview">
          <p class="sidebar-tenant-overview-label">{{ t('shell.tenantSwitch') }}</p>
          <div class="sidebar-tenant-overview-list">
            <button
              v-for="tenant in tenants"
              :key="tenant.tenantId"
              class="sidebar-tenant-link"
              :class="{ 'sidebar-tenant-link-active': isActive(tenant) }"
              type="button"
              :title="tenant.name"
              @click="switchTenant(tenant); tenantOverview?.hide()"
            >
              <TenantAvatar :name="tenant.name" :icon="tenantIcon(tenant.tenantId)" :active="isActive(tenant)" />
              <span class="sidebar-tenant-text">
                <strong>{{ tenant.name }}</strong>
                <small>{{ tenant.domain }}</small>
              </span>
              <span v-if="isActive(tenant)" class="pi pi-check sidebar-tenant-current" aria-hidden="true" />
            </button>
          </div>
        </Popover>
      </div>
    </section>

    <nav class="sidebar-nav" :aria-label="t('shell.spaces')">
      <!-- The shared Tenant home is the active-tenant row above (cr-0108); Personal rides `#~me`. Both share the
           `/` path (only the fragment differs), so the active class is driven from the leading token, not
           RouterLink's path-based `router-link-active`. -->
      <RouterLink v-slot="{ href, navigate }" :to="{ name: 'home', hash: personalHash }" custom>
        <a
          :href="href"
          class="sidebar-nav-link sidebar-personal-link"
          :class="{ 'router-link-active': personalActive }"
          @click="navigate"
        >
          <span class="pi pi-user" />
          <span>{{ t('shell.personal') }}</span>
        </a>
      </RouterLink>
    </nav>

    <section v-if="favoriteMemories.length" class="sidebar-section sidebar-favorites-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('shell.favorites') }}</p>
      </div>
      <RouterLink
        v-for="memory in favoriteMemories"
        :key="memory.id"
        :to="{ name: 'home', hash: memory.hash }"
        class="sidebar-scope-link sidebar-favorite-link"
      >
        <span class="sidebar-favorite-cover">
          <MemoryCoverImage :src="favoriteCover(memory.id)?.url" :crop="favoriteCover(memory.id)?.crop ?? null" :alt="memory.name" />
        </span>
        <span><strong>{{ memory.name }}</strong></span>
      </RouterLink>
    </section>

    <!-- Groups (en.spaces.group-scope, cr-0032). The header + "＋" is always present so the first group is
         creatable; the group links appear as they land. Desktop renders `.desktop-group-link`; the compact rail
         renders `.compact-group-nav` (layout.css toggles which is visible). Active class is driven from the
         leading token (all groups share the `/` path, so RouterLink's path-based active can't distinguish). -->
    <section class="sidebar-section sidebar-groups-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('shell.groups') }}</p>
        <button
          v-if="canCreateGroup"
          class="sidebar-section-action"
          type="button"
          :title="t('shell.groupCreate')"
          :aria-label="t('shell.groupCreate')"
          @click="createGroupOpen = true"
        >
          <span class="pi pi-plus" />
        </button>
      </div>
      <RouterLink
        v-for="group in groups"
        :key="group.scopeId"
        v-slot="{ href, navigate }"
        :to="{ name: 'home', hash: groupHash(group) }"
        custom
      >
        <a
          :href="href"
          class="sidebar-scope-link desktop-group-link"
          :class="{ 'router-link-active': groupActive(group.scopeId) }"
          @click="navigate"
        >
          <TenantAvatar v-if="groupIcon(group.scopeId)" :name="group.name" :icon="groupIcon(group.scopeId)" />
          <span v-else class="sidebar-scope-icon pi pi-users" />
          <span><strong>{{ group.name }}</strong></span>
        </a>
      </RouterLink>
      <!-- Compact strip (measured overflow): only the groups that fit, then a "＋N" chevron menu for the rest. -->
      <div class="compact-group-nav">
        <div ref="compactGroupLinks" class="compact-group-links">
          <RouterLink
            v-for="group in visibleCompactGroups"
            :key="group.scopeId"
            v-slot="{ href, navigate }"
            :to="{ name: 'home', hash: groupHash(group) }"
            custom
          >
            <a
              :href="href"
              class="sidebar-scope-link compact-group-link"
              :class="{ 'router-link-active': groupActive(group.scopeId) }"
              @click="navigate"
            >
              <TenantAvatar v-if="groupIcon(group.scopeId)" :name="group.name" :icon="groupIcon(group.scopeId)" />
              <span v-else class="sidebar-scope-icon pi pi-users" />
              <span><strong>{{ group.name }}</strong></span>
            </a>
          </RouterLink>
          <button
            v-if="groupMenuItems.length"
            class="compact-group-overflow-button"
            type="button"
            :aria-label="t('shell.groupsMenuOpen')"
            aria-haspopup="menu"
            @click="toggleGroupMenu"
          >
            <span class="pi pi-chevron-down" />
          </button>
        </div>
      </div>
      <!-- Off-screen clone: every group + the overflow button, so their rendered widths can be measured. -->
      <div ref="compactGroupMeasure" class="compact-group-measure" aria-hidden="true">
        <span
          v-for="group in groups"
          :key="group.scopeId"
          class="sidebar-scope-link compact-group-link"
          :data-compact-group-id="group.scopeId"
        >
          <span class="sidebar-scope-icon pi pi-users" />
          <span><strong>{{ group.name }}</strong></span>
        </span>
        <button class="compact-group-overflow-button" type="button" tabindex="-1" data-compact-group-overflow>
          <span class="pi pi-chevron-down" />
        </button>
      </div>
      <Menu ref="groupMenu" :model="groupMenuItems" popup />
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>

    <!-- Mobile: the inline nav is CSS-hidden below the compact threshold — this hamburger opens the full nav
         as a bottom sheet (cr-0084, ported from the legacy design source; the styles were already vendored). -->
    <button class="mobile-navigation-button" type="button" :aria-label="t('shell.menuOpen')" @click="mobileMenuOpen = true">
      <span class="pi pi-bars" />
    </button>

    <Dialog v-model:visible="mobileMenuOpen" modal position="bottom" class="tenant-dialog mobile-nav-dialog" :header="t('shell.menu')">
      <div class="mobile-nav-sheet">
        <!-- One BEREICHE group (cr-0108): the active tenant row enters the shared Tenant home (the old separate
             "Gemeinsam" entry, folded in), other tenants switch, and Personal follows — mirroring the desktop
             merge. No popover here; the full sheet lists the tenants inline. -->
        <section class="mobile-nav-section">
          <h2>{{ t('shell.spaces') }}</h2>
          <div class="mobile-nav-list">
            <template v-for="tenant in tenants" :key="tenant.tenantId">
              <RouterLink
                v-if="isActive(tenant)"
                :to="{ name: 'home', hash: '' }"
                class="mobile-nav-item"
                :class="{ 'mobile-nav-item-active': tenantActive }"
                @click="closeMobileMenu"
              >
                <TenantAvatar :name="tenant.name" :icon="tenantIcon(tenant.tenantId)" active />
                <span>
                  <strong>{{ tenant.name }}</strong>
                  <small v-if="activeSpace.tenantUnavailable" class="sidebar-tenant-awaiting">{{ t('members.awaitingAccess') }}</small>
                  <small v-else>{{ tenant.domain }}</small>
                </span>
              </RouterLink>
              <button
                v-else
                class="mobile-nav-item"
                type="button"
                @click="switchTenant(tenant); closeMobileMenu()"
              >
                <TenantAvatar :name="tenant.name" :icon="tenantIcon(tenant.tenantId)" />
                <span><strong>{{ tenant.name }}</strong><small>{{ tenant.domain }}</small></span>
              </button>
            </template>
            <RouterLink
              :to="{ name: 'home', hash: personalHash }"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': personalActive }"
              @click="closeMobileMenu"
            >
              <span class="sidebar-scope-icon pi pi-user" /><span>{{ t('shell.personal') }}</span>
            </RouterLink>
          </div>
        </section>

        <section class="mobile-nav-section">
          <h2>{{ t('shell.groups') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-for="group in groups"
              :key="group.scopeId"
              :to="{ name: 'home', hash: groupHash(group) }"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': groupActive(group.scopeId) }"
              @click="closeMobileMenu"
            >
              <TenantAvatar v-if="groupIcon(group.scopeId)" :name="group.name" :icon="groupIcon(group.scopeId)" /><span v-else class="sidebar-scope-icon pi pi-users" /><span><strong>{{ group.name }}</strong></span>
            </RouterLink>
            <button v-if="canCreateGroup" class="mobile-nav-item" type="button" @click="createGroupOpen = true; closeMobileMenu()">
              <span class="sidebar-scope-icon pi pi-plus" /><span>{{ t('shell.groupCreate') }}</span>
            </button>
          </div>
        </section>

        <section v-if="favoriteMemories.length" class="mobile-nav-section">
          <h2>{{ t('shell.favorites') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-for="memory in favoriteMemories"
              :key="memory.id"
              :to="{ name: 'home', hash: memory.hash }"
              class="mobile-nav-item mobile-nav-favorite"
              @click="closeMobileMenu"
            >
              <span class="sidebar-favorite-cover">
                <MemoryCoverImage :src="favoriteCover(memory.id)?.url" :crop="favoriteCover(memory.id)?.crop ?? null" :alt="memory.name" />
              </span><span><strong>{{ memory.name }}</strong></span>
            </RouterLink>
          </div>
        </section>
      </div>
    </Dialog>

    <CreateGroupDialog v-if="createGroupOpen" @done="onGroupCreated" @close="createGroupOpen = false" />
  </aside>
</template>
