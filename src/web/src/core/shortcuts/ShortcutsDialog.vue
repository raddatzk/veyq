<script setup lang="ts">
// The keyboard shortcut overview (uc.platform.keyboard-shortcuts), mounted once by the app shell (`AppShell`'s member
// layout - a share page has no shell and no overview). `?` opens it from anywhere but a field being typed in, where it is
// a character, and never while another dialog is open, which keeps the keyboard (E1); the account menu's *Keyboard
// shortcuts* opens it too (`openShortcuts`). The layer's dialog closes it on Escape, its close button or a click beside
// it, and gives the focus back to where it was.
//
// **One list** (the use case's quality note): core's groups (`CORE_SHORTCUTS`), then each application's as its registrar
// registered them (`registry.shortcuts()`), only for an application switched on here - the same answer
// (`useCapabilities().isSwitchedOff`) every other place of the shell reads (uc.platform.switch-application-off). Core
// names no application's shortcut; what is not listed, and why, is `shortcuts.ts`' header.
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import ShortcutKeys from './ShortcutKeys.vue'
import { registry } from '../applications/registry'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { CORE_SHORTCUTS, shortcutsOpen, typingInto, type ShortcutGroup } from './shortcuts'

const { t } = useI18n()
const { isSwitchedOff } = useCapabilities()

const groups = computed<readonly ShortcutGroup[]>(() => [
  ...CORE_SHORTCUTS,
  ...registry.shortcuts().filter((s) => !isSwitchedOff(s.application)).flatMap((s) => s.groups),
])

/** Any open dialog: the layer's (Reka's `role="dialog"`, a popover's included), an alert, or a modal of an application's
 *  own (the large view of a photo). */
const OPEN_DIALOG = '[role="dialog"], [role="alertdialog"], [aria-modal="true"]'

/** `?` opens the overview (`CORE_SHORTCUTS`' *Everywhere*). Ctrl or ⌘ with it is another shortcut, not this one; AltGr
 *  (Ctrl+Alt) is how some layouts type `?`. */
function onKeydown(event: KeyboardEvent) {
  if (event.key !== '?' || event.defaultPrevented || event.isComposing) return
  if (event.metaKey || (event.ctrlKey && !event.altKey)) return
  if (typingInto(event.target)) return
  if (document.querySelector(OPEN_DIALOG)) return
  event.preventDefault()
  shortcutsOpen.value = true
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  shortcutsOpen.value = false
})
</script>

<template>
  <VeyqDialog v-model:visible="shortcutsOpen" :header="t('shortcuts.title')" class="shortcuts-dialog" data-testid="shortcuts-dialog">
    <section v-for="group in groups" :key="group.title" class="shortcuts-group">
      <h3 class="shortcuts-group__title">{{ t(group.title) }}</h3>
      <dl class="shortcuts-group__list">
        <div v-for="entry in group.entries" :key="entry.label" class="shortcuts-entry">
          <dt class="shortcuts-entry__keys"><ShortcutKeys :keys="entry.keys" /></dt>
          <dd class="shortcuts-entry__label">{{ t(entry.label) }}</dd>
        </div>
      </dl>
    </section>
  </VeyqDialog>
</template>

<style scoped>
.shortcuts-group + .shortcuts-group {
  margin-top: 1.25rem;
}
.shortcuts-group__title {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
  font-weight: 600;
}
.shortcuts-group__list {
  display: grid;
  gap: 0.4rem;
  margin: 0;
}
.shortcuts-entry {
  display: grid;
  grid-template-columns: minmax(7.5rem, 11rem) 1fr;
  align-items: baseline;
  gap: 0.75rem;
}
.shortcuts-entry__keys,
.shortcuts-entry__label {
  margin: 0;
}
.shortcuts-entry__label {
  color: var(--app-text);
}
@media (max-width: 480px) {
  .shortcuts-entry {
    grid-template-columns: 1fr;
    gap: 0.2rem;
  }
}
</style>
