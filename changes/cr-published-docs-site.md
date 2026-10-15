---
id: cr-published-docs-site
title: Published documentation as a Starlight site; docs/ for development only
status: planned
satisfies: []
adrs: ["0087", "0057", "0064"]
invariants:
  - "Self-hostable by default: the site's output is static and hostable anywhere; nothing in it needs GitHub at runtime"
  - "All docs in English: the site's German locale is the one published exception, as wiki/ is today"
  - "No deprecated dependencies: Astro's tree goes through scripts/npm_ci_no_deprecated.py like every npm package"
---

## Goal

Replace the GitHub Wiki with one static documentation site in `site/` (Astro + Starlight), published to GitHub
Pages, with three areas: **Use** (EN+DE, for users, from `wiki/`), **Run** (EN, for administrators, from the
production half of `infra/README.md`) and **Security** (EN, a reading path for researchers over the normative
docs in `docs/`). At the end `wiki/` and `wiki-sync.yml` are gone, and `docs/` holds only development material:
each use-case's bilingual end-user section is one English `## User impact` line. ADR 0087 holds every decision
and its rejected alternatives; this CR only orders the work.

## Context & constraints

- [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md): the
  decision, the locale scheme (English root, German at the same path under `/de/`), `sources:` frontmatter,
  `.md` for guard-parsed pages, no versions before V1.
- [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) and
  [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md): the release verification and both
  deployment targets the Run area documents; edited in place where they name `infra/README.md` as the home of
  the operator commands.
- [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve):
  the ceiling for every Security page claim.
- [`docs/features/README.md`](../docs/features/README.md) § use-case format and
  [`docs/ai/README.md`](../docs/ai/README.md) § Two documentation audiences, § State vs. flow: the rules this CR
  rewrites.
- Guards that anchor on what moves — `changes/README.md` § *before reshaping a symbol, grep `scripts/`*:
  `validate_wiki_sources.py`, `validate_doc_links.py` (`DOC_ROOTS`), `validate_feature_format.py` (`BILINGUAL`),
  `validate_guard_gaps.py` (forges 31–33 build wiki pages), `validate_release_identity.py` (parses `cosign
  verify` out of `infra/README.md`), `validate_image_pins.py` and `validate_build_input_pins.py`
  (`SKIP_PREFIXES` name `wiki/`), `npm_ci_no_deprecated.py` + `validate_npm_installs_gated.py` (install from the
  repo root only, today).
- There is no remote yet. The Pages deploy can only be proven after the first push; until then the proof is a
  local `npm run build` of `site/`.

### Inventory — the locators

Run from the repo root; each finds the sites, none is a tally to trust.

| What | Locator |
|---|---|
| Everything that names the wiki | `grep -rIl "wiki" --exclude-dir=node_modules --exclude-dir=graphify-out --exclude-dir=.git --exclude-dir=wiki .` |
| In-app links to the wiki | `grep -rIn "github.com/[^ ]*wiki" web/src admin-web/src` |
| Docs that link a wiki page back | `grep -rln "wiki/\(en\|de\)/" docs` |
| Everything that points at `infra/README.md` | `grep -rIn "infra/README" --exclude-dir=node_modules --exclude-dir=graphify-out --exclude-dir=.git .` |
| Use-cases with the bilingual section | `grep -rl "^## What this means for you" docs/features/*/use-cases` |
| Which of those a wiki page declares | `for f in wiki/en/*.md; do head -n 1 "$f"; done \| sed 's/.*speaks-for: //; s/; pair.*//' \| tr ',' '\n' \| sed 's/^ *//' \| grep "^docs/features" \| sort -u` |
| Production sections of `infra/README.md` | `grep -n "^##" infra/README.md` — *Running in production* down to *Backup & restore*; *Cutting a release* and the dev stack stay |

Measured at refine on `main` @ `00e44849`, uncommitted neighbour edits to `docs/inbox.md` and two ADRs: 72
use-cases carry the bilingual section, 34 of them are declared by a wiki page, 38 are not. No wiki page uses
Mermaid (`grep -rn mermaid wiki` is empty), so the site needs no diagram plugin. The Dockerfile copies only
`infra/docker-compose.prod.yml` and `infra/.env.example`, so moving runbook prose out of `infra/README.md`
changes nothing in the image.

## Phases

