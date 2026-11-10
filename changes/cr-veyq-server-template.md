---
id: cr-veyq-server-template
title: veyq-server holds the server half as a Copier template, released and signed
status: in-progress
satisfies: []
adrs: ["0126"]
invariants:
  - "veyq-server holds nothing of veyq's: no veyq Application, values, Secret or host-access part of veyq's (ADR 0126 decision 1) — two lines name it, by design: the AppProject allows veyq's chart (the Application veyqctl writes joins it) and Renovate leaves gitops/apps/veyq/ alone (veyqctl's)"
  - "a release is verifiable before Copier reads it: a manifest naming the commit and the sha256 of its tree, signed keylessly by veyq-server's own release workflow (decision 6)"
  - "no secret value is in the template or in a rendered repository in plaintext; the template ships no instance value (no host, user, key, domain, URL)"
  - "the rendered repository's layout is gitops/ beside ansible/ (owner 2026-11-10), so veyqctl offers gitops/apps/veyq"
---

## Goal

`github.com/raddatzk/veyq-server` (public, empty since 2026-11-10) becomes what sets up a server and its cluster beneath a
veyq GitOps instance ([ADR 0126](../docs/decisions/0126-veyqctl-writes-a-gitops-repository-and-veyq-server-sets-up-its-cluster.md)
decision 1 and 6): the server half of the maintainer's `affenbande` repository — Ansible (admin user, sshd, the host
firewall, unattended upgrades, k3s, Argo CD with KSOPS) and the cluster's applications under `gitops/` (Argo CD itself,
the Gateway API CRDs, Traefik, cert-manager, the system-upgrade-controller, Argo CD's host-only NodePort) — as a
**Copier template** whose questions `veyqctl init` answers, released with a signed manifest. The operator never runs
anything of this repository by hand; `cr-veyqctl-server-path` drives it.

## Context & constraints

