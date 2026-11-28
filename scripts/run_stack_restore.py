#!/usr/bin/env python3
"""Lane L3 of ADR 0091: archived backups restored into the candidate — one per schema state of its major and the major
entry's, each pulled from the archive, verified under `archive.yml`'s identity, restored by the candidate's own
`veyqctl restore` into a fresh install of the candidate, and read by the candidate's reader and browser spec (ADR 0091
decision 6, amended 2026-11-26, and its *L3 and the archive as built*).

**Which backups.** The selection is `run_web_e2e.backup_selection_ref()` — next to `major_entry_ref()`, the same tags
(`git tag --list`, a fix release on `release/<old>.x` included): the releases of the candidate's major grouped by the
highest migration of their own pin file, the oldest of each group, plus the major entry. Derived, never listed. It is
split over checks.yml's `stack-restore` shards (`--shard N`; the count is that job's matrix, `snapshot.restore_shards`,
stated once) round-robin in version order ([shard_of]), so each shard fits the job's budget (ADR 0091 decision 1). A
shard carrying more than [MAX_PER_SHARD] is red before it runs anything: the budget needs another shard, and a timeout
would name nothing.

**Its stages.** Once per run:
  0. **preflight** — ports 80, 443 and the admin port free, the Playwright library the image's version, the host's
     containers and volumes recorded; **browser image** — the overlay's Playwright image, which also runs the suites.
  1. **candidate** — the candidate as a release, by lane L1's own `assets` stage (`release_assets.py`, the publish's
     producer) into the run's `src/`; **candidate tree** — the checkout whose reader and browser spec run (on a host
     that is not Linux its install also takes the suite container's native packages, as lane L2's).

Then for each backup of the shard, one after another, in its own directory `<tag>/` and its own compose project — a red
backup is red and the next one still runs (each stage is named `<tag> <stage>`):
  2. **archive** — the archive's tag `<registry>/veyq-backups:<major>.<minor>` read (`archive_backup.lookup`): a
     backup the selection names that is not archived is RED, naming the release — a defect, never a skip (ADR 0091
     decision 6, point 5) — and so is a registry that will not say. Then `cosign verify` of the digest the tag names,
     under `archive.yml`'s identity (`archive_backup.OIDC_ISSUER` / `IDENTITY_REGEXP`, read from there, never
     restated): an artifact signed under any other identity — a release's, a snapshot's, a key's — is refused. Then
     `archive_backup.pull` BY THAT DIGEST, so the bytes read are the ones verified: every layer against its descriptor,
     every unpacked file against `archive.json`. Red unless `archive.json` names this release, the highest migration
     the selection read from its pin file, a record version the candidate's reader reads, and the domain this lane's
     stack serves (read from `archive.json`, never assumed: a domain the run's Pebble, Traefik alias and browser are
     not set up for is red, naming it).
  3. **pebble**, **install**, **certificate** — as lane L1's: the candidate installed FRESH by its own `veyqctl install
     --unsigned-local` on the archived domain (`VEYQ_DOMAIN` from `archive.json`), the overlay without its release
     check test double as the override file (lane L2's [override]: the double stamps the instance with a fixed release).
  4. **restore** — the CANDIDATE's `veyqctl restore <capture> --domain <archived domain> --key-from <key>
     --objects-from <pool>` (the copy the install keeps in `home/releases/<candidate>/`): the instance is empty, so
     the pool's objects go into its store; the capture's database is loaded and the app migrates it up at its start.
     Red unless it exits 0 with the candidate running. A refusal names itself in `restore.log`; Flyway's refusal is
     read out of the app's log and named (`run_web_e2e.flyway_cause`).
  5. **read** — the candidate's reader (`upgrade.b-read.e2e.test.ts`, lane L2's `read`) against the restored
     instance with the archive's record (`upgrade-record.json`, shape-checked by `run_web_e2e.upgrade_record_problems`)
     in the backup's `group/`: `configSecretState` OK first, then every item byte for byte.
  6. **browser** — the candidate's one spec of the `upgrade` project (`tests/e2e/tests/upgrade/`, lane L2's
     `browser`): the archived owner signs in on a new device and opens the Memory and the Depot files.
  7. **evidence**, **teardown**, **host** — as L1's, per backup: the project is torn down before the next backup
     installs on the same ports, and the host check runs after each.

**What a shard hands its record** (`snapshot.py lane-result --lane L3`, checks.yml's `stack-restore`): on a green run,
`--asset-set-out FILE` the hash of the candidate's release files (`release_assets.py --asset-set`, as L1's and L2's)
and `--backups-out FILE` the backups it restored, each `{"tag", "digest"}` with the artifact's manifest digest
cosign verified. A shard the selection leaves empty restores nothing and passes, saying so; with those flags it still
produces the candidate's release files for their hash and writes `[]`, so every shard of the matrix is attested.

**`--resolve`** prints the selection (`backups=<tag>,<tag>,…`, nothing after `=` when it is empty) — the whole of it, or
one shard's with `--shard` — and runs nothing: checks.yml's resolve step, and the promotion's comparison of an
attested backup list with the one the selection yields now (`scripts/promote.py`, check 8).

**The run directory** (`.e2e-stack-out/l3-<shard>-<stamp>/`, gitignored, kept after the run): `src/` (the
candidate's release), and per backup `<tag>/`: `archive/` (the pulled artifact, unpacked under `content/`), `home/`
(the instance), `override.yml`, `install.log`, `restore.log`, `read-<tag>.log`, `reading-<tag>.json`,
`group/upgrade-record.json`, the browser's `junit.xml`, `report/` and traces, `compose.log`, `ps.json`.

**Not a `validate_*` guard.** It needs Docker, cosign (`veyqctl`'s floor, 3.1.3), `oras` (`VEYQ_ORAS` or the PATH), the
network to GHCR, host ports 80 and 443, the root `pnpm install`, and the tags (a full clone). Its pure parts — the
shard split and the archive's facts it checks — are held by `scripts/stack_restore_selftest.py`, with the selection.

**Its limits.** Pebble, the overlay and the host check are L1's, with L1's limits. The backups are written by each
release's API seeder (`archive.yml`), so a data format only the browser writes is not in them. The candidate is
installed `--unsigned-local`, as in L1 and L2. One backup per schema state: a release that writes a new data format
without a migration is covered only at the edges of its state — the oldest release here, the newest by L2 (ADR 0091
decision 6, point 4's limit).

Usage:
    python3 scripts/run_stack_restore.py --resolve [--shard N]          # print `backups=…`, run nothing
    python3 scripts/run_stack_restore.py --app veyq:local --admin veyq-admin:local --shard 1 [--keep]
    python3 scripts/run_stack_restore.py --app … --admin … --backup v1.260   # one archived backup, named
    python3 scripts/run_stack_restore.py --app … --admin … --shard 1 --asset-set-out F --backups-out G   # CI

`--registry` names the archive's registry namespace (default `ghcr.io/raddatzk`, `archive_backup.SOURCE`'s owner);
`--plain-http` reads a local one. Environment: lane L1's (`VEYQ_STACK_WAIT_SECS`, `VEYQ_STACK_CERT_SECS`,
`VEYQ_STACK_ADMIN_PORT`), `VEYQ_ORAS` for an `oras` not on the PATH.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path
from typing import NamedTuple

import stack_lane as lane  # the stack primitives lane L1 runs on
from stack_lane import Red, run

import archive_backup as archive  # the archive's reference, its registry reads, its pull and the identity it is signed under
import run_stack_upgrade as l2  # lane L2's building block: the override file, the suites, the browser spec, the summary
import snapshot  # the shard count, read from checks.yml's `stack-restore` matrix

proof = l2.proof  # run_web_e2e: the selection, the record's shape, Flyway's refusal read out of a log

DEFAULT_REGISTRY = "ghcr.io/raddatzk"
# What one shard may carry and still fit the job's 15 minutes (ADR 0091 decision 1): a backup costs its pull, a fresh
# install, the restore, the reader, the browser spec and the teardown; the shard's fixed part is the dependencies, the
# browser image and the candidate's release. Measured on the first push's L3 run (46154117080, 2026-11-28): 4 min 9 s
# and 4 min 1 s for two backups, 3 min 3 s for one — about 3 minutes fixed and one a backup, so four is about 7
# minutes, with room. A shard over it is red, naming the fix.
MAX_PER_SHARD = 4


class Backup(NamedTuple):
    """One backup of the selection: its release tag and the schema state the selection read from its pin file."""
    tag: str
    highest: str  # the digits of `V<…>`, as run_web_e2e.highest_migration returns them


# ---------------------------------------------------------------- which backups a shard restores


def shard_of(selection: list[Backup], shard: str, shards: list[str]) -> list[Backup]:
    """The backups shard `shard` restores: the selection dealt round-robin, in version order, over `shards` (checks.yml's
    matrix, in its own order) — so neighbouring states, whose restores cost alike, land on different shards."""
    if shard not in shards:
        raise Red(f"shard {shard!r} is not one of checks.yml's `{snapshot.RESTORE_JOB}` shards {shards}")
    ordered = sorted(selection, key=lambda b: proof.release_order(b.tag))
    index = shards.index(shard)
    return [b for i, b in enumerate(ordered) if i % len(shards) == index]


def shards_in_order() -> list[str]:
    """checks.yml's `stack-restore` shards in the matrix's own order (snapshot.restore_shards sorts them as strings)."""
    shards = snapshot.restore_shards(lane.ROOT)
    return sorted(shards, key=lambda s: (len(s), s))


def selection() -> list[Backup]:
    try:
        return [Backup(tag, highest) for tag, highest in proof.backup_selection_ref()]
    except ValueError as error:
        raise SystemExit(f"[l3] {error}") from None


def check_capacity(chosen: list[Backup], shard: str) -> None:
    if len(chosen) > MAX_PER_SHARD:
        raise Red(f"shard {shard} carries {len(chosen)} backups ({', '.join(b.tag for b in chosen)}), more than the "
                  f"{MAX_PER_SHARD} one shard fits in the job's budget — add a shard to checks.yml's `"
                  f"{snapshot.RESTORE_JOB}` matrix and to publish.yml's `predicates --lane L3`")


# ---------------------------------------------------------------- stage 2: the archived backup, verified and pulled


def check_facts(doc: dict, backup: Backup) -> str:
    """`archive.json` against what the selection expects of it; the domain to install on."""
    if doc.get("tag") != backup.tag:
        raise Red(f"the archive at {backup.tag}'s tag describes {doc.get('tag')!r}")
    if doc.get("highestMigration") != f"V{backup.highest}":
        raise Red(f"the archive of {backup.tag} holds schema state {doc.get('highestMigration')}, and {backup.tag}'s pin "
                  f"file names V{backup.highest} — the backup is not of the state the selection restores it for")
    if doc.get("recordVersion") not in proof.UPGRADE_RECORD_VERSIONS:
        raise Red(f"the archive of {backup.tag} carries record version {doc.get('recordVersion')!r}; the candidate's "
                  f"reader reads {list(proof.UPGRADE_RECORD_VERSIONS)}")
    domain = doc.get("domain")
    if domain != lane.DOMAIN:
        raise Red(f"the archive of {backup.tag} names the tenant domain {domain!r}; this lane's stack serves "
                  f"{lane.DOMAIN} alone (its Pebble, its Traefik alias, the browser's stack.json)")
    return domain


def cosign_verify(reference: str, plain_http: bool) -> None:
    """`cosign verify` of `reference` (by digest) under archive.yml's identity; red on anything else."""
    insecure = ["--allow-insecure-registry", "--allow-http-registry"] if plain_http else []
    result = run("cosign", "verify", *insecure, "--certificate-oidc-issuer", archive.OIDC_ISSUER,
                 "--certificate-identity-regexp", archive.IDENTITY_REGEXP, reference, check=False)
    if result.returncode != 0:
        said = (result.stderr or "").strip().splitlines()
        raise Red(f"{reference} does not verify as an archive (signed by {archive.ARCHIVE_WORKFLOW} on a publishing "
                  f"branch): {said[-1][:300] if said else f'cosign exited {result.returncode}'}")
    print(f"verified: {reference} is signed under {archive.ARCHIVE_WORKFLOW}'s identity", flush=True)


