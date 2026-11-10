# ADR 0057 — A release is identified by the tree, and the deployment recipe is its assets

- Status: Accepted
- Built: yes (§ 2's `v<major>.<minor>` ref and § 3's GitHub Release are now set by ADR 0091's promotion,
  `release.yml`, first run 2026-11-10: v0.2480 and v1.0)
- Date: 2026-08-10
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)
- Replaced in part: § 1 by ADR 0091 (built: the snapshot and its promotion), § 7 by ADR 0091 (not built); § 3 by ADR
  0096 (built)

## Context

[ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) §6 makes a backup the precondition of an
upgrade and states the way back in one sentence: restore the backup taken before it. That sentence assumes that the
previous release has a **name**, and that the artifacts needed to run it can be obtained **under that name**. Neither
held when this was decided:

1. **No release could be built.** The publish read a `VERSION` file that did not exist, and the operator's example
   named an image tag in a shape the pipeline never emitted.
2. **The version was a function of the registry, not of the tree.** The workflow asked the registry for the highest
   published minor and incremented it: the same commit published twice was two versions, and a checkout could not say
   which version it was.
3. **The registry query failed into a silent republish.** A transient API error read as *"nothing published yet"* and
   republished an already-delivered release under its own name — so an operator who pinned that tag received different
   bits with no signal, exactly the assumption ADR 0056 §6 rests on.
4. **An image tag had no git ref**, so there was no ref at which the deployment files belonging to an image could be
   found.
5. **A shipped artifact already carried the gap:** the backup manifest's `app.version` was read from an environment
   variable never set where the capture runs, so the field that would name a rollback target was always `null`.

A version already exists on the wire: [ADR 0032](0032-capability-discovery-version-gating.md) puts
`version: { major, build }` on the pre-auth `GET /api/setup` as the client-compatibility gate. The precise release
answers a different question, and putting it on that pre-auth surface is the mistake Decision 4 prevents.

A first form of Decision 1 kept `major.minor` in `VERSION` by hand and published on every push to `main`: the first
push was release `1.0`, and every push after it a red run until someone committed a bump, with no way to try the
publish path before it named something for good.

## Decision

**1. The release version comes from the tree, and publishing is a deliberate act.** A `VERSION` file at the
repository root holds the **major** alone; the minor is the number of commits since the one that last changed
`VERSION`, so it restarts at every major. The version is `<major>.<minor>`, both halves a function of the commit. A
checkout answers what it is, no network call decides a version, no commit exists only to move a number, and the
derivation is checkable locally. Counting every reachable commit instead is just as offline, but put the first release
at `0.1944`, a number that says nothing about its major. The count needs the full history.

Nothing is published by a push or a pull request; both build and test the release's images and write to no registry.
A publish is dispatched by hand from `main` or `release/<major>.x`, as one of two kinds:

- **A snapshot** — `<major>.<minor>-SNAPSHOT` (and `sha-<sha>`), signed, with no `<major>`, no `latest` and no git ref.
  It is for trying a build on an instance that may be thrown away, and **never a rollback target**: an instance that
  ran one may hold a migration that changes before it is released, which Flyway's checksum check then refuses.
- **A release** — `<major>.<minor>`, `<major>`, `sha-<sha>`, and `latest` from `main`, signed. A release whose name
  already exists **fails** rather than replacing it, so a republish is an error instead of a silent substitution.

**Major `0` is pre-release.** A `0.<minor>` release exercises the publish path end to end — signature, git ref, the
refusal to republish — and promises no upgrade path. The migration set is frozen by the first release of a major ≥ 1,
which the owner declares on the migration pin file's `release:` line
([backend/CLAUDE.md](../../src/backend/CLAUDE.md) § Migrations); a `0.x` release does not move that line.

**2. A release has exactly one git ref, `v<major>.<minor>`,** created by its publish. One release, one name, one tree.
A snapshot has none: it names a build, not a release.

