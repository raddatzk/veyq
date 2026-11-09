<script setup lang="ts">
// The keys of one shortcut (uc.platform.keyboard-shortcuts), each a `<kbd>`: a chord's keys joined by "+", the
// alternatives side by side when each is a single key ("↑ ↓") and joined by "or" once one is a chord ("/ or ⌘+K"). The
// command modifier is named for the platform (`modifier.ts`); a word key in the UI's language, as a keyboard of that
// language prints it (`shortcuts.key`); an arrow by its glyph; any other key as the character it types.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ARROW_KEYS, NAMED_KEYS, type ShortcutChord } from './shortcuts'
import { commandIsApple } from './modifier'

const props = defineProps<{ keys: readonly ShortcutChord[] }>()
const { t } = useI18n()

function name(key: string): string {
  if (key === 'mod') return commandIsApple() ? '⌘' : t('shortcuts.key.ctrl')
  if (NAMED_KEYS[key]) return t(`shortcuts.key.${NAMED_KEYS[key]}`)
  return ARROW_KEYS[key] ?? key
}
const chords = computed(() => props.keys.map((chord) => chord.map(name)))
const spoken = computed(() => props.keys.some((chord) => chord.length > 1))
</script>

<template>
  <span class="shortcut-keys">
    <template v-for="(chord, i) in chords" :key="i">
      <span v-if="i > 0 && spoken" class="shortcut-keys__or">{{ t('shortcuts.or') }}</span>
      <span class="shortcut-keys__chord">
        <template v-for="(key, j) in chord" :key="j">
          <span v-if="j > 0" class="shortcut-keys__plus" aria-hidden="true">+</span>
          <kbd class="shortcut-keys__key">{{ key }}</kbd>
        </template>
      </span>
    </template>
  </span>
</template>

<style scoped>
.shortcut-keys {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.35rem;
}
.shortcut-keys__chord {
  display: inline-flex;
  align-items: center;
  gap: 0.15rem;
}
.shortcut-keys__or,
.shortcut-keys__plus {
  color: var(--app-muted);
  font-size: 0.85em;
}
.shortcut-keys__key {
  display: inline-block;
  min-width: 1.75em;
  padding: 0.1rem 0.4rem;
  border: 1px solid var(--app-control-line);
  border-bottom-width: 2px;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
  color: var(--app-control-text);
  font: inherit;
  font-size: 0.85em;
  line-height: 1.4;
  text-align: center;
  white-space: nowrap;
}
</style>
