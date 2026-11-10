---
id: cr-veyqctl-gitops-target
title: veyqctl manages a GitOps instance — its instances listed, veyq's half of an Argo CD repository written and upgraded
status: planned
satisfies: []
adrs: ["0126", "0096", "0085", "0098", "0064", "0050"]
invariants:
  - "a Compose instance behaves exactly as today — every existing command, flag and refusal; `veyqctl_selftest.py`'s Compose cases stay green unchanged"
  - "nothing is written into a repository before the release's manifest, its images and its chart verify under the release identity (Verify a release)"
  - "a secret value that exists is never generated again, and the config-secret key is never printed into the repository in plaintext"
  - "`upgrade` never goes down a release and enters a major only from its major entry (ADR 0085 decision 10, ADR 0096 decision 5)"
  - "nothing is pushed without `--push`, and nothing calls a forge's or a cloud provider's API"
  - "`veyqctl` stays one file, Python standard library only, running on Python 3.9 (ADR 0096 decision 2)"
---

## Goal

`veyqctl` today manages a Compose instance only. After this CR it also manages an instance that runs on a Kubernetes
cluster through Argo CD ([ADR 0126](../docs/decisions/0126-veyqctl-writes-a-gitops-repository-and-veyq-server-sets-up-its-cluster.md)
decisions 2–5 and 7): it lists the instances a user manages in `~/.config/veyq/` and reads each one's target from the
instance itself; `init` asks Compose or GitOps; on a GitOps instance `init` writes veyq's half of a repository the
operator's Argo CD syncs — the Application, the values, the namespace, the backup claim and the Secrets from the provider
chosen — and `upgrade`, `check` and `verify` keep it current by commit. Decision 6 (setting the server up from
`veyq-server`) is **not** in this CR: `veyq-server` exists since 2026-11-10 (`github.com/raddatzk/veyq-server`, public,
empty) and first needs its content — affenbande's server half as a Copier template, released and signed — which is
work in that repository; `veyqctl`'s side of decision 6 is a CR of its own after that.

## Context & constraints

- [ADR 0126](../docs/decisions/0126-veyqctl-writes-a-gitops-repository-and-veyq-server-sets-up-its-cluster.md) (this
  CR builds decisions 2–5 and 7), [ADR 0096](../docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)
  (the rules `upgrade` keeps), [ADR 0085](../docs/decisions/0085-one-major-moved-on-a-yearly-train.md) decision 10 (the
  major entry), [ADR 0098](../docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) (the
  chart's backup gate, which captures before the release change the commit causes), [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)
  (the chart), [ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md) (the config-secret key).
- Model: [release-and-deployment](../docs/crosscutting/release-and-deployment.md) § `veyqctl` (Compose), § Decided, not built.
- The operator's page: [Verify a release](../site/src/content/docs/run/verify-a-release.md) — what `verify` and
  `upgrade` run, as written.

### Measured substrate (`main` at `c35b16c0c`, 2026-11-10)

- `src/tools/veyqctl.py` 1929 lines, `veyqctl_selftest.py` 1564; the self-test runs in `checks.yml`'s `guards` job
  (`python3 src/tools/veyqctl_selftest.py`, Docker-free).
- Reusable as they are: `Source`/`GitHubSource`/`DirSource`/`HttpSource` and `open_source` (which releases are offered),
  `select`, `upgrade_target` and `newer_major` (the selector and the major entry), `fetch_manifest`/`parse_manifest`,
  `require_cosign`, `verify_manifest`, `verify_images`, `write_private`. Compose-bound: `State`/`read_state` (a home's
  `.env`), every `cmd_*` but `verify` and `check`.
- **The signed manifest names the images, not the chart** (v1.0's: `release`, `date`, `images.{app,admin,tools}`,
  `unscheduled`, `majorEntry`, `assets`). `parse_manifest` requires `images.app` and `images.admin` and ignores `tools`.
- The maintainer's repository (`affenbande`, private) holds veyq's half at `gitops/apps/veyq/`:
  `application.yaml` (chart `oci://ghcr.io/raddatzk/charts/veyq` by digest, a `# renovate:` comment naming the
  version), `values.yaml` (`image`, `admin.image`, `backup.image` each `tag` + `digest`), `resources/` (namespace,
  backup claim, SOPS Secrets through a KSOPS generator, the host-access component). Upgraded by hand to v1.0 on
  2026-11-10 exactly as phase 3's `upgrade` will do it; host access measured the same day (Argo CD 200 and the admin
  surface 200 through one tunnel, every NodePort and 6443 closed from outside).

### Measured at refine (2026-11-10, this Mac)

- `git`, `helm`, `kubectl`, `cosign`, `sops`, `age`, `kustomize` present; **`kubeseal` absent** — so the self-test
  drives every external program through the `Host` runner's stand-ins (as it drives `cosign` today), never the real
  one, and the `sealed-secrets` provider is proved by its stand-in plus one manual run where `kubeseal` exists.
