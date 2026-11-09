// The keyboard shortcuts the overview lists (uc.platform.keyboard-shortcuts): core's own list here, an application's
// through its registrar (`ApplicationRegistrar.shortcuts`), so the overview (`ShortcutsDialog.vue`) holds no copy of a
// shortcut an application owns. A handler that spells a listed key points at its entry here (or at its application's
// list) in a one-line comment: whoever changes the key finds the overview.
//
// **What is listed.** The shortcuts a member could not guess: the shell's, the search's and an application's own. A
// control's own keyboard - Enter and Escape on the in-place rename, Escape on a menu, a popover or the file viewer's full
// screen, the arrows on slide-to-confirm - is what every such control does, and is not listed.
import { ref } from 'vue'

/**
 * A key as the overview names it: `mod` - the platform's command modifier, ⌘ on macOS and iOS, Ctrl elsewhere
 * (`modifier.ts`) -, one of {@link NAMED_KEYS}, an arrow (`ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`), or the
 * character the key types (`/`, `?`, `K`, `+`, `-`, `0`), shown as it is.
 */
export type ShortcutKey = string
/** Keys pressed together (`['mod', 'K']`). */
export type ShortcutChord = readonly ShortcutKey[]

/** One line of the overview: what its keys do. */
export interface ShortcutEntry {
  /** The alternatives, each a chord: `[['/'], ['mod', 'K']]` is "/ or ⌘+K". */
  keys: readonly ShortcutChord[]
  /** The i18n key of what the keys do - core's, or the registering application's own namespace. */
  label: string
}

/** A group of the overview, under its heading ("Everywhere", "In the gallery"). */
export interface ShortcutGroup {
  /** The i18n key of the heading. */
  title: string
  entries: readonly ShortcutEntry[]
}

/** The keys named by a word, their i18n key under `shortcuts.key` (a German keyboard prints "Entf", not "Delete"). */
export const NAMED_KEYS: Readonly<Record<string, string>> = {
  Enter: 'enter', Escape: 'escape', Backspace: 'backspace', Delete: 'delete',
  Home: 'home', End: 'end', PageUp: 'pageUp', PageDown: 'pageDown',
}
/** The arrows, drawn as their glyph in every language. */
export const ARROW_KEYS: Readonly<Record<string, string>> = { ArrowUp: '↑', ArrowDown: '↓', ArrowLeft: '←', ArrowRight: '→' }

/** Core's shortcuts: the shell's and the search's. */
export const CORE_SHORTCUTS: readonly ShortcutGroup[] = [
  {
    title: 'shortcuts.everywhere',
    entries: [
      // `components/app/AppTopbar.vue` (`onShortcut`)
      { keys: [['/'], ['mod', 'K']], label: 'shortcuts.toSearch' },
      // `ShortcutsDialog.vue` (`onKeydown`)
      { keys: [['?']], label: 'shortcuts.showThese' },
    ],
  },
  {
    // `core/search/SearchField.vue` over `shared/ui/VeyqTagsInput.vue`
    title: 'shortcuts.inSearch',
    entries: [
      { keys: [['ArrowUp'], ['ArrowDown']], label: 'shortcuts.searchMove' },
      { keys: [['Enter']], label: 'shortcuts.searchChoose' },
      { keys: [['Backspace']], label: 'shortcuts.searchRemoveFilter' },
      { keys: [['Escape']], label: 'shortcuts.searchClose' },
    ],
  },
]

/** Whether the overview is open: the `?` key and the account menu's entry open it, the dialog closes it. */
export const shortcutsOpen = ref(false)

/** Open the overview (the account menu's *Keyboard shortcuts*). */
export function openShortcuts(): void {
  shortcutsOpen.value = true
}

/** Whether a key pressed at `target` is typed into something - a field, a text area, a select or an editable region -
 *  where a printable shortcut key (`/`, `?`) is a character, never a shortcut. */
export function typingInto(target: EventTarget | null): boolean {
  const el = target instanceof Element ? target : null
  return Boolean(el && (el.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]') || (el as HTMLElement).isContentEditable))
}
