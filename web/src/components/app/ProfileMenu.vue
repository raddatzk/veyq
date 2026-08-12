<script setup lang="ts">
// The sidebar profile (cr-0031). Markup + classes are the design source's ProfileMenu verbatim (.profile-button
// + a popup TieredMenu) so layout.css styles it identically; only the data + actions are ours — the label is
// the in-memory account email, and the menu carries the language quick-picker, a settings-hub entry, and
// sign-out. Theme is a one-tap top-bar toggle (cr-0078), not here.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Avatar from 'primevue/avatar'
import TieredMenu from 'primevue/tieredmenu'
import type { MenuItem } from 'primevue/menuitem'
import { account } from '../../app/account'
import { signOut } from '../../features/account/login/signOut'
import { setLocalePreference, localePreference, LOCALE_PREFERENCES, LOCALE_LABELS } from '../../i18n'

defineProps<{ showName?: boolean }>()

const router = useRouter()
const { t } = useI18n()
const localePref = localePreference()

const userLabel = computed(() => account.email || 'veyq')
const profileLabel = computed(() => userLabel.value.charAt(0).toUpperCase() || 'V')

const userMenu = ref<InstanceType<typeof TieredMenu> | null>(null)

const languageMenuItems = computed<MenuItem[]>(() =>
  LOCALE_PREFERENCES.map((pref) => ({
    label: pref === 'system' ? t('shell.themeSystem') : LOCALE_LABELS[pref],
    icon: localePref.value === pref ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocalePreference(pref),
  })),
)
const menuItems = computed<MenuItem[]>(() => [
  { label: userLabel.value, icon: 'pi pi-user' },
  { separator: true },
  // All settings (personal + tenant + server, scope/role-filtered) live in the hub (cr-0078, ADR 0022); the
  // menu keeps the language quick-picker + this one entry into the hub. Theme is a one-tap top-bar toggle.
  { label: t('shell.settings'), icon: 'pi pi-cog', command: () => void router.push('/settings') },
  // Group invitations moved out of this menu into the top-bar attention badge (cr-invitations-topbar-badge,
  // InvitationsBadge.vue) — an appears-on-demand badge routing to /invitations, so it is no longer a quiet,
  // always-present menu row here.
  { label: t('shell.language'), icon: 'pi pi-language', items: languageMenuItems.value },
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
