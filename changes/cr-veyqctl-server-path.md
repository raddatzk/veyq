---
id: cr-veyqctl-server-path
title: veyqctl init sets up the server too — one resumable command from an empty repository to a running instance
status: planned
satisfies: []
adrs: ["0126", "0096"]
invariants:
  - "the operator never has to know which command comes next: init runs the whole process; waiting on the person (DNS, the provider's console, the deploy key where no forge tool may enter it) is said exactly when due (owner 2026-11-10)"
  - "init asks before every step that changes something outside the repository or cannot be undone: trusting the host key, entering the deploy key at the forge, pushing, the bootstrap that runs as root, deleting certificates"
  - "every question comes before the first step that does something: the answers that decide the programs, then the programs (all checked at once, missing ones named, waited for — never installed by veyqctl), then every other question; after that only confirmations and waits (owner 2026-11-10)"
  - "the answers are the repository's veyq-setup.json, written as given and holding no secret; init in a checkout that has it asks nothing again — an interrupted run goes on, a lost server is set up again the same; which steps are done stays this machine's progress file, outside the repository"
  - "nothing of veyq-server is rendered or run unless its release manifest verifies and the checked-out tree matches it"
  - "no secret value is printed, logged or written in plaintext; a value that exists in the repository is never generated again"
  - "veyqctl stays one file, Python 3.9 standard library; every other program it needs is checked before its first action, named"
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
veyqctl init            answers: <checkout>/veyq-setup.json · steps done: ~/.config/veyq/init-<hash>.json
== 1. Setup             gitops · the checkout · set up the server too? [Y/n] · secrets (sops when the server is set up)
== 2. Programs          every one the run starts, at once; missing → named, `brew install …` where brew is, Enter: again
== 3. Questions         repository: origin's URL, branch · server: address, admin user, SSH key (offered from ~/.ssh and
                        agents), age key (new, or a public one) · veyq: today's init questions, TLS staging or
                        production, host access — offers from the cluster and repository; then no more questions
== 4. Server            veyq-server's newest release: manifest verified, commit checked out, tree compared
                        Copier renders veyq-server; .sops.yaml names the age key
                        host key scanned → "compare SHA256:… with your provider's console" → trust? [y/N]
                        admin password (hidden, or generated) and Argo CD's deploy key, encrypted
                        gh signed in: "enter Argo CD's deploy key at <owner/repo>, read-only?" [Y/n] → gh repo deploy-key add
                        else (or no): "enter this deploy key at <forge URL>, read-only" → waits for Enter
== 5. veyq              veyq's half written from the answers
                        "A records for <domain> and <store> → <server IP>": checks they resolve, waits
== 6. Commit and push   the files, the message → commit and push? [Y/n]
== 7. Bootstrap         "runs as root on <server>: admin user, hardening, firewall, k3s, Argo CD" → run? [Y/n]
== 8. Connect           ~/.ssh/config host and kubeconfig context; make it current? [y/N]
                        waits until Argo CD and veyq are Synced/Healthy (the tunnel it opens itself, for the wait)
== Done                 Argo CD's first password (the command), the Server Owner (its URL), the config-secret key
```

- **Resuming and rebuilding**: `veyq-setup.json` holds each answer the moment it is given (keyed by section: the
  server, and veyq's half by its directory), the progress file each step's mark (`rendered`, `host_key_trusted`,
  `pushed`, `bootstrapped`, …); a re-run says "the last run stopped at <step>" and goes on. Before trusting a mark it
  re-checks what it can cheaply (the file is there, the commit is on origin, the node answers). A checkout whose
  answers are complete and whose halves are written, on a machine with no marks (a new server, another laptop), runs
  the steps again with no question: host key, bootstrap, connect — the age key from the operator's keeper.
- **The bootstrap from veyqctl**: `ansible-playbook` runs from the rendered repository with the age key in its
  environment only; the push check runs first, as `play` did.
- **TLS staging**: `--acme-staging` / asked: the chart's `gateway.tls.acme.server` set to Let's Encrypt's staging
  directory. `veyqctl tls production` sets the production directory, commits and pushes on confirmation, deletes the
  issued certificate Secrets so cert-manager orders new ones (asked, as it cannot be undone), and waits for them.
- **Host access for veyq-admin** becomes an option of veyq's half (`init` asks; a Service and a NetworkPolicy beside
  the chart's), so nothing of veyq's is in veyq-server.

## Phases

- [x] **1 resumable init** — the progress file and resuming, for the existing path (no server step) too. · Done when:
  the self-test interrupts init after each step and the re-run continues, asking nothing twice.
- [x] **2 veyq's host access** — the admin NodePort as an option of veyq's half. · Done when: rendered and parsed in the
  render check; the self-test writes it on yes and not on no.
- [x] **2b questions first** — for the existing path and Compose: the order of the design (setup answers, the
  programs, every other question, then the steps), the program check waiting on Enter with Homebrew's command, the
  answers in `veyq-setup.json` and only the step marks in the progress file; `init` again in a written checkout says
  so and writes nothing. · Done when: the self-test shows no command but git's before the programs, no question after
  the first step, a missing program waited for and found, and a re-run in a written checkout asking nothing.
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

## Progress (2026-11-10)

Phase 1 (`d8da4d55f`): the progress file and resuming, bitten. Phase 2 (`95d180600`): veyq-admin's host access as an
option of veyq's half, in the render check. Toward the invariant on programs (owner 2026-11-10: "check initially that
all its tools are installed"): `require_programs` checks every program a command starts before its first question —
`init --gitops` and the GitOps verbs (`GITOPS_PROGRAMS`), Compose (docker; cosign where a manifest is verified), the
secrets provider's once chosen — all missing ones at once, each with where to get it, cosign also against its floor.
Self-tested (`test_programs_checked_first`), seven mutations bite.

Phase 2b (owner 2026-11-10: "really ask everything at the start", order B: the answers that decide the programs, the
programs, the rest): `init --gitops` asks directory, name and secrets provider, then checks every program the run
starts — waiting on Enter (`Host.wait`) with Homebrew's command, refused without a terminal — then the cluster, veyq and
Argo CD questions; no question after the release's verification. The answers are in `veyq-setup.json` at the
checkout's root (`Progress`: answers there by veyq's directory, steps in `~/.config/veyq/init-<hash of checkout and
directory>.json`), the flags that shape the files too (`SETTLED_FLAGS`, and `tls`, `repo_url`, `revision` when
derived — the live run found `tls` missing: derived from `--acme-email`, never recorded, so a resume without flags was
refused). `init` alone in a checkout takes the unfinished directory, or the only one; in a written one it says
"nothing to write", and a differing flag is refused. Self-tested (`test_programs_checked_first`,
`test_init_resumes_from_flags`, the rewritten resume and second-init cases), twelve mutations bite; run once with the
real git, kubectl, helm and cosign (PATH without helm: refused after the setup answers, which were in the file; then
continued with no answer given to a written half against release 1.0; then `init` alone: nothing to write). Phase 3 adds the server path's programs to the same
table (`ssh`, `ssh-keyscan`, `ssh-keygen`, `age-keygen`, `copier`, `ansible-playbook`; `gh` optional, never required).
