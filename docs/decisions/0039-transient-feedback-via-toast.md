# ADR 0039 — Transient feedback via a floating Toast

- Status: Accepted
- Built: yes
- Date: 2026-07-13
- Model: [ui-web-styleguide](../crosscutting/ui-web-styleguide.md)

## Context

The web client's user feedback is entirely **inline** today:

- **Success confirmations** — a `const notice = ref('')` rendered as `<Message severity="success" :closable="false">`
  next to the form (ProfilePanel, TenantMembersPanel, GroupMembersPanel, TenantTermsPanel, TenantDetailPanel).
  Persistent, form-adjacent, cleared on the *next* action rather than by a timer.
- **Undo affordances** — two hand-rolled undo bars: the gallery/lightbox delete-undo (cr-0110, in
  `useMediaGalleryLive`) and the buckets multi-delete-undo (cr-0122, in `MemoryBuckets.vue`). Each is a
  transient bar with an "Undo" button and a `setTimeout` auto-dismiss; the timer/ref/cleanup skeleton is
  duplicated between them.

The project rule is **reuse the existing UI, never invent** ([ui-web-styleguide](../crosscutting/ui-web-styleguide.md)
§ Reuse first) — the nearest built screen answers the question. A floating Toast has no built precedent here, so
it is in tension with that rule and must be a deliberate, recorded decision rather than a silent "reuse".

What makes this the case where reuse is *not* the answer: there is no single existing pattern to reuse. The two
undo bars are the same skeleton built twice, and the success `notice` refs are a third shape. "Match your
neighbour" only works when the neighbour is right; here it would mean copying a duplication a third time.
Audit finding #74 (P3d) flagged exactly this ("add `useToast`/`useUndo` — no toast infra today").

## Decision

Adopt **one toast** as the client's single **transient-feedback channel**, and route *both* kinds of
transient feedback through it:

- Mount **one** global toaster (`App.vue`), reached only through a thin **`useToast`** composable that fixes the app
  conventions (severity, i18n summary/detail, default `life`). The channel is this decision; what draws it is the UI
  layer's ([ADR 0107](0107-the-clients-own-their-ui-components-reka-ui-underneath-primevue-out.md)): the toaster is
  `shared/ui/VeyqToaster.vue` on Reka's Toast, fed by `shared/ui/toast.ts`, and a swap of what draws it changes no
  call site.
- **Success confirmations** (the `notice` refs) become **auto-dismissing toasts** — the inline
  `<Message severity="success">` + `notice` ref pattern is retired at those sites.
- **Undo affordances** become **undo-toasts**: a toast carrying a "Rückgängig / Undo" action, its
  timeout + pending-restore managed by a generic **`useUndo<T>`** composable (the timer/ref/auto-dismiss/
  `onBeforeUnmount` skeleton the two bars duplicated); the item-specific restore stays at each call site.

This **deliberately overrides "reuse UI, never invent" for transient feedback only.** The justification: there
was no one pattern to reuse — two hand-rolled undo bars duplicating a timer skeleton, plus scattered `notice`
refs — so consolidating all ephemeral feedback into one consistent, accessible channel (the toaster carries the
live region, the role and the focus semantics) beats copying either shape a third time.
Form-level *validation errors* and persistent *load-error* states stay inline — this decision covers success +
undo (ephemeral) feedback, not error states pinned to a form.

## Consequences

- **New paradigm cost.** Confirmation moves off the form and becomes ephemeral: a "saved" / "invited"
  acknowledgement is no longer pinned beside the control that produced it. Accepted as the price of one
  consistent feedback channel. (This is the main thing given up versus the status quo.)
- **One global surface.** A single `<VeyqToaster />` in `App.vue`; `useToast` + `useUndo` live in `shared/state/`,
  matching the `useAsyncData`/`useAsyncAction` primitive style.
- **Rejected — inline-only + `useUndo` dedup.** Keep all feedback inline, extract only `useUndo` to dedupe the
  two bars. Most reuse-faithful and lowest-risk, but leaves the scattered `notice` refs and two rendering paths;
  rejected in favour of consolidating feedback into one channel.
- **Rejected — undo-toasts only, notices stay inline.** A middle path (Toast gains a real consumer while
  form confirmations stay pinned). Rejected in favour of full consolidation for a single consistent paradigm.