- ADR 0126 decisions 1, 6 and 7; the Run page *Argo CD with veyqctl*; `affenbande` (private) at `bca6dc4` as the source.
- What moves, measured on `affenbande` 2026-11-10: `ansible/` (7 roles: admin_user, argocd, base, firewall, k3s, sshd,
  unattended_upgrades; 2 playbooks, `bootstrap` and `server`; the inventory), `gitops/root.yaml`, `gitops/apps/{project,
  argocd, gateway-api, cert-manager, traefik, system-upgrade}`, `gitops/components/` (veyq-admin's host-only NodePort
  component stays with veyq's half — see below), `.sops.yaml`, `renovate.json` and its workflow, `.yamllint.yaml`, the
  pre-commit secret check, `secret-templates/ansible-server.yaml`.
- What does NOT move as a script: `scripts/setup.py`, `scripts/connect.py`, `ansible/play` and `scripts/render.sh` —
  their logic becomes `veyqctl`'s (the questions, the host key, the secrets, the push check, the connection) or a
  Copier answer; `render.sh` becomes the template's own CI check. `affenbande`'s run notes in its README are not template
  text.
- veyq-admin's NodePort component (`gitops/components/veyq-host-access`) is veyq's, not the server's: it moves into
  `veyqctl` as an option of veyq's half (the chart cannot do it, its admin Service is ClusterIP), so the template carries
  only Argo CD's NodePort and the host firewall that keeps both host-only.

## Decisions this CR takes

- **Copier's questions are the setup script's**, nothing more: `server_address`, `admin_user`, `admin_ssh_public_key`,
  `age_recipient`, `repo_url`, `branch` (default `main`), `host_access` (bool), `node_name`. Generated values (the admin
  password, the deploy key) are not Copier answers: `veyqctl` makes and encrypts them after rendering, so `copier
  update` never sees a secret.
- **The release manifest**: `veyq-server-manifest.json` (`release`, `commit`, `tree_sha256` of `git archive` of that
  commit) as a GitHub Release asset with its Sigstore bundle, signed by `release.yml` of `raddatzk/veyq-server` on
  `main`. Releases are `v<major>.<minor>` like veyq's, immutable releases on.
- **Where the template's own checks run**: veyq-server's CI renders the template with sample answers and runs
  `yamllint`, `kustomize build` of every app, `helm template` of the charts, `ansible-lint` and an Ansible syntax check —
  no server. A real server run is the maintainer's (phase 4).

## Phases

- [x] **1 the template** — the files above under `template/` with Jinja placeholders for the answers, `copier.yml`,
  the README for operators (what it installs, what each answer is; how veyqctl uses it). · Done when: `copier copy` with
  sample answers renders a repository whose `gitops/` builds with kustomize and helm and whose Ansible passes the syntax
  check.
- [x] **2 its CI** — the render-and-check workflow on every push and pull request. · Done when: green on GitHub.
- [x] **3 its release** — `release.yml` (manifest, keyless signature, immutable release), the first release `v0.1`
  (owner's go). · Done when: `cosign verify-blob` of the manifest under the workflow identity passes and the tree hash
  matches `git archive` of the named commit.
- [ ] **4 measured with cr-veyqctl-server-path** — the `raddatz.me` repository (renamed from `affenbande`) rendered from
  it by `veyqctl init` on a fresh server. · Done when: recorded there.

## Definition of done

The template renders and its checks pass in veyq-server's CI; `v0.1` is released and verifies; ADR 0126 decision 6's
verification has a release to verify. `affenbande`'s server half is retired into it (phase 4 of the other CR).

## Progress (2026-11-10)

Phase 1 in `raddatzk/veyq-server` at `6822b05` (pushed): 63 files from `affenbande` at `bca6dc4` under `template/`,
12 of them Copier templates (`.jinja`, markers `[[ ]]`/`[% %]`); the questions `server_address`, `node_name` (offered
from the address), `admin_user`, `admin_ssh_public_key`, `age_recipient`, `repo_url`, `branch` (main), `project`
(server), `host_access` (yes); system names `affenbande` → `veyq-server` (nftables `veyq_server`, sshd's
`01-veyq-server.conf`, `/var/lib/veyq-server/bootstrap`); `.copier-answers.yml` rendered. Not moved: `setup.py`,
`connect.py`, `render.sh`, `play`, the veyq upgrade workflow, veyq's half and its host-access component.

Measured locally (`scripts/check_template.py --no-lint`, copier 9.10.3 in a venv): both answer sets render with no
marker left; yamllint; `kustomize build` of gitops/apps, Argo CD's install (its NodePort exactly with host access) and
system-upgrade; the Gateway API CRDs at the pinned commit; `helm template` of cert-manager and Traefik by digest;
`ansible-galaxy` installs the requirements; `--syntax-check` of both playbooks — all green. ansible-lint is not
measured locally: Homebrew's runs on Python 3.15, which ansible-core 2.21 does not support, and resolves no collection;
CI installs it under the runner's Python 3.12 (`requirements-ci.txt`).

Phase 2: `checks.yml` pushed; its first run waits on GitHub (Actions partial outage, githubstatus.com, 2026-11-10 ~17:50).
Phase 3 (`release.yml`, `scripts/tree_hash.py` — a sorted `<mode> <sha256> <path>` listing rather than `git archive`,
whose tar may differ between git versions) is written; the first release waits on the owner's go and immutable
releases switched on in the repository's settings.

Phase 2 and 3 (2026-11-10, owner's go "mach das Release"): checks.yml green on GitHub, both answer sets, ansible-lint
included (run 42889413487). Pushes to veyq-server start no run — not one of four did, though both workflows are
registered and active; the checks were started by hand (`workflow_dispatch`, added for that). Why is open: a question
for the repository's settings, not for the template. The first release is `v0.5` (the minor counts commits since
VERSION changed, so not `v0.1`), immutable, commit `1ece48b`. Measured with veyqctl's own code
(`verify_server_template`): the manifest verifies under veyq-server's release identity, the commit cloned has the
manifest's tree sha256; the same manifest under veyq's identity, and the manifest with one byte changed, are refused
by cosign.
