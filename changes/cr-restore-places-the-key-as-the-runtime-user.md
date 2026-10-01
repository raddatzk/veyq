---
id: cr-restore-places-the-key-as-the-runtime-user
title: A restore places the config-secret key where the app can read it — on both deployment targets
status: in-progress     # phases 1, 2, 4 landed 2026-10-01; phase 3 (live, root, production stack) is the owner's
satisfies: [uc.admin.back-up-and-restore-instance]
adrs: ["0050", "0056", "0064"]
invariants: [self-hostable by default]
---

## Open for the owner

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Where does the tool learn which owner the key must have?**
   - (a) From the key's **parent directory**. On the production recipe that directory is the
     `veyq-config-secret` volume, which Docker seeds from the image as `10001:10001`, and
     `scripts/validate_app_volume_ownership.py` already holds that true. In the dev loop it is a directory the
     invoking user owns.
   - (b) From the app image's `USER`, via `docker inspect` of the compose app service. The tool already
     inspects that service for the release version (`app_release`).
   - (c) An explicit `--key-owner UID:GID` flag.

   **Recommendation: (a).** It needs no daemon and no number the operator has to remember, and it is right
   exactly where the image seeded the directory. (b) is authoritative, but it has nothing to read in the dev
   loop (`quarkusDev` runs no app container), so the tool would need two sources. (c) is the one an operator
   gets wrong.
2. **Under Helm, does the tool place the key, or does the runbook?** The tool cannot reach a
   PersistentVolumeClaim. **Recommendation: the runbook**, as the mirror of the capture side. Capture already
   copies the key *out* with `kubectl cp` (`infra/README.md` § Upgrading with `helm upgrade`, step 0). Restore
   copies it *in* the same way, and then restarts the pod. No new tool mode is needed.
3. **Should a key-placement failure be caught before anything is written?** Today it cannot be: placement
   runs after the database and the objects are already in. **Recommendation: yes, move the check to the
   gates.** The ownership check this CR adds is exactly the check that belongs there.

## Goal

Inbox #188. `scripts/instance_backup.py restore` writes the config-secret key as whoever runs the tool. On a
production host that is root, because the documented target is the `veyq_veyq-config-secret` volume's
mountpoint under `/var/lib/docker/volumes`. The app runs as uid 10001 (`Dockerfile` `USER 10001`, in both
runtime stages). A root-owned `0600` key is therefore unreadable to it:
- `ConfigSecretSeal.readOrGenerate` catches the IO failure;
- sealing becomes unavailable, and the state reads `ABSENT`;
- member invites and password resets are refused with `503`.

The instance behaves as if leg (c) were missing, even though the key is sitting right there. After this CR:
- a restore always leaves the key owned by the directory's owner;
- a restore that cannot place the key readably is refused before its first write;
- the Helm target has a written procedure for leg (c).

## Inventory (measured at refine, `main` @ `faf9ef4`, read, not run)

