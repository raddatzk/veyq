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
| `application.yaml` | The Argo CD `Application`, named `veyq` (`--application-name`; `--name` only names the instance on your machine): the chart from `oci://ghcr.io/raddatzk/charts/veyq` by digest, its values and `resources/` from this repository; automated sync with self-heal, never prune |
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

## A veyq half that exists already

`init` writes a fresh directory and refuses one that holds files. If veyq already runs from your repository, write
`veyqctl.json` beside its files yourself; from then on `veyqctl` manages them. Never run `init` over it to "start
again": it would generate new Secrets, and the database and every backup are bound to the ones you have.

**The shape `veyqctl` manages.** The directory holds:

- the Argo CD `Application`, with exactly one chart source `oci://<registry>/<owner>/charts/veyq` pinned by digest
  (`targetRevision: sha256:…`);
- the chart's values, pinning the three images by digest — each under its key, `image.digest`, `admin.image.digest` and
  `backup.image.digest` — with each `tag` naming the same release.

Anything else — a chart pinned by version, images by tag, an instance installed with `helm install` rather than from a
repository, a snapshot — is brought into this shape first: [Verify a release](/run/verify-a-release/) resolves and
verifies the four digests.

**`veyqctl.json`**, beside them:

```json
{
  "target": "gitops",
  "release": "1",
  "secrets": "sops",
  "application": "application.yaml",
  "values": "values.yaml",
  "chart": "ghcr.io/raddatzk/charts/veyq",
  "namespace": "veyq",
  "context": "",
  "running": "1.0",
  "pins": {
    "chart": "sha256:…",
    "app": "sha256:…",
    "admin": "sha256:…",
    "tools": "sha256:…"
  }
}
```

| Field | What it is |
|---|---|
| `release` | The selector `upgrade` follows: `<major>.<minor>` pinned, `<major>` its newest, `latest` the newest of all, one major per run |
| `secrets` | Where your Secrets come from: `sops`, `external-secrets`, `sealed-secrets` or `none` — `check` looks for both of the chart's Secrets either way |
| `application`, `values` | The two files' names, beside `veyqctl.json` |
| `chart` | The chart's repository, without `oci://` |
| `namespace`, `context` | Where `check` looks for the Secrets; an empty context is `kubectl`'s current one |
| `running`, `pins` | The release your files pin, and its four digests exactly as they stand in them |

Then run `veyqctl check` (with `--home <that directory>` once; `init` is what lists an instance). It verifies that
`pins` are the running release's as it was signed and that your files hold them, and names whatever differs.
`upgrade` refuses to start from a release that does not verify, because what it may do next — never down, a new major
only from its entry — stands on that release.

**A pipeline that proposes upgrades.** Like Renovate, a scheduled job can run `upgrade` on a branch and open a pull
request; merging it is the upgrade. On GitHub Actions, with the repository setting that lets Actions create pull
requests:

```yaml
on:
  schedule: [{ cron: "41 5 * * 1" }]
  workflow_dispatch:
permissions: { contents: read }
jobs:
  propose:
    runs-on: ubuntu-latest
    permissions: { contents: write, pull-requests: write }
    env:
      INSTANCE: apps/veyq   # the directory holding veyqctl.json
      IDENTITY: ^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$
      GH_TOKEN: ${{ github.token }}
    steps:
      - uses: actions/checkout@v7          # pin each action by its commit
      - uses: sigstore/cosign-installer@v4
        with: { cosign-release: v3.1.3 }
      - uses: azure/setup-helm@v4          # or Helm's tarball against its published sha256
      - run: |                             # veyqctl from veyq's newest release, verified before it runs
          tag="$(gh release view --repo raddatzk/veyq --json tagName --jq .tagName)"
          for f in veyqctl veyqctl.bundle; do
            curl -fsSLo "$RUNNER_TEMP/$f" "https://github.com/raddatzk/veyq/releases/download/$tag/$f"
          done
          cosign verify-blob --bundle "$RUNNER_TEMP/veyqctl.bundle" \
            --certificate-oidc-issuer https://token.actions.githubusercontent.com \
            --certificate-identity-regexp "$IDENTITY" "$RUNNER_TEMP/veyqctl"
      - run: |
          git config user.name veyqctl; git config user.email veyqctl@users.noreply.github.com
          before="$(git rev-parse HEAD)"
          git switch -c veyq/upgrade
          python3 "$RUNNER_TEMP/veyqctl" --home "$INSTANCE" upgrade
          [ "$(git rev-parse HEAD)" = "$before" ] && exit 0
          release="$(jq -r .running "$INSTANCE/veyqctl.json")"
          git branch -m "veyq/upgrade-$release"
          git push --force-with-lease origin "veyq/upgrade-$release"
          gh pr create --head "veyq/upgrade-$release" --title "veyq $release" \
            --body "Read https://github.com/raddatzk/veyq/releases/tag/v$release before merging."
```

Never let it merge itself (below), and keep other updaters off these lines: a second mover is drift, which `upgrade`
refuses.

## Which instance a command acts on

`init` lists each instance it creates in `~/.config/veyq/instances.json` and makes it current. `veyqctl instances` lists
them with their target and path, `veyqctl use <name>` switches, `--instance <name>` acts on another one once, and
`--home <path>` names one by its path. The target is the instance's: a directory with `veyqctl.json` is a GitOps one, so
no command names it.

## Keep it current: `check`, `verify`, `upgrade`

- **`veyqctl check`** — which release the next `upgrade` takes, and a newer major beyond the selector; any **drift** (a
  pin the files no longer hold); whether `veyqctl.json`'s pins are the running release's as it was signed; and, with
  the cluster reachable, whether both Secrets exist with every key.
- **`veyqctl verify`** — the selected release's manifest, three images and chart, nothing written.
- **`veyqctl upgrade`** — under the same rules as on Compose ([Upgrade](/run/upgrade/#which-release-upgrade-takes)):
  it never goes down, and enters a major only from the release that major names as its entry. It refuses drift,
  uncommitted changes in its files and a running release whose pins do not verify, verifies the new release, rewrites the four pins and the three tags (each found exactly
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

- the maintainer's existing veyq half on single-node k3s (release 1.0, its `veyqctl.json` written once beside files
  written by hand): `check` verified its four pins as release 1.0's — the chart's digest resolved at ghcr.io equal to the
  one pinned — and found both Secrets with every key; `verify` passed; `upgrade` had nothing to do;
- the whole server rebuilt, then `init --gitops --secrets sops` writing veyq's half into the maintainer's repository
  against the fresh cluster: Argo CD synced it, KSOPS decrypted both generated Secrets, the instance came up with both
  hosts certified, and `check` confirmed release 1.0's pins and both Secrets;
- `init` into an empty repository against that cluster: the cluster check passed, the release verified, the chart
  rendered, the files written; with `--secrets sops`, both Secrets encrypted by `sops` 3.13.3 and decrypted again;
- `upgrade` of a repository written at 0.2480 to 1.0 — major 1 entered from its entry — as one commit of the three
  files.

Not yet run: an `upgrade` of a live instance that Argo CD then applies (the next release's), `--secrets external-secrets` and
`sealed-secrets` against a cluster that has them, and Flux.
