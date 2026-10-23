# ADR 0107 — The clients own their UI components; Reka UI underneath, PrimeVue out

- Status: Accepted 2026-10-22 (owner: Reka UI behind a wrapper layer of our own); built 2026-10-23 (cr-own-ui-components-on-reka): `src/web/` in the steps decision 7 names, `src/admin-web/` in one, its layer written on Reka directly with its call sites moved alongside. No PrimeVue, `@primeuix/*` or `primeicons` package is left in the repository.
- Date: 2026-10-22

## Context

`src/web/` and `src/admin-web/` are built on **PrimeVue 4** in styled mode: its components, the `@primeuix/themes`
Aura preset with our `--app-*` roles routed onto its `--p-*` variables
([ui-web-styleguide](../crosscutting/ui-web-styleguide.md) § Contrast), its `locale` fed from our catalog
(`src/app/primeVueLocale.ts`), its Toast as the one transient-feedback channel ([ADR 0039](0039-transient-feedback-via-toast.md))
and `primeicons` for every icon. Views import PrimeVue directly, and component styles, unit tests and the e2e suite
reach into its DOM (`.p-*` classes).

**PrimeVue 5 is no longer open source** (checked 2026-10-22 on npm and in the published `LICENSE.md` of
`primevue@5.0.0`). From PrimeVue 5.0.0, `@primeuix/themes` 3, `@primeuix/styled` 1 and `primeicons` 8 on, the packages
ship under the **PrimeUI License** of PrimeTek: free only under a Community License with caps (under USD 1M revenue,
fewer than 5 developers, fewer than 10 employees, under USD 3M funding), with a licence key that is renewed yearly and
whose absence may put a licence notice into the page, and with redistribution to third parties needing a separate
OEM licence. PrimeTek states that existing MIT releases stay MIT; **4.5.5 is the last MIT release** of `primevue`
(`@primeuix/themes` 2.0.3, `primeicons` 7.0.0 for the rest). Nothing published by PrimeTek says how long the 4.x
line gets fixes.

Two project rules decide what that means here. The dependency rule in [CLAUDE.md](../../CLAUDE.md) forbids keeping
a dependency whose only living successor is commercial, and makes replacing it work for now, not someday. And
*self-hostable by default* is a security guarantee: every operator who runs veyq receives our client in the image,
which is exactly the redistribution PrimeUI restricts, and a yearly key whose expiry changes what an operator's
users see is a dependency on PrimeTek inside every instance.

## Decision

1. **PrimeVue leaves both clients, and so do `@primeuix/*` and `primeicons`.** We do not move to PrimeVue 5 under any
   licence tier. Until the replacement has landed we stay on the MIT releases listed above, pinned below the next
   major so no update can cross into the PrimeUI line.
2. **Each client owns its UI components.** A layer of our own components — buttons, fields, dialogs, menus, messages,
   toasts and the rest of what the views use — is the only thing a view imports for UI; no view, composable or
   application imports a component library. The layer's API is ours, named and shaped for what veyq's screens need,
   not a copy of PrimeVue's prop surface. The rule is enforced by the package's lint (`no-restricted-imports` on the
   library outside the layer), so the next time a library has to go, the change stays inside one directory.
3. **Underneath, Reka UI where the behaviour is hard, plain HTML where it is not.** [Reka UI](https://reka-ui.com)
   (MIT, headless, the maintained successor of Radix Vue) implements the primitives whose keyboard, focus and ARIA
   behaviour is the real work: dialog, popover, menu, select, tabs, checkbox, switch, toggle group, toast, tooltip,
   date picker. A component with no such behaviour — a button, a text field, a message, a card — is our own markup.
   Reka is never re-exported: a wrapper takes the props it needs and passes them on.
4. **The look lives in our stylesheets, on our tokens.** Headless means no theme preset and no runtime-injected
   style: the components are styled by the package's own sheets on the `--app-*` roles that already carry the
   colours, and the `--p-*` routing block retires with PrimeVue. The target is the screens as they look today; a
   redesign is not part of this decision.
5. **Icons come from [Lucide](https://lucide.dev)** (`@lucide/vue`, ISC), as components rendered to inline SVG, behind
   the same layer (an icon component of ours takes our icon names), so no icon font ships.
6. **One layer per client.** Following [ADR 0065](0065-admin-ui-cli-separate-service.md), web and admin-web share
   nothing but `@veyq/crypto`, so each gets its own layer and its own copy of what both need, as the stylesheets
   already are copied (ui-web-styleguide § Source of truth). In `src/web/` the layer is core's: an application may
   import it, it imports no application ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)).
