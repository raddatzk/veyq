# ADR 0057 — A release is identified by the tree, and the deployment recipe travels in the image

- Status: Accepted
- Date: 2026-08-10

## Context

[ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) §6 makes a backup the precondition of an
upgrade and states the way back in one sentence: restore the backup taken before it. That sentence assumes two
things this repository does not provide — that the previous release has a **name**, and that the artifacts
needed to run it can be obtained **under that name**. Neither holds, and the gap is not a missing feature but a
set of claims already written down that nothing performs.

Five facts, measured on this tree:

1. **No release has ever been built.** [`container.yml`](../../.github/workflows/container.yml)'s first job
   runs `major="$(tr -d '[:space:]' < VERSION)"`, and there is no `VERSION` file — so the job dies at its first
   command. `VEYQ_IMAGE=ghcr.io/<owner>/veyq:1.0.0` in [`infra/.env.example`](../../infra/.env.example)
   therefore names an artifact with no producer, in a three-part shape this pipeline never emits (it publishes
   `<major>`, `<major>.<minor>`, `sha-<sha>`, `latest`).
2. **The version is a function of the registry, not of the tree.** The workflow asks the registry for the
   highest published minor and increments it. The same commit published twice is two versions, and a checkout
   cannot answer which version it is.
3. **The registry query fails into a silent republish.** `gh api … || gh api … || printf '[]'` makes a
   transient API error indistinguishable from *"nothing published yet"*, and both paths end at `${major}.1` —
   republishing an already-delivered `1.1` under its own name. An operator who pinned that tag then receives
   different bits without any signal, which is exactly the assumption ADR 0056 §6 rests on.
4. **An image tag has no git ref.** A branch publish produces the image tag `<major>.<minor>` and no
   `v<major>.<minor>`; the repository carries no tags at all. There is no ref at which the deployment artifacts
   belonging to a given image can be found.
5. **The gap is already inside a shipped artifact.** `scripts/instance_backup.py` writes
   `"app": {"version": os.environ.get("VEYQ_VERSION") or None}` into every backup manifest. `capture` runs on
   the **host**, outside the container, where that variable is never set — so the field that would name a
   rollback target is always `null`, beside a `database.schemaVersion` that is genuinely read from the
   instance.

A sixth fact shapes the decision rather than motivating it: a version already exists on the wire.
[ADR 0032](0032-capability-discovery-version-gating.md) puts `version: { major, build }` on the pre-auth
`GET /api/setup` as the client-compatibility gate. It is a different number answering a different question, and
merging the two is the obvious mistake this ADR exists to prevent.

## Decision

**1. The release version comes from the tree.** A `VERSION` file at the repository root holds `major.minor`,
and that value — not a registry count — is what a publish stamps. A publish whose tag already exists **fails**
rather than replacing it. The cost is one bump commit per release, which is what a release already is. What it
buys: a checkout answers what it is, no network call decides a version number, a republish is an error instead
of a silent substitution, and the whole derivation is **checkable locally** — which the registry form can never
be, and which is the difference between a provable change and an asserted one in a repository with no remote.

**2. A published image has exactly one git ref, `v<major>.<minor>`,** created by the publish. One release, one
name, one tree.

**3. The deployment recipe travels inside the image.** `infra/docker-compose.prod.yml` and
`infra/.env.example` are copied into the runtime image, and a tool about to run a given image reads them **out
of that image** rather than fetching them from a URL. Two things follow that no fetch-by-ref scheme provides:

- **The recipe cannot drift from the image it describes.** Today the operator holds a copy taken once, by hand,
  and a version that adds a service or a required variable has no delivery path to them at all.
- **It inherits the image's signature.** The publish signs the manifest keylessly with cosign, so a recipe
  extracted from a verified image is verified material, with no second trust path for the files beside the
  image. **A verification is only as narrow as the identity it pins.** The operator's documented
  `cosign verify` pins the certificate identity to `container.yml` in `raddatzk/veyq`, on `refs/heads/main`
  or `refs/heads/release/<major>.x`, with GitHub's OIDC issuer. A regex over the repository accepts a
  signature from any workflow on any ref. The upstream is written out literally, because a placeholder
  would verify a fork just as well. The publish accepts exactly those two branch shapes, so what signs and
  what verifies can agree. Both images are verified, and the recipe is extracted and deployed **by the
  verified digest**, never by the tag, because a tag can move between the verify and the pull.

**4. There are two version notions and they are never merged.**

- The **protocol version** (`major` / `build`, pre-auth, ADR 0032) answers *"can these two sides talk to each
  other?"* — for the web client, and for the native client that will drift from its server because a store, not
  the server, ships it. It must be readable **before login**, which is where its gate fires, so it stays where
  it is.
- The **release version** (`1.7` — the image tag) answers *"which bits are running, and what do I roll back
  to?"* It is **operator information**: readable by the Server Owner, never on the pre-auth surface. The reason
  is specific rather than general caution. The capability manifest already discloses this instance's feature set
  to any visitor, so a version number adds little *there*; what a precise release number adds is a **CVE list**,
  and a self-hosted instance is precisely the kind that upgrades late.
