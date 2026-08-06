<script setup lang="ts">
// The "Persönlich" section of the settings hub (cr-0078) — appearance (theme) + language. Device-local
// preferences (never synced, operator-blind); reuses the existing theme/locale state (cr-0035/0037) that the
// profile menu's quick toggles also drive, so the two stay in sync. No new behaviour — just a settings home.
import { useI18n } from 'vue-i18n'
import { useThemeState, setThemePreference, type ThemePreference } from '../../shared/state/useThemeState'
import {
  localePreference,
  setLocalePreference,
  LOCALE_PREFERENCES,
  LOCALE_LABELS,
  type LocalePreference,
} from '../../i18n'

const { t } = useI18n()
const { themePreference } = useThemeState()
const localePref = localePreference()

const themes: ThemePreference[] = ['system', 'light', 'dark']
const themeLabel = (p: ThemePreference) => t(`shell.theme${p.charAt(0).toUpperCase()}${p.slice(1)}`)
const localeLabel = (p: LocalePreference) => (p === 'system' ? t('shell.themeSystem') : LOCALE_LABELS[p])
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('settings.appearanceTitle') }}</h2>
          <p>{{ t('settings.appearanceSub') }}</p>
        </div>
      </div>

      <div class="settings-row">
        <span class="settings-row-label">{{ t('shell.theme') }}</span>
        <div class="settings-choice" role="group" :aria-label="t('shell.theme')">
          <button
            v-for="p in themes"
            :key="p"
            type="button"
            class="settings-choice-btn"
            :class="{ 'is-active': themePreference === p }"
            :aria-pressed="themePreference === p"
            @click="setThemePreference(p)"
          >
            {{ themeLabel(p) }}
          </button>
        </div>
      </div>

      <div class="settings-row">
        <span class="settings-row-label">{{ t('shell.language') }}</span>
        <div class="settings-choice" role="group" :aria-label="t('shell.language')">
          <button
            v-for="p in LOCALE_PREFERENCES"
            :key="p"
            type="button"
            class="settings-choice-btn"
            :class="{ 'is-active': localePref === p }"
            :aria-pressed="localePref === p"
            @click="setLocalePreference(p)"
          >
            {{ localeLabel(p) }}
          </button>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.settings-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.6rem 0;
  border-top: 0.5px solid var(--p-content-border-color);
}
.settings-row-label {
  font-weight: 500;
}
.settings-choice {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
}
.settings-choice-btn {
  padding: 0.35rem 0.8rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
}
.settings-choice-btn.is-active {
  background: var(--p-primary-color);
  color: var(--p-primary-contrast-color);
  border-color: var(--p-primary-color);
}
</style>