**3. The deployment recipe is the release's assets; the binding is the release, not the image.** A release carries
its recipe as assets of an immutable GitHub Release on `v<major>.<minor>`, beside a signed manifest; what the release
carries is [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision 3. No image carries a
recipe.

- **The recipe cannot drift from the images it describes.** The manifest names the digests that release deploys, and
  an immutable release changes neither after it is published.
- **The images' signatures bind the images; the manifest's signature binds the files**, so a source decides which
  releases are offered, never what one contains (ADR 0096 decision 7).
- **A verification is only as narrow as the identity it pins.** The documented `cosign verify` pins the certificate
  identity to the workflow that publishes, in `raddatzk/veyq`, on `main` or `release/<major>.x`, with GitHub's OIDC
  issuer. A regex over the repository accepts a signature from any workflow on any ref, and a placeholder for the
  upstream would verify a fork just as well, so it is written out literally. The publish accepts exactly those two
  branch shapes, so what signs and what verifies agree. Images are deployed **by the verified digest**, never by the
  tag, because a tag can move between the verify and the pull.

**4. One major, and the precise release never pre-auth.** The release major *is* the protocol major
([ADR 0085](0085-one-major-moved-on-a-yearly-train.md) decision 1): both count the same event, a removal, and two
counters for one event drift. What stays apart is the precision.

- The **protocol version** (`major` / `build`, pre-auth, ADR 0032) answers *"can these two sides talk to each
  other?"* — also for a native client that drifts from its server because a store ships it. It must be readable
  **before login**, where its gate fires.
- The **release version** answers *"which bits are running, and what do I roll back to?"* It is **operator
  information**: readable by the Server Owner, never on the pre-auth surface. The capability manifest already discloses
  the feature set; what a precise release adds is a **CVE list**, and a self-hosted instance is the kind that upgrades
  late.
- `build` stays a hand-maintained diagnostic that **gates nothing** (ADR 0032 § Decision 1), its contract text saying
  so. It is not removed: once a native client exists, a pre-auth build number is what answers *"which server build is
  this app talking to?"*.
- **The built client is pre-auth too.** The web bundle is served to anyone, so a value compiled into it is as public as
  one in `GET /api/setup`. The release stamp enters an image only in the stage a publish ships, never in a stage that
  compiles a client, and what a client writes about itself — an export manifest's `appVersion`
  ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)) — is
  the **major**. The bundle's content hashes still fingerprint a build for whoever catalogues the public images; that
  takes a catalogue, where a compiled number would take a `grep`.

**5. A field that cannot be true is not shipped.** The backup manifest's `app.version` is sourced from the instance
being captured, or it is removed. A field that is structurally always `null` is a claim with nothing behind it, in the
one artifact whose job is to still be trustworthy a year later.

**6. A release pins what it runs and what it is built from.** A signed recipe that names a mutable tag signs nothing
about what actually runs. So every third-party image is pinned by digest at run time (the compose recipe, the chart
values, the backup tool's helper images), written `repo:tag@sha256:…` so a human can still read the version; and
everything a release is built from is pinned at build time — base images by index digest, downloaded tools by SHA-256,
the backend's dependencies by Gradle verification, the build's actions by commit SHA with the full version as a comment
(a tag can be moved; a major-only comment goes stale at the first bump). The list of pins is
[release-and-deployment](../crosscutting/release-and-deployment.md). Bumping a pin is part of cutting a release, not
something that happens on its own.

**7. An upgrade is proven before it is published.** A release does not only claim that it upgrades an instance of its
predecessor; a run shows it ([Upgrade](../../site/src/content/docs/run/upgrade.mdx) is the operator's procedure):

- **The proof.** The predecessor's own build writes sealed content into a fresh instance; ADR 0056's tool captures it;
  the capture is restored onto the **candidate**, which migrates it up at its start (ADR 0056 §7); the candidate must
  then open the restored config-secret key, and the **candidate's** suites must open the predecessor's content and
  assert it — names, media, files — not merely that nothing failed, because a misread sealed field degrades to
  empty, not to an error.
- **The predecessor** is the commit's first parent until the `release:` line is set, and from then on the newest
  `v<major>.<minor>` tag the first parent contains. `release:` names a migration, not a tag, so a set release with no
  such tag refuses the run rather than guess. A release is proved a second time from its major entry
  ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md) decision 10) when that is not the predecessor already.