| # | Where | What | How measured |
|---|---|---|---|
| 1 | `scripts/instance_backup.py`, `cmd_restore`, the key-placement block after `store.mirror_in` | `os.open(target, O_WRONLY\|O_CREAT\|O_TRUNC, 0o600)` + `fchmod`, **no `chown`**. If the file already exists, `O_TRUNC` keeps its owner. If the file does **not** exist, it is created as the invoking user. | `grep -n 'O_TRUNC\|fchmod\|chown' scripts/instance_backup.py` returns only the O_TRUNC/fchmod lines and no chown |
| 2 | same block | It runs **after** `db.load` and `store.mirror_in`. A `PermissionError` here (a non-root invoker facing a 10001-owned key) is **not** a `BackupError`, so it escapes as a traceback and leaves a half-restored instance that the A5 gate then refuses to retry. | read `cmd_restore` top to bottom (`grep -n 'def cmd_restore' scripts/instance_backup.py`) |
| 3 | `backend/…/shared/crypto/ConfigSecretSeal.kt`, `readOrGenerate` | **Whether the target's key exists at restore time decides whether the bug fires.** The seal is `@ApplicationScoped`, and no `StartupEvent`/`@Startup` observer touches it or its users. A fresh install's one start (§ Restore step 1) therefore generates **no** key unless a mail-settings read or a send happened, so the restore takes the `O_CREAT` branch and the key is born root-owned. | `grep -rln 'ConfigSecretSeal\|ConfigSecretKeyGuard\|TenantMailSender' backend/src/main/kotlin \| xargs grep -ln 'Startup\|@Observes'` returns nothing. *Remembered, not measured:* ArC constructs an `@ApplicationScoped` bean on its first proxied call. |
| 4 | same, `Files.exists(path)` → `readString` | The directory is `10001:10001 0700`, so `exists` is true. Reading a `root:root 0600` file then throws `AccessDeniedException`, which is caught, and the key becomes `null`: state `ABSENT`, not `MISMATCH`. The row said "the A2 symptom". The refusal is the same, the state string is different. | read `readOrGenerate` |
| 5 | `infra/README.md` § Restore | The only command shown is the **dev** one (`--config-secret-file backend/config-secret.key`). The production restore exists only as a pointer: § An upgrade, step by step, "Rollback", says restore "onto an empty instance (§ Restore)". The operator must transpose the capture's `$(docker volume inspect …)/config-secret.key`, which needs root. | `grep -n 'config-secret-file' infra/README.md` |
| 6 | `infra/README.md` § Upgrading with `helm upgrade` | Capture under Helm is written down: `kubectl cp` the key out, scale to 0, port-forward. **Restore under Helm is not**: "Rollback is the backup plus the previous tags and digests, exactly as under compose". | same grep, plus `grep -n 'kubectl cp' infra/README.md` |
| 7 | `charts/veyq/templates/app-deployment.yaml` | The pod runs `runAsUser: 10001`, `fsGroup: 10001`, `fsGroupChangePolicy: OnRootMismatch`. | `grep -n 'fsGroup\|runAsUser' charts/veyq/templates/app-deployment.yaml` |
| 8 | `scripts/run_web_e2e.py` restore hook | The rehearsal restores onto a fresh key path as the **same** user that runs the backend. It crosses no ownership boundary, so it is green today and stays the positive control. | `grep -n 'config-secret-file\|key-from' scripts/run_web_e2e.py` |
| 9 | `docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md` main flow step 9 | "puts the config-secret key file … where the application expects it". It says nothing about who has to be able to read it. | read |

## Decisions

- **The key takes the owner of the directory it lands in.** This is Open question 1 (a). The tool extracts
  one function, `place_key(source, target)`, that does four things:
  - it `stat`s the target's parent directory;
  - it opens the file `0600`, as today;
  - it `fchown`s the open descriptor to the parent's `uid:gid` **before** the bytes land, when the euid is 0
    and the owners differ;
  - it `fchmod`s, then writes.

  The ownership decision is a **pure function**, `key_owner_plan(euid, parent_uid, parent_gid, existing_uid)`,
  returning one of *as-is*, *chown to X*, or *refuse with reason*. That makes it testable without root.
- **Refuse at the gates, not after the load.** Before `db.load`, the key gate checks four things:
  - the target's parent exists;
  - the plan is not *refuse*;
  - a non-root invoker owns the parent;
  - an existing target is writable.

  A non-root invoker facing a directory someone else owns gets a `BackupError` that says: run as root, or
  place the key through the app container. It is not left to discover this halfway through.
- **Helm: the runbook places leg (c), and the tool stays daemon- and cluster-agnostic.** The new § Restore
  under Helm block runs these steps in order:
  1. scale the app to 0;
  2. port-forward to MariaDB and Garage, as the capture does;
  3. run `restore` with `--config-secret-file ./veyq-config-secret.key` and `--key-from <kept key>`. The tool
     writes a local, invoking-user copy, which is exactly its dev-loop path;
  4. scale to 1;
  5. `kubectl cp` that copy to `/var/lib/veyq/config-secret.key` in the app pod, then delete the local copy;
  6. `kubectl rollout restart`;
  7. verify.

  The restart is part of the procedure for a reason. Anything that touched the seal before the copy landed
  holds the fresh install's key in memory, and the fingerprint guard would report `MISMATCH` until a restart.
  *Remembered, verify at implement:* `kubectl cp` needs `tar` in the image (the JVM image's jammy base is
  remembered to carry it), and extraction runs as the container's uid 10001, so the file is born `10001`.
- **Compose production gets its own restore command in the README**, mirroring the capture in § An upgrade,
  step by step, step 2: `sudo` plus the volume mountpoint. The dev example stays as it is.
- **The use-case says whose key it is.** Step 9 gains "readable by the application's runtime user". The coverage
  YAML's criterion `three-legs-or-it-is-not-a-backup` is unchanged. This CR proves a precondition of it; it does
  not add a behaviour.