class Pulled(NamedTuple):
    backup: Backup
    digest: str
    content: Path   # the unpacked capture/, pool/, key and record
    domain: str


def fetch(backup: Backup, registry: str, plain_http: bool, bdir: Path) -> Pulled:
    ref = archive.artifact_ref(backup.tag, registry)
    found = archive.lookup(ref, plain_http)
    if found.state == "absent":
        raise Red(f"{backup.tag} is selected and not archived: {ref} is not found ({found.said[:200]}) — dispatch "
                  f"{archive.ARCHIVE_WORKFLOW} with tag {backup.tag}; a selected backup is never skipped")
    if found.state != "present" or not found.digest:
        raise Red(f"the registry will not say whether {backup.tag} is archived at {ref} ({found.said[:200]})")
    by_digest = ref.at(found.digest)
    cosign_verify(by_digest, plain_http)
    into = bdir / "archive"
    doc = archive.pull(by_digest, into, plain_http)
    domain = check_facts(doc, backup)
    return Pulled(backup, found.digest, into / "content", domain)


# ---------------------------------------------------------------- stages 3 to 6: a fresh candidate, restored, read


def install_values(candidate: lane.Release, domain: str, project: str, bdir: Path) -> dict[str, str]:
    """The run's values (`stack_lane.base_values`) and the operator's the candidate's template assigns, on the
    archived domain."""
    template = {m.group(1) for m in map(lane.ASSIGNMENT.match,
                                         (candidate.tag / "env.example").read_text().splitlines()) if m}
    if "VEYQ_DOMAIN" not in template:
        raise Red("the candidate's env.example assigns no VEYQ_DOMAIN: this run cannot name the instance's domain")
    operator = {"VEYQ_DOMAIN": domain, "VEYQ_ACME_EMAIL": f"acme@{domain}"}
    return {**{k: v for k, v in operator.items() if k in template}, **lane.base_values(project, bdir)}