- `veyqctl` already takes a global `--home` (Compose's instance directory, `args.home` → `host.workdir`). The order
  becomes: `--home` (a path, as today) wins, then `--instance <name>`, then the current instance in
  `~/.config/veyq/instances.json`, then `/opt/veyq`.
- The self-test runs in `checks.yml`'s `guards` job, which has no `helm`; the render of the written repositories runs
  in the `chart` job, which installs it.
- The maintainer's Mac reaches the cluster as context `raddatz.me` through one ssh tunnel
  (`affenbande`'s `scripts/connect.py`, measured 2026-11-10): phase 4's `check` against the live cluster needs that
  tunnel open.

## Decisions this CR takes

- **The chart is verified by its own signature**, not through the manifest: `upgrade` resolves
  `ghcr.io/<owner>/charts/veyq:<release>` to a digest once and verifies that digest under the release identity, as
  Verify a release does. Binding the chart into the manifest would change the asset set the lanes attest between the
  snapshot and its promotion (the chart is packaged at promotion); a later CR may move it there with the lanes.
  The tools image is read from `images.tools`, now required for a GitOps instance only (Compose never runs it).
- **An instance is the directory that holds `veyqctl.json`** for GitOps (ADR 0126's `veyq/veyqctl.json` is the default
  place `init` writes), `releases/` and `.env` for Compose. `veyqctl.json` holds the target, the release selector, the
  secrets provider, the paths of the Application and the values relative to it, and the chart and image references —
  so an existing layout (affenbande's `gitops/apps/veyq/`) is adopted by writing that file alone.
- **No YAML library**: `veyqctl` rewrites pins line by line, anchored on the keys it wrote (`targetRevision:` under the
  chart's source, `digest:`/`tag:` under each image), and refuses a file where an anchor is missing or ambiguous rather
  than guessing; `helm template` with the repository's values is the check that the result is still the chart's input.
- **A one-time `kubectl apply` of the Application** is what `init` prints for an existing cluster, never runs: the
  operator's Argo CD layout (an app of apps, an ApplicationSet, a plain Application) is theirs.

## Phases

- [ ] **1 instances** — `~/.config/veyq/instances.json` (`$XDG_CONFIG_HOME` if set): name → path, and the current one;
  `instances`, `use <name>`, `--instance <name>`; with no file, `/opt/veyq` as today. The target is read from the
  instance directory. `start`, `stop`, `status`, `logs`, `backup`, `restore`, `rollback`, `self-update`'s Compose
  part refuse on a GitOps instance, naming what does it there. `init` asks Compose or GitOps (and takes `--compose` /
  `--gitops <dir>` without asking); under Compose it is `install`. · Done when: the self-test covers the registry,
  the fallback, `--instance`, every refusal, and its Compose cases are unchanged and green.
- [ ] **2 gitops init** — for a cluster that exists: the contract check against a reachable cluster (ADR 0126
  decision 4: Argo CD, Gateway API ≥ v1.5 with the class the values name, a StorageClass, cert-manager ≥ 1.21 when
  TLS is its issuer), skipped with `--no-cluster-check` for a repository written before the cluster exists; then the
  release resolved and verified (manifest, three images, chart); then `veyqctl.json`, `application.yaml`,
  `values.yaml` (the domains asked), `resources/` (namespace, backup claim) and the Secrets for the provider chosen —
  `sops` (needs `sops` and the repository's `.sops.yaml`), `external-secrets` (a `SecretStore` name and the remote key
  names asked), `sealed-secrets` (`kubeseal` against the cluster), `none`. Values generated once; under every provider
  but `sops` handed over once in a file only the operator can read; the config-secret key named as the value to keep
  in a password manager; the deploy key printed, the `kubectl apply` of the Application printed. · Done when: the
  self-test writes a repository per provider into a temp dir and `helm template` renders each (in `checks.yml`'s
  `chart` job, which has `helm`); a second `init` over the same directory refuses to overwrite and never regenerates
  a value.
- [ ] **3 gitops upgrade, check, verify** — `verify`: manifest, images, chart under the release identity, nothing
  written. `check`: which release the next `upgrade` takes and which major the one after it would enter (as under
  Compose), and whether the provider's Secrets exist with every key when the cluster is reachable. `upgrade`: resolve,
  major entry, verify, rewrite the chart's `targetRevision` and the three images' `tag`/`digest`, `helm template`,
  commit with the release in the message; `--push` pushes. · Done when: the self-test drives `upgrade` against a
  mirror it writes (two releases, a major between them) over a repository from phase 2 and over a copy of affenbande's
  layout, and every refusal (a downgrade, a major skipped, a missing anchor, an unverified chart) leaves the repository
  untouched.
- [ ] **4 affenbande adopts it** — `veyqctl.json` written into `gitops/apps/veyq/` (the owner's repository; the owner's
  go for the commit and the push): `check` and `verify` against v1.0 green, `upgrade` a no-op on v1.0; the first real
  `upgrade` is the next release. · Done when: those three ran against the live repository and their output is
  recorded here.
- [ ] **5 docs** — a Run page for the GitOps target (EN, Server Owners) beside Set up a new instance and Upgrade; the
  Kubernetes page points at it; `veyqctl`'s docstring; the model's § `veyqctl` covers both targets and § Decided, not
  built keeps only decision 6; ADR 0126's `Built:` line. · Done when: the trust chain and `pnpm run check` in `site/`
  are green.

## Definition of done

`python3 src/tools/veyqctl_selftest.py` green with the Compose cases unchanged; the render check green in CI; the trust
chain and the site's check green; phase 4's runs recorded; ADR 0126 `Built: partly (not built: decision 6)`.
No use-case is satisfied: `veyqctl` is operator tooling, held by its self-test like the Compose half (ADR 0096).

## Open questions (owner)

None blocking. Decision 6 waits for `veyq-server`'s first signed release; this CR does not depend on it.