7. **The layer comes first, the library swap second.** The wrappers are introduced on PrimeVue 4.5.5 and every call
   site moves onto them while the screens stay unchanged; only then is each wrapper's inside replaced by Reka UI or
   plain markup, one component at a time, and PrimeVue is removed once nothing imports it. Each step leaves both
   clients working.

## Behaviour changed on purpose

The target was the screens as they were (decision 4); these are the only changes made deliberately, and a layer
component that cites "behaviour changed on purpose" means one of them:

- **No dialog drags.** The library's dialog was draggable by default, by mouse on its title bar; nobody had chosen it,
  and Reka has none.
- **A password field's accessible name is its label only.** The library added a hidden "Enter a password" to every
  password field's name; with its strength meter always off that text had no job.
- **A date is typed into segments**, day, month and year, each a spinbutton typed as digits or stepped by the arrow
  keys, in the order and with the separators of the language: `tt.mm.jjjj` in German, `mm/dd/yyyy` in English, on
  every date field (owner 2026-10-22; the share and drop-box expiry and the moderation deadlines showed a fixed
  `yy-mm-dd` before). Pasting a whole date as text is gone. The calendar stays: its button opens it, and so does the
  field's focus, on every date field, as the library's did (its open-on-focus was the default everywhere, not only
  on the Memory date). It steps by month; a distant year is typed into its segment. Days outside the bounds are
  disabled, and a typed one is refused: leaving the field restores the day it held. The model (`YYYY-MM-DD`) stays.
- **A breadcrumb entry with a `url` is a real link**; a plain click runs its command in place of the link's default,
  a modified click follows it.
- **The console's filtered select is a combobox** (`src/admin-web/`, the operator recipients' country): the field is
  the filter input, and a pick can be cleared.

## Consequences

- **The migration is large, and most of its cost is in what reaches into PrimeVue's DOM**, not in the imports:
  component `<style>` blocks and the shared sheets that target `.p-*` classes, tests that select by them, and the e2e
  suite. Moving those onto the layer's own classes and roles is part of the layer step, so the swap does not have to
  touch them again.
- **Accessibility is ours to keep.** PrimeVue shipped focus traps, `aria-*` wiring and its translated hidden texts;
  Reka covers the primitives in decision 3, and our own markup has to carry the rest. The translated texts move from
  `primeVueLocale.ts` into the components that render them, from the same catalog.
- **[ADR 0039](0039-transient-feedback-via-toast.md) keeps its decision** (one global toast, `useToast`, `useUndo`);
  only what renders the toast changes.
- **The CSP's stated reason for `style-src 'unsafe-inline'` goes away** ([cc.security.csp](../crosscutting/cc.security.csp.md)):
  it names PrimeVue's and `@primeuix`'s runtime `<style>` tags. Whether the remaining `:style` bindings still need
  it is a measurement after the swap, and tightening the policy is a change of its own.
- **Guards that read PrimeVue move with it**: `scripts/validate_web_contrast.py` pairs read `--p-*` variables, and
  the stylesheet-anchor guard sees every `.p-*` rule as anchored only while PrimeVue renders that class.
- **The e2e suites have not run against the layer yet.** Their selectors moved with the layer's classes and roles;
  the first run is the owner's V1-end run (web-e2e and L1), and a red there that names a `veyq-*` hook or a role goes
  back to the layer.
- **Rejected — PrimeVue 5 under the Community License.** Free for us today, but every operator receives the client,
  the caps and the yearly key bind the project to PrimeTek's terms, and the next change of those terms would find us
  as deeply coupled as now.
- **Rejected — stay on 4.5.5.** Allowed by its licence, refused by the dependency rule: a line with no stated
  maintenance whose successor is commercial; every fix it needs from then on would be ours, without the source being
  ours.
- **Rejected — a community fork of 4.5.5.** The one found (OpenVue) was known only from its own announcement on
  2026-10-22; a fork that young is a bet on its maintainers, and the coupling to PrimeVue's DOM would stay.
- **Rejected — another styled suite (Vuetify, Element Plus, Naive UI, Quasar).** MIT, but each brings its own theme
  system and DOM to override, which is the coupling that made this migration expensive, with a new owner.
- **Rejected — Nuxt UI, or shadcn-vue.** Both sit on Reka UI. Nuxt UI is styled through Tailwind and owned by one
  company, the risk this decision is about. shadcn-vue copies components into the repository — the ownership chosen
  here — but its copies are Tailwind classes, and our styles are token-based sheets checked by the contrast and
  anchor guards; copying its components would bring in a second styling system.
- **Rejected — another headless library (Ark UI, Headless UI).** Ark UI is a sound alternative with a smaller Vue
  community; Headless UI's Vue package covers too few of the primitives in decision 3. Behind our layer this choice
  stays reversible, which is the point of decision 2.
