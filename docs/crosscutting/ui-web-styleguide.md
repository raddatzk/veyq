# Web UI Styleguide

Visual + interaction rules for the **web** client, so extending the (reused) UI stays "in-style"
instead of by feel. veyq should feel like a curated private Memory archive — not a cloud drive, folder
gallery or social network.

## Source of truth

**`src/web/src/styles/`** — core's stylesheets (`base` tokens, `ui`, `layout`, `forms`, `markdown`, `settings`,
**`dark`**); `src/web/src/style.css` is the import barrel. **An application's own global classes are its own
sheet in its directory** (`src/web/src/features/memories/memories.css`, inbox #513 — the media lightbox too since #517),
imported by the application, never by the barrel (a core component's global rules are its own sheet beside it, imported by
it: the PDF text layer's `src/web/src/shared/pdf/pdf.css`, #517, with `PdfPages.vue`): it loads with the application, *before* `style.css`, so a rule of it that styles an element a core class
also styles compounds with that class (`.veyq-card.memory-card`) and wins by specificity, never by source order — and
its dark twins sit at its end, not in `dark.css`. Which rules tie is measured in the built bundle, not read off the
sheets: #517's move compounded eleven lightbox rules (`.veyq-icon-button.lightbox-nav`,
`.veyq-surface.media-lightbox-details`, `.veyq-text-button.lightbox-text-button`, …) once the computed style of every
lightbox element was compared before and after. Individual edits carry a comment with their
reason where the reason isn't obvious. It read *~175 KB* until 2026-09-21, when the 67 % of it that no markup
had ever matched was deleted (#146) — the sheets had come across from the pre-E2EE source with the design
system of views this repo never had. Re-measure before quoting the size; `du -ch src/web/src/styles/*.css`.

**`src/admin-web/src/styles/`** is a five-sheet **copy** of it — everything but `layout` and `markdown`, which style a
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

**The existing implementation is the styleguide.** `src/web/` is a whole product now, so the nearest built screen
answers almost every question this page could: use an existing `.veyq-*` shared class and `--app-*` token before
writing anything local. Add a local class only when no shared class can express the behaviour without leaking
styles elsewhere. Wrap a shared primitive in a feature component rather than copying markup.

**This gets stronger as web grows, and that is the design.** A rule that enumerates values goes stale the moment
the code moves; a rule that says *"match your neighbour"* cannot. It is also the one rule here that is
demonstrably obeyed — `.veyq-dialog-action-row`: **23 uses across 18 components** in `src/web/`, plus 6 across 3 in
`src/admin-web/`, and **zero** per-component copies [M: 2026-09-20]. Uses are markup uses, not occurrences, so the
number is re-measurable rather than remembered:

```
grep -rn 'class="[^"]*veyq-dialog-action-row' src/web/src | wc -l   # uses
grep -rln 'class="[^"]*veyq-dialog-action-row' src/web/src | wc -l  # components
```

This sentence said *15* until 2026-09-20, having been written when that was true — which is the paragraph's own
argument landing on the paragraph. **The load-bearing half is "zero copies", and only that half is a rule**; the
counts are colour, and colour is what goes stale. Re-measure before quoting them, or quote neither.

## Color — semantic role tokens

Use `--app-*` role tokens before raw colors. **Both values live in `styles/base.css`**: the light one under
`:root`, the dark one under `:root.veyq-dark` (45 tokens there [M: 2026-10-08, `awk '/^:root.veyq-dark/{f=1;next}
f&&/^}/{f=0} f' src/web/src/styles/base.css | grep -cE '^\s*--'`]). A new one must define both.

**`styles/dark.css` is not where the values are, and this page said it was until 2026-09-21.** That sheet
defines no custom property at all — it is one `.veyq-dark { … }` of *component* overrides that read the tokens.
Checked with `grep -nE '^\s*--' src/web/src/styles/dark.css`, which returns nothing, and returned nothing on the
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

- **`--app-primary` / `-hover` / `-active`** — the submit/confirm green. The layer's primary button, checkbox, switch
  and primary-coloured link paint from them. Light is emerald-700/800/900 under white text; dark is emerald-400/300/200
  under dark text.
- **`--app-on-fill`** — text or an icon on a *filled* role colour (primary, accent, success, danger): white in light,
  near-black in dark, because the dark twins of those fills are light. **Not `--app-on-strong`**, which stays white
  in both modes and paints white surfaces and labels over media scrims.
- **`--app-control-line`** — the boundary of an input, checkbox or switch track. `--app-line` stays the decorative
  divider and is not held to 3:1.

**Every colour the UI layer paints is an `--app-*` role; there is no preset.** The former component library's preset
(Aura) failed AA in 36 pairs (inbox #388: white on `#10b981` 2.54:1, warn/success/error messages 2.8–4.4:1, input
borders 1.5:1); since ADR 0107 the layer's components (`shared/ui/layer.css`) paint from the roles directly, so a
colour changes in `styles/base.css`, never in a component's `<style>`. `scripts/validate_web_contrast.py` (trust-chain
glob) computes every key pair from `base.css` in both modes and is red below the floor, or when a pair reads a role
`base.css` does not set. **A new pairing — a role's text on a fill no row names, a new layer component's own — gets a
row in its `PAIRS`**; nothing else will notice it.

**`src/admin-web/` follows the same rule on its own copy** (inbox #390): its `src/styles/base.css` carries the same
tokens with both twins — ported, not imported (ADR 0065) — and the guard reads that sheet against the same `PAIRS`
(its `SHEETS`), so a lightened admin primary is red there too. Before the port the copy was still on the preset's
defaults: 34 pairs below AA (23 light, 11 dark), measured on a throwaway harness of the preset, admin-web's
`style.css` and its `LoginView`. The guard holds each copy to the floor, not the
two to each other — they may drift apart, and both must still pass.

## Typography

**Reuse the class; it already carries the size.** If a shared class fits, you never write `font-size` at all —
that is the whole answer, and §*Reuse first* is where it lives. When you genuinely must write CSS, **match the
neighbour**: the same row, the same panel, the same screen. Settings, management and navigation screens are
**dense and scannable** — reach for the smaller end, and do not leave a component library's default button text
or a one-off `1rem` label sitting in them.

> **There is no `--app-font-size-*` token family, and this section used to mandate one.** Zero existed; none ever
> did (`grep -r 'font-size: var(--app-' src/web/src` → 0). That was not an oversight to correct — **it was the rule
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
  (core's `NodeHeader`, [ADR 0110](../decisions/0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md), drawn
  by core for every kind — an application contributes data, never markup: the
  leading visual beside the name, then subtitle and facts, in one of two layouts the header picks by its own width — wide: the
  full leading visual, the name in one line and the actions in its row, the name first down to the kind's first two actions,
  then shortened with an ellipsis; compact (`.is-compact`): a small leading visual, the name in two lines at most, the actions
  in a row of their own; as many actions as fit are buttons in the kind's priority and the rest one ⋯ menu, delete and report
  always in it — no action is hidden by a width rule, and no viewport query places the header; tokens only, the icon tile has
  its dark twin).
- **Badges:** `.veyq-badge.success|.warning|.muted` for status; no one-off pills.
- **Forms:** `.veyq-form`, `.veyq-form-grid`, `.veyq-form-copy`; full-width inputs; concise labels;
  muted helper text that explains *consequences*, not the label.
- **One field box:** every one-line field — text, password, select, date field, the console's combobox — is one box of
  `--veyq-field-height` (`shared/ui/layer.css`), weight 400 and one radius (6px, `--app-radius-sm` inside a
  `.veyq-form`), so fields side by side line up, their labels with them, and an empty select keeps its height. A new
  field kind joins that rule; it never brings a height of its own.
- **An icon button inside a field** is `.veyq-field-action` (the password's eye, the date field's calendar, the Secret
  Key's upload/scan/eye): its look — size, colour, hover, focus — is that class; the field's own rule only places it.
- **A raw `<button>` sets its `font-size`.** A button does not inherit the font, and `.veyq-icon` is sized in `em`, so a
  button whose rule sets none draws its icon at the browser's 13.33px beside a 16px one (the Secret Key's icons did).
  A layer class (`.veyq-field-action`, `.veyq-icon-button`, `VeyqButton`) already carries it — reuse one.
- **A shared container styles its parts by class, never by element.** `.veyq-section-header span` greyed every span in a
  header, a primary button's label and icon included; a header's quiet fact (a count) is `.veyq-section-meta`.
- **A card step that is no form** — a result, a note and the button that goes on — is `.veyq-stack`: one gap between
  them, as a form's fields have; a card's content has no layout of its own, so without it they touch. A button in it
  keeps its own width.
- **A row that leads with a picture or icon** (a person's avatar) is `.veyq-list-row.has-leading`: the visual, the name
  beside it, then the actions. Never the visual in the row's wide first column — that pushed the name to the far end.
- **Every cover in a node header is 244 px wide, 16:9; the icon tile a 4rem square** — one size for every kind, as tall
  as a Memory's four rows beside it (64 px and 42 px compact). Where the cover may be set it is the button that opens
  core's cover dialog, its hint (`.veyq-node-header-cover-hint`) shown on hover and focus. **A fact under the name** is
  core's look too: a date or place a borderless chip (`.veyq-node-header-chip`), people core's `PersonGroup`.
- **Empty/loading:** `.veyq-empty`, `.veyq-loading` — concise and action-oriented, not marketing copy.
- **The UI layer** ([ADR 0107](../decisions/0107-the-clients-own-their-ui-components-reka-ui-underneath-primevue-out.md)):
  a button, field, dialog, menu, select, tabs, toast, tooltip, date field or icon is a `Veyq*` component of
  `shared/ui/` (its header comment is its API, its look `shared/ui/layer.css`), on our markup or on Reka UI underneath.
  **Views, composables, applications and tests import the layer, never a component library** (`reka-ui`,
  `@lucide/vue`, or any other): lint holds it (`veyq/ui-through-the-layer` in `src/web/`, `veyq-admin/ui-through-the-layer`
  in `src/admin-web/`), so the next library swap stays in one directory. An icon is a `VeyqIcon` name
  (`shared/ui/icons.ts`), never a library's class or component. A sheet reaches a layer part by its `veyq-*` class.
  `src/admin-web/` has its own copy of the layer, cut to what it renders (ADR 0065) — never an import of web's.
- **Where a component lives:** one used by two features, or by a feature and the app shell, lives in
  `shared/ui/`; `components/app/` is the shell alone. `shared/` imports no feature. The one known inversion,
  `SecretKeyField` importing the Emergency Kit's read half out of `features/account/`, is left because undoing it
  moves the kit import and its jsQR dependency, not a file — it is not a precedent. **Core and application:**
  what is platform lives outside `features/memories/` — in `core/` (`core/node/` the node tree, the folder level view
  `LevelView.vue` and its delete/trash dialogs, `core/applications/` the registry, `core/sharing/`), `shared/`,
  `components/app/` or `views/`; what is Memories' (media, gallery, covers, its level rows, its dialogs and share
  body) lives under `features/memories/`. Core renders an application's component only through the registry (a kind's
  detail or level section, a slot) and never imports it
  ([src/web/CLAUDE.md](../../src/web/CLAUDE.md) says what an application registers; the guard enforces the direction).

## Buttons & dialog actions

- Submit/save/confirm = positive (green); cancel/neutral = secondary; destructive = danger with an
  explicit label. Icon-only actions use `.veyq-icon-button` + an `aria-label`/tooltip. Two icon-only actions in one bar
  (a `NodeHeader`'s, a row's) never share an icon: the icon is the only thing a sighted user tells them apart by, so a new
  action's icon is checked against the list it joins.
- **Dialog footers use the one shared `.veyq-dialog-action-row`** (a `space-between` row; never a
  per-component `.dialog-actions` copy): **cancel/secondary anchored left, submit/confirm anchored right**;
  source order matches visual order. A **destructive tertiary** action that coexists with the primary confirm
  (e.g. *Cover entfernen* / *Ort entfernen*) sits in a trailing `.veyq-action-group` **immediately left of the
  confirm**, styled **`variant="danger" appearance="outlined"`** (danger colour, but lighter than the filled primary so it
  doesn't compete). When the destructive action **is** the dialog's primary confirm (a dedicated delete/confirm
  dialog), it is a **filled `variant="danger"`** button on the right — the dialog's one primary. Confirm/
  destructive actions show an in-progress state and **disable confirm/cancel/close + inputs while the mutation
  runs** (no double-submit).

### Confirming a destructive action — three mechanisms, and the one that is retired

- **The app's own dialog** — `shared/ui/ConfirmDialog.vue`, asked for through `shared/state/useConfirm`'s
  `ask()`, which resolves `true`/`false` so the call site keeps its guard clause
  (`if (!(await ask({ … }))) return`). It is mounted **once** in `App.vue` beside the toasts and follows the
  footer rules above. This is the default for a destructive action that needs a sentence of consequence.
  A request may carry **opt-ins** through `askWithOptions()` (one through `askWithOption()`) — a checkbox each under
  the consequence, unchecked each time the dialog opens, their states answered in order only on accept (`null` on
  decline). The tenant removal carries two ("also turn off every link", "also give this space a new lock"). The first
  is the dialog form of the opt-in the inline confirm carries in the group panel's removal; the two are one question
  in two mechanisms, not a copy to merge.
  **`admin-web` has its own copy of both files**, not an import — [ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md),
  the same relationship `shared/state/useToast.ts` already has with web's. There is no shared UI package and
  there must not be one.
- **The inline two-step confirm** — a confirm label revealed in the row itself (`groups.deleteConfirm`,
  `kit.backupDownloadConfirm`, …). Deliberate and unchanged: the low-ceremony, in-row form.
- **A dedicated dialog** where the dialog does more than ask — it owns the mutation, its busy state and/or its
  inline error. The shared dialog resolves and closes; it never runs the action, so anything that needs a
  `:loading` confirm button stays its own component. There are **five** in `src/web/` [M: 2026-09-20]:

  | Dialog | What it owns beyond asking |
  |---|---|
  | `core/node/components/DeleteNodesDialog.vue` | busy (gates `closable`/`close-on-escape`) + a summary of what was not deleted + one Undo; one node's header by its kind (folder, Memory, …) |
  | `account/trustDevice/DeviceTrustControl.vue` | busy + inline error |
  | `memories/components/MemoryDetail.vue` (trash purge) | a data payload (the ids) + busy + pluralised body; **no** inline error |
  | `account/rotation/KeyRotationPanel.vue` | busy + inline error, both shared with the form it gates |
  | `memories/components/MemoryBuckets.vue` (delete bucket) | a **form** — type-the-name — + saving state + an inline error field |

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
  "Secure your account?", "Delete bucket?". **4 of 4** question headers sit there, on a consequence-only body.

So: match the voice to the mechanism you picked, and do not "fix" one to look like the other. The single
counterexample is `DeleteNodesDialog` asking for one node ("Delete folder", or the kind's own title such as "Delete Memory") — a dedicated dialog wearing the
imperative voice. It is the mechanism axis it crosses, not the voice one: both its headers are imperative.

**`window.confirm` is retired, and `no-alert` is what keeps it retired** — in `src/web/eslint.config.js` and in
`src/admin-web/eslint.config.js`, including in test files, which are deliberately not exempted.
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

## Settings hub

One page, `/settings/<section>`, behind an unlocked vault
([ADR 0022](../decisions/0022-settings-information-architecture.md)). The profile menu's *Settings* entry is the
shell's only door into it — the top bar carries none, and lint holds that — and beside it the menu carries only the
theme and language quick-pickers and sign-out.

- **The rail** mixes two axes. *Personal* is always there: appearance and language, map and place names (this browser's
  map consent, uc.memory.place A5), sessions, password, adding a device
  (where the instance links devices), security, the trust reset (only while this device refuses a document), the
  profile (in a tenant), the way into moderation (for an appointed account) and erasing the account, last. *Shared
  space* is there while the Tenant scope is active: members, who's who and terms for every member, and mail, storage
  and capabilities for its owner only. *Groups* is a collapsible collection of every group the caller owns, with a
  create entry where the instance offers groups.
- **A group the caller belongs to but does not own** is not in the rail, yet its page opens: the member directory,
  read-only. No owner control is rendered (not hidden by CSS) — each is gated by the page's own owner answer from the
  directory, false until it is in — and one info note says that only an owner of the group changes it (no reassurance
  beside it).
- **No section given** opens the active scope's own: a group's page (owned or not), the Tenant's members, else
  appearance. A section the caller may not see is not in the rail, and its address leads back to `/settings`; a
  default that would lead back gives way to appearance, so the two redirects never trade the route.
- **One breakpoint, 720 px**: above it the rail and the detail pane side by side, the default section's address made
  explicit; at or below it the rail is a list that drills into a section.
- **Hub or inline** (ADR 0022 decision 4): what configures me, a space or its access lives here; what changes one node
  — rename, cover, date, place, move, delete — stays on that node.

## Feedback

One toast channel per client ([ADR 0039](../decisions/0039-transient-feedback-via-toast.md)), mounted once in `App.vue`
and reached only through `shared/state/useToast` (and `useUndo` for the undo timer); the toast is a layer component
(§ Components). Three kinds:

- **success** — an acknowledgement that dismisses itself after 4 s;
- **undo** — a toast with an *Undo* action, its lifetime chosen by the call site, the restore the call site's own;
- **error** — a failure no open form can hold inline (an undo that could not restore, a write a closed dialog
  started); it stays until closed.

A validation error and a load error stay inline, beside the form or in the panel they belong to.

## Long lists

Every view over a list of media — the gallery, the trash, the contributors' view, a guest's share (which is the
member's view, with fewer actions) — goes through one windowed grid
([ADR 0105](../decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)):

- **Windowing.** The justified layout is computed for the whole list, so the scrollbar is true, and only the tiles
  whose rows are near the viewport are mounted. The grid appears once its order is final and never reflows a placed
  tile because a later page arrived.
- **Thumbnails** are read only for mounted tiles, nearest first, a bounded number in flight; their object URLs sit in a
  bounded store and are revoked when evicted.
- **The opened listing.** Each listed seal (a medium's listed tier, its original's blob meta) is opened once per seal
  and kept, so a sort, a filter or a resize opens nothing twice; a meta edit is a new seal and is opened anew.
- **The listing worker** opens them off the main thread. It holds the content keys of the open Memory only, drops them
  when the Memory closes and refuses every later request for it; without a worker, or when it fails, the same work runs
  on the page thread.
- **Keyboard.** The grid is one tab stop; the arrow keys, Home/End and PageUp/PageDown move it, and closing the
  lightbox returns the focus to the tile it showed last.

A new view over media that mounts every item, or opens a seal on the page at each render, is a defect. The targets are
ADR 0105 § Targets; the reading against them is [uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md)
§ Quality notes.

## Product language

**Memory / Chapter** (DE: *Kapitel*) for content; folders are organizational and visually **secondary**
to Memories. Never reintroduce *Album*/*Explorer*. Public/sharing UI must not imply public profiles,
cross-Memory person discovery, feeds, reactions or a social graph.

### Copy: short, and it says what happens

Owner 2026-10-23; the same rule holds for `src/admin-web/`'s texts.

- **A text says what happens and what it costs the reader, in as few sentences as that takes — usually one.** A
  switch's hint says what switching it does; a dialog's body says what the step changes.
- **It does not reassure.** "Stored as ciphertext only", "never the password itself", "nothing about your users
  leaves this server": that veyq is safe follows from using it, so a text does not keep telling the reader. How a
  step is protected is the published Security area's job, not the screen's.
- **A consequence the reader cannot see is not reassurance.** That existing links stop at once, or that nothing is
  deleted and the links work again when switched back, tells the person clicking what their click does to *others*.
  Keep it, in a clause.
- **The exception: what the reader should keep safe gets enough explanation that they do.** How much depends on what
  a loss costs. A lost Secret Key or Emergency Kit cannot be replaced — securing the account issues a new one, but only
  against the current one — so the text says so plainly, and says that a kit someone else may hold is replaced by
  securing the account. The
  config-secret key costs work rather than data: without it, media on a store of the Server Owner's own are unreachable until its
  credentials are entered again, and so is every other sealed secret; the bundled store reads its deployment's hint and is
  untouched (ADR 0089 decisions 3 and 4, ADR 0050). So its panel says what the key
  is, what its status means and what a loss would mean, without alarm.

**Deleting an i18n key needs a grep — nothing else catches it.** A missing `vue-i18n` key resolves at *runtime*
(it renders as the key path), so neither typecheck nor the component tests see it — those supply their own
message objects. Removing the last *member*-surface user of `members.copyLink*` would have left the ServerOwner
bootstrap dialogs rendering nothing; the sweep that caught it was a grep, not a build. So before deleting a key,
list every key a template actually reads and check it against **both** locale blocks:

```bash
grep -rho "t('members\.\w*'" src/web/src --include="*.vue" --include="*.ts" | sort -u
```

Run it on the branch the deletion lands on, not the one it was written on: the owner's pass that removed
`password.pwnedNote` was two days older than `main`, and `main` had meanwhile added a test asserting the note
(admin-web's `SetupView.test.ts`), which only the landing branch's `npm run check` caught.

**A sweep for reassurance reads more than one pattern.** `ciphertext|encrypt|sealed|never (the|shown|sees|leaves|reads)|safe|secure`
finds most of it, but the 2026-10-23 sweep found a fifth of its changes only through `locked (on|with)`, `in your browser`,
`leaves`, `blind` and `stored or sent`; a future sweep reads both sets, and every hit is a lead, not a verdict.

**The layer's own texts come from the catalog too.** What a `shared/ui/` component says on its own - a dialog's
close button, the password field's show/hide toggle, the date field's segment names and calendar buttons, the toast
region's name - is a key of the catalog's `ui` block (one per package), read where the component renders, in EN and
DE; no layer component carries a literal string, and a new one brings its keys into both locale blocks (inbox #434,
ADR 0107 § Consequences, *Accessibility is ours to keep*). Month and weekday names come from the browser's `Intl` for
the language, not the catalog.

## Accessibility & responsiveness

Visible focus states, accessible names, predictable keyboard behaviour. No hover-only affordances for
essential actions. Text must not overflow on mobile — prefer existing responsive `.veyq-*` behaviour
before component-specific media queries.

### Heading levels

Owner 2026-10-24.

- **A page has exactly one `h1`, its subject.** A node's page names the node in core's `NodeHeader`: a Memory, a
  file, a drop box, and a folder's level view.
- **Sections are `h2`, and a head inside a section is one level deeper.** Never skip a level. A component used at
  two depths takes its level from its host, as `SpaceMap` does with `headingLevel`.
- **A dialog's title heads the dialog,** not the page under it.
- **The space's top level has the space's name as its `h1`** (owner 2026-10-24): it has no node to name, so the space
  is its subject — `LevelView`'s `spaceName`, core's `NodeHeader` with no actions.

## Checklist before finishing web UI work

- Reused a `.veyq-*` class and `--app-*` token before adding local styles / raw colors.
- Defined light **and** dark for any new semantic color — both in `styles/base.css` (`:root` and `:root.veyq-dark`).
- Text 4.5:1, controls/icons/focus 3:1, both modes; text on a filled role colour uses `--app-on-fill`; a new
  colour pairing has a row in `scripts/validate_web_contrast.py` (§ *Contrast*).
- **Every rule is anchored on a class the markup applies** — enforced, not advice: `scripts/validate_stylesheet_anchors.py` fails the build on a rule whose *first* compound names only classes nothing renders — in every `.css` under the package's `src/`, an application's own sheet included. Landing a stylesheet ahead of its markup turns CI red, and that is the point (inbox #140: a shipped feature's only signal of missing its surface was CSS nobody had matched). Adjusting a layer component is a rule compounded with or scoped by one of ours (`.memory-card .veyq-card-frame__body`, `.veyq-danger-action.veyq-button`), and the layer's own classes are applied by its markup like any other.
- Memory/Chapter language; folders secondary; no Album/Explorer.
- Copy is short, says what happens, and does not reassure; only what the reader should keep safe gets an
  explanation, sized to what its loss costs (§ *Copy*).
- One `h1` per page, its subject; sections `h2`, no skipped level (§ *Heading levels*).
- Rows for management data, cards for content; no nested cards.
- Dialog actions ordered + in-progress-safe; icon-only actions labelled.
- Confirmed a destructive action through `useConfirm` (or an existing inline/dedicated form) — never
  `window.confirm`, and never a test that *stubs* one; gave its header the voice its mechanism takes; and any
  label your copy quotes is one the reader can actually see.
- Matched the surrounding density rather than introducing a size of your own.
- Grepped for remaining readers before deleting any i18n key, in both locale blocks.
