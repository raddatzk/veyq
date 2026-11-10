---
id: cr-veyq-server-template
title: veyq-server holds the server half as a Copier template, released and signed
status: planned
satisfies: []
adrs: ["0126"]
invariants:
  - "veyq-server holds nothing of veyq's: no veyq Application, values, Secret or host-access part of veyq's (ADR 0126 decision 1)"
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

- [ ] **1 the template** — the files above under `template/` with Jinja placeholders for the answers, `copier.yml`,
  the README for operators (what it installs, what each answer is; how veyqctl uses it). · Done when: `copier copy` with
  sample answers renders a repository whose `gitops/` builds with kustomize and helm and whose Ansible passes the syntax
  check.
- [ ] **2 its CI** — the render-and-check workflow on every push and pull request. · Done when: green on GitHub.
- [ ] **3 its release** — `release.yml` (manifest, keyless signature, immutable release), the first release `v0.1`
  (owner's go). · Done when: `cosign verify-blob` of the manifest under the workflow identity passes and the tree hash
  matches `git archive` of the named commit.
- [ ] **4 measured with cr-veyqctl-server-path** — the `raddatz.me` repository (renamed from `affenbande`) rendered from
  it by `veyqctl init` on a fresh server. · Done when: recorded there.

## Definition of done

The template renders and its checks pass in veyq-server's CI; `v0.1` is released and verifies; ADR 0126 decision 6's
verification has a release to verify. `affenbande`'s server half is retired into it (phase 4 of the other CR).
