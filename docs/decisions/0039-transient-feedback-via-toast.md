# ADR 0039 — Transient feedback via a floating Toast

- Status: Accepted
- Built: yes
- Date: 2026-07-13
- Model: [ui-web-styleguide](../crosscutting/ui-web-styleguide.md)

## Context

The web client's user feedback was entirely **inline** when this was decided:

- **Success confirmations** — a success message held in a component's own state and rendered next to the form, in
  the profile, member, group, terms and tenant panels. Persistent, form-adjacent, cleared on the *next* action
  rather than by a timer.
- **Undo affordances** — two hand-rolled undo bars: the gallery and lightbox delete-undo, and the buckets'
  multi-delete-undo. Each was a transient bar with an "Undo" button and a timed auto-dismiss; the
  timer/state/cleanup skeleton was duplicated between them.

The project rule is **reuse the existing UI, never invent** ([ui-web-styleguide](../crosscutting/ui-web-styleguide.md)
§ Reuse first) — the nearest built screen answers the question. A floating Toast had no built precedent here, so
it is in tension with that rule and must be a deliberate, recorded decision rather than a silent "reuse".

What makes this the case where reuse is *not* the answer: there was no single existing pattern to reuse. The two
undo bars were the same skeleton built twice, and the inline success messages a third shape. "Match your
neighbour" only works when the neighbour is right; here it would mean copying a duplication a third time.

## Decision

Adopt **one toast** as the client's single **transient-feedback channel**, and route these three kinds of
feedback through it:

- Mount **one** global toaster, reached only through a thin **`useToast`** composable that fixes the app
  conventions (severity, i18n summary, default lifetime). The channel is this decision; what draws it is the UI
  layer's ([ADR 0107](0107-the-clients-own-their-ui-components-reka-ui-underneath-primevue-out.md) decision 3, a
  toast is one of its Reka primitives), and a swap of what draws it changes no call site.
- **Success confirmations** become **auto-dismissing toasts** where the action closes or leaves the view it was taken
  in — the place the user looked is gone. Where the form stays open, its success stays **inline** beside it, where the
  user is looking; a flow's final page is a page, not a toast.
- **Undo affordances** become **undo-toasts**: a toast carrying a "Rückgängig / Undo" action, its
  timeout + pending-restore managed by a generic **`useUndo<T>`** composable (the timer/ref/auto-dismiss/
  `onBeforeUnmount` skeleton the two bars duplicated); the item-specific restore stays at each call site.
- **A failure no open form can hold inline** — an undo that could not restore, a write a closed dialog started —
  becomes an **error toast that stays until it is closed**: it is the one place the user would otherwise never hear
  of it, so it does not time out.

This **deliberately overrides "reuse UI, never invent" for transient feedback only.** The justification: there
was no one pattern to reuse — two hand-rolled undo bars duplicating a timer skeleton, plus scattered `notice`
refs — so consolidating all ephemeral feedback into one consistent, accessible channel (the toaster carries the
live region, the role and the focus semantics) beats copying either shape a third time.
Form-level *validation errors* and persistent *load-error* states stay inline — this decision covers success and
undo (ephemeral) feedback, and a failure only where no form is open to carry it, never an error state pinned to a
form.

## Consequences

- **New paradigm cost.** Confirmation moves off the form and becomes ephemeral: a "saved" / "invited"
  acknowledgement is no longer pinned beside the control that produced it. Accepted as the price of one
  consistent feedback channel. (This is the main thing given up versus the status quo.)
- **One global surface**, mounted once, reached through `useToast` and `useUndo` in the same primitive style as the
  client's async-state helpers.
- **Rejected — inline-only + `useUndo` dedup.** Keep all feedback inline, extract only `useUndo` to dedupe the
  two bars. Most reuse-faithful and lowest-risk, but leaves the scattered `notice` refs and two rendering paths;
  rejected in favour of consolidating feedback into one channel.
- **Rejected — undo-toasts only, notices stay inline.** A middle path (Toast gains a real consumer while
  form confirmations stay pinned). Rejected in favour of full consolidation for a single consistent paradigm.
