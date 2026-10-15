---
id: cr-use-area-content
title: The Use area becomes task-first guides for users and space owners
status: planned
satisfies: []
adrs: ["0087", "0086", "0079"]
invariants:
  - "Product language: Memory / Chapter (Erinnerung / Kapitel), application (Anwendung), never Album, Explorer, plugin or app for an application (ADR 0086)"
  - "No page claims more than is built, and none claims more about privacy than security-model § What holds today"
---

## Goal

Turn the Use area of the docs site from nine moved wiki pages, which mostly explain why data is safe, into
**task-first guides** for non-technical users and for space owners: numbered steps in the web client's own words,
English and German, grouped as Getting started · Your account · Spaces · Memories · Depot · Sharing · For space
owners · Privacy · Help. Pictures are **screenshot placeholders** the owner fills in later. This CR comes before
phase 5 of `cr-published-docs-site`, which cuts the use-cases' end-user sections only after these pages have taken
in what they hold. ADR 0087 decision 1 (the Use bullet) holds the decisions.

## Context & constraints

- [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md) decision 1:
  task-first, the UI's words per language, German with *du*, only what is built, a closing privacy note that links
  the Security area, Depot named as the UI names it, screenshot placeholders.
- [ADR 0086](../docs/decisions/0086-one-word-per-meaning.md): the word rules, and *Depot* as the second
  application's name (built 2026-10-15: the UI says *Depot* in both languages).
- [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md): platform vs.
  application — Getting started, Your account, Spaces and Sharing are platform; Memories and Depot are applications.
- [`cc.inclusive-language.md`](../docs/crosscutting/cc.inclusive-language.md): German neutral-first. The UI says
  "space owner" / *wer den Bereich besitzt*, never "tenant"; the pages follow.
- The sources guard (`scripts/validate_wiki_sources.py`, rebuilt for `site/` in `cr-published-docs-site` phase 2)
  reads `.md` and `.mdx`, pairs EN/DE by path, and requires the declared docs to link the English page back.
- Feature status: the `docs/features/*/_index.md` lines were found stale at refine for change-password,
  rotate-keys, memory.place, memory.cover and tenant-mail-settings (each called not built; each has its panel in
  `web/src`). A page's status comes from the code and the coverage file, never from an index line alone.

### Inventory — the locators

| What | Locator |
|---|---|
| What a user can reach | `grep -n "path:" web/src/router.ts` and the settings sections in `web/src/views/SettingsHubView.vue` |
| The UI's words, EN and DE | `web/src/i18n/index.ts` (EN first, DE after `de:`); look a term up there before writing it |
| User-facing feature folders | `ls web/src/features web/src/core` |
| The use-cases and their end-user sections (the raw material) | `grep -rl "^## What this means for you" docs/features/*/use-cases` |
| Which use-cases a Use page already declares | `grep -rh -A20 "^sources:" site/src/content/docs/*.md \| grep "docs/features"` |
| Today's Use pages | `ls site/src/content/docs/*.md site/src/content/docs/de/*.md` |

## Phases

Every phase writes English and German together, pairs at the same path; every page declares `sources:` and gets
its back-links; every step is checked in the web client (dev backend with S3) before it is written; and the
phase closes only after the owner has read its pages in conversation. Done when, per phase: `npm run check` in
`site/`, the sources guard and the trust-chain green, and the owner's read.

- [ ] 1 frame — the sidebar groups of the nine sections; a `Screenshot` component for MDX pages that takes an id
      and a description of what the shot must show, renders `site/src/assets/screenshots/<id>.<en|de>.png` when it
      exists and a visible placeholder (id + description) when it does not, and never fails the build for a
      missing image; a shot list (`npm run shots` in `site/`) that prints every placeholder — id, page, description,
      which language files exist — as the owner's to-do list. The existing pages move to their section
      (`adding-another-device` → Your account, `people-in-your-memories` → Memories, `download-and-import` →
      Spaces, `who-gets-into-your-space` → For space owners, `is-my-data-safe` and
      `quantum-computers-and-your-photos` → Privacy; `account-keys-and-recovery`, `files` and `sharing` are split
      or absorbed in phases 2–7) without breaking a link (the in-app link in `web/src/views/ShareView.vue` and
      the docs' back-links follow). Bites: a placeholder with no image renders its box and the build stays green;
      an image dropped in renders instead; the shot list names both.
      *Built, waiting for the owner's read:* a section is a folder (`getting-started/`, `account/`, `spaces/`,
      `memories/`, `depot/`, `sharing/`, `space-owners/`, `privacy/`, `help/`), so its pages' URLs say the section;
      `account-keys-and-recovery`, `depot` and `sharing` stay at the root until the phase that splits them
      (`validate_guard_gaps.py` forges the first at that path — re-anchor it then). A section with no page yet is
      not in the sidebar. The shot list is `scripts/site_shots.py`; it also reports a German page that places other
      ids than its English page. No redirects from the old URLs: nothing was published (no remote).
- [ ] 2 Getting started — what veyq is (spaces, Memories, Depot); accepting an invitation and creating the account
      (there is no self-sign-up: `/tenant/accept`); the Emergency Kit and Secret Key, and why losing both loses
      the data; signing in on a new device (Secret Key by paste, kit upload or QR scan).
- [ ] 3 Your account — stay signed in and trust a device; add another device; devices and sessions; change
      password; change email; forgot password; replace your keys; profile and picture; appearance and language;
      verify a person (safety number by scan or digits, reviewing a changed key).
- [ ] 4 Spaces — personal, group and shared space, and who sees what; groups (create, invite by username, accept
      an invitation, co-owner, remove, rename, delete and restore, icon); organising (folders, favorites, move,
      copy, select several, trash, views); download and import.
- [ ] 5 Memories — create a Memory and chapters; add photos; gallery; date and location (with the map consent);
      who was there; cover; marked photos; contributions; the photo trash.
- [ ] 6 Depot — upload files and folders, view, replace, rename, delete; drop boxes.
- [ ] 7 Sharing and For space owners — share with people who have no account (roles, note, password, expiry,
      renew, revoke); turn off every link in a space; what a guest sees. For owners: invite and manage members,
      remove a member, terms, email settings, storage and the upload-link limit.
- [ ] 8 Privacy and Help — the two Privacy pages re-read against the new sections and linked into the Security
      area; Help: lost Emergency Kit, lost device, a changed-key notice, "Start a list over", the offline page,
      forgotten password (pointing to its page). At the end, `npm run shots` is handed to the owner as the list
      of screenshots to supply.

## Definition of done

Every built user-facing capability in the inventory has a Use page in English and German that a non-technical
reader can follow step by step in today's web client; every page declares its sources and the sources guard is
green; no page names a capability that is not built; the shot list is with the owner. Phase 5 of
`cr-published-docs-site` can then cut the use-cases' end-user sections without losing material.

## Retrospective

(filled at close)
