# ADR 0087 — Published documentation is a Starlight site; `docs/` is for development only

- Status: Accepted; built, except the first GitHub Pages deploy, which runs on the first push to the public remote
- Date: 2026-10-15 (amended 2026-10-16: the site's root is the project website, point 7)

## Context

veyq has three outside readers, and the repository serves none of them on purpose:

- **Users** who want to know how to use veyq and have no technical background.
- **Administrators** who install, upgrade, back up and restore an instance.
- **Security researchers** who want to see how operator-blindness and E2EE are actually built.

What exists: `wiki/` (nine topic pages, EN+DE, written for users, mostly about *why* their data is safe and
hardly about *how* to do things), one English operator page in it, the production runbook inside
[`infra/README.md`](../../infra/README.md) next to the dev stack, and the security model in
[`docs/crosscutting/security-model.md`](../crosscutting/security-model.md) with the ADRs and crypto vectors,
which `wiki-sync.yml` deliberately does not publish. Nothing is published at all: the repository has no remote,
so the wiki sync has never run.

`docs/` also carries user-facing text: every use-case has a bilingual `What this means for you / Was das für dich
bedeutet` section, enforced by `scripts/validate_feature_format.py`. It is the only reason `docs/` holds German.

The GitHub Wiki does not fit the three readers. It has no locales (`en/` and `de/` are folders), it has no
versions while an operator needs the runbook of the release they run
([ADR 0057](0057-release-identity-and-deployment-recipe.md)), it can show researchers `docs/` content only as a
copy (the drift `validate_wiki_sources.py` was built after, inbox #120), and it lives only on GitHub.

## Decision

1. **One static documentation site, built from this repository and published to GitHub Pages**, with three
   areas, each with one source:
   - **Use** (EN+DE) — for users **and space owners** (who manage their space inside the web client: members,
     terms, email, storage); what lives in `wiki/` today moves here. Its pages are **task-first**: numbered steps
     in the UI's own words, per language (German with *du*, as the UI), each closing with a short note on why
     it stays private that links the Security area. They describe only what is built. The second application
     is called **Depot** ([ADR 0086](0086-one-word-per-meaning.md)), as the web client labels it. Where a
     picture helps, a page places a **screenshot placeholder** that names what the shot must show; the owner
     supplies the images, one per language, and a missing image renders as its placeholder, never as a broken
     build.
   - **Run** (EN only) — for administrators; the production half of `infra/README.md` moves here, and
     `infra/README.md` keeps the dev stack and cutting a release.
   - **Security** (EN only) — for researchers; a guided reading path that explains the model and links the
     normative docs in `docs/` on GitHub. It claims nothing beyond
     [security-model § What holds today](../crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
     It **explains, and points exactly**: concepts, protocols and their rationale at whitepaper depth, while byte
     formats stay normative in [`src/packages/crypto/src/v1.ts`](../../src/packages/crypto/src/v1.ts) and the
     [vectors](../../contracts/crypto/vectors/v1.json) and are linked, never restated — a second byte spec would
     be a second source nothing holds to the vectors. Its pages describe what is **built**; everything designed
     and not built (Key Transparency, strict verification mode, abuse-report intake, passkey unlock, post-quantum
     signatures, a client served by a trusted third party) lives on **one Roadmap page** of the area, which the
     other pages link where a gap is closed only by a roadmap item. A rationale the Security pages need and no
     doc records is written into its ADR before the page that explains it. The first such gap, why the hybrid
     KEM had its own HKDF combiner, was closed by replacing the combiner with X-Wing
     ([ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) decision 2).

   The repository becomes public; GitHub Pages on the free plan requires it.
2. **The site is Astro with Starlight**, in its own package `site/`, **outside** the npm workspace (the workspace
   exists for `@veyq/crypto` alone and the site shares no code), with its own lockfile and its own
   `npm run check`, installed through the same deprecated-package gate as every other npm package. English is
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
7. **The site's root is the project website** (amended 2026-10-16). `/` and one page per further language (`/de/`)
   are plain Astro pages, not Starlight ones (`site/src/pages/`, words and layout in `site/src/components/website/`);
   the Use area's start page moved to `/start/`, every other URL is unchanged. The website says what veyq is — a
   platform, not one application —, which applications are built, why it is needed, and links into the three areas.
   Every claim it makes is one a docs page makes, and it links that page; the build fails on a link to a docs page
   that does not exist, since `starlight-links-validator` checks the docs' content only. It loads nothing from a
   third party (no web font, analytics or CDN): the front door of an operator-blind product does not report its
   readers. Its languages are one list (`languages` in `strings.ts`), each also a Starlight locale; its screenshots
   are of the web client, one per language, `site/src/assets/website/<id>.<lang>.png`, taken of a throwaway
   instance whose content is made up and generated, never of real people's data.

## Consequences

- `wiki/`, `wiki-sync.yml` and the GitHub Wiki go away. In-app links to the wiki move to the site
  (`src/web/src/views/ShareView.vue` links `is-my-data-safe` today), and the German one loses its translated slug.
- The guards follow the content: `validate_wiki_sources.py` reads `sources:` from frontmatter and pairs by
  path; `validate_doc_links.py` covers the site's content; `validate_feature_format.py` checks `## User impact`;
  `validate_release_identity.py` reads the `cosign verify` commands from the Run page instead of
  `infra/README.md`. The root `CLAUDE.md` rule that `infra/README.md` owns the commands for both deployment
  targets changes with it, and so does every file that points at its production sections.
- `docs/` loses all German and its last user-facing prose. The 72 bilingual sections are material for the site's
  user pages first and are cut only after that. 38 of them back no wiki page today, so their German text is
  dropped rather than moved; a German page is written from its English page, not from a use-case.
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
