---
id: cr-run-area-content
title: The Run area becomes the Server Owner's complete manual
status: planned
satisfies: []
adrs: ["0087", "0089", "0057", "0064", "0065", "0071", "0056", "0085"]
invariants:
  - "Self-hostable by default: every procedure works on both deployment targets, Compose and Kubernetes, or says plainly which one it is for"
  - "No page claims more than is built or proven: each procedure says what has been rehearsed and what has not"
---

## Goal

Turn the Run area from the moved production runbook into the Server Owner's manual: fix what the existing pages
get wrong, then add an overview with the architecture, planning and sizing, the admin console, a configuration
reference kept honest by a guard, networking and TLS, storage and email, operations (health, logs, what is not
monitored), hardening, moderation and troubleshooting. English only, Compose and Helm side by side, commands in
plain fenced blocks. It describes the configuration model of
[ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md), so its
configuration and storage pages wait for `cr-store-connection-in-admin-web`.

## Context & constraints

- [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md) decision 1
  (Run, English, for Server Owners; tenant owners are Use) and decision 3 (a page whose commands a guard parses
  stays `.md` with plain fenced blocks — `validate_release_identity.py` reads `verify-a-release.md`).
- The root `CLAUDE.md`: the Run area owns the operator commands for both targets; `infra/README.md` keeps the dev
  stack and cutting a release.
- Built vs. proven, measured at refine: capture → destroy → restore → suites runs on every
  `scripts/run_web_e2e.py`; the upgrade proof's data half runs with `--upgrade`; the rendered chart is checked
  (`chart_meta_check.py`, `chart_upgrade_check.py`). Not rehearsed: an image pull, recipe diff and rollback on a
  host, Helm capture and restore, `helm upgrade` in a cluster, verifying a real release. There is no health
  extension and no metrics (`/api/setup` is the probe; ADR 0062).
- Found wrong at refine (each fixed in phase 1): `upgrade.mdx` sends the Server Owner to "Settings → Server →
  Release" (now admin-web's Server section); `backup-and-restore.md`'s capture and fast path use the dev stack's
  network, volume and container names, and the production capture lives only inside the upgrade runbook;
  `new-instance.mdx` says "five services" and lists four; `infra/docker-compose.prod.yml`'s tunnel comment says
  8443 where every page says 8081; the index's honest-state section lags what `upgrade.mdx` says has executed and
  points to `docs/architecture/README.md` §7, whose deployment view has no admin container and no proxy. The
  config-secret verification step is `cr-config-secret-state-on-admin-channel`'s, not this CR's.
- The sources guard (`scripts/validate_wiki_sources.py`) holds `sources:` and the back-links.

### Locators

| What | Locator |
|---|---|
| Today's Run pages | `ls site/src/content/docs/run/` |
| Every Compose variable an operator sets | `grep -oE "\\$\\{[A-Z_]+" infra/docker-compose.prod.yml \| sort -u` and `grep -oE "^[A-Z_]+=" infra/.env.example` |
| Every Helm value | `charts/veyq/values.yaml` (and `values.schema.json`) |
| Operator-meant backend keys | `backend/core/src/main/kotlin/me/raddatz/veyq/core/config/VeyqConfig.kt` |
| Admin console and CLI | `admin-web/src/router.ts`, `admin-web/src/views/AdminConsoleView.vue`, `admin-web/src/cli/commands.ts` |
| Pointers back to the dev README | `grep -rn "infra/README" site/src/content/docs/run` |

## Phases

Each phase: every page declares `sources:` and gets its back-links; every procedure states what has been rehearsed;
done when `npm run check` in `site/`, the sources guard and the trust-chain are green and the owner has read the
pages in conversation.

- [ ] 1 fix what is there — the wrong-at-refine list above; the production capture becomes its own section of
      Backup & restore and the upgrade runbook links it; the honest state brought level with `upgrade.mdx`; the
      architecture moves onto the Overview (phase 2) and the pointer to `docs/architecture/README.md` §7 goes.
- [ ] 2 Overview, Plan your instance, Hardening, Moderation — what self-hosting means and what the operator can and
      cannot see (linking the Security area), the containers on each target, requirements and sizing with storage
      growth and quotas, Compose or Kubernetes, the three domains; the hardening checklist (unprivileged containers,
      network policy, the admin listener kept local, the config-secret key's custody, HSTS, CSP); moderation (take a
      tenant offline; what is not built).
- [ ] 3 First-run setup and the admin console — creating the Server Owner and its Emergency Kit, then a tour of
      the console (tenants: create, the owner's invite, domain, quota, offline; server: release, store, config-secret
      state) and `veyq-admin`; split out of `new-instance.mdx`. **Entry condition:**
      `cr-config-secret-state-on-admin-channel` and `cr-store-connection-in-admin-web` are done, so the tour shows
      the console as built.
- [ ] 4 Configuration reference — every Compose variable, every Helm value and every backend key meant for an
      operator, with required or optional, default and meaning, plus the settings that live in the console; a guard
      fails when a variable in the production compose file or `.env.example`, or a key in `values.yaml`, is missing
      from the page. **Entry condition:** `cr-store-connection-in-admin-web` is done (the store variables are gone).
      Bite: a variable added to `.env.example` and not to the page turns the guard red.
- [ ] 5 Networking & TLS, Storage & email, Operations, Troubleshooting — Traefik/ACME and Ingress/cert-manager,
      HSTS, trusted proxies and what a custom proxy means for the rate limits; the store as the console sets it, the
      bundled hint, quotas, no instance mail (tenant owners set SMTP) and what the config-secret key protects; the
      health probe, what the logs hold and do not, that there are no metrics; troubleshooting by symptom (uploads
      refused, ACME failing, config-secret ABSENT or MISMATCH, admin console unreachable). **Entry condition:** as
      phase 4.
- [ ] 6 Upgrade & backup on the new model — the backup tool's operator-supplied store credentials, the restored
      store setting and the bundled hint after a restore, and the compatibility promise of the yearly major train
      ([ADR 0085](../docs/decisions/0085-one-major-moved-on-a-yearly-train.md), stated as decided and not yet built).
      **Entry condition:** as phase 4.

## Definition of done

A Server Owner can plan, install, set up, configure, operate, harden, back up, restore and upgrade an instance from
the Run area alone, on either target, and each procedure says how far it has been proven; the configuration
reference cannot silently fall behind the recipes; no page points an operator back to `infra/README.md`.

## Retrospective

(filled at close)
