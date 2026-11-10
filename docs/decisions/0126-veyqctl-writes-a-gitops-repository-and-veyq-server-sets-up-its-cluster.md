# ADR 0126 — `veyqctl` writes a GitOps repository, and `veyq-server` sets up the cluster it runs on

- Status: Accepted
- Built: no
- Date: 2026-11-10
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

veyq ships two deployment targets: Docker Compose, managed by `veyqctl`
([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)), and a Helm chart
([ADR 0064](0064-kubernetes-helm-deployment-target.md)) that ADR 0096 decision 8 left to the operator's own GitOps
tooling. The maintainer's instance runs that way: a private repository (`affenbande`) provisions a Debian server with
Ansible (admin user, sshd, a host firewall, unattended upgrades, k3s), installs Argo CD, Gateway API, Traefik,
cert-manager and the system-upgrade-controller, and syncs veyq's chart by digest, its secrets SOPS-encrypted in the
repository. Upgrading veyq there is done by hand and is exactly ADR 0096's upgrade in another form: resolve the
release, verify chart and images under the release identity, rewrite the digests, render, commit, push (done so for
v1.0, 2026-11-10). Two forces:

- That repository is most of what a self-hoster on one server needs, and nobody can use it: it is private, bound to
  one instance, and nothing upgrades veyq in it the way `veyqctl` does under Compose.
- Its two halves move at different speeds. The server half pins k3s, Argo CD, Traefik, cert-manager and Debian
  packages, which take security fixes on their own schedules; veyq's half moves with veyq's releases. An immutable
  veyq release that froze the server half would be stale within weeks.

## Decision

**1. Two halves, two repositories.** The **server half** — the Ansible roles and the cluster's own applications — is
a repository of its own, **`veyq-server`**, released on its own schedule. The **veyq half** — veyq's Argo CD
Application, its values, and what the chart expects and does not create (the namespace, the backup claim, the
secrets) — is `veyqctl`'s, released with veyq. Neither ships the other's files.

**2. `veyqctl` gains a GitOps target, and the target is the instance's, never an argument.** Beside Compose, `veyqctl`
manages an instance whose desired state is a Git repository that Argo CD syncs. Which target an instance has is read
from the instance itself: a Compose home holds `releases/` and `.env` (ADR 0096 decision 6); a GitOps repository holds
`veyq/veyqctl.json` (the target, the release selector, the secrets provider). So no command names a target, and one
machine can manage several instances of both kinds — a test instance under Compose beside a cluster's repository.

- **`init` asks Compose or GitOps**, creates the instance (under Compose what `install` does; `install` stays its
  Compose-only name), registers it and makes it current.
- **`~/.config/veyq/` holds the instances a user manages and which is current** (`$XDG_CONFIG_HOME` if set), as
  `kubectl` holds its contexts: `instances` lists them with their target and path, `use <name>` switches,
  `--instance <name>` acts on another once. It holds paths and names, never a secret. With no entry, the current
  instance is `/opt/veyq`, as before — a server with one Compose instance needs no list, and a run under `sudo`, whose
  home differs, still finds it.
- **The verbs are one set.** `init`, `upgrade`, `check` and `verify` act on either target. `start`, `stop`, `status`,
  `logs`, `backup`, `restore` and `rollback` are Compose's; on a GitOps instance each refuses and names what does it
  there — under Helm the chart's backup gate captures before a release change and its restore Job restores
  ([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)), and Argo CD runs the workloads.

`veyqctl` stays one file, standard library only (ADR 0096 decision 2), and calls the tools the GitOps target needs as
programs (`git`, `helm`, `kubectl`, `cosign`, and for decision 6 `copier`, `ansible-playbook`, `sops`), checking at
the start which are missing.

**3. An upgrade is a commit, held to ADR 0096's rules.** On a GitOps instance `upgrade` resolves the release `veyq/veyqctl.json`'s
selector names (an exact release, a major or `latest`, as `VEYQ_RELEASE` does), enters a major only from its major
entry ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md) decision 10) and never goes down; verifies the release's
manifest, then the chart and the three images under the release identity
([Verify a release](../../site/src/content/docs/run/verify-a-release.md)); rewrites the chart's and the images'
digests; renders the chart with the repository's values; and commits. It pushes only when asked (`--push`), and it
runs only by hand (ADR 0096 decision 5). Argo CD then applies the commit, and the backup gate captures before the new
release migrates.

**4. The cluster contract.** veyq's half assumes, and `init` checks before it writes anything against a
cluster it can reach: Argo CD; the Gateway API CRDs at v1.5 or later with an implementation serving the chart's
`Gateway` (the class named in the values); a StorageClass for the claims; and, when TLS is the chart's cert-manager
issuer, cert-manager 1.21 or later. A cluster `veyq-server` set up meets it by construction; any other — a managed
one such as AKS included — meets it or is refused with what is missing.

