# ADR 0087 — Published documentation is a Starlight site; `docs/` is for development only

- Status: Accepted
- Built: partly (not built: decision 1's publish to GitHub Pages)
- Date: 2026-10-15

## Context

veyq has three outside readers, and the repository serves none of them on purpose:

- **Users** who want to know how to use veyq and have no technical background.
- **Administrators** who install, upgrade, back up and restore an instance.
- **Security researchers** who want to see how operator-blindness and E2EE are actually built.

What existed: `wiki/` (nine topic pages, EN+DE, written for users, mostly about *why* their data is safe and
hardly about *how* to do things), one English operator page in it, the production runbook inside
[`infra/README.md`](../../infra/README.md) next to the dev stack, and the security model in
[`docs/crosscutting/security-model.md`](../crosscutting/security-model.md) with the ADRs and crypto vectors,
which `wiki-sync.yml` deliberately did not publish. Nothing was published at all: the repository had no remote,
so the wiki sync had never run.

`docs/` also carried user-facing text: every use-case had a bilingual `What this means for you / Was das für dich
bedeutet` section, enforced by a guard. It was the only reason `docs/` held German.

The GitHub Wiki does not fit the three readers. It has no locales (`en/` and `de/` are folders), it has no
versions while an operator needs the runbook of the release they run
([ADR 0057](0057-release-identity-and-deployment-recipe.md)), it can show researchers `docs/` content only as a
copy (the drift the wiki's source guard was built after), and it lives only on GitHub.

## Decision

1. **One static documentation site, built from this repository and published to GitHub Pages**, with three
   areas, each with one source:
   - **Use** (EN+DE) — for users **and space owners** (who manage their space inside the web client: members,
     terms, email, storage); it replaces `wiki/`. Its pages are **task-first**: numbered steps in the UI's own
     words, per language (German with *du*, as the UI), each closing with a short note on why it stays private
     that links the Security area. They describe only what is built, and name each application as the web client
     labels it ([ADR 0086](0086-one-word-per-meaning.md)). Where a picture helps, a page places a **screenshot
     placeholder** that names what the shot must show; the owner supplies the images, one per language, and a
     missing image renders as its placeholder, never as a broken build.
   - **Run** (EN only) — for administrators; it holds the production runbook, and `infra/README.md` keeps the dev
     stack and cutting a release.
   - **Security** (EN only) — for researchers; a guided reading path that explains the model and links the
     normative docs in `docs/` on GitHub. It claims nothing beyond
     [security-model § What holds today](../crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
     It **explains, and points exactly**: concepts, protocols and their rationale at whitepaper depth, while byte
     formats stay normative in [`src/packages/crypto/src/v1.ts`](../../src/packages/crypto/src/v1.ts) and the
     [vectors](../../contracts/crypto/vectors/v1.json) and are linked, never restated — a second byte spec would
     be a second source nothing holds to the vectors. Its pages describe what is **built**; everything designed
     and not built lives on **one
     [Roadmap page](../../site/src/content/docs/security/roadmap.md)** of the area, which the other pages link where
     a gap is closed only by a roadmap item. A rationale the Security pages need and no doc records is written into
     its ADR before the page that explains it.

   The repository becomes public; GitHub Pages on the free plan requires it.
2. **The site is Astro with Starlight**, in its own package `site/`, **outside** the pnpm workspace (the workspace
   exists for `@veyq/crypto` alone and the site shares no code), with its own lockfile and its own
   `pnpm run check`, installed through the same deprecated-package gate as every other package. English is
   the root locale and German lives under `/de/`. A German page is the English page's translation at the
   **same path**, which is how Starlight pairs them, so file names and URLs are English in both locales. A page
   with no translation falls back to English with Starlight's notice; that is how Run and Security appear under
   `/de/`.
3. **Site pages are written natively for Starlight**: frontmatter `title`, `:::` asides, and MDX where a
   component (cards, tabs, steps) earns it. A page whose commands a guard parses stays `.md` and keeps those
   commands in plain fenced blocks.
4. **A site page names the docs it speaks for** in a frontmatter field `sources:`, and each declared doc under
   `docs/` links the page back. This keeps the address that `validate_wiki_sources.py` gives today: a change to a
   doc shows which published page to re-read. Whether the page and the doc *claim the same thing* stays
   unchecked, as it is today.
5. **`docs/` holds only what development needs.** A use-case's bilingual section becomes `## User impact`: one
   English line saying why the feature exists for the user. The numbered flows with their error paths stay the
   use-case's substance. User-facing prose lives only on the site.
6. **No versioned docs before V1.** The site shows `main`. From V1, versions are built **from release tags**, one
   build per version, never as copied folders; the version granularity is decided then, against
   [ADR 0085](0085-one-major-moved-on-a-yearly-train.md).
7. **The site's root is the project website.** `/` and one page per further language (`/de/`) are plain Astro
   pages, not Starlight ones; the Use area starts at `/start/`. The website says what veyq is — a platform, not one
   application —, which applications are built, why it is needed, and links into the three areas.
   Every claim it makes is one a docs page makes, and it links that page; the build fails on a link to a docs page
   that does not exist, since `starlight-links-validator` checks the docs' content only. It loads nothing from a
   third party (no web font, analytics or CDN): the front door of an operator-blind product does not report its
   readers. Its languages are one list, each also a Starlight locale; its screenshots are of the web client, one per
   language, taken of a throwaway instance whose content is made up and generated, never of real people's data.

## Consequences

- **The first GitHub Pages deploy runs on the first push to the public remote.** Until then the site is built and
  checked, and nothing is published.
- The GitHub Wiki is gone, and in-app help links point at the site.
- The guards follow the content: they read the site's pages where they read the wiki and `infra/README.md`'s
  production sections before.
- `docs/` holds no German and no user-facing prose; a German page is written from its English page, not from a
  use-case.
- A self-hosted instance links its users to `github.io` for help, as it links to `github.com` today: a third
  party sees the click. Serving the site from the instance itself is possible later, because the output is
  static, and is not part of this decision.
- The site adds Astro's dependency tree to the deprecated-package gate. If that tree cannot be kept green, this
  decision is reopened rather than the gate relaxed.
- **Rejected:** keeping the GitHub Wiki (no locales, no versions, no `docs/` content without a copy, GitHub
  only); Material for MkDocs (in maintenance mode since November 2025, security fixes for about a year, which
  the root `CLAUDE.md` dependency rule excludes) and its successor Zensical (still alpha); Docusaurus (a heavy
  React tree against the deprecated gate, and Algolia, a third-party search, as its default); keeping the site
  sources as GitHub-flavoured Markdown converted at build time (worth it only for files read on GitHub too,
  which site pages are not; `docs/` is not moved, so the question does not arise for it); the
  `starlight-versions` plugin (0.x, *"still in early development"* in its own README, and it versions by copying
  every page into a folder per version, a second copy of each page that no guard sees).