- `build` stays a hand-maintained diagnostic that **gates nothing** (ADR 0032 § Decision 1). Its contract text
  is corrected to say so, instead of claiming a bump on every additive change that nothing performs. It is not
  removed: the moment a native client exists, a pre-auth build number is what answers *"which server build is
  this app talking to?"*, and removing it now would mean re-adding it later at the cost of two `major` bumps
  instead of none.

**5. A field that cannot be true is not shipped.** The backup manifest's `app.version` is sourced from the
instance being captured, or it is removed. A field that is structurally always `null` is a claim with nothing
behind it, and it sits in the one artifact whose entire job is to still be trustworthy a year later.

**6. A release pins what it runs and what it is built from.** A signed recipe that names a mutable tag signs
nothing about what actually runs. So:

- **At run time**, every third-party image is pinned by digest, written `repo:tag@sha256:…` so a human can
  still read the version. That covers the compose recipe, the chart values, and the helper images the backup
  tool starts.
- **At build time**, the Dockerfile bases are pinned by their multi-arch index digest. The OpenAPI generator
  JAR is checked against a pinned SHA-256 before it runs, because its npm wrapper checks nothing. The Gradle
  distribution carries its `distributionSha256Sum`, and the backend's dependency graph is held by Gradle
  dependency verification. The surfaces run one generator version.

Bumping a pin is part of cutting a release, not something that happens on its own.

## Consequences

- **A release becomes a deliberate act.** Bumping `VERSION` is the release; the publish is its mechanical
  consequence. Nothing is released by pushing, and nothing is released twice.
- **The upgrade procedure gains the two things it was missing** — a nameable rollback target, and a way to
  obtain the recipe that belongs to a version. That procedure is not decided here; this ADR only makes it
  possible for one to exist.
- **Every deployment artifact now has two homes, and they must agree.** `infra/docker-compose.prod.yml` is both
  a file in this tree and a file inside the image. The image's copy is authoritative for a running instance; a
  guard holds the two to each other, in the same shape as
  `scripts/validate_config_secret_persistence.py` holding three files to one path.
- **Nothing here is observed until a publish runs.** This repository has no remote and no workflow has ever
  executed, so every statement about what the publish *does* is a statement about a file rather than about a
  run. What is locally provable is the tree-side half: the version derivation, the image building, the recipe
  being extractable from the built image, and that extracted recipe resolving under `docker compose config`.
  The publish path stays an explicitly stated gap, in the same shape as the coverage flip that
  [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md) leaves owed.
- **A pin trades silent patches for explicit ones.** A tag-pinned `mariadb:11.4` picked up patch releases on
  the next pull; a digest-pinned one does not until the release bumps it. That is the intended trade: the
  same silent path would deliver a compromised tag just as quietly. The bump needs an updater:
  [`.github/dependabot.yml`](../../.github/dependabot.yml) runs Dependabot's `docker`, `docker-compose` and
  `helm` ecosystems over the Dockerfiles, both compose files and the chart values — each one's reach read from
  dependabot-core's source, not assumed, and never observed, because the repository has no remote. Nothing
  reaches the backup tool's image constants, so those are bumped by hand; `scripts/validate_image_pins.py` holds
  one `repo:tag` to one digest everywhere, which turns a bump that misses one of its sites red. It is remembered, not measured, that Dependabot does not regenerate Gradle's
  verification metadata, so a Gradle bump regenerates it by hand. The metadata holds checksums only, trusted
  on first write, which is the same trust the generator JAR's pin has.
- **Rejected — deriving the version from the registry.** It makes the released artifact the authority on its own
  name, which is circular; it cannot be checked without the network; and its error path republishes silently
  (Context 3). A version that a `git checkout` cannot answer is not an identity.
- **Rejected — fetching the recipe from a raw URL at the matching ref.** It works, but it requires the git tag
  *and* a second trust decision about unsigned files served beside a signed image, and it fails for exactly the
  publishes that have no tag. Extraction from the image needs neither.
- **Rejected — putting the release version on the pre-auth `GET /api/setup`.** It would make version disclosure
  — and therefore applicable-CVE disclosure — unauthenticated on every instance, to buy a convenience the
  Server Owner already has authenticated.
- **Rejected — removing `build` from the wire.** It gates nothing, so its staleness costs nothing observable;
  removing a `required` field to fix a sentence of prose is an expensive remedy for a cheap defect, and it
  would have to be undone when a native client arrives (Decision 4).

## Related

- [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) — the backup that an upgrade begins with, and the rollback that needs a name
- [ADR 0032](0032-capability-discovery-version-gating.md) — the protocol version this one must not be merged with
- [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md) — why a workflow that has never run proves nothing
- [uc.admin.identify-instance-release](../features/admin/use-cases/uc.admin.identify-instance-release.md) — the operator-facing half
- [infra/README](../../infra/README.md) — the stack the recipe describes