## Phases

- [x] 1 tool: extract `key_owner_plan` and `place_key`, move the key check into the gates, and add a docker-free
      self-test next to the tool. Following `scripts/mutation_meta_check_selftest.py`'s shape, it goes outside
      the `validate_*` glob, because it asserts nothing about the repository. It exercises every branch of
      `key_owner_plan` and one real `place_key` into a temporary directory the invoking user owns.
      Locator for every call site: `grep -n 'config_secret_file\|KEY_NAME\|write_key' scripts/instance_backup.py scripts/run_web_e2e.py`.
      · Done when: the self-test is green, and `python3 scripts/instance_backup.py restore --help` still parses.
- [x] 2 docs, both targets: `infra/README.md` gets the compose-production restore command and § Restore under
      Helm. The use-case gets step 9's clause. Update the module docstring's "Everything this tool writes is
      owner-only" paragraph with the new owner rule.
      · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` is green.
- [ ] 3 live proof on the production recipe, with the owner, because it needs Docker and root. Restore a capture
      onto a freshly installed `infra/docker-compose.prod.yml` stack whose app has **not** read mail settings, so
      the key is absent (Inventory 3). Then run:
      - `sudo stat -c '%u:%g %a' <mountpoint>/config-secret.key`, which must print `10001:10001 600`;
      - `GET /api/tenants/{id}/mail-settings`, which must report `configSecretState: OK`.

      · Done when: both are recorded in this CR.
- [x] 4 coverage: `docs/coverage/…/uc.admin.back-up-and-restore-instance.yaml` notes where the ownership
      precondition is proven: the self-test, and phase 3's recorded run.
      · Done when: `python3 scripts/validate_coverage.py` is green.

## Definition of done

- **Red first, on the live defect.** Before phase 1, run phase 3's procedure with today's tool. Expected:
  `0:0 600` and `configSecretState: ABSENT`, with a log line
  `Could not read or generate the config-secret key … AccessDenied`. Record it. Then land the fix and re-run
  the same procedure for green. **Lever:** the live defect itself. No injection is needed while it exists.
- **Self-test bite:** make `key_owner_plan` return *as-is* unconditionally. The root-invoker/10001-directory case
  must go red. Separately, drop the gate call from `cmd_restore`. A test that drives `cmd_restore` up to its
  first write against a stub `Database` must see the refusal disappear. If no such seam exists without Docker,
  say so in the retrospective rather than claim the bite.
- **Positive control:** `scripts/run_web_e2e.py`'s `restore.*` group is still green. That is the same-user path,
  and the plan is *as-is* there.
- **The Helm procedure has run once** (owner, a local cluster) with `configSecretState: OK` recorded, **or** the
  README says in plain words that it is derived, not rehearsed, as § An upgrade already does for its own steps.
- The trust-chain glob is green.

## Where it stands

**2026-10-01.** Phases 1, 2 and 4 are in; the self-test's two bites went red on the named case
(plan forced to *as-is* → the #188 case; the gate call dropped → "refused at the gates, before the database"),
via the stub-`Database` seam in front of the first write, so that bite needed no Docker. `kubectl cp`'s `tar` was
measured: present in the JVM runtime base, **absent** from the native target's `ubi-minimal`. **Open, and the
reason this file is not deleted:** the red-first run and phase 3 both need a production compose stack and root,
which an implement agent cannot bring up (no production `up` from an agent session). The owner runs phase 3's
procedure once — today's tool first if it is still at hand (`0:0 600`, `ABSENT`), then this one
(`10001:10001 600`, `OK`) — records both here, and then this CR closes. The Helm procedure is documented as
derived, not rehearsed.

## Retrospective

- **The CR's red-first was unrunnable by the session it was handed to.** Its lever is a production stack plus
  root, and the wave's rules forbid the first while the sandbox lacks the second; the CR said "with the owner"
  for phase 3 but put the same requirement in the DoD's *first* step as if the implementer could run it. Next
  time: a refine that needs an owner-run live proof marks the DoD step owner-run too, and names the cheapest
  agent-runnable red (here: the self-test against today's tool, which has no gate to refuse with).
- **One finding beyond the CR:** the native target cannot take part in either Helm `kubectl cp` (no `tar`), which
  no doc said; written into infra/README's Helm restore block.