def install(candidate: lane.Release, bdir: Path, values: dict[str, str], pebble_id: str, override: Path) -> lane.Installed:
    shown = l2.shorten(f"{candidate.tag}/veyqctl --home home --source src install --unsigned-local --set …")
    try:
        installed = lane.install(candidate, bdir, values, pebble_id, verified=False, override=override)
    except Red:
        l2.STEPS.append(l2.Step("install", shown, "red (install.log)"))
        raise
    l2.STEPS.append(l2.Step("install", shown, "exit 0"))
    if installed.release != candidate.tag.name[1:]:
        raise Red(f"`veyqctl install` installed release {installed.release}, not the candidate {candidate.tag.name}")
    return installed


def restore(pulled: Pulled, installed: lane.Installed, bdir: Path) -> lane.Installed:
    """The candidate's own `veyqctl restore`, the pool's objects into the empty store, the key placed; red names
    Flyway's refusal when the app's log holds one."""
    content = pulled.content.absolute()
    veyqctl = installed.home / "releases" / installed.release / "veyqctl"
    status = l2.step(f"{pulled.backup.tag} restore",
                     [sys.executable, veyqctl, "--home", installed.home, "restore", content / archive.CAPTURE_DIR,
                      "--domain", pulled.domain, "--key-from", content / archive.KEY_FILE,
                      "--objects-from", content / archive.POOL_DIR], "restore.log", bdir,
                     {"VEYQ_WAIT_SECS": str(lane.WAIT_SECS)})
    if status != 0:
        cause = proof.flyway_cause(l2.app_log(installed.project, bdir))
        raise Red(f"the candidate's `veyqctl restore` of {pulled.backup.tag}'s backup exited {status}"
                  + (f": {cause}" if cause else "") + " — restore.log in its directory says where")
    after = lane.addressed(installed.home)
    if after.release != installed.release:
        raise Red(f"after `veyqctl restore` the instance runs {after.release}, not the candidate {installed.release}")
    print(f"restore: the candidate's veyqctl restored {pulled.backup.tag}'s backup onto {after.release}", flush=True)
    return after


