---
id: cr-backup-key-apart-from-dump
title: A capture keeps the config-secret key away from the dump, and everything it writes is owner-only
status: planned
satisfies: [uc.admin.back-up-and-restore-instance]
adrs: ["0056", "0050", "0064"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Goal

`scripts/instance_backup.py capture` writes `database.sql` and `config-secret.key` into one directory. The
dump gets the default umask (typically `0644`), and nothing is encrypted. That directory is precisely the
leaked backup ADR 0050's key file claims to defend: the dump plus the key opens every tenant's SMTP password.
After this CR:
- `capture` writes the key to a destination of its own and refuses one inside the capture directory; putting
  it beside the dump takes an explicit flag;
- the manifest records which of the two happened;
- `restore` takes the key from wherever the manifest says, and refuses a separately-kept key that is not
  supplied unless explicitly told to go on without it;
- every file and directory the tool writes is owner-only, and `verify` checks it;
- the runbook says to encrypt a capture before it leaves the host.

Inbox #164. ADR 0050 stays as it is, and becomes true again.

## Context & constraints

- **The rule:** [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) §1,
  *Leg (c) is captured, but never beside legs (a) and (b)*, written by this refinement. The flow is
  [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  steps 4 and 10, A2, and the corrected *Content is ciphertext* note. The threat model it restores is
  [ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md) § Consequences, *the leaked DB backup*.
- **What the tool does today** (`scripts/instance_backup.py`):
  - no `os.umask` anywhere;
  - the dump is opened at `:219`;
  - the key is `copyfile`d and only then `chmod 0600` (`:563-564`), so it is world-readable for a moment;
  - `verify` checks the key's mode and nothing else's (`:653-658`);
  - `restore` reads the key only from the capture directory, and warns then exits 0 when it is missing
    (`:750-761`), never reading the manifest's `configSecret`;
  - the manifest records `configSecret.present` (`:585`).
- **`objects/` is written by the rclone container** through a bind mount (`:372-377`), so its files' owner and
  mode are the container's. The capture root's `0700` is what protects them. Measure what rclone-in-docker
  actually produces and write that into the tool's docstring. Don't assume it.
- **The harness depends on the layout.** `scripts/run_web_e2e.py` restores from `<capture>/config-secret.key`.
  Its leg-3 rehearsal deletes that file and flips `manifest["configSecret"]["present"]` (`:934-939`), and it
  never calls `verify`.
- **No capture in the wild needs reading back.** The production stack has never been deployed (ADR 0063
  § Consequences), so the manifest shape changes with no compatibility branch.
- **Kubernetes** has no native backup (ADR 0064 § Decision); the README carries a manual `kubectl` capture as
  comments. It follows the same rule as a documentation change.
- **Out of scope:** encrypting inside the tool. The decision is a documented recommendation, and the tool
  gains no crypto dependency.

## Phases

- [ ] 1 tool — `scripts/instance_backup.py`:
      - `os.umask(0o077)` for the whole run, and the capture root chmodded `0700` even when it pre-existed
        empty. This also closes the `copyfile` window.
      - `capture` takes a key destination and refuses a path that resolves inside the capture directory. The
        refusal comes **before** anything is dumped, so a refused run leaves no half-capture. An explicit flag
        restores the old placement. With no key source present, it warns as today and records the leg as
        absent.
      - The manifest's `configSecret` becomes one of `separate | beside | absent`.
      - `verify` checks that the root is `0700`, that the files the tool wrote have no group or other bits, and
        the key per the manifest. For `separate` it checks a key path when one is given, and says it did not
        otherwise. `absent` stays exit 2.
      - `restore` resolves the key per the manifest. For `separate` with no key supplied it refuses **at the
        gates, before any write**, unless the explicit go-on-without flag is passed. It keeps its existing
        target path for where the key goes.

      Flag names are the implementer's; the README owns them. Locator:
      `grep -n -E 'KEY_NAME|config_secret_file|configSecret|umask|chmod|mkdir|\.open\(' scripts/instance_backup.py`.
      · Done when: the three refusals have each been run by hand and seen: a key destination inside the capture;
        a `separate` restore without the key; a `verify` over a capture with one file chmodded `0644`. Each is
        paired with its accepted twin: the same command with the path outside, with the key given, and after
        chmodding back.
- [ ] 2 harness — `scripts/run_web_e2e.py`:
      - `restore_hook` captures with the key kept separately and restores with it;
      - the leg-3 rehearsal stops editing the manifest and restores with the explicit go-on-without flag, which
        is the operator's knowing path to A2;
      - `verify` runs after every capture and must exit 0 with no key file in the capture directory;
      - one negative joins `assert_populated_target_is_refused`: a `separate` restore without the key is refused
        naming the key, against the same instance where the positive restore then succeeds.

      `rehearse_production_project`'s decoy key (`:1091`) keeps working. Locator:
      `grep -n -E 'instance_backup|KEY_NAME|configSecret|config-secret' scripts/run_web_e2e.py`.
      · Done when: `python3 scripts/run_web_e2e.py` green. It needs the built fast-jar
        (`cd backend && ./gradlew quarkusBuild`) and the dev stack. `restore.b-reopen` still reads `OK` and
        `legthree.b-mismatch` still reads `MISMATCH`.
- [ ] 3 docs — the sites, from two angles:
      `grep -rn -E 'config-secret-file|config-secret\.key|instance_backup' infra wiki docs .gitignore charts` and
      `grep -rn -i -E 'three legs|leg \(c\)|ciphertext or keys' infra wiki docs`. They include:
      - **`infra/README.md` § Backup & restore and the upgrade step**: the commands with a key destination, and
        one short paragraph *before a capture leaves this host*: encrypt it, keep the key on another medium.
        Name one tool as an example and ship none.
      - **The Kubernetes manual capture comment**: `kubectl cp` the key to its own destination. Also reconcile
        `charts/veyq/templates/mariadb.yaml:3` ("reaches it through `kubectl exec`") with the README's
        port-forward. One of them is stale; measure which is documented as the procedure and fix the other.
      - **`wiki/en/running-your-instance.md:55-56`**: *"All three are ciphertext or keys to ciphertext; none of
        it lets you read anything"* is false for the key plus the dump. Correct it in plain language. That page
        is English-only (`wiki/Home.md`).
      - **The `.gitignore` comment** at `:210-212`.

      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).
- [ ] 4 coverage — a new criterion in `docs/coverage/features/admin/use-cases/uc.admin.back-up-and-restore-instance.yaml`:
      a capture keeps the key out of the capture directory unless explicitly told otherwise, writes nothing
      another user can read, and a restore refuses a separately-kept key that is not supplied. Its observable is
      the operator tool's exit and message, like `a-populated-target-is-refused-rather-than-reconciled`, so it
      rides in the same harness hook. The existing `web` gap text gains it, and the file stays
      `status: planned` for the reason its header gives.
      · Done when: `python3 scripts/validate_coverage.py` green.
- [ ] 5 stale pointers — ADR 0050 no longer has an amendment section (it was folded into the decision), yet
      code and docs still cite "ADR 0050 amendment". Rewrite those citations to plain "ADR 0050". The one other
      miss in that area is `Entities.kt:92-93` ("env KEK"). **Exclude `backend/src/main/resources/db/migration/`**:
      editing a comment in an applied Flyway migration changes its checksum and fails validation on every
      existing database. Locator:
      `grep -rn -i 'amendment' docs infra scripts backend/src charts | grep -i -E '0050|config.?secret|kek' | grep -v db/migration`.
      · Done when: that locator returns only the inbox rows; `./gradlew check` and the trust-chain guards green.

## Definition of done

- Every phase is done, and each refusal in phase 1 has been seen with its accepted twin.
- `python3 scripts/run_web_e2e.py` green, now calling `verify`.
- `python3 scripts/validate_coverage.py` and the trust-chain guards are green; `./gradlew check` green after
  phase 5.

**Not proven by the above, named:** the web-e2e job has never run in CI (no remote; the coverage file's header),
so the new criterion is a gap for the same reason as its siblings. The encryption recommendation is prose. It
is only as good as the operator who follows it.

## Retrospective
