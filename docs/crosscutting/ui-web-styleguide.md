# Web UI Styleguide

Visual + interaction rules for the **web** client, so extending the (reused) UI stays "in-style"
instead of by feel. veyq should feel like a curated private Memory archive — not a cloud drive, folder
gallery or social network.

## Source of truth

**`web/src/styles/`** — core's stylesheets (`base` tokens, `ui`, `layout`, `forms`, `markdown`, `settings`,
**`dark`**); `web/src/style.css` is the import barrel. **An application's own global classes are its own
sheet in its directory** (`web/src/features/memories/memories.css`, inbox #513 — the media lightbox too since #517;
`web/src/features/depot/depot.css`, the PDF text layer, #517), imported by the application, never by
the barrel: it loads with the application, *before* `style.css`, so a rule of it that styles an element a core class
also styles compounds with that class (`.veyq-card.memory-card`) and wins by specificity, never by source order — and
its dark twins sit at its end, not in `dark.css`. Which rules tie is measured in the built bundle, not read off the
sheets: #517's move compounded eleven lightbox rules (`.veyq-icon-button.lightbox-nav`,
`.veyq-surface.media-lightbox-details`, `.veyq-text-button.lightbox-text-button`, …) once the computed style of every
lightbox element was compared before and after. Individual edits carry a comment with their
reason where the reason isn't obvious. It read *~175 KB* until 2026-09-21, when the 67 % of it that no markup
had ever matched was deleted (#146) — the sheets had come across from the pre-E2EE source with the design
system of views this repo never had. Re-measure before quoting the size; `du -ch web/src/styles/*.css`.

**`admin-web/src/styles/`** is a five-sheet **copy** of it — everything but `layout` and `markdown`, which style a
Memory shell and rendered Markdown that surface does not have — and the rules on this page
govern it too. The copy is deliberate, not drift: [ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md)
keeps the two packages independent in everything but `@veyq/crypto`, so a redesign of one is never a regression
in the other. **The flip side is that nothing here is automatically true of both** — a token added on this side
reaches the admin surface only when somebody carries it over, and the admin package's own `style.css` holds the
shell rules web keeps in `layout.css`.

**The design system is complete — there is nothing to fetch from anywhere.** If something seems missing, that
is a **design decision to make**, not a copy job. What the code already does is the answer; this page only says
how to extend it.

## Reuse first — this is the load-bearing rule

**The existing implementation is the styleguide.** `web/` is a whole product now, so the nearest built screen
answers almost every question this page could: use an existing `.veyq-*` shared class and `--app-*` token before
writing anything local. Add a local class only when no shared class can express the behaviour without leaking
styles elsewhere. Wrap a shared primitive in a feature component rather than copying markup.

**This gets stronger as web grows, and that is the design.** A rule that enumerates values goes stale the moment
the code moves; a rule that says *"match your neighbour"* cannot. It is also the one rule here that is
demonstrably obeyed — `.veyq-dialog-action-row`: **23 uses across 18 components** in `web/`, plus 6 across 3 in
`admin-web/`, and **zero** per-component copies [M: 2026-09-20]. Uses are markup uses, not occurrences, so the
number is re-measurable rather than remembered:

```
grep -rn 'class="[^"]*veyq-dialog-action-row' web/src | wc -l   # uses
grep -rln 'class="[^"]*veyq-dialog-action-row' web/src | wc -l  # components
```

This sentence said *15* until 2026-09-20, having been written when that was true — which is the paragraph's own
argument landing on the paragraph. **The load-bearing half is "zero copies", and only that half is a rule**; the
counts are colour, and colour is what goes stale. Re-measure before quoting them, or quote neither.

## Color — semantic role tokens

Use `--app-*` role tokens before raw colors. **Both values live in `styles/base.css`**: the light one under
`:root`, the dark one under `:root.veyq-dark` (45 tokens there [M: 2026-10-08, `awk '/^:root.veyq-dark/{f=1;next}
f&&/^}/{f=0} f' web/src/styles/base.css | grep -cE '^\s*--'`]). A new one must define both.

**`styles/dark.css` is not where the values are, and this page said it was until 2026-09-21.** That sheet
defines no custom property at all — it is one `.veyq-dark { … }` of *component* overrides that read the tokens.
Checked with `grep -nE '^\s*--' web/src/styles/dark.css`, which returns nothing, and returned nothing on the
pre-#146 file too: the sentence was wrong when written, not made wrong by a deletion.

- **accent / brand** — brand marks, selected states, focus accents, navigation highlights.
- **success / positive** — submit, save, confirm, upload, consent, progress, maps, tracks, journal links.
- **warning** — group/shared/context badges, warm status; never for submit.
- **danger** — destructive actions, removal, deletion, errors only.
- **info** — informational/helper cues, non-critical metadata.
- **control / surface / text / line** — neutral controls, panels, foreground, borders.

Raw colors are allowed only for *rendered content* (lightbox overlays, media scrims, map/SVG/canvas
values), never for application UI roles. Repeated raw colors must become a role token before merge.

### Contrast — WCAG AA, in both modes, pinned by a guard

**Text reaches 4.5:1 against what it sits on; a control's boundary or fill, an icon that carries meaning and the
focus ring reach 3:1** (WCAG 1.4.3 / 1.4.11) — in light **and** dark. Exempt: disabled controls, text over media or
a gradient (rendered content), decorative dividers (`--app-line`). Three tokens carry the rule:

- **`--app-primary` / `-hover` / `-active`** — the submit/confirm green. PrimeVue's primary is routed onto them, so
  every primary button, checkbox, switch and primary-coloured link follows. Light is emerald-700/800/900 under
  white text; dark keeps Aura's emerald-400/300/200 under dark text.
- **`--app-on-fill`** — text or an icon on a *filled* role colour (primary, accent, success, danger): white in light,
  near-black in dark, because the dark twins of those fills are light. **Not `--app-on-strong`**, which stays white
  in both modes and paints white surfaces and labels over media scrims.
- **`--app-control-line`** — the boundary of an input, checkbox or switch track. `--app-line` stays the decorative
  divider and is not held to 3:1.

**PrimeVue's colours come from our tokens, not from its preset.** Aura's defaults failed AA in 36 pairs (inbox
#388: white on `#10b981` 2.54:1, warn/success/error messages 2.8–4.4:1, input borders 1.5:1). The `--p-*` block at
the end of `:root` in `styles/base.css` routes the primary, the danger and warn buttons, messages, toasts and form
boundaries onto `--app-*` roles; that block is the one place to change a PrimeVue colour — never a component's
`<style>` and never `main.ts`'s preset. `scripts/validate_web_contrast.py` (trust-chain glob) computes every key
pair from `base.css` in both modes and is red below the floor, or when a pair reads a `--p-*` variable the block no
longer sets. **A new pairing — a role's text on a fill no row names — gets a row in its `PAIRS`**; nothing else
will notice it.

**`admin-web/` follows the same rule on its own copy** (inbox #390): its `src/styles/base.css` carries the same three
tokens with both twins and the same `--p-*` block — ported, not imported (ADR 0065) — and the guard reads that
sheet against the same `PAIRS` (its `SHEETS`), so a lightened admin primary is red there too. Before the port the
copy was still on Aura's defaults: 34 pairs below AA (23 light, 11 dark), measured on a throwaway harness of the
real PrimeVue preset, admin-web's `style.css` and its `LoginView`. The guard holds each copy to the floor, not the
two to each other — they may drift apart, and both must still pass.

## Typography

**Reuse the class; it already carries the size.** If a shared class fits, you never write `font-size` at all —
that is the whole answer, and §*Reuse first* is where it lives. When you genuinely must write CSS, **match the
neighbour**: the same row, the same panel, the same screen. Settings, management and navigation screens are
**dense and scannable** — reach for the smaller end, and do not leave a component library's default button text
or a one-off `1rem` label sitting in them.

> **There is no `--app-font-size-*` token family, and this section used to mandate one.** Zero existed; none ever
> did (`grep -r 'font-size: var(--app-' web/src` → 0). That was not an oversight to correct — **it was the rule
> that was wrong**, and this is worth stating so nobody "completes" the token system later:
>
> **Tokens exist here where theming forces them, not where tidiness suggests them.** Colour is 91% tokenised,
> background 75%, shadow 58% — those are exactly the axes `dark.css` cannot work without. The axes with no
> structural need were never tokenised and nobody missed them: **spacing is 578 declarations across 115 distinct
> raw values, 0% tokenised** — 2.4× worse than type, and no rule ever asked for `--app-space-*`. Type was the
> lone outlier demanding a token layer for a non-theming axis, and 89 CRs went by without anyone feeling its
> absence. That is the evidence, not an excuse.
>
> Imposing one now would mean choosing a scale the data does not contain (the most common value per role wins
> only 19–42% of its own uses), then editing **243 sites** and shifting the product's look — with no visual
> regression test anywhere, on a surface whose e2e tests no CI run executes. The cost is real and the defect is
> invisible. If a scale is ever genuinely wanted, it is an **ADR** with that migration priced in — not a line
> here, and writing the line did not make it exist.

## Components

- **Surfaces & cards:** `.veyq-surface` for framed panels; `.veyq-card` for repeated content objects;
  `.veyq-card-interactive` only when the card is clickable. **No nested cards.** Page sections are
  normally unframed.
- **Rows vs cards:** rows (`.veyq-list`, `.veyq-list-row`, `.veyq-row-main/-title/-subtitle/-meta`) for
  management/dense data; cards for repeated content objects (Memories, media, tracks). Rows collapse to
  one column on mobile — don't hide essential content in trailing action columns.
- **Node cards (core):** `.veyq-card-grid` is the one track rule for a grid of node cards; the folder level view
  (`core/node/LevelView.vue`) adds `.veyq-level-grid` (list variant, narrow column), `.veyq-folder-card`,
  `.veyq-create-card` (Memories' chapter card wears it too) and `.veyq-danger-action` (`styles/ui.css`, end of sheet; dark
  twins in `dark.css`); `.memory-card-grid` is Memories' twin of the grid (in Memories' own `memories.css`). Creation lives in the level toolbar's "New" menu
  (`.veyq-new-menu-button`), not in tiles. **Node header:** every node page and level opens with `.veyq-node-header`
  (core's `NodeHeader`: leading visual, title, subtitle, actions on the right; tokens only, the icon tile has its dark twin).
- **Badges:** `.veyq-badge.success|.warning|.muted` for status; no one-off pills.
- **Forms:** `.veyq-form`, `.veyq-form-grid`, `.veyq-form-copy`; full-width inputs; concise labels;
  muted helper text that explains *consequences*, not the label.
- **Empty/loading:** `.veyq-empty`, `.veyq-loading` — concise and action-oriented, not marketing copy.
- **Where a component lives:** one used by two features, or by a feature and the app shell, lives in
  `shared/ui/`; `components/app/` is the shell alone. `shared/` imports no feature. The one known inversion,
  `SecretKeyField` importing the Emergency Kit's read half out of `features/account/`, is left because undoing it
  moves the kit import and its jsQR dependency, not a file — it is not a precedent. **Core and application:**
  what is platform lives outside `features/memories/` — in `core/` (`core/node/` the node tree, the folder level view
  `LevelView.vue` and its delete/trash dialogs, `core/applications/` the registry, `core/sharing/`), `shared/`,
  `components/app/` or `views/`; what is Memories' (media, gallery, covers, its level rows, its dialogs and share
  body) lives under `features/memories/`. Core renders an application's component only through the registry (a kind's
  detail or level section, a slot) and never imports it
  ([web/CLAUDE.md](../../src/web/CLAUDE.md) says what an application registers; the guard enforces the direction).

## Buttons & dialog actions

- Submit/save/confirm = positive (green); cancel/neutral = secondary; destructive = danger with an
  explicit label. Icon-only actions use `.veyq-icon-button` + an `aria-label`/tooltip. Two icon-only actions in one bar
  (a `NodeHeader`'s, a row's) never share an icon: the icon is the only thing a sighted user tells them apart by, so a new
  action's icon is checked against the list it joins (the Memory header carries `pi-user-plus` twice until inbox #425 is decided).
- **Dialog footers use the one shared `.veyq-dialog-action-row`** (a `space-between` row; never a
  per-component `.dialog-actions` copy): **cancel/secondary anchored left, submit/confirm anchored right**;
  source order matches visual order. A **destructive tertiary** action that coexists with the primary confirm
  (e.g. *Cover entfernen* / *Ort entfernen*) sits in a trailing `.veyq-action-group` **immediately left of the
  confirm**, styled **`severity="danger" outlined`** (danger colour, but lighter than the filled primary so it
  doesn't compete). When the destructive action **is** the dialog's primary confirm (a dedicated delete/confirm
  dialog), it is a **filled `severity="danger"`** button on the right — the dialog's one primary. Confirm/
  destructive actions show an in-progress state and **disable confirm/cancel/close + inputs while the mutation
  runs** (no double-submit).

### Confirming a destructive action — three mechanisms, and the one that is retired

- **The app's own dialog** — `shared/ui/ConfirmDialog.vue`, asked for through `shared/state/useConfirm`'s
  `ask()`, which resolves `true`/`false` so the call site keeps its guard clause
  (`if (!(await ask({ … }))) return`). It is mounted **once** in `App.vue` beside the toasts and follows the
  footer rules above. This is the default for a destructive action that needs a sentence of consequence.
  A request may carry **one opt-in** through `askWithOption()` — a checkbox under the consequence, unchecked each
  time the dialog opens, its state answered only on accept (`null` on decline). It is the dialog form of the
  opt-in the inline confirm carries in the group panel's removal ("also turn off every link"); the two are one
  question in two mechanisms, not a copy to merge.
  **`admin-web` has its own copy of both files**, not an import — [ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md),
  the same relationship `shared/state/useToast.ts` already has with web's. There is no shared UI package and
  there must not be one.
- **The inline two-step confirm** — a confirm label revealed in the row itself (`groups.deleteConfirm`,
  `kit.backupDownloadConfirm`, …). Deliberate and unchanged: the low-ceremony, in-row form.
- **A dedicated dialog** where the dialog does more than ask — it owns the mutation, its busy state and/or its
  inline error. The shared dialog resolves and closes; it never runs the action, so anything that needs a
  `:loading` confirm button stays its own component. There are **five** in `web/` [M: 2026-09-20]:

  | Dialog | What it owns beyond asking |
  |---|---|
  | `core/node/components/DeleteNodeDialog.vue` | busy (gates `closable`/`close-on-escape`) + inline error; folder-vs-Memory header variant |
  | `account/trustDevice/DeviceTrustControl.vue` | busy + inline error |
  | `memory/components/MemoryDetail.vue` (trash purge) | a data payload (the ids) + busy + pluralised body; **no** inline error |
  | `account/rotation/KeyRotationPanel.vue` | busy + inline error, both shared with the form it gates |
  | `memory/components/MemoryBuckets.vue` (delete bucket) | a **form** — type-the-name — + saving state + an inline error field |

  **They are deliberately not merged into a mutation-owning `ConfirmDialog` variant**, and the table is the
  argument: they share no contract. One has no inline error at all, one carries a payload and pluralised copy,
  one closes only after a state re-read, one is a form built from a different button family. What is actually
  duplicated is ~12 lines of dialog *chrome* plus a `.dialog-copy` rule; what differs is precisely the mutation
  ownership the shared dialog exists to refuse. A shared component whose five callers agree on nothing is five
  props and five branches.

#### Header voice follows the mechanism

Two title voices coexist, and that is the rule rather than an inconsistency to flatten [M: 2026-09-20]:

- **Imperative header on the shared `ask()`** — "Sign this device out", "Remove member", "Cancel invite",
  "Take this tenant offline". **10 of 10** `ask()` call sites. Their `message` is the pre-existing `*Confirm`
  sentence, which already *asks*, so a second question in the header would ask twice.
- **Question header on a mutation-owning dedicated dialog** — "Forget this device?", "Delete forever?",
  "Replace your keys?", "Delete bucket?". **4 of 4** question headers sit there, on a consequence-only body.

So: match the voice to the mechanism you picked, and do not "fix" one to look like the other. The single
counterexample is `DeleteNodeDialog` ("Delete folder", or the kind's own title such as "Delete Memory") — a dedicated dialog wearing the
imperative voice. It is the mechanism axis it crosses, not the voice one: both its headers are imperative.

**`window.confirm` is retired, and `no-alert` is what keeps it retired** — in `web/eslint.config.js` and in
`admin-web/eslint.config.js`, including in test files, which are deliberately not exempted.
**Know exactly how far that reaches** [M: 2026-09-20, bite-tested]: `no-alert` flags a native *call*, in `src`
and in tests alike. It does **not** flag the `window.confirm = () => …` *stub assignment* — which is the shape
a test fixture actually uses, and the shape seven lines of `TenantDetailPanel.test.ts` used. Those were found
by `grep`, not by the linter. So the rule stops a native dialog being *used*; only a grep stops one being
*faked*, and a faked one is worse — it passes a confirm-gating test by confirming nothing.

**An action's name has to be visible, not only accessible.** An icon-only destructive action names itself in
`aria-label`/`title` alone, so prose that quotes its label quotes something no reader can see — and a label
too long for its row is what tempts the icon-only shape in the first place. Where both are true, give the
button a short visible label and keep the full sentence as its accessible name and the dialog's header
subject, then quote the *visible* one in the surrounding copy.

## Product language

**Memory / Chapter** (DE: *Kapitel*) for content; folders are organizational and visually **secondary**
to Memories. Never reintroduce *Album*/*Explorer*. Public/sharing UI must not imply public profiles,
cross-Memory person discovery, feeds, reactions or a social graph.

**Deleting an i18n key needs a grep — nothing else catches it.** A missing `vue-i18n` key resolves at *runtime*
(it renders as the key path), so neither typecheck nor the component tests see it — those supply their own
message objects. Removing the last *member*-surface user of `members.copyLink*` would have left the ServerOwner
bootstrap dialogs rendering nothing; the sweep that caught it was a grep, not a build. So before deleting a key,
list every key a template actually reads and check it against **both** locale blocks:

```bash
grep -rho "t('members\.\w*'" web/src --include="*.vue" --include="*.ts" | sort -u
```

**PrimeVue's own texts come from the catalog too, set in one place.** A PrimeVue component reads some strings from
its config's `locale` - a Dialog's close button, every `<Password>`'s hidden "Enter a password", the DatePicker's
month names - and `src/app/primeVueLocale.ts` (one per package, `web/` and `admin-web/`) writes them from the
catalog's `primevue` block at start and on every language change; no component sets PrimeVue's locale itself, and
a newly used PrimeVue component or prop brings the keys it reads into that file and both locale blocks (inbox #434).

## Accessibility & responsiveness

Visible focus states, accessible names, predictable keyboard behaviour. No hover-only affordances for
essential actions. Text must not overflow on mobile — prefer existing responsive `.veyq-*` behaviour
before component-specific media queries.

## Checklist before finishing web UI work

- Reused a `.veyq-*` class and `--app-*` token before adding local styles / raw colors.
- Defined light **and** dark for any new semantic color — both in `styles/base.css` (`:root` and `:root.veyq-dark`).
- Text 4.5:1, controls/icons/focus 3:1, both modes; text on a filled role colour uses `--app-on-fill`; a new
  colour pairing has a row in `scripts/validate_web_contrast.py` (§ *Contrast*).
- **Every rule is anchored on a class the markup applies** — enforced, not advice: `scripts/validate_stylesheet_anchors.py` fails the build on a rule whose *first* compound names only classes nothing renders — in every `.css` under the package's `src/`, an application's own sheet included. Landing a stylesheet ahead of its markup turns CI red, and that is the point (inbox #140: a shipped feature's only signal of missing its surface was CSS nobody had matched). Overriding a PrimeVue class is unaffected as long as it stays scoped by one of ours (`.memory-card .p-card-body`, `.veyq-danger-action.p-button`) — which is the house style anyway, and is what lets that guard need no list of vendor class names.
- Memory/Chapter language; folders secondary; no Album/Explorer.
- Rows for management data, cards for content; no nested cards.
- Dialog actions ordered + in-progress-safe; icon-only actions labelled.
- Confirmed a destructive action through `useConfirm` (or an existing inline/dedicated form) — never
  `window.confirm`, and never a test that *stubs* one; gave its header the voice its mechanism takes; and any
  label your copy quotes is one the reader can actually see.
- Matched the surrounding density rather than introducing a size of your own.
- Grepped for remaining readers before deleting any i18n key, in both locale blocks.
