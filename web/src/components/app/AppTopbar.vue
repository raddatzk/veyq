<script setup lang="ts">
// The shell's top bar (cr-0030). Markup + classes are the design source's WorkspaceTopbar (the
// .workspace-topbar grid) so layout.css styles it identically. Ours differs in the data — the breadcrumb is
// built CLIENT-SIDE from the local decrypted tree (the blind server computes none) — and in the grid: the
// design source's search center is GONE. Search is out of V1 (docs/inbox.md § V1), and a permanently disabled
// box advertising a ⌘K shortcut promises a feature that does not exist. The grid is two columns here; restoring
// the center means restoring the third (`minmax(18rem, 29rem)`) in layout.css alongside it.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Breadcrumb from 'primevue/breadcrumb'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { breadcrumb } from '../../app/breadcrumb'
import InvitationsBadge from '../../features/spaces/InvitationsBadge.vue'
import UploadStatusMenu from '../../features/memory/components/UploadStatusMenu.vue'
import { useThemeState, setThemePreference, type ThemePreference } from '../../shared/state/useThemeState'

const { t } = useI18n()
const router = useRouter()

// Theme lives in the top bar (cr-0078 follow-up); language, which grows more options, stays in the profile menu +
// the settings hub. It's a **labelled dropdown** (cr-0085): the trigger shows the current mode, the popup lists
// System / Light / Dark with the active one checked — clearer than a mystery cycle-button.
const THEME_ICON: Record<ThemePreference, string> = { system: 'pi pi-desktop', light: 'pi pi-sun', dark: 'pi pi-moon' }
const { themePreference } = useThemeState()
const themeIcon = computed(() => THEME_ICON[themePreference.value])
const themeMenu = ref<InstanceType<typeof Menu> | null>(null)
const themeLabel = (p: ThemePreference) => t(`shell.theme${p.charAt(0).toUpperCase()}${p.slice(1)}`)
const themeMenuItems = computed<MenuItem[]>(() =>
  (['system', 'light', 'dark'] as ThemePreference[]).map((pref) => ({
    label: themeLabel(pref),
    icon: themePreference.value === pref ? 'pi pi-check' : THEME_ICON[pref],
    command: () => setThemePreference(pref),
  })),
)

const home = computed<MenuItem>(() => ({
  label: breadcrumb.trail[0]?.name,
  command: () => void router.push({ hash: breadcrumb.trail[0]?.hash ?? '#~me' }),
}))
const items = computed<MenuItem[]>(() =>
  breadcrumb.trail.slice(1).map((crumb) => ({
    label: crumb.name,
    command: () => void router.push({ hash: crumb.hash }),
  })),
)
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" :home="home" class="app-breadcrumb" />
    <div class="workspace-topbar-actions">
      <button
        type="button"
        class="topbar-icon-button topbar-theme-button"
        :aria-label="t('shell.theme')"
        :title="t('shell.theme')"
        aria-haspopup="menu"
        @click="themeMenu?.toggle($event)"
      >
        <span :class="themeIcon" />
        <span class="pi pi-chevron-down topbar-icon-caret" />
      </button>
      <Menu ref="themeMenu" :model="themeMenuItems" popup />
      <InvitationsBadge />
      <UploadStatusMenu />
      <button
        type="button"
        class="topbar-icon-button"
        :aria-label="t('settings.title')"
        :title="t('settings.title')"
        @click="router.push('/settings')"
      >
        <span class="pi pi-cog" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.topbar-icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.topbar-icon-button:hover {
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
}
/* The theme trigger carries a small caret so it reads as a menu, not a mystery toggle (cr-0085). */
.topbar-theme-button {
  width: auto;
  gap: 0.2rem;
  padding: 0 0.45rem;
}
.topbar-icon-caret {
  font-size: 0.65em;
  opacity: 0.55;
}
</style>
