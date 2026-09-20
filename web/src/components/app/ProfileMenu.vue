<script setup lang="ts">
// The sidebar profile (cr-0031). Markup + classes are the design source's ProfileMenu verbatim (.profile-button
// + a popup TieredMenu) so layout.css styles it identically; only the data + actions are ours — the label is
// the in-memory account email, and the menu carries the theme and language quick-pickers, the shell's single
// entry into the settings hub, and sign-out. It is the one door: the top bar holds no preferences (ADR 0022 §5).
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Avatar from 'primevue/avatar'
import TieredMenu from 'primevue/tieredmenu'
import type { MenuItem } from 'primevue/menuitem'
import { account } from '../../app/account'
import { signOut } from '../../features/account/login/signOut'
import { setLocalePreference, localePreference, LOCALE_PREFERENCES, LOCALE_LABELS } from '../../i18n'
import { useThemeState, setThemePreference, type ThemePreference } from '../../shared/state/useThemeState'

defineProps<{ showName?: boolean }>()

const router = useRouter()
const { t } = useI18n()
const localePref = localePreference()
const { themePreference } = useThemeState()

const userLabel = computed(() => account.email || 'veyq')
const profileLabel = computed(() => userLabel.value.charAt(0).toUpperCase() || 'V')

const userMenu = ref<InstanceType<typeof TieredMenu> | null>(null)

// Theme and language are one pair (ADR 0022 §5), so the two submenus are built identically: a labelled list,
// `pi pi-check` on the active entry, the command writing the device-local preference. Neither reaches the
// server. The hub's Persönlich section stays the canonical home; these are the shortcuts.
const THEME_PREFERENCES: ThemePreference[] = ['system', 'light', 'dark']
const appearanceMenuItems = computed<MenuItem[]>(() =>
  THEME_PREFERENCES.map((pref) => ({
    label: t(`shell.theme${pref.charAt(0).toUpperCase()}${pref.slice(1)}`),
    icon: themePreference.value === pref ? 'pi pi-check' : 'pi pi-circle',
    command: () => setThemePreference(pref),
  })),
)
const languageMenuItems = computed<MenuItem[]>(() =>
  LOCALE_PREFERENCES.map((pref) => ({
    label: pref === 'system' ? t('shell.systemDefault') : LOCALE_LABELS[pref],
    icon: localePref.value === pref ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocalePreference(pref),
  })),
)
const menuItems = computed<MenuItem[]>(() => [
  { label: userLabel.value, icon: 'pi pi-user' },
  { separator: true },
  { label: t('shell.theme'), icon: 'pi pi-palette', items: appearanceMenuItems.value },
  { label: t('shell.language'), icon: 'pi pi-language', items: languageMenuItems.value },
  { separator: true },
  // All settings (personal + tenant + server, scope/role-filtered) live in the hub (cr-0078, ADR 0022), and
  // this is the ONLY route into it — a second door anywhere in the shell is the duplication this menu ended.
  { label: t('shell.settings'), icon: 'pi pi-cog', command: () => void router.push('/settings') },
  // Group invitations moved out of this menu into the top-bar attention badge (cr-invitations-topbar-badge,
  // InvitationsBadge.vue) — an appears-on-demand badge routing to /invitations, so it is no longer a quiet,
  // always-present menu row here.
  { separator: true },
  { label: t('shell.logout'), icon: 'pi pi-sign-out', command: () => void signOut(router) },
])
</script>

<template>
  <button class="profile-button" type="button" @click="userMenu?.toggle($event)">
    <Avatar :label="profileLabel" shape="circle" />
    <span v-if="showName" class="profile-button-name">{{ userLabel }}</span>
    <span v-if="showName" class="pi pi-chevron-down profile-button-chevron" />
  </button>
  <TieredMenu ref="userMenu" :model="menuItems" popup />
</template>
