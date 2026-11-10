---
title: Argo CD with veyqctl
description: veyqctl writes veyq's half of a repository your Argo CD syncs, and upgrades it by commit.
sources:
  - docs/decisions/0126-veyqctl-writes-a-gitops-repository-and-veyq-server-sets-up-its-cluster.md
  - docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md
  - docs/crosscutting/release-and-deployment.md
  - src/tools/veyqctl.py
---

On a Kubernetes cluster that **Argo CD** keeps in step with a Git repository, `veyqctl` manages veyq the same way it
does on Docker Compose — only its result is a commit, not a restart. It writes veyq's part of that repository once and
keeps it current: the Argo CD `Application` pinning the chart by digest, the chart's values pinning the three images, and
what the chart needs and does not create (the namespace, the backup gate's claim, the Secrets). Argo CD then applies
what you push. ([ADR 0126](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0126-veyqctl-writes-a-gitops-repository-and-veyq-server-sets-up-its-cluster.md).)

It does not set up the cluster itself: that is [`veyq-server`](https://github.com/raddatzk/veyq-server)'s job, not
built yet. Without it, the cluster is yours, and it must have what [Kubernetes](/run/kubernetes/) lists.

## What you need

On your machine: `veyqctl` ([Verify a release](/run/verify-a-release/) says how to fetch and check it), `git`, `helm`,
`kubectl` with a context for the cluster, `cosign` 3.1.3 or later, and the tool of the secrets provider you choose
(`sops` or `kubeseal`). A checkout of the repository Argo CD syncs.

On the cluster — `init` checks this before it writes anything and names every gap at once:

- **Argo CD**;
- **the Gateway API CRDs, v1.5 or later**, and the `GatewayClass` you name;
- **a StorageClass** for the claims;
- with the chart's ACME issuer (`--tls acme`), **cert-manager 1.21 or later**.

`--no-cluster-check` skips this, for a repository written before the cluster exists.

## Write the instance: `init`

```sh
veyqctl init --gitops <checkout>/apps/veyq \
  --domain veyq.example.org --gateway-class traefik --acme-email ops@example.org \
  --gateway-namespace traefik --gateway-pod-label app.kubernetes.io/name=traefik \
  --secrets external-secrets --secret-store vault --context my-cluster
```

`<checkout>` is your local clone of the repository Argo CD syncs, and `apps/veyq` the directory veyq's half goes into —
any directory inside the clone but its root, empty or not there yet. `init` without `--gitops` asks Compose or GitOps;
every option it does not get, the secrets provider included, it asks for at a terminal, and without one it refuses
and names the flag. In order, it:

1. finds the checkout the directory lies in (the directory may not exist yet; `init` refuses one that is not empty);
2. checks the cluster (above);
3. resolves the release (`--release`, default `latest`) and **verifies it before anything is written**: the release's
   signed manifest, the app, admin and tools images it names, and the chart — whose tag `veyqctl` resolves to a digest
   at the registry, verifies under the release identity, and whose `Chart.yaml` version it reads back;
4. renders the chart with the values it is about to write (`helm template`);
5. writes the directory, lists the instance (below) and prints what is left to you.

What it writes:

| File | What it holds |
|---|---|
| `application.yaml` | The Argo CD `Application`: the chart from `oci://ghcr.io/raddatzk/charts/veyq` by digest, its values and `resources/` from this repository; automated sync with self-heal, never prune |
| `values.yaml` | The domains, the three images by tag and digest, the Secrets' names, the gateway, TLS, and the NetworkPolicy peer (your Gateway's data-plane pods) |
| `resources/` | The namespace (`restricted`), the backup gate's claim (never pruned or deleted by Argo CD), the Secrets, a `kustomization.yaml` |
| `veyqctl.json` | Which release it follows (`release`: a pin, a major, or `latest`), the provider, the running release and its four pins |

Then **you** review it, commit and push, give Argo CD read access to the repository (a deploy key), and register the
`Application` once — `kubectl apply -f application.yaml`, or an app of apps that picks it up. `veyqctl` calls no
forge's API and applies nothing to the cluster.

### The Secrets

The chart reads two Secrets by name, `veyq-secrets` (the database passwords and the bundled store's keys) and
`veyq-config-secret-key`. `veyqctl` generates every value once and writes them for the provider you choose:

| `--secrets` | In the repository | The values |
|---|---|---|
| `sops` | Both Secrets encrypted with the repository's `.sops.yaml`, and a KSOPS generator — Argo CD's repo server decrypts them | Only in the repository, encrypted |
| `external-secrets` | `ExternalSecret`s reading each key from `<prefix><key>` (`--remote-prefix`, default `veyq-`) through the store you name (`--secret-store`, `--secret-store-kind`): any store External Secrets Operator speaks — Azure Key Vault, AWS Secrets Manager, Google Secret Manager, HashiCorp Vault, OpenBao, 1Password, Bitwarden … | Handed over once: put each under its remote key in the store |
| `sealed-secrets` | Both Secrets sealed with your cluster's controller key (`kubeseal`) | Handed over once |
| `none` | Nothing | Handed over once: create the two Secrets yourself |

"Handed over once" is `~/.config/veyq/<name>.secrets.json`, readable by you alone, outside the repository, never
overwritten: put the values where they belong, then move the file off the machine. **Keep the config-secret key in a
password manager as well**, whatever the provider: without it no backup can be read
([ADR 0050](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0050-operator-config-secret-encryption.md)).
`veyqctl` never calls a cloud provider's API, and never generates a value again — a second `init` into the same
directory is refused.

### A veyq half you wrote by hand: `--adopt`

```sh
veyqctl init --gitops <checkout>/apps/veyq --adopt --secrets sops
```

writes only `veyqctl.json` — and only once the chart digest in the `Application` and the three image digests in the
values are exactly those of a release that verifies. Its tags name the running release; `veyqctl` records the major it
belongs to as the selector. Your files keep their comments and layout; an upgrade rewrites only the pins.

It takes over this one shape and nothing else, by design: a chart pinned by version, images by tag, a release
installed with `helm install` rather than from a repository, or a snapshot is refused, naming what differs. Bring
the files into this shape first ([Verify a release](/run/verify-a-release/) pins by digest), or write a fresh
instance with `init` and give its Secrets your running instance's values — never new ones: the database and every
backup are bound to them.

## Which instance a command acts on

`init` lists each instance it creates in `~/.config/veyq/instances.json` and makes it current. `veyqctl instances` lists
them with their target and path, `veyqctl use <name>` switches, `--instance <name>` acts on another one once, and
`--home <path>` names one by its path. The target is the instance's: a directory with `veyqctl.json` is a GitOps one, so
no command names it.

## Keep it current: `check`, `verify`, `upgrade`

- **`veyqctl check`** — which release the next `upgrade` takes, and a newer major beyond the selector; any **drift** (a
  pin the files no longer hold); and, with the cluster reachable, whether both Secrets exist with every key.
- **`veyqctl verify`** — the selected release's manifest, three images and chart, nothing written.
- **`veyqctl upgrade`** — under the same rules as on Compose ([Upgrade](/run/upgrade/#which-release-upgrade-takes)):
  it never goes down, and enters a major only from the release that major names as its entry. It refuses drift and
  uncommitted changes in its files, verifies the release, rewrites the four pins and the three tags (each found exactly
  once, or nothing is changed), renders, and commits those three files alone. `--push` pushes; otherwise you push.
  Argo CD applies the commit, and the chart's backup gate captures the database before the new release migrates it
  ([Upgrade](/run/upgrade/), [Backup and restore](/run/backup-and-restore/#under-kubernetes)).

**On a schedule, as a pull request.** A job in the repository's own pipeline can run `veyqctl upgrade` on a branch of
its own and open a pull request, the way Renovate proposes the cluster's other pins; merging it is the upgrade. Never
let it merge itself: an upgrade replaces the web client that holds your users' keys, so a person reads the release
notes first. The job fetches `veyqctl` from veyq's newest release and verifies it first (`cosign verify-blob` against
`veyqctl.bundle`, as [Verify a release](/run/verify-a-release/) says). Keep other updaters off veyq's pins — a second
mover on the same lines is drift, which `upgrade` refuses. On GitHub, such a job needs the repository
setting that lets Actions create pull requests (Settings → Actions → General → Workflow permissions); the maintainer
runs one weekly, beside Renovate.

`start`, `stop`, `status`, `logs`, `backup`, `restore`, `rollback` and `self-update` are Compose's: on a GitOps instance
each refuses and names what does that job there (Argo CD, `kubectl`, the backup gate, the restore Job).

## How far this is proven

`veyqctl`'s self-test drives every step and every refusal above against stand-ins for `git`, `kubectl`, `helm`,
`sops`, `kubeseal` and the registry, each refusal checked by breaking the code it guards; the pipeline renders the values
`init` writes with the real `helm` against the chart, for every TLS choice with and without the bundled store. Run
by hand on 2026-11-10, against the published releases:

- `init --adopt` of the maintainer's existing veyq half on single-node k3s (release 1.0): every pin verified, the
  chart's digest resolved at ghcr.io equal to the one pinned; then `verify`, `check` (both Secrets present with every
  key) and `upgrade` (nothing to do);
- `init` into an empty repository against that cluster: the cluster check passed, the release verified, the chart
  rendered, the files written; with `--secrets sops`, both Secrets encrypted by `sops` 3.13.3 and decrypted again;
- `upgrade` of a repository written at 0.2480 to 1.0 — major 1 entered from its entry — as one commit of the three
  files.

Not yet run: an `upgrade` of a live instance that Argo CD then applies, `--secrets external-secrets` and
`sealed-secrets` against a cluster that has them, and Flux.