- [x] 1 scaffold — `site/` as an Astro + Starlight project outside the npm workspace, own `package-lock.json`,
      `base: '/veyq/'`, English root locale and `de`, Pagefind search, `docsSchema` extended with `sources:`, the
      three areas as an empty sidebar with one English and one German placeholder page, and a German page
      deliberately missing to see the fallback. `npm run check` in `site/` = `astro check` + build + an internal
      link check. Teach `npm_ci_no_deprecated.py` to install a package directory outside the workspace and add
      `site/` to the gated installs `validate_npm_installs_gated.py` pins. A CI job runs `site/`'s check, and a
      Pages workflow builds and deploys `site/dist`. **Entry condition, decided before anything else:** if
      `site/`'s install is red on a deprecated package nobody can replace, stop and take it back to the owner
      (ADR 0087 § Consequences) — do not relax the gate. · Done when: the gate green on `site/` (exit code
      recorded), `npm run check` in `site/` green, the built site serves the placeholder pages, `/de/` shows the
      German placeholder and the English fallback with its notice; bite: a deprecated package pinned into
      `site/` turns the gate red.
- [ ] 2 Use area — move `wiki/en/*` to `site/src/content/docs/` and `wiki/de/*` to `site/src/content/docs/de/`
      under the **English** path of their pair; the `speaks-for` comment becomes frontmatter `title` +
      `sources:`, the H1 goes (Starlight renders the title). Merge `running-your-instance.md` into phase 3
      instead. Rebuild `validate_wiki_sources.py` for the site (frontmatter `sources:`, pair = same path, both
      declare the same set, every declared doc under `docs/` links the English page back) and re-point forges
      31–33 in `validate_guard_gaps.py`; `validate_doc_links.py` covers the site's content instead of `wiki/`;
      `SKIP_PREFIXES` follow. Re-point the docs' back-links and the in-app links (locators above). Delete
      `wiki/`, `wiki-sync.yml`, and `Home.md` becomes the site's landing page. · Done when: trust-chain green,
      `npm run check` green in `web/` and `site/`; bites: a German page with one source fewer than its English
      pair, and a declared doc with its back-link removed, each turn the rebuilt guard red.
- [ ] 3 Run area — the production sections of `infra/README.md` and `wiki/en/running-your-instance.md` become
      the Run pages (verify a release, new instance, Kubernetes, upgrade, backup & restore), Compose and Helm side
      by side where both apply. The release-verification page stays `.md` with its commands in plain fenced
      blocks. Re-anchor `validate_release_identity.py` on that page; re-point every file that means the
      production procedure (locator above: code comments, chart files, scripts, workflows, `SECURITY.md`, ADRs
      0057/0064, the root and `backend/` `CLAUDE.md`). `infra/README.md` keeps the dev stack and *Cutting a
      release* and links the Run area. · Done when: trust-chain green, `python3 scripts/chart_meta_check.py`
      green, `npm run check` in `site/` green; bite: removing the image's `cosign verify` line from the Run page
      turns `validate_release_identity.py` red, and it names the new file.
- [ ] 4 Security area — a reading path for researchers: start here (what operator-blind means and what it does
      not cover), threat model, what holds today, key hierarchy, cryptographic stance with how to reproduce the
      vectors, and disclosure (from `SECURITY.md`). Each page declares its `sources:` in `docs/` and links the
      normative docs on GitHub; no page claims more than § What holds today. · Done when: trust-chain green,
      `npm run check` in `site/` green, the pages read with the owner in conversation before the phase closes.
- [ ] 5 `docs/` for development — each use-case's `## What this means for you / Was das für dich bedeutet`
      becomes `## User impact` with one English line on why the feature exists for the user; a section whose
      content a Use page does not yet carry is checked against that page first (the German text is dropped, ADR
      0087 § Consequences). `validate_feature_format.py` checks `## User impact`; `docs/features/README.md`,
      `docs/ai/README.md` (§ Two documentation audiences, § State vs. flow) and the root `CLAUDE.md` repository
      shape describe `site/` instead of `wiki/`. · Done when: trust-chain green, the bilingual locator returns
      nothing, `grep -rln "Was das für dich bedeutet" docs` returns nothing; bite: a use-case without
      `## User impact` turns `validate_feature_format.py` red.

## Definition of done

`site/` builds and checks green, its install passes the deprecated gate, and the trust-chain is green. `wiki/`
and `wiki-sync.yml` are gone, nothing in the repository links the GitHub Wiki, and `infra/README.md` no longer
holds the production runbook. Every rebuilt or re-anchored guard was bitten as its phase says. Not provable here:
the first Pages deploy, which happens on the first push to the public remote — check one published page then,
including the German fallback notice.

## Retrospective

(filled at close)
