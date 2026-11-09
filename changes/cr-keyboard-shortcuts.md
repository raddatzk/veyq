---
id: cr-keyboard-shortcuts
title: "? opens an overview of every keyboard shortcut, built from what core and each application register"
status: planned
satisfies: [uc.platform.keyboard-shortcuts]
adrs: ["0079"]
invariants:
  - core imports no application; an application's shortcuts reach the overview through its registrar (`validate_web_core_imports_no_applications.py`)
  - one list - the overview never holds a hand-written copy of a shortcut an application owns
  - no new behaviour for any key but ? (Cmd/Ctrl+F stays the browser's, owner 2026-11-09)
  - no backend and no contract change
---

## Goal

Build [uc.platform.keyboard-shortcuts](../docs/features/platform/use-cases/uc.platform.keyboard-shortcuts.md) (owner,
2026-11-09: "Übersicht mit ? bauen, Cmd+F weglassen").

The shortcuts today, measured at refine with a grep over the web client's `keydown` handlers:

| Group | Shortcuts | Where they live |
|---|---|---|
| Everywhere | `/` and Cmd/Ctrl+K (the search) | `components/app/AppTopbar.vue` |
| In the search | ↑↓, Enter, Backspace in an empty field (last chip), Escape | `core/search/SearchField.vue`, `shared/ui/VeyqTagsInput.vue` |
| In the gallery (Memories) | arrows, Home/End, PageUp/PageDown | `features/memories/components/MediaGrid.vue` |
| In the lightbox (Memories) | ←→, + / - / 0 (zoom), arrows while zoomed (pan), Delete/Backspace (delete), Escape | `MediaLightbox.vue`, `shared/ui/usePanZoom.ts` |
| Small ones | Enter/Escape on the in-place rename; Escape on menus and popovers and on the file viewer's full screen; arrows on slide-to-confirm | `NodeHeader.vue`, `UploadStatusMenu.vue`, `PersonGroup.vue`, `FileViewer.vue`, `SlideToConfirm.vue` |

The overview lists the first four groups. The small ones are what a control's own keyboard does (Escape closes,
Enter confirms) and are not listed; say so in the overview's code comment.

## Design

- **The list:** core's own list in `src/web/src/core/shortcuts/` (or beside the shell, the implementer's call): groups
  of entries `{ keys, label }`.
  - **`keys`** are key names rendered by one component, `<kbd>` per key, with the modifier resolved per platform (⌘ on
    macOS and iOS, Ctrl elsewhere; read from `navigator.platform`/`userAgentData` once, in one helper).
  - **`label`** is an i18n key.
- **The registry door:** `ApplicationRegistrar.shortcuts(groups)`, once per application, in the shape of
  `personSection`/`lifecycle`.
  - Memories registers *In the gallery* and *In the lightbox*.
  - The overview lists only switched-on applications (`registry.applications()` minus `access.applicationsOff`, as
    the rest of the shell reads it).
- **The dialog:** `ShortcutsDialog.vue`, the layer's `VeyqDialog`, mounted once by the app shell.
  - **Opened by** `?` from a key handler at the shell, as `AppTopbar`'s `/` handler is. It ignores the key when the
    focus is in an input, textarea or contenteditable, or while another dialog is open. **Opened also by** a
    *Keyboard shortcuts* entry in the account menu (bottom-left).
  - **Closed by** Escape, the close button or a click beside it; the focus returns.
  - **Text:** EN+DE; Memories' labels in its own catalog.
- **Keep the sources honest:** where a handler's keys are spelled (`AppTopbar`'s `/` and K, the lightbox's keys), a
  one-line comment points at the list entry that documents it. That is cheap, and the next person changing a key
  finds the overview.

## Phases

Single phase (web, site, docs).

- **Build:** the design above.
- **Tests:**
  - `?` opens the dialog; in a text field it does not; with a dialog open it does not.
  - The account-menu entry opens it, and Escape closes it.
  - Core's groups and Memories' groups are listed.
  - With Memories switched off, its groups are absent, with a control that they are present when on.
  - The modifier renders ⌘ on a Mac user agent and Ctrl otherwise.
- **Bites** (on copies, never on a file the owner's Vite serves):
  - Drop the text-field check, and the "in a text field" test goes red.
  - Drop the switched-off filter, and that test goes red.
- **Browser pass** on the owner's running instance at `http://veyq.localhost:8189/?devlogin` (do not restart it):
  `?` opens the overview; the account-menu entry does too. Desktop, light and dark, final screenshots only.
- **Docs:**
  - Coverage `uc.platform.keyboard-shortcuts.yaml`: refs, and drop `status: planned`.
  - The use case's Status line, and `_index.md`.
  - A short Use page section (EN+DE) where the site lists app-wide things. Find a fitting page with
    `git grep -ln "uc.platform" -- site/src/content/docs`; if none fits, add `site/src/content/docs/getting-started/keyboard-shortcuts.mdx` and its `de/` twin, naming the use case in `sources:`.
- **Done when:** `pnpm run check` is green in `src/web/`, the core-import guard is green, and the trust-chain guards are
  green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

(filled by the implementer)
