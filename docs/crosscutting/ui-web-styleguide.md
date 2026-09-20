# Web UI Styleguide

Visual + interaction rules for the **web** client, so extending the (reused) UI stays "in-style"
instead of by feel. veyq should feel like a curated private Memory archive — not a cloud drive, folder
gallery or social network.

## Source of truth

**`web/src/styles/`** — eight stylesheets (~175 KB: `base` tokens, `ui`, `layout`, `forms`, `memory`, `media`,
`settings`, **`dark`**); `web/src/style.css` is the import barrel. Individual edits carry a comment with their
reason where the reason isn't obvious.

**`admin-web/src/styles/`** is a five-sheet **copy** of it — everything but `layout`, `memory` and `media`,
which style a Memory shell, a gallery and a viewer that surface does not have — and the rules on this page
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
demonstrably obeyed — `.veyq-dialog-action-row`: 15 uses, zero per-component copies.

## Color — semantic role tokens

Use `--app-*` role tokens before raw colors — they are defined in `styles/base.css`, and **every semantic color
carries a dark value in `styles/dark.css`**. A new one must define both:

- **accent / brand** — brand marks, selected states, focus accents, navigation highlights.
- **success / positive** — submit, save, confirm, upload, consent, progress, maps, tracks, journal links.
- **warning** — group/shared/context badges, warm status; never for submit.
- **danger** — destructive actions, removal, deletion, errors only.
- **info** — informational/helper cues, non-critical metadata.
- **control / surface / text / line** — neutral controls, panels, foreground, borders.

Raw colors are allowed only for *rendered content* (lightbox overlays, media scrims, map/SVG/canvas
values), never for application UI roles. Repeated raw colors must become a role token before merge.

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
- **Badges:** `.veyq-badge.success|.warning|.muted` for status; no one-off pills.
- **Forms:** `.veyq-form`, `.veyq-form-grid`, `.veyq-form-copy`; full-width inputs; concise labels;
  muted helper text that explains *consequences*, not the label.
- **Empty/loading:** `.veyq-empty`, `.veyq-loading` — concise and action-oriented, not marketing copy.

## Buttons & dialog actions

- Submit/save/confirm = positive (green); cancel/neutral = secondary; destructive = danger with an
  explicit label. Icon-only actions use `.veyq-icon-button` + an `aria-label`/tooltip.
- **Dialog footers use the one shared `.veyq-dialog-action-row`** (a `space-between` row; never a
  per-component `.dialog-actions` copy): **cancel/secondary anchored left, submit/confirm anchored right**;
  source order matches visual order. A **destructive tertiary** action that coexists with the primary confirm
  (e.g. *Cover entfernen* / *Ort entfernen*) sits in a trailing `.veyq-action-group` **immediately left of the
  confirm**, styled **`severity="danger" outlined`** (danger colour, but lighter than the filled primary so it
  doesn't compete). When the destructive action **is** the dialog's primary confirm (a dedicated delete/confirm
  dialog), it is a **filled `severity="danger"`** button on the right — the dialog's one primary. Confirm/
  destructive actions show an in-progress state and **disable confirm/cancel/close + inputs while the mutation
  runs** (no double-submit).

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

## Accessibility & responsiveness

Visible focus states, accessible names, predictable keyboard behaviour. No hover-only affordances for
essential actions. Text must not overflow on mobile — prefer existing responsive `.veyq-*` behaviour
before component-specific media queries.

## Checklist before finishing web UI work

- Reused a `.veyq-*` class and `--app-*` token before adding local styles / raw colors.
- Defined light **and** dark (`styles/dark.css`) for any new semantic color.
- Memory/Chapter language; folders secondary; no Album/Explorer.
- Rows for management data, cards for content; no nested cards.
- Dialog actions ordered + in-progress-safe; icon-only actions labelled.
- Matched the surrounding density rather than introducing a size of your own.
- Grepped for remaining readers before deleting any i18n key, in both locale blocks.