**5. Secrets come from a provider the operator chooses.** The chart takes only the names of existing Secrets
(`secrets.existingSecret`, `app.configSecret.existingSecret`), so the contract is "these Secrets with these keys exist
in the namespace". `init` writes them for one of:
- `sops` — SOPS-encrypted in the repository, decrypted by KSOPS in Argo CD's repo server (what `veyq-server` installs);
- `external-secrets` — `ExternalSecret` manifests on a `SecretStore` the operator names, so any store External Secrets
  Operator speaks serves (Azure Key Vault, AWS Secrets Manager, Google Secret Manager, HashiCorp Vault, OpenBao,
  1Password, Bitwarden …);
- `sealed-secrets` — sealed with the cluster's controller key;
- `none` — the operator creates them.

`veyqctl` generates every value once (the database passwords, the Garage keys, the config-secret key). Under `sops` it
encrypts them into the repository; under every other provider it hands them over once, in a file only the operator can
read, and `check` reports whether the Secrets exist with every key. It never calls a cloud provider's API. A
value that exists is never generated again, and the config-secret key — without which no backup can be read
([ADR 0050](0050-operator-config-secret-encryption.md)) — is named at init as the one value to keep in a password
manager besides wherever the provider holds it.

**6. With no cluster, `init` sets one up from `veyq-server`.** Asked first whether to set up the server too;
if so, it asks what `veyq-server` needs (the server's address, the admin user and key, the domains, the ACME contact,
the age key) and renders the server half into the same repository with **Copier**, from a **verified** `veyq-server`
release: each release names its commit and the sha256 of its tree in a manifest signed keylessly by `veyq-server`'s
own release workflow, and `veyqctl` checks out that commit and compares the tree before Copier reads it — the server
half runs Ansible as root, so it is never taken from a branch. Then it writes veyq's half beside it. The result is one
repository holding the whole server as code. Without the server step, `init` writes veyq's half alone, into a
repository the operator already syncs.

**7. After init the repository is the operator's.** Renovate (or any updater) keeps the server half's pins current;
`upgrade` keeps veyq's; `copier update` brings a later `veyq-server` release's structural changes in as a merge,
verified as decision 6 verifies. Nothing requires GitHub: Argo CD reads any Git remote, and `veyqctl` prints the deploy
key to register rather than calling a forge's API.

## Consequences

- **A third path to document and to test.** The Run area gains the GitOps target beside Compose and Helm; every
  infra-level decision now has an answer on Compose and on Helm, and the GitOps target only writes Helm's. What a CI run
  can prove without a cluster is the writer: `veyqctl`'s self-test and a render of the repository `init` writes
  and `upgrade` changes. A lane that syncs it into a cluster is not built; `veyq-server`'s own pipeline owns its
  Ansible and its cluster applications.
- **`veyq-server` and veyq are paired only through the contract** (decision 4), not through a tested combination of
  releases: `veyqctl` takes `veyq-server`'s newest verified release. A pairing a veyq release names in its manifest is
  the stronger form and waits for that lane.
- **`affenbande` becomes the first repository this writes**: its server half is `veyq-server`'s first release, its
  veyq half what `init` and `upgrade` produce; its host access (Argo CD and the admin surface on
  host-only NodePorts behind one ssh tunnel) moves with the half each part belongs to.
- **ADR 0096 decision 8 is replaced:** `veyqctl` no longer drives Compose only. It still ships no in-cluster operator.
- **Rejected — the whole server repository as a veyq release asset.** One download, but it freezes the server half's
  pins at veyq's release and leaves every later fix to a stack component waiting for a veyq release.
- **Rejected — one provider for secrets (SOPS only).** Simplest, but it makes a managed cluster with its own vault
  install KSOPS beside it; the chart already takes Secret names, so a provider choice costs only the manifests.
- **Rejected — writing secrets into a cloud vault through its CLI.** Saves one copy-paste, and binds `veyqctl` to each
  vendor's tooling and credentials.
- **Rejected — `veyq-server` fetched from its default branch.** Ansible runs as root on the server; an unverified
  checkout is the one place a compromised repository would reach it.
- **Rejected — a template engine of our own.** Copier renders and, unlike a one-shot generator, merges a template's
  later changes into a repository already written; it is open source, maintained, and vendor-neutral.
- **Rejected — the target as a user-wide setting.** Asked first (`~/.veyq` naming Compose or GitOps); it breaks as soon
  as one machine manages two instances, and under `sudo` the setting is in another home. The instance carries its
  target; the user's config only lists instances.
- **Rejected — a `gitops` command group.** Explicit, but every call repeats what the instance already says.
- **Rejected — Flux beside Argo CD now.** The repository layout differs per tool; Argo CD is what the maintainer runs
  and what `veyq-server` installs. A Flux writer is another decision.
