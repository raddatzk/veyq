---
id: cr-volume-measurement-cax
title: Does a small ARM host carry an instance? The run on a real CAX11, the sizing recommendation, the harness removed
status: planned
satisfies: ["uc.memory.gallery", "uc.memory.browse", "uc.spaces.add-member"]
adrs: ["0057"]
invariants:
  - all seeded content is sealed by the real client crypto, and every seeded member is a real account onboarded through the product's own invite flow
  - no product code changes; a fix found here is a CR of its own
  - the conclusion is a sizing recommendation, not a target (architecture §10 "Deliberately unstated" stays true)
  - nothing is deployed to the server, and the server is never changed, without the owner's explicit go for that step
---

## Goal

**The second of two CRs** (owner 2026-10-18). It answers the owner's question on the hardware it is about: **does
a Hetzner CAX11 (2 Ampere vCPU, 4 GB) carry a veyq instance for a household, and up to what volume?** It runs the
harness that [`cr-volume-measurement-local`](cr-volume-measurement-local.md) built against a real CAX11, judges
the result by the rule that CR decided, and writes the recommendation where operators read it:
`site/src/content/docs/run/plan.md` § Size the host, which today says "No CPU, memory or disk minimum is
documented". Then it deletes the harness.

**Blocked until** `cr-volume-measurement-local` is done and has filled § Local pre-check below, and the owner has
set up the server (§ What the owner provides).

## Context & constraints

- **Same procedure as the local pre-check, different host.** The stages, the scenarios, the seeders and the
  sampler are the local CR's, unchanged. The sampler runs over `ssh` through its command prefix. The seeders and
  the browsers run on this Mac against the server's domain, so every browser number includes a real internet path,
  as it would for the household.
- **Network is measured, not ignored.** The round-trip time and the throughput between this Mac and the server are
  recorded before each stage. A slow reading is then attributed to the host or to the line, not guessed.
- **A throwaway instance.** The server holds nothing but the measurement and is deleted by the owner afterwards.
- **The readings stay out of git; the conclusion goes in** (owner 2026-10-18).

### What the owner provides

1. A CAX11 with a current Debian or Ubuntu, reachable by SSH key, and what the Run area lists for a Compose host:
   Docker with Compose v2, `python3` 3.9 or later, `cosign` v3.1.3 or later (`site/src/content/docs/run/plan.md`,
   § Docker Compose or Kubernetes).
2. **A DNS name** for it (Q1), with its A record set and ports 80 and 443 open, so the recipe's Let's Encrypt
   HTTP-01 challenge succeeds.
3. An explicit go before the deploy (phase 1) and before the bulk-upload scenario. That scenario moves about 4 GB
   over the owner's uplink.

### Measured substrate (worktree `load-testing-data-server-4b2e42` at `c9e4310a`)

| Fact | Locator |
|---|---|
| The release is built per architecture, arm64 included | `scripts/run_pipeline.py:463-470`; `ci.yml`'s `stack` job matrix |
| `veyqctl --unsigned-local` installs local images, a bare `name:tag` only | `scripts/run_stack_e2e.py:4-5` |
| What a Compose install needs and how it runs | `site/src/content/docs/run/new-instance.mdx` |
| The recipe's limits: application `mem_limit` 1 GiB, heap 60 % of it; MariaDB and Garage unlimited on Compose | `site/src/content/docs/run/plan.md` § Size the host |
| Nothing has ever been installed from a published release | `site/src/content/docs/run/plan.md` (the table's *Proven* row) |

Still to locate in phase 1, before anything is copied to the server: whether `veyqctl install --unsigned-local`
needs `cosign` on the host at all, and how the release assets get there (`scripts/release_assets.py` writes them).

### Local pre-check

_Filled by `cr-volume-measurement-local` phase 9: the local verdict per stage, the hotspots it found, the exact
boot, seeding and measuring commands, and the judging rule as decided in its Q1._

## Decisions this CR takes

1. **The images are built here, not published.** arm64 images are built on this Mac (itself arm64), moved with
   `docker save | ssh … docker load`, and installed through the release's own `veyqctl --unsigned-local` from
   assets `scripts/release_assets.py` writes. Publishing a release only to measure would sign and ship something
   no one has accepted.
2. **The stages run in the local CR's order**, each judged before the next is seeded. A stage that fails the rule
   ends the axis there, because a bigger stage on the same box cannot pass.
3. **One extra reading, only possible here: disk.** The space the dataset takes in MariaDB and in Garage per stage,
   so § Size the host can say how much disk a household of that size needs beside the photos themselves.
4. **Where the result lands:**
   - The Run area's § Size the host: the smallest host measured, what it carried by the rule, where it first ran
     short, and the disk per volume. Its `sources:` line names nothing new: a measurement is not a doc.
   - Architecture §10: one sentence pointing there, beside "Deliberately unstated", saying the recommendation
     is a measurement and not a promise.
   - Architecture §11: a row per hotspot that limits the box; an inbox row for its fix, unless the local CR already
     added it.

## Open questions (owner)

**Decided (Q1).** Owner 2026-10-18 ("Ja passt alles"): the recommendation is taken; the name itself is the
owner's to give with the server.

- **Q1: The server's domain.** **Recommendation:** a subdomain of a domain you own (for example
  `veyq-measure.<your-domain>`), with an A record pointing at the CAX, set before phase 1.

## Phases

- [ ] **1 deploy** (owner's go): locate what the substrate section leaves open; build the arm64 images; move them
  and the release assets to the server; `veyqctl install --unsigned-local` with the domain of Q1; first-run setup
  by the harness. · Done when: the instance answers on its domain with a valid certificate; the sampler reads all
  recipe containers over SSH; the network baseline recorded. **Bite:** the harness's first-run setup on the
  installed instance succeeds once and is refused a second time.
- [ ] **2 volume stages**: photos, tree and members, every stage, with the server leg, the sampler and the browser
  leg, exactly as the local procedure. The invite limit is raised only while seeding members (the local CR's
  decision 9), and the instance is restarted with defaults before measuring. · Done when: every stage judged by the
  rule, or an axis ended by decision 2.
- [ ] **3 scenarios**: idle, five at once, bulk upload (owner's go for the upload). · Done when: each judged by the
  rule, the disk readings of decision 3 recorded.
- [ ] **4 record**: decision 4's three places; the verdict compared with the local pre-check in one sentence in the
  retrospective (how far the local run was off is worth knowing for the next one). · Done when: the site check and
  the trust-chain guards green.
- [ ] **5 remove**: delete the harness (`web/scripts/seed-volume.mts`, `web/scripts/measure-volume.mts`,
  `scripts/measure_volume.py`, `e2e/measure/`, the bench files, the `.gitignore` line for `.run/measurements/`);
  the owner deletes the server and the DNS record. · Done when: `git ls-files` shows none of the harness files;
  `npm run check` in `web/` green; the owner confirms the server is gone.

## Definition of done

Every phase's Done-when; § Size the host names the smallest host measured and what it carried; every
`scripts/validate_*.py` except `validate_guard_gaps.py` green; no harness file left in the tree.

## Retrospective

_Filled at implementation._
