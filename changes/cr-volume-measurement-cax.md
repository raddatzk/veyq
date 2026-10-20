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
harness the first CR, `cr-volume-measurement-local` (done and deleted 2026-10-18), built against a real CAX11, judges
the result by the rule that CR decided (§ Local pre-check states it), and writes the recommendation where operators
read it: `site/src/content/docs/run/plan.md` § Size the host, which today says "No CPU, memory or disk minimum is
documented". Then it deletes the harness.

**Blocked until** the owner has set up the server (§ What the owner provides). The local pre-check is done:
§ Local pre-check below holds its verdict, the procedure and what it changes for this run.

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

Filled by `cr-volume-measurement-local` (done 2026-10-18; that CR is deleted, everything it left that binds is here).

**The judging rule (its Q1, owner 2026-10-18).** A host carries a stage when, across that stage and all three
scenarios: no container is OOM-killed or restarted; peak host memory stays at or below 80 % of its RAM; no request
fails; on a desktop browser a 3,000-photo Chapter's list arrives within 2 s and its last thumbnail within 15 s. The
10,000 stage is reported against the same rule but does not decide the recommendation. `measure_volume.py judge
<dir>` applies it to one readings directory; its docstring says how each clause is read (an OOM is an `oom` event or a
`die` with any exit but 143; locally "host memory" is the recipe's five containers summed, against 80 % of 4 GiB).

**The local verdict** (2026-10-18, 20:03–23:44, this Mac's Docker VM, the recipe pinned to two vCPUs, every stage
started at load ≤ 3 with the owner's idle dev stack as the only neighbour). A local reading is a pre-check: an M4 Pro
core is much faster than an Ampere one, so the server times below are a floor for the CAX, never its answer.

| Stage | Server leg (warm median; SELECTs) | Browser, desktop | Browser, 4× throttle | Rule |
|---|---|---|---|---|
| photos 1,000 | 180 ms; 4,011; 2.3 MB, no `Content-Encoding` | list 160–218 ms, last thumbnail 14.9–15.5 s | last thumbnail 70–75 s | carries |
| photos 3,000 | 814 ms; 12,013; 7.0 MB | list 426 ms; **stopped at 1,370 of 3,000** thumbnails (1,630 presigns refused by Chromium, #488) | stopped at 2,178 | **fails — on the client** |
| photos 10,000 | 3.0 s (cold 1.9–3.1 s); 40,017; 23.4 MB | list 2.3–2.7 s; stopped at 1,544–1,656 | the page stopped answering | reported: fails on the client |
| tree 500 / 2,000 / 5,000 | 17 / 30 / 48 ms; 10 | root view 0.13 / 0.32 / 0.84–0.89 s; deep Chapter 0.12 / 0.23 / 0.42 s | root 0.35 / 1.1 / 3.2–3.4 s | carries |
| wide 2,000 | 30 ms; 10 | root 0.24–0.25 s, deep 0.25–0.26 s | 0.76–0.86 s | carries |
| members 20 / 200 | 19 / 39 ms; 32 / 213 (one per member, no ETag) | every row named and pictured at 0.50–0.65 s | 0.65–1.6 s | carries |
| members 1,000 | **unreachable**: the owner's identity-pin store is full at 444 pins (#491) | — | — | measured at 445 instead: 42 ms, 458 SELECTs; page complete at 0.83–0.88 s (×4: 2.3–2.7 s); carries |
| idle 30 min | — | — | — | carries; the app's memory 412 → 424 MB after its first five minutes (+2.9 %, flat) |
| five at once, 10,000 | app CPU mean 69 % of 200 %, MariaDB peak 76 %, no server-side failure | every device stopped (#488; the rig, below) | — | the host carried; the clients did not |
| upload 1,000 × 4 MB | 1.7 min, 593 photos a minute, 47 MB/s of plaintext | — | — | carries; the **app peaked at 0.91 GiB of its 1 GiB `mem_limit`** |

Memory never came near the budget: the recipe's summed peak was 1.46 GiB (the upload), 1.30 GiB with five devices on
the 10,000 Chapter, at most 1.23 GiB in every stage — 3.2 GiB was allowed. No container was OOM-killed or restarted
unbidden in any stage. Every request the server answered succeeded; every failure counted was the browser's own
(`net::ERR_INSUFFICIENT_RESOURCES`) or a page too busy to answer. **So by the rule the local host carries the household
except where the client gives out first**: the 3,000-photo clause fails because of #488, which no host fixes. Disk
after the run: Garage data 14.4 GB and meta 170 MB, MariaDB 330 MB, for 10,000 seeded photos (small originals), 1,000
phone photos, 7,001 nodes and 446 members.

**Hotspots, each an inbox row with the number that justifies it:** #488 the gallery's unbatched, unbounded presigns;
#489 decryption and rendering on the main thread, the grid not virtualized; #490 the media list's 1 + 4N queries,
unpaged, and no compressed answer anywhere; #491 the identity-pin store's 1 MiB cap. Seen and not worth a row yet: the
member list's one query per member and missing ETag (458 SELECTs, 42 ms at 445); the app at 91 % of its memory limit
while a phone uploads — the CAX run should watch the app there first.

**For this CR's run.**
- *Phase 2's member axis* runs to 1,000 as planned: the identity-pin store (built 2026-10-19, #491, ADR 0104) keeps a pin
  per entry, and its live proof seeded this harness's member stage to 1,000 on a local production-recipe instance (999
  members granted; the profile stage was stopped at 722 on the owner's word).
- *The gallery's browser leg changes shape* now that the windowed grid is built ([ADR 0105](../docs/decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md), #489): its harness metric is
  *viewport complete* and *end complete*, not "last thumbnail of all", and its targets replace the 15 s clause for a
  windowed grid.
- *#488 is fixed* (2026-10-19): the 3,000 Chapter loaded completely, its last thumbnail after 20.7 s desktop and 120 s at 4x,
  1,000 after 4.7 s and 23 s (this Mac at load 7–17, so indicative); the client rows of the table above predate it.
- *The five-at-once scenario* ran five Chromiums in one container on this Mac, which drove the Mac's load to 30: its
  client numbers say nothing, its server numbers stand. On the CAX the five browsers run here and the server runs
  there, so the clients no longer compete with each other's host — but they still share this Mac; the scenario's
  client half is only meaningful once #488 is fixed.
- *A browser wait needs a wall-clock guard too*: under the throttle the 10,000 Chapter kept the page's main thread busy
  for over 30 minutes, so the page-side stall clock never ran; `tests/e2e/measure/volume.ts` now stops a run whose page does
  not answer for `stallSecs`.
- *The L1 overlay moves with main*: on 2026-10-18 it gained `garage=info` (the wire sweep's control), which the
  measurement overlay removes again. Re-read `tests/e2e/stack/docker-compose.e2e.yml`'s diff before booting.

**The procedure, exactly as it ran** (`I` = an instance directory outside git; the readings go to `.run/measurements/`):
1. Images from the tree: `docker build -f infra/docker/Dockerfile -t veyq-vol:local .` and `docker build -f src/admin-web/Dockerfile -t
   veyq-admin-vol:local .` (after a merge, `npm run generate:api` in `src/web/` first, or the seeders do not compile).
2. `python3 scripts/measure_volume.py --instance I fixtures` (ImageMagick), `… boot --app veyq-vol:local --admin
   veyq-admin-vol:local` (lane L1's own install stages; ports 80/443/18081 free; recipe on vCPUs 2,3, doubles and
   browsers on 6–13), `… seed birth` (`birthInstance`; the generated owner credentials land in `I/accounts.json`).
3. Per stage, `… stage <photos|tree|wide|members> <N>`: seed to N, wait for load ≤ 3, then — sampler running — the
   server leg (five cold calls each after an app restart, a 60 s drift window, five warm, the 304 call, a drift window;
   refused when a window exceeds the idle baseline + 10) and the browser leg (3 runs × throttle 1 and 4), then the
   judge. The member stage raises the invite limit through the instance's `compose.override.yml` and `veyqctl start`
   while seeding and restores the default before measuring.
4. `… scenario idle --minutes 30`, `… scenario five --items 10000`, `… scenario upload --count 1000`.
5. `… teardown` (the instance's files move to `I/torn-<project>/`), then remove the two images.
On the CAX the sampler runs through `--prefix 'ssh <host>'`; `boot` is this Mac's and does not apply there (phase 1
installs through `veyqctl` on the server), so `instance.json` for the remote instance is written by hand or by phase 1.
Re-seed time for the full dataset locally: boot 20 s, photos about 3,200 a minute, tree about 3,800 nodes a minute,
members about 12 a minute end to end (20 joined, 31 profiled; Argon2id dominates).
The harness files phase 5 deletes are the ones listed there, plus `src/web/scripts/volumeHarness.mts`,
`src/web/scripts/volumePrelude.mts`, the `installPinnedFetch` half of `src/web/scripts/apiTransport.mts`, `src/env.d.ts` in
`src/web/tsconfig.scripts.json`'s `include`, and `measure` in `tests/e2e/tsconfig.json`'s `include`.

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
  leg, exactly as the local procedure. The invite limit is raised only while seeding members (§ Local pre-check,
  step 3), and the instance is restarted with defaults before measuring. · Done when: every stage judged by the
  rule, or an axis ended by decision 2.
- [ ] **3 scenarios**: idle, five at once, bulk upload (owner's go for the upload). · Done when: each judged by the
  rule, the disk readings of decision 3 recorded.
- [ ] **4 record**: decision 4's three places; the verdict compared with the local pre-check in one sentence in the
  retrospective (how far the local run was off is worth knowing for the next one). · Done when: the site check and
  the trust-chain guards green.
- [ ] **5 remove** (not before #489's reading at the V1 heavy stage — it measures with this harness): delete the harness (`src/web/scripts/seed-volume.mts`, `src/web/scripts/measure-volume.mts`,
  `scripts/measure_volume.py`, `tests/e2e/measure/`, the bench files, the `.gitignore` line for `.run/measurements/`);
  the owner deletes the server and the DNS record. · Done when: `git ls-files` shows none of the harness files;
  `npm run check` in `src/web/` green; the owner confirms the server is gone.

## Definition of done

Every phase's Done-when; § Size the host names the smallest host measured and what it carried; every
`scripts/validate_*.py` except `validate_guard_gaps.py` green; no harness file left in the tree.

## Retrospective

_Filled at implementation._
