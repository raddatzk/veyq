// The sections of the settings page and their gates (ADR 0022, ADR 0125 decision 9): ONE list, read by the settings rail
// (`views/SettingsHubView.vue`) and by the search (`core/search/SearchField.vue`), so a section the rail hides is never a
// search result and the two cannot disagree about a gate. Each caller holds its own answers (`useSettingsSections()` in its
// setup) and asks for them with `load()`: the rail when it mounts, the search when its field gains focus.
//
// **A section is never judged on a guess.** `ready` is true once every answer a gate depends on is in: the groups, this
// device's refused trust documents, the moderator appointment, and the tenant role read for the tenant the shell opened
// (a reload on a settings page boots with no space open, and the shell enters the tenant while the hub mounts - until that
// entry is through, no tenant is known, and a role read for no tenant says "no owner"). A failed read is an answer: the
// section it gates is not offered.
//
// **Words a section is found by** (uc.platform.search step 5): beside its label, each section names a few words in
// its entry (`words`, from the catalogs' `settings.words.<id>`, comma-separated, in the UI's language) - "E-Mail" finds the
// mail section, "Kennwort" the password. The search matches them from a word's start (`SearchDocument.keywords`), never
// inside one, and from the third typed character on; the rail does not show them, the search's row does where one is why
// the section is listed.
//
// The rail mixes two axes (ADR 0022 §3): the account's sections (Persönlich, always), the scope-in-path ones (the tenant's
// while the Tenant scope is active) and a collection (Gruppen: every group the caller owns).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { refusedDocuments } from '../account/trustReset/refusedDocuments'
import { myAppointment, type ModeratorAppointment } from '../moderation/appointment'
import { ModeratorAppointmentStateEnum } from '../../api/generated/moderation'
import { activeSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { listMyGroups, listMyTenants, type GroupSpace } from '../spaces/scopes'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { CORE_DEVICE_LINKING } from '../platform/capabilities'
import type { VeyqIconName } from '../../shared/ui/icons'

/** One section of the settings page: its address is `/settings/<id>`. */
export interface SettingsSection {
  id: string
  label: string
  icon: VeyqIconName
  /** Other words the search finds the section by (see the header); none for a group's section. */
  words?: readonly string[]
}

/** One heading of the rail with the sections under it. */
export interface SettingsCategory {
  key: 'personal' | 'tenant' | 'groups'
  label: string
  items: SettingsSection[]
  /** A collection (Gruppen) folds; the account's and the space's sections do not. */
  collapsible: boolean
  /** What a collection with nothing in it says. */
  empty?: string
}

/** The owner-only sections: their reads are owner-gated, so only an owner is offered them. */
export const OWNER_ONLY_SECTIONS: ReadonlySet<string> = new Set(['mail', 'storage', 'capabilities'])

/** The sections of the settings page and their gates for the caller (see the header). Call in a component's setup. */
export function useSettingsSections() {
  const { t } = useI18n()
  const { hasCapability } = useCapabilities()
  /** A section's words from its catalog entry (see the header). */
  const words = (id: string): string[] => t(`settings.words.${id}`).split(',').map((w) => w.trim()).filter(Boolean)
  // Adding a device (uc.account.onboard-device) needs the instance's `core.device-linking`: the server refuses every
  // /api/pairing/* call without it.
  const deviceLinking = computed(() => hasCapability(CORE_DEVICE_LINKING))

  // Every group the caller belongs to (names decrypted on-device), and of those the ones it OWNs — the Gruppen collection
  // (ADR 0022 §3). A member-only group isn't listed (nothing to manage), but its page is reachable read-only: as the
  // contextual default inside that group and by its address (inbox #814). What that page offers is the panel's own
  // fail-closed owner answer, read from the group's directory - the list only decides that the page exists for the caller.
  const myGroups = ref<GroupSpace[]>([])
  const ownedGroups = computed(() => myGroups.value.filter((g) => g.role === 'OWNER'))

  // Whether this device refuses its pin store or its device register right now (ADR 0028 Decision 7) — the one
  // condition under which the rail offers "Start a list over". A read like every other: it opens through the marks
  // and writes nothing; the reset itself is reachable only from that section's confirm dialog.
  const trustRefused = ref(false)

  // The caller's moderator appointment (uc.moderation.serve-as-moderator): the Moderation section — a door to the
  // surface, which lives outside this shell (ADR 0099 § 6) — is offered to a pending or accepted appointee and to
  // nobody else. Null until the answer is in, and on a failed read.
  const appointment = ref<ModeratorAppointment | null>(null)
  const isAppointed = computed(
    () =>
      appointment.value?.state === ModeratorAppointmentStateEnum.Pending ||
      appointment.value?.state === ModeratorAppointmentStateEnum.Accepted,
  )

  // Whether the caller owns the active tenant: the owner-only sections are offered to an owner and to nobody else - a
  // member is not offered a page that can only refuse them. False until the answer is in, and on a failed read.
  const isTenantOwner = ref(false)
  /** The tenant `isTenantOwner` answers for (undefined before the first answer): an answer for another tenant is none. */
  const roleTenant = ref<string | null | undefined>(undefined)

  /** True once every answer of a first `load()` is in. */
  const loaded = ref(false)
  /** True once `load()` was asked for: before it, a tenant change reads nothing (the search's field was never focused). */
  let started = false

  async function loadGroups() {
    try {
      myGroups.value = await listMyGroups()
    } catch {
      /* the rail tolerates an empty groups collection */
    }
  }
  async function loadTrustRefusals() {
    try {
      trustRefused.value = (await refusedDocuments()).length > 0
    } catch {
      trustRefused.value = false
    }
  }
  async function loadAppointment() {
    try {
      appointment.value = await myAppointment()
    } catch {
      appointment.value = null
    }
  }
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
  watch(() => activeSpace.tenantId, () => {
    if (started) void loadTenantRole()
  })

  /** Read every answer (again): the previous answers stand until the new ones are in. */
  async function load(): Promise<void> {
    started = true
    await Promise.all([loadGroups(), loadTrustRefusals(), loadTenantRole(), loadAppointment()])
    loaded.value = true
  }

  /** The shell is entering a space: no tenant is known until that entry is through. */
  const entering = computed(() => activeSpace.status === 'loading')
  /** Every answer is in, for the tenant on screen (see the header). */
  const ready = computed(() => loaded.value && !entering.value && roleTenant.value === activeSpace.tenantId)

  const categories = computed<SettingsCategory[]>(() => {
    const cats: SettingsCategory[] = [
      {
        key: 'personal',
        label: t('settings.personal'),
        collapsible: false,
        items: [
          { id: 'appearance', label: t('settings.appearance'), icon: 'palette', words: words('appearance') },
          // This browser's consent to the map tiles and the place-name lookup (uc.memory.place A5): device-local like
          // appearance, so it sits beside it; always listed, since a consent must be withdrawable wherever it was given.
          { id: 'maps', label: t('settings.maps'), icon: 'map', words: words('maps') },
          // Every signed-in device + the remote sign-out (uc.account.sessions), AND how this device unlocks
          // (uc.account.trust-device § Untrust) — one rubric, because both are facts about a device and the list
          // already carries a row for this one. Account-wide, not scope-bound, and gated on a session rather than
          // an unlock. The former separate "This device" section was REPLACED, not redirected (ADR 0022).
          { id: 'sessions', label: t('settings.sessions'), icon: 'mobile', words: words('sessions') },
          // Change password (uc.account.change-password) sits beside sessions: it is the credential-adjacent
          // action, account-wide like sessions, gated on a session rather than an unlock (like it, and unlike
          // security below, which rewrites Master-Key-sealed material).
          { id: 'password', label: t('settings.password'), icon: 'key', words: words('password') },
          // Adding a device (uc.account.onboard-device) sits beside the sessions it produces. The route's own
          // `requiresUnlock: true` is the gate and it is the correct one here: the granting device is unlocked by
          // premise — it seals the Master Key it is holding. Offered only where the instance links devices at all
          // (`core.device-linking`); off, its address leads back to /settings like any hidden section (`isHidden`).
          ...(deviceLinking.value ? [{ id: 'pairing', label: t('pairing.navTitle'), icon: 'qrcode', words: words('pairing') } satisfies SettingsSection] : []),
          // Replacing the account's keys after a compromise (uc.account.rotate-keys). It sits below the rubric
          // whose failure it answers — a device trusted by mistake, a session that revoking cannot reach — because
          // that is the order the person arrives in. The route's `requiresUnlock: true` is the
          // right gate: the rotation rewrites Master-Key-sealed material, so a locked vault could not run it at
          // all; its OWN gate is narrower still and lives in the panel (ADR 0060 Decision 2 — a device wrap
          // opened this vault is refused before the form is offered).
          { id: 'security', label: t('settings.security'), icon: 'shield', words: words('security') },
          // Starting a refused trust document over (ADR 0028 Decision 7's way back) — beside key rotation and the
          // sessions, and shown ONLY while this device refuses one, so it is never a standing button next to them.
          // Every refusal notice links here; the section's confirm dialog is the one place the reset can run.
          ...(trustRefused.value ? [{ id: 'trust-reset', label: t('trustReset.navTitle'), icon: 'history', words: words('trust-reset') } satisfies SettingsSection] : []),
          // Per-tenant identity (username + real name) needs any tenant context (ADR 0023).
          ...(activeSpace.tenantId ? [{ id: 'profile', label: t('profile.title'), icon: 'user', words: words('profile') } satisfies SettingsSection] : []),
          // The way into moderation, for an appointed account only. Account-wide like an appointment (the instance's, not a
          // space's); the panel only links out, because reported content is never opened beside this shell's vault.
          ...(isAppointed.value ? [{ id: 'moderation', label: t('moderation.entry.navTitle'), icon: 'flag', words: words('moderation') } satisfies SettingsSection] : []),
          // Erasing the account (uc.account.erase-account, ADR 0101) — last, below everything it ends. Account-wide like
          // sessions; the hub's `requiresUnlock` gives the panel the account id and address its proof binds. The Server
          // Owner's account is never erased: the panel reads that from the server and says so (A2), as it is the only
          // place that knows.
          { id: 'erase', label: t('erasure.navTitle'), icon: 'user-minus', words: words('erase') },
        ],
      },
    ]
    // Tenant members + terms — scope-in-path: managed while the shared Tenant scope is active (needs K_tenant open).
    // Terms are member-readable (the editor is owner-only, cr-0114); the item is shown to every member.
    if (activeSpace.kind === ScopeKind.Tenant) {
      cats.push({ key: 'tenant', label: t('settings.tenant'), collapsible: false, items: [
        { id: 'tenant-members', label: t('members.title'), icon: 'users', words: words('tenant-members') },
        // Who's who (inbox #562): every member's key in this space - confirmed or not, verified by whom, a new identity.
        // Every member's, like Members: the records it reads are every member's, so no role changes what it shows.
        { id: 'tenant-people', label: t('whoIsWho.navTitle'), icon: 'verified', words: words('tenant-people') },
        { id: 'terms', label: t('terms.navTitle'), icon: 'file-edit', words: words('terms') },
        // Mail settings, storage and capabilities are owner-only (their reads are owner-gated): only an owner is offered
        // them, so a member is never taken to a page that says it could not load. The panels still gate - the server
        // refuses a member either way.
        ...(isTenantOwner.value
          ? ([
              { id: 'mail', label: t('mail.navTitle'), icon: 'envelope', words: words('mail') },
              { id: 'storage', label: t('storage.navTitle'), icon: 'database', words: words('storage') },
              { id: 'capabilities', label: t('tenantCapabilities.navTitle'), icon: 'sliders-h', words: words('capabilities') },
            ] satisfies SettingsSection[])
          : []),
      ] })
    }
    // Gruppen collection — every group I own (management hub, decoupled from active scope). A group in pending-deletion
    // (cr-0090) is badged with a trash icon so the roster shows it's on its way out.
    if (activeSpace.tenantId) {
      cats.push({
        key: 'groups',
        label: t('shell.groups'),
        collapsible: true,
        empty: t('groups.noneOwned'),
        items: ownedGroups.value.map((g): SettingsSection => ({ id: `group-${g.scopeId}`, label: g.name, icon: g.deletedAt ? 'trash' : 'users' })),
      })
    }
    return cats
  })

  /**
   * Whether the caller may not see section `id`: such a section is not offered, and its address leads back to /settings.
   * The owner-only pages need the tenant role, a trust reset needs a refused document, a group page needs the caller to
   * be its member (an owner or not - a failed group read is no membership), moderation needs an appointment; adding a
   * device needs the instance's `core.device-linking`. Meaningful once `ready`.
   */
  function isHidden(id: string): boolean {
    if (id === 'pairing') return !deviceLinking.value
    if (OWNER_ONLY_SECTIONS.has(id)) return !isTenantOwner.value
    if (id === 'trust-reset') return !trustRefused.value
    if (id === 'moderation') return !isAppointed.value
    if (id.startsWith('group-')) return !myGroups.value.some((g) => `group-${g.scopeId}` === id)
    return false
  }

  /** The sections offered now, each with its heading: none until `ready`. */
  const offered = computed<{ section: SettingsSection; category: SettingsCategory }[]>(() =>
    ready.value ? categories.value.flatMap((category) => category.items.map((section) => ({ section, category }))) : [],
  )

  return {
    categories, offered, ready, entering, isHidden, load, loadGroups, loadTrustRefusals,
    myGroups, appointment, isAppointed, isTenantOwner, trustRefused,
  }
}