def place_record(pulled: Pulled, bdir: Path) -> dict:
    """The archive's seeder record into the backup's `group/`, where the reader and the browser spec read it."""
    record = json.loads((pulled.content / archive.RECORD_FILE).read_text(encoding="utf-8"))
    problems = proof.upgrade_record_problems(record)
    if problems:
        raise Red(f"the archive of {pulled.backup.tag} carries a record the reader does not read: {'; '.join(problems)}")
    group = bdir / l2.GROUP_DIR
    group.mkdir(exist_ok=True)
    shutil.copyfile(pulled.content / archive.RECORD_FILE, group / proof.UPGRADE_RECORD_FILE)
    return record


# ---------------------------------------------------------------- what a green shard hands over to its record


def hand_over(args: argparse.Namespace, candidate: lane.Release | None, restored: list[dict]) -> None:
    if args.asset_set_out is not None:
        import release_assets  # the publish's producer, whose hash the promotion compares (ADR 0091 decision 5)

        if candidate is None:
            raise Red("no candidate release to hash")
        try:
            digest = release_assets.asset_set_digest(candidate.tag)
        except release_assets.ReleaseError as error:
            raise Red(f"asset set: {error}") from None
        args.asset_set_out.parent.mkdir(parents=True, exist_ok=True)
        args.asset_set_out.write_text(digest + "\n")
        print(f"asset set of the candidate: {digest}")
    if args.backups_out is not None:
        args.backups_out.parent.mkdir(parents=True, exist_ok=True)
        args.backups_out.write_text(json.dumps(restored) + "\n")
        print(f"backups: {json.dumps(restored)}")


