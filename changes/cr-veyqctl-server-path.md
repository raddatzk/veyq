---
id: cr-veyqctl-server-path
title: veyqctl init sets up the server too — one resumable command from an empty repository to a running instance
status: planned
satisfies: []
adrs: ["0126", "0096"]
invariants:
  - "the operator never has to know which command comes next: init runs the whole process; waiting on the person (DNS, the provider's console, the deploy key where no forge tool may enter it) is said exactly when due (owner 2026-11-10)"
  - "init asks before every step that changes something outside the repository or cannot be undone: trusting the host key, entering the deploy key at the forge, pushing, the bootstrap that runs as root, deleting certificates"
  - "an interrupted run costs nothing: the same command continues where it stopped, asking nothing twice; the progress file holds no secret and lives outside the repository"
  - "nothing of veyq-server is rendered or run unless its release manifest verifies and the checked-out tree matches it"
  - "no secret value is printed, logged or written in plaintext; a value that exists in the repository is never generated again"
  - "veyqctl stays one file, Python 3.9 standard library; every other program it needs is checked at the start, named"
---

## Goal

`veyqctl init` today writes veyq's half into a repository a running cluster already syncs. After this CR, asked whether
to set up the server too, it takes an operator from an empty Git repository and a fresh Debian server to a running veyq
in one command ([ADR 0126](../docs/decisions/0126-veyqctl-writes-a-gitops-repository-and-veyq-server-sets-up-its-cluster.md)
decision 6 and its paragraphs on one command and resuming): what `affenbande`'s `scripts/setup.py`, the operator's own
`git commit`/`git push`, `ansible/play` and `scripts/connect.py` did, in that order, with the questions in the style the
operator liked in `setup.py`. Two commands join it for later use: `veyqctl connect` (the tunnel host and the kubeconfig
context, again after a rebuild) and `veyqctl tls production` (from Let's Encrypt staging to production).

## Context & constraints

- ADR 0126; `cr-veyq-server-template` (its first release is what this renders); the Run page *Argo CD with veyqctl*.
- What it replaces, measured on `affenbande` at `bca6dc4` (2026-11-10): `setup.py` (age key, repository URL, the
  placeholders, the server's secrets and deploy key, the host key, host access), `ansible/play` (placeholder and push
  checks, the host key into `~/.ssh/known_hosts` by type, the age key, `ansible-playbook`), `connect.py` (the
  `~/.ssh/config` block found again under any name, never shadowing the owner's own `Host`, the kubeconfig context,
  making it current on request). Each lesson they carry moves with it: host keys compared by type (ssh's UpdateHostKeys),
  the push before the bootstrap (an AppProject overwritten by GitHub's older copy stalled the 2026-11-10 rebuild), a
  forward ending at the server's own address, `ClearAllForwardings` for a read while the tunnel is open.
- Programs `init` calls on this path: `gh` (optional: the deploy key, when signed in and allowed), `git`, `ssh`, `ssh-keyscan`, `ssh-keygen`, `age-keygen`, `sops`, `copier`,
  `ansible-playbook` (+ the collections the template names), `kubectl`, `helm`, `cosign`.

## Design

```
veyqctl init                                    (resumable: ~/.config/veyq/init-<repository hash>.json)
== 1. Target            gitops · set up the server too? [Y/n]
== 2. Repository        the checkout (empty, or one init started); origin's URL; branch
                        veyq-server's newest release: manifest verified, commit checked out, tree compared
== 3. Server            address · admin user · SSH key (offered from ~/.ssh and agents) · age key (new, or a public one)
                        Copier renders veyq-server; .sops.yaml names the age key
                        host key scanned → "compare SHA256:… with your provider's console" → trust? [y/N]
                        admin password (hidden, or generated) and Argo CD's deploy key, encrypted
                        gh signed in: "enter Argo CD's deploy key at <owner/repo>, read-only?" [Y/n] → gh repo deploy-key add
                        else (or no): "enter this deploy key at <forge URL>, read-only" → waits for Enter
== 4. veyq              the questions of today's init (domains · TLS: staging or production · …) and host access [Y/n]
                        "A records for <domain> and <store> → <server IP>": checks they resolve, waits
== 5. Commit and push   the files, the message → commit and push? [Y/n]
== 6. Bootstrap         "runs as root on <server>: admin user, hardening, firewall, k3s, Argo CD" → run? [Y/n]
== 7. Connect           ~/.ssh/config host and kubeconfig context; make it current? [y/N]
                        waits until Argo CD and veyq are Synced/Healthy (the tunnel it opens itself, for the wait)
== Done                 Argo CD's first password (the command), the Server Owner (its URL), the config-secret key
```

- **Resuming**: the progress file holds each answer and each step's mark (`rendered`, `host_key_trusted`, `pushed`,
  `bootstrapped`, …) the moment they exist; a re-run says "the last run stopped at <step>" and goes on. Before trusting
  a mark it re-checks what it can cheaply (the file is there, the commit is on origin, the node answers).
- **The bootstrap from veyqctl**: `ansible-playbook` runs from the rendered repository with the age key in its
  environment only; the push check runs first, as `play` did.
- **TLS staging**: `--acme-staging` / asked: the chart's `gateway.tls.acme.server` set to Let's Encrypt's staging
  directory. `veyqctl tls production` sets the production directory, commits and pushes on confirmation, deletes the
  issued certificate Secrets so cert-manager orders new ones (asked, as it cannot be undone), and waits for them.
- **Host access for veyq-admin** becomes an option of veyq's half (`init` asks; a Service and a NetworkPolicy beside
  the chart's), so nothing of veyq's is in veyq-server.

## Phases

- [ ] **1 resumable init** — the progress file and resuming, for the existing path (no server step) too. · Done when:
  the self-test interrupts init after each step and the re-run continues, asking nothing twice.
- [ ] **2 veyq's host access** — the admin NodePort as an option of veyq's half. · Done when: rendered and parsed in the
  render check; the self-test writes it on yes and not on no.
- [ ] **3 the server path** — sections 2, 3, 5, 6 and 7 of the design, `veyqctl connect`, against stand-ins for every
  program. · Done when: the self-test drives the whole run, each confirmation's no stopping before its step, each
  refusal (a manifest that does not verify, a tree that differs, a host key not confirmed, an unpushed tree) changing
  nothing outside the repository.
- [ ] **4 TLS staging and `veyqctl tls production`.** · Done when: values rendered for both directories; the switch
  driven against stand-ins, nothing deleted on no.
- [ ] **5 measured: `raddatz.me` from scratch** — `affenbande` renamed to `raddatz.me` on GitHub; the repository emptied;
  a fresh Debian server; `veyqctl init` alone, with staging, then `veyqctl tls production`; Argo CD and veyq healthy, the
  Server Owner created. Owner's go for the rename, the wipe and each confirmation. · Done when: the run is recorded
  here, every step that stopped and why.
- [ ] **6 docs** — the Run page's server path; `affenbande`'s scripts and README retired; ADR 0126 built. · Done when:
  the trust chain and the site's check are green.

## Definition of done

The self-test covers the whole path with every confirmation and refusal; phase 5's run is recorded; the Run page tells
an operator one command; `affenbande` is `raddatz.me` and was made by it.