- **Before the first release it gates nothing.** A baseline migration may still change then, so a red proof informs;
  the change that sets `release:` makes it gate.
- **The Helm path is a check of its own**: what breaks a `helm upgrade` is an immutable field, not the data
  ([ADR 0064](0064-kubernetes-helm-deployment-target.md)'s chart-upgrade check, against the same predecessor).

## Consequences

- **A release becomes a deliberate act.** Bumping `VERSION` is reserved for a new major. Nothing is released by
  pushing, and nothing is released twice.
- **Minors have gaps, and say nothing about size.** `1.42` → `1.57` means fifteen commits, not fifteen releases; a
  docs-only commit still counts. A `release/<major>.x` branch counts its own history and can reach a number `main`
  already released — that publish is refused, red rather than wrong.
- **`VERSION` is touched only to change the major.** Any commit that touches it restarts the minor at `0`; the next
  release would reuse a released number, and the refusal to republish turns that red. It cannot ship silently.
- **The upgrade procedure gains a nameable rollback target and a way to obtain the recipe that belongs to a version.**
- **The recipe has two homes, the tree and the release, and the release's is produced from the tree** — derived from
  the compose file, so a file the recipe gains cannot be left out of a release.
- **A pin trades silent patches for explicit ones.** A tag-pinned image picked up patch releases on the next pull; a
  digest-pinned one does not until the release bumps it. That is the intended trade: the same silent path would deliver
  a compromised tag just as quietly. An updater proposes the bumps; what none reaches is bumped by hand, and a guard
  holds one `repo:tag` to one digest everywhere.
- **Rejected — deriving the version from the registry.** It makes the released artifact the authority on its own
  name, which is circular; it cannot be checked without the network; and its error path republishes silently
  (Context 3). A version that a `git checkout` cannot answer is not an identity.
- **Rejected — a hand-kept minor in `VERSION`, published on every push.** A bump commit per release, and a red run for
  every commit in between.
- **Rejected — publishing every push to `main` as a release.** It removes the red runs by making every commit a
  release, and with it the line between "built" and "promised"; a rollback target would be any commit at all.
- **Rejected — fetching the recipe from a raw URL at the matching ref.** A ref can be moved by anyone with write
  access; an immutable release's assets cannot change after they are published.
- **Rejected since — the recipe inside the image**, this decision's first form: the image carried the files a
  hand-kept list named and not the `garage.toml` the compose file reads, and the operator needed the repository for
  backups regardless. [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) records why it left.
- **Rejected — putting the release version on the pre-auth `GET /api/setup`.** It would make version disclosure —
  and therefore applicable-CVE disclosure — unauthenticated on every instance, for a convenience the Server Owner
  already has authenticated.
- **Rejected — removing `build` from the wire.** It gates nothing, so its staleness costs nothing observable; removing
  a `required` field to fix a sentence of prose is an expensive remedy, and would be undone when a native client
  arrives (Decision 4).

## Related

- [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) — the backup that an upgrade begins with, and the rollback that needs a name
- [ADR 0032](0032-capability-discovery-version-gating.md) — the protocol version, whose major this one shares and whose pre-auth surface never carries the precise release
- [ADR 0085](0085-one-major-moved-on-a-yearly-train.md) — when that one major moves, and which majors a client speaks
- [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md) — why a workflow that has never run proves nothing
- [uc.admin.identify-instance-release](../features/admin/use-cases/uc.admin.identify-instance-release.md) — the operator-facing half
- Run area of the docs site: [Verify a release](../../site/src/content/docs/run/verify-a-release.md) — the operator's
  signature check, [Set up a new instance](../../site/src/content/docs/run/new-instance.mdx) — the stack the recipe
  describes, and [Upgrade](../../site/src/content/docs/run/upgrade.mdx)
- Security area of the docs site: [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md)