# ---------------------------------------------------------------- the run


def blocking(red: list[str], name: str) -> list[str]:
    """A backup's stage is skipped by a red of the run's shared stages or of its own backup — never by another
    backup's: each backup is red or green on its own."""
    mine = name.split(" ", 1)[0] if proof.RELEASE_TAG.match(name.split(" ", 1)[0]) else None
    out = []
    for verdict in red:
        owner = verdict.split(" ", 1)[0]
        owner = owner if proof.RELEASE_TAG.match(owner) else None
        if owner is None or owner == mine:
            out.append(verdict)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    which = parser.add_mutually_exclusive_group()
    which.add_argument("--shard", help="one of checks.yml's `stack-restore` shards: its part of the selection")
    which.add_argument("--backup", metavar="TAG", help="one archived backup, by its release tag (a local run)")
    parser.add_argument("--resolve", action="store_true",
                        help="print `backups=<tag>,…` (the selection, or the shard's part of it) and run nothing")
    parser.add_argument("--app", help="the candidate app image")
    parser.add_argument("--admin", help="the candidate admin image")
    parser.add_argument("--registry", default=DEFAULT_REGISTRY, help="the archive's registry namespace")
    parser.add_argument("--plain-http", action="store_true", help="a local registry without TLS")
    parser.add_argument("--keep", action="store_true", help="leave the last backup's stack running (no teardown)")
    parser.add_argument("--asset-set-out", type=Path, default=None, metavar="FILE",
                        help="on a green run (or a shard with nothing to restore), the hash of the candidate's asset set")
    parser.add_argument("--backups-out", type=Path, default=None, metavar="FILE",
                        help="on a green run, the backups restored as JSON `[{tag, digest}]`; `[]` for an empty shard")
    args = parser.parse_args()

    everything = selection()
    if args.backup:
        chosen = [b for b in everything if b.tag == args.backup]
        if not chosen:
            raise SystemExit(f"[l3] {args.backup} is not in the selection ({', '.join(b.tag for b in everything) or 'none'})")
        label = args.backup
    elif args.shard:
        try:
            chosen = shard_of(everything, args.shard, shards_in_order())
        except Red as error:
            raise SystemExit(f"[l3] {error}") from None
        label = f"shard {args.shard}"
    else:
        chosen, label = everything, "the selection"
    print(f"[l3] the selection: {', '.join(f'{b.tag} (V{b.highest})' for b in everything) or 'none'}; {label}: "
          f"{', '.join(b.tag for b in chosen) or 'none'}", flush=True)
    if args.resolve:
        print(f"backups={','.join(b.tag for b in chosen)}")
        return 0
    if not args.app or not args.admin:
        parser.error("--app and --admin name the candidate")
    if args.shard is None and args.backup is None:
        parser.error("a run restores a shard (--shard) or one backup (--backup)")
    if args.shard is not None:
        try:
            check_capacity(chosen, args.shard)
        except Red as error:
            print(f"RED\n  capacity: {error}")
            return 1

    stamp = time.strftime("%Y%m%d-%H%M%S")
    workdir = lane.RUN_ROOT / f"l3-{(args.shard or args.backup).replace('.', '_')}-{stamp}"
    workdir.mkdir(parents=True)
    if not chosen:
        print("[l3] nothing to restore in this shard: it passes, restoring nothing")
        if args.asset_set_out or args.backups_out:
            try:
                hand_over(args, l2.candidate_release(args.app, args.admin, workdir), [])
            except Red as error:
                print(f"RED\n  candidate: {error}")
                return 1
        print("GREEN (nothing to restore)")
        return 0

    stage = lane.Stages(blocking)
    before = stage("preflight", lambda: (lane.ports_free(), lane.browser_pins(), lane.host_snapshot())[2])
    stage("browser image", lane.browser_image)
    candidate = stage("candidate", l2.candidate_release, args.app, args.admin, workdir)
    stage("candidate tree", l2.candidate_tree)
    restored: list[dict] = []
    for n, backup in enumerate(chosen, 1):
        tag = backup.tag
        bdir = workdir / tag
        bdir.mkdir()
        project = f"veyq-l3-{stamp}-{n}"
        started = False
        try:
            pulled = stage(f"{tag} archive", fetch, backup, args.registry, args.plain_http, bdir)
            if not (pulled and candidate):
                continue
            override_file = stage(f"{tag} override", l2.override, bdir)
            if not override_file:
                continue
            values = install_values(candidate, pulled.domain, project, bdir)
            started = True
            pebble_id = stage(f"{tag} pebble", lane.pebble, candidate, bdir, values, args.app, override_file)
            installed = stage(f"{tag} install", install, candidate, bdir, values, pebble_id, override_file)
            stage(f"{tag} certificate", lane.certificate, bdir)
            installed = stage(f"{tag} restore", restore, pulled, installed, bdir) if installed else None
            stage(f"{tag} record", place_record, pulled, bdir)
            if installed:
                stage(f"{tag} read", l2.read, installed, lane.ROOT, bdir, stamp, tag)
                stage(f"{tag} browser", l2.browser, installed, bdir)
            if not any(r.startswith(f"{tag} ") for r in stage.red):
                restored.append({"tag": tag, "digest": pulled.digest})
        finally:
            if started:
                stage(f"{tag} evidence", lane.evidence, project, bdir, always=True)
                keep = args.keep and n == len(chosen)
                if not keep:
                    stage(f"{tag} teardown", lane.teardown, project, bdir, always=True)
                if before is not None:
                    stage(f"{tag} host", lambda: lane.host_untouched(before, lane.host_snapshot(), project, not keep),
                          always=True)

    print(f"\n== summary — lane L3, {label}, run directory {workdir.relative_to(lane.ROOT)}")
    for name, secs, verdict in stage.stages:
        print(f"  {name:<22} {secs:7.1f}s  {verdict}")
    print(f"  {'total':<22} {stage.total():7.1f}s")
    print("steps:")
    for done in l2.STEPS:
        print(f"  {done.stage:<22} {done.outcome:<24} {done.command}")
    for backup in chosen:
        reading = l2.READINGS.get(backup.tag)
        state = l2.shown_state(reading, "(never read)") if reading is not None else "(never read)"
        verdict = "GREEN" if any(r["tag"] == backup.tag for r in restored) else "RED"
        print(f"backup {backup.tag} (V{backup.highest}): {verdict}; configSecretState {state}")
    if stage.red:
        print("RED\n  " + "\n  ".join(stage.red))
        return 1
    try:
        hand_over(args, candidate, restored)
    except Red as error:
        print(f"RED\n  hand-over: {error}")
        return 1
    print(f"GREEN — {', '.join(b.tag for b in chosen)} restored into fresh installs of the candidate, read and opened "
          f"in a browser")
    return 0


if __name__ == "__main__":
    sys.exit(main())
