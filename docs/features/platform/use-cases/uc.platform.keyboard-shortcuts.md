# UC: See every keyboard shortcut

- **ID:** uc.platform.keyboard-shortcuts
- **Status:** planned (owner, 2026-11-09).
- **Primary actor:** A member (signed in, in the app shell).
- **Goal:** Learn which keys do what, in the app and in the place they are, without leaving the page.
- **Preconditions:** The member is on a page of the app shell (not a share page, which has no shell).
- **Postconditions (success):** An overview of the shortcuts has been shown and closed again; nothing is stored.

## Main flow

1. **Open the overview.** The member presses `?`, or chooses *Keyboard shortcuts* in the account menu. `?` is ignored
   while the focus is in a text field, where it is a character.
2. **Read it.** A dialog lists the shortcuts in groups: *Everywhere* (core's: `/` and Cmd/Ctrl+K into the search), *In
   the search* (core's), then each application's groups (Memories: *In the gallery*, *In the lightbox*). Each entry
   names its keys and what they do, in the UI's language, with the platform's modifier (⌘ on a Mac, Ctrl elsewhere).
3. **Close it.** Escape, the close button or a click beside it closes the dialog; the focus returns to where it was.

## Alternate / exception flows

- **A1 — a switched-off application:** its groups are not listed. The overview reads the same registry answer as
  everything else (`applicationsOff`).
- **E1 — a dialog is already open:** `?` does nothing; the open dialog keeps the keyboard.

## Quality notes

- **One list.** The overview is built from what core and each application register (core's own list, an
  application's through its registrar), never from a second hand-written copy inside the dialog. A shortcut a handler
  adds without registering it is the gap a review must catch.
- **No new shortcut is invented here.** Cmd/Ctrl+F stays the browser's own find-in-page (owner, 2026-11-09).

## User impact

You can press `?` anywhere in veyq to see which keys do what, instead of discovering them by chance.

## Related

- Feature: [Platform](../_index.md)
- Use-cases: [uc.platform.search](uc.platform.search.md) (its keys), [uc.memory.gallery](../../memory/use-cases/uc.memory.gallery.md)
  (the grid's and the lightbox's keys)
- Decisions: [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core lists what
  an application registers, never imports it)
