#!/usr/bin/env python3
"""Lane L2 of ADR 0091: a published release upgraded in place to the candidate by its own `veyqctl`, written by its own
seeder, read by the candidate, rolled back (ADR 0091 decision 6, amended 2026-11-26).

**What it does.** One leg: a starting release — the newest release (`--leg newest`, `run_web_e2e.py`'s
`default_predecessor_ref`), the candidate's major entry (`--leg major-entry`, `major_entry_proof_ref`, crossing the
major in place) or a tag named with `--from` — installed on the production recipe exactly as a Server Owner installs
it, written into by that release's own API seeder, upgraded to the candidate and back by that release's own
`veyqctl`, and read by each side's own reader. Stages 0 to 6 are the building block lane L3 reuses (a published
release, installed and seeded); 7 to 12 are the upgrade in place. Its stages:

  0. **preflight** — ports 80, 443 and the admin port free, the Playwright library the image's version, the host's
     containers and volumes recorded; **browser image** — the Playwright image the overlay pins, which also runs the
     suites.
  1. **start release** — the starting release's published files, `gh release download <tag>` into the run's
     `mirror/<tag>/` with a `releases.json` naming it alone: a mirror in the layout `veyqctl --source` reads, carrying
     the release's `manifest.json.bundle`, so the install below verifies everything under the release identity and the
     run does not spend GitHub's unauthenticated API budget (`GitHubSource`). Red unless the release carries its
     `veyqctl` and the bundle.
  2. **pebble** — as lane L1's (`stack_lane.pebble`): Pebble alone, its root read and the app's truststore written,
     before there is an instance. The truststore is written by the CANDIDATE image's `keytool` (`--app`), a local
     build: the starting release's images are pulled only by `veyqctl`, once it has verified them.
  3. **install** — the starting release's OWN `veyqctl` (`mirror/<tag>/veyqctl`, the copy its operator fetches),
     `install --source mirror/` WITHOUT `--unsigned-local`: the manifest verified against its bundle and both images by
     the manifest's digests, with the release identity that `veyqctl` carries, before anything is pulled or started.
     The operator's values are given only for the keys the STARTING release's `env.example` assigns (an older template
     asks for `VEYQ_ACME_EMAIL`, today's does not), stdin closed. The override file is the overlay without its release
     check test double ([override]): that double stamps the instance with a fixed release, which an upgrade changes.
  4. **certificate** — as L1's: TLS to `:443` for `veyq.test` against the run's Pebble root alone, then
     `GET /api/setup` 200.
  5. **start tree** — a detached worktree of the starting release's tag in the run directory, its dependencies
     installed by its own lockfile (which generates its client): `run_web_e2e.prepare_predecessor`, the jar-level
     proof's own, without the jar. On a host that is not Linux (a laptop) the install also fetches the native packages
     of the suite container's platform (`--os`/`--cpu`/`--libc`), which a CI runner has by being one.
  6. **seed** — the starting release's `upgrade.a-seed.e2e.test.ts`, run by that tree's vitest in the overlay's
     `browser` service — the container L1's browser runs in, on the run's `edge` and `admin` networks, with its
     entrypoint replaced by `node` and the tree mounted at `/suite` — against the instance: `https://veyq.test` through
     Traefik, the admin API through the admin container's nginx (`http://admin:8080`), the run's Pebble root as
     `NODE_EXTRA_CA_CERTS`. It cold-births the instance itself (`setupRequired=true`), so nothing here sets it up. Red
     unless it exits 0 and its record (`group/upgrade-record.json`) has the shape the candidate's reader reads
     (`run_web_e2e.upgrade_record_problems`).
  7. **candidate** — the candidate as a release, by lane L1's own `assets` stage (`release_assets.py`, the publish's
     producer) into the run's `src/`: what `upgrade` reads with `--source`.
  8. **candidate tree** — the candidate's checkout, whose reader and browser spec run; on a host that is not Linux its
     install takes the suite container's native packages (as stage 5's).
  9. **upgrade** — `VEYQ_RELEASE` in `.env` set to the candidate's release (crossing a major needs it named; `upgrade`
     has no flag for it), then the STARTING release's `veyqctl` (`home/releases/<start>/veyqctl`, the one its operator
     has installed — never the candidate's) runs `upgrade --unsigned-local --source src/ --key-into upgrade.key`: its
     backup, its template check, its health wait. Red unless it exits 0 with the candidate running and exactly one
     capture `*-before-<candidate>` and the key left; on a red, Flyway's refusal is read out of the app's log and named
     (`run_web_e2e.flyway_cause`) — a migration edited after it was released stops the upgrade there.
  10. **read** — the CANDIDATE's reader (`upgrade.b-read.e2e.test.ts`, the `upgrade` group's last member in this
     checkout) in the same container: `configSecretState` OK first, then every item of the record, byte for byte.
  11. **browser** — the candidate's one spec of this lane (`tests/e2e/tests/upgrade/`, project `upgrade`) in L1's
     browser service: the seeded owner signs in on a new device and opens the seeded Memory and Depot files.
  12. **rollback** — the starting release's `veyqctl rollback <the upgrade's capture> --key-from upgrade.key --yes`:
     the recovery `upgrade` names on a failure, run. Then **read again** — the STARTING release's own reader, from its
     worktree (the path is in the log), against the instance it is back on.
  13. **evidence**, **teardown**, **host** — as L1's; the worktree is removed last.

The summary prints each stage's wall time (the budget: the job's 15 minutes), every operator step with its command and
outcome, then — as the jar-level proof's summary does — the predecessor, both highest migrations, the migrations
changed or removed between them, each reading's `configSecretState`, and the outcome with its cause.

**What a leg hands its record** (`snapshot.py lane-result --lane L2`, checks.yml's `stack-upgrade`): on a green run,
`--asset-set-out FILE` the hash of the candidate's release files (`release_assets.py --asset-set`, as lane L1's) and
`--predecessor-out FILE` the starting release with the app and admin digests the manifest `veyqctl install` verified
named (`{"tag", "images": {"veyq", "veyq-admin"}}`). A leg that resolves to nothing — the major entry while it is the
newest release, or no previous major — builds and installs nothing and passes, saying so; with those flags it still
produces the candidate's release files for their hash and writes `null` as its predecessor, so every leg of the
matrix is attested and a promotion can tell "had none" from "lost".

**The run directory** (`.e2e-stack-out/l2-<leg>-<stamp>/`, gitignored, kept after the run): `mirror/` (the starting
release), `src/` (the candidate's), `home/` (the instance, with `backups/`), `override.yml`, `install.log`, `seed.log`,
`upgrade.log`, `read-*.log`, `reading-*.json`, `rollback.log`, `group/upgrade-record.json`, the browser's `junit.xml`,
`report/` and traces, `compose.log`, `ps.json`.

**Not a `validate_*` guard.** It needs Docker, cosign (`veyqctl`'s floor, 3.1.3), `gh`, the network to GitHub and
GHCR, host ports 80 and 443, and the root `pnpm install`.

**Its limits.** Pebble, the overlay and the host check are L1's, with L1's limits. The seeder writes what a
person writes through the API (the seeder's header says what), not through the browser; the browser spec opens the
Memory and the Depot files, the reader asserts every item. The candidate is installed `--unsigned-local`, as in L1:
the signature path is exercised by the starting release's install only. No wire sweep runs here (L1's and the API
suites' are the operator-blind proof). A green run proves the upgrade from the release it installed; a data format
written by a release between the two legs' starting points is L3's (ADR 0091 decision 6, point 4's limit).

Usage:
    python3 scripts/run_stack_upgrade.py --app veyq:local --admin veyq-admin:local --leg newest [--keep]
    python3 scripts/run_stack_upgrade.py --app … --admin … --from v1.318
    python3 scripts/run_stack_upgrade.py --leg major-entry --resolve     # print `predecessor=<tag>` (or nothing), run nothing
    python3 scripts/run_stack_upgrade.py --app … --admin … --leg newest --asset-set-out F --predecessor-out G   # CI

Environment: lane L1's (`VEYQ_STACK_WAIT_SECS`, `VEYQ_STACK_CERT_SECS`, `VEYQ_STACK_ADMIN_PORT`), and `GH_TOKEN` for
`gh` where it is not signed in (a CI job's `github.token`).
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import NamedTuple

import stack_lane as lane  # the stack primitives lane L1 runs on (the run directory, Pebble, install, the browser)
from stack_lane import Red, run

import repo_layout

# The jar-level upgrade proof's predecessor resolution, worktree build and record check: ONE derivation of which
# release a leg starts from, so this lane and that proof never start from different releases (it imports no stack).
import run_web_e2e as proof

LEGS = ("newest", "major-entry")
RELEASE_TAG = re.compile(r"^v\d+\.\d+$")
# The operator's values a release's template may assign and this run gives, each only where the STARTING release's
# `env.example` assigns it (stage 3). `VEYQ_DOMAIN` every template assigns; `VEYQ_ACME_EMAIL` an older one does
# (v0.2480's), today's does not.
OPERATOR_VALUES = {"VEYQ_DOMAIN": lane.DOMAIN, "VEYQ_ACME_EMAIL": f"acme@{lane.DOMAIN}"}
REQUIRED_ASSETS = ("veyqctl", "manifest.json", "manifest.json.bundle", "env.example", "docker-compose.prod.yml")
# The overlay's release check test double (tests/e2e/stack/docker-compose.e2e.yml, `release-mirror` and the app's
# stamp): left out of this lane's override file ([override]).
RELEASE_CHECK_DOUBLE = ("VEYQ_VERSION", "VEYQ_RELEASE_CHECK_EVERY")
# The suite tree inside the browser service's container, and the record the seeder writes there.
SUITE_MOUNT = "/suite"
GROUP_DIR = "group"
# `.env`'s release selector (ADR 0096 decision 4), and the Playwright project of this lane's browser spec.
RELEASE_KEY = "VEYQ_RELEASE"
BROWSER_PROJECT = "upgrade"
SUITE_TEST_MS = "120000"  # run_web_e2e.TEST_MS's default: the seeder's one `it` births an instance and seals content


class Start(NamedTuple):
    """The starting release: its tag, how the leg chose it, and its files as a mirror `veyqctl --source` reads."""
    tag: str
    asked: str
    release: lane.Release
    manifest: dict


# ---------------------------------------------------------------- which release a leg starts from


def resolve(leg: str | None, tag: str | None) -> tuple[str | None, str]:
    """(the starting release's tag or None, the line saying which and why). None only for the major-entry leg, when
    the candidate's major has no entry other than the newest release, which the newest leg covers."""
    if tag is not None:
        chosen, why = tag, f"{tag} (named with --from)"
    elif leg == "newest":
        try:
            chosen, why = proof.default_predecessor_ref()
        except ValueError as error:
            raise SystemExit(f"[l2] {error}") from None
    else:
        try:
            chosen, why = proof.major_entry_proof_ref()
        except ValueError as error:
            raise SystemExit(f"[l2] {error}") from None
        if chosen is None:
            return None, why
    if not RELEASE_TAG.match(chosen):
        # Before the first release the default predecessor is a commit (`HEAD^1`), which has no published release.
        raise SystemExit(f"[l2] the leg starts from {why}, which is no published release (v<major>.<minor>): lane L2 "
                         "installs a release from its published files, never a commit")
    return chosen, why


# ---------------------------------------------------------------- stage 1: the starting release as a mirror


def start_release(tag: str, asked: str, workdir: Path) -> Start:
    """`gh release download` of the release's files into `mirror/<tag>/`, and a `releases.json` naming it alone."""
    repo_layout.tools_importable()
    import veyqctl  # the upstream the operator's veyqctl reads releases from

    mirror = workdir / "mirror"
    target = mirror / tag
    target.mkdir(parents=True)
    run("gh", "release", "download", tag, "--repo", veyqctl.UPSTREAM, "--dir", str(target))
    missing = [name for name in REQUIRED_ASSETS if not (target / name).is_file()]
    if missing:
        raise Red(f"the published release {tag} carries no {', '.join(missing)}: without them `veyqctl` installs and "
                  "verifies nothing")
    (mirror / "releases.json").write_text(json.dumps({"releases": [{"tag": tag, "prerelease": False}]}, indent=2) + "\n",
                                          encoding="utf-8")
    # Read for the summary and the record only — `veyqctl install` verifies it before anything trusts a field of it.
    manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
    names = sorted(p.name for p in target.iterdir())
    print(f"start release: {tag} ({asked}) from github.com/{veyqctl.UPSTREAM}: {', '.join(names)}", flush=True)
    return Start(tag, asked, lane.Release(mirror, target), manifest)


def override(workdir: Path) -> Path:
    """The run's override file: the overlay with its release check test double left out — the stub mirror service and
    the app's fixed release stamp, which would hold the instance at one release across the upgrade — and nothing
    else changed. Read from the overlay, never written a second time."""
    doc = lane.yaml.safe_load(lane.OVERLAY.read_text(encoding="utf-8"))
    services = doc["services"]
    if "release-mirror" not in services or not all(k in services["app"]["environment"] for k in RELEASE_CHECK_DOUBLE):
        raise Red("the overlay's release check test double moved (no `release-mirror`, or the app sets no "
                  f"{', '.join(RELEASE_CHECK_DOUBLE)}): this lane's reading of the overlay must follow it")
    del services["release-mirror"]
    for key in RELEASE_CHECK_DOUBLE:
        del services["app"]["environment"][key]
    path = workdir / "override.yml"
    path.write_text(f"# {lane.OVERLAY.relative_to(lane.ROOT)} without its release check test double "
                    "(scripts/run_stack_upgrade.py, override).\n" + lane.yaml.safe_dump(doc, sort_keys=False),
                    encoding="utf-8")
    return path


def install_values(start: Start, project: str, workdir: Path) -> dict[str, str]:
    """The run's values (lane L1's, `stack_lane.base_values`) and the operator's the STARTING template assigns."""
    template = {m.group(1) for m in map(lane.ASSIGNMENT.match,
                                         (start.release.tag / "env.example").read_text().splitlines()) if m}
    if "VEYQ_DOMAIN" not in template:
        raise Red(f"{start.tag}'s env.example assigns no VEYQ_DOMAIN: this run cannot name the instance's domain")
    return {**{k: v for k, v in OPERATOR_VALUES.items() if k in template}, **lane.base_values(project, workdir)}


def install(start: Start, workdir: Path, values: dict[str, str], pebble_id: str, override_file: Path) -> lane.Installed:
    """The starting release's own `veyqctl install`, verified (`stack_lane.install` holds `state.json` to that), with
    `-v` where that `veyqctl` has it, so install.log names every `cosign` it ran."""
    veyqctl = start.release.tag / "veyqctl"
    usage = run(sys.executable, str(veyqctl), "install", "--help", check=False).stdout
    flags = ("-v",) if "--verbose" in usage else ()
    shown = shorten(f"{veyqctl} --home home --source mirror install {' '.join(flags)} --set … (verified)")
    try:
        installed = lane.install(start.release, workdir, values, pebble_id, verified=True, override=override_file,
                                 flags=flags)
    except Red:
        STEPS.append(Step("install", shown, "red (install.log)"))
        raise
    STEPS.append(Step("install", shown, "exit 0"))
    if installed.release != start.tag[1:]:
        raise Red(f"`veyqctl install` installed release {installed.release}, not {start.tag}")
    return installed


# ---------------------------------------------------------------- stages 5 and 6: a tree's suite in the browser service


def platform_args() -> tuple[str, ...]:
    """The extra pnpm install flags for the suite container's native packages (esbuild, rolldown, lightningcss …) on a
    host that is not Linux. None on Linux: a CI runner is the container's platform already."""
    if sys.platform.startswith("linux"):
        return ()
    arch = run("docker", "info", "--format", "{{.Architecture}}").stdout.strip()
    cpu = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "x64", "amd64": "x64"}.get(arch)
    if cpu is None:
        raise Red(f"Docker reports architecture {arch!r}, which this lane maps to no pnpm --cpu")
    print(f"platform: {platform.system()} host, suite container linux/{cpu}: the install adds its native packages",
          flush=True)
    return ("--os", "current", "--os", "linux", "--cpu", "current", "--cpu", cpu, "--libc", "current",
            "--libc", "glibc")


def start_tree(start: Start, workdir: Path) -> proof.Predecessor:
    """A worktree of the starting release's tag, installed by its own lockfile — the jar-level proof's build, no jar."""
    return proof.prepare_predecessor(start.tag, workdir, jar=False, install_args=platform_args())


def suite_env(stamp: str) -> dict[str, str]:
    """What a seeder or reader is told, inside the container (`src/web/src/test/e2eBootstrap.ts`, `upgradeRecord.ts`)."""
    return {
        "VEYQ_E2E": "1",
        "VEYQ_HOST": lane.DOMAIN,
        "VEYQ_BASE": f"https://{lane.DOMAIN}",
        "VEYQ_ADMIN_BASE": f"http://{lane.ADMIN_UPSTREAM}",
        "VEYQ_MAILPIT": lane.MAIL_API,
        "VEYQ_STAMP": stamp,
        "VEYQ_GROUP_DIR": f"{lane.CONTAINER_OUT}/{GROUP_DIR}",
        "NODE_EXTRA_CA_CERTS": f"{lane.CONTAINER_OUT}/pebble-root.pem",
        "HOME": "/tmp/home",
    }


def suite(installed: lane.Installed, tree: Path, test: str, workdir: Path, stamp: str, log_name: str) -> None:
    """One e2e suite of `tree` (a path below its web client's directory), by that tree's own vitest, in the overlay's
    `browser` service: the run's networks, its posture and uid, the run directory at /out, the tree at /suite."""
    web = repo_layout.surface_in(tree, repo_layout.WEB).relative_to(tree).as_posix()
    workdir_in = f"{SUITE_MOUNT}/{web}"
    env = [a for k, v in suite_env(stamp).items() for a in ("-e", f"{k}={v}")]
    cmd = [*installed.compose, "run", "--rm", "--no-deps", "-T", "-v", f"{tree}:{SUITE_MOUNT}", "-w", workdir_in,
           "--entrypoint", "node", *env, "browser", f"{workdir_in}/node_modules/vitest/vitest.mjs", "run", test,
           "--testTimeout", SUITE_TEST_MS]
    print("+", " ".join(cmd), flush=True)
    with (workdir / log_name).open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert proc.stdout is not None
        for line in proc.stdout:
            print(f"  | {line}", end="", flush=True)
            log.write(line)
        status = proc.wait()
    where = tree.relative_to(lane.ROOT).as_posix() if tree != lane.ROOT else "the candidate's checkout"
    STEPS.append(Step(log_name.removesuffix(".log"), f"vitest run {test} from {where}",
                      "exit 0" if status == 0 else f"exit {status} ({log_name})"))
    if status != 0:
        failed = first_failure(workdir / log_name)
        raise Red(f"{test} from {where} exited {status}" + (f": {failed}" if failed else "")
                  + f" — {log_name} in the run directory says the rest")


def seed(installed: lane.Installed, tree: proof.Predecessor, workdir: Path, stamp: str) -> dict:
    """The starting release's seeder against its own instance, and its record's shape checked."""
    web = tree.build.web
    seeder = proof.upgrade_seeder(p.relative_to(web).as_posix() for p in proof.discover(web))
    if seeder is None:
        raise Red(f"{tree.asked} carries no seeder (no `{proof.UPGRADE_GROUP}.*` e2e group)")
    (workdir / GROUP_DIR).mkdir(exist_ok=True)
    suite(installed, tree.build.root, seeder, workdir, stamp, "seed.log")
    record_file = workdir / GROUP_DIR / proof.UPGRADE_RECORD_FILE
    if not record_file.is_file():
        raise Red(f"the seeder exited 0 and wrote no {proof.UPGRADE_RECORD_FILE}")
    record = json.loads(record_file.read_text(encoding="utf-8"))
    problems = proof.upgrade_record_problems(record)
    if problems:
        raise Red(f"the seeder's record is not one the reader reads: {'; '.join(problems)}")
    print(f"seed: {seeder} from {tree.build.root.relative_to(lane.ROOT)} wrote record version {record['version']} "
          f"for tenant {record['owner']['tenantId']}", flush=True)
    return record


# ---------------------------------------------------------------- stages 8 to 13: upgrade, read, browser, rollback, read


class Step(NamedTuple):
    """One operator step of the run, for the summary: what ran, and how it ended."""
    stage: str
    command: str
    outcome: str


STEPS: list[Step] = []
# Each reader's reading (`upgrade-reading.json`: its configSecretState, and the items it opened), by who read — kept
# whether the reader passed or not, for the summary.
READINGS: dict[str, dict] = {}
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def first_failure(log: Path) -> str | None:
    """The first assertion a vitest run reported failing, as one line, from its log — what a red read names."""
    lines = [ANSI.sub("", line).strip().lstrip("|").strip()
             for line in log.read_text(encoding="utf-8", errors="replace").splitlines()]
    for marks in (("AssertionError", "Error:", "TypeError"), ("FAIL ",)):
        for text in lines:
            if text.startswith(marks):
                return text[:300]
    return None


def shorten(command: str) -> str:
    """A command as the summary shows it: paths below the checkout relative to it."""
    return command.replace(f"{lane.ROOT}/", "")


def step(stage: str, cmd: list[str], log_name: str, workdir: Path, env: dict[str, str] | None = None) -> int:
    """Run `cmd` with stdin closed, its output streamed and kept in `log_name`; recorded for the summary."""
    shown = shorten(" ".join(str(c) for c in cmd))
    print("+", shown, flush=True)
    with (workdir / log_name).open("w", encoding="utf-8") as log:
        proc = subprocess.Popen([str(c) for c in cmd], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, env={**os.environ, **(env or {})})
        assert proc.stdout is not None
        for line in proc.stdout:
            print(f"  | {line}", end="", flush=True)
            log.write(line)
        status = proc.wait()
    STEPS.append(Step(stage, shown, "exit 0" if status == 0 else f"exit {status} ({log_name})"))
    return status


def candidate_release(app: str, admin: str, workdir: Path) -> lane.Release:
    """The candidate as a release, by the publish's own producer — lane L1's `assets` stage, the same mirror layout."""
    import run_stack_e2e as l1  # lane L1's stage, by import: one producer of the candidate's release

    return l1.assets(app, admin, workdir)


def candidate_tree() -> None:
    """The candidate's own checkout, whose reader and browser spec run: on a host that is not Linux, its install also
    takes the suite container's native packages (on a CI runner the job's install is that already)."""
    extra = platform_args()
    if extra:
        status = step("candidate tree", ["pnpm", "install", "--frozen-lockfile", *extra], "candidate-install.log",
                      lane.RUN_ROOT)
        if status != 0:
            raise Red(f"the candidate's `pnpm install` for the suite container exited {status}")
    if not (repo_layout.WEB / "node_modules" / "vitest").exists():
        raise Red("the candidate's web client has no vitest installed — run `python3 scripts/pnpm_install_no_deprecated.py` "
                  "at the repository root first")


def set_release(env: Path, release: str) -> None:
    """`VEYQ_RELEASE=<release>` in the instance's `.env`, as its operator writes it to cross a major or pin a release
    (ADR 0096 decision 4): `upgrade` takes what `.env` selects and has no flag for it."""
    lines = env.read_text(encoding="utf-8").splitlines()
    hits = [i for i, line in enumerate(lines) if line.startswith(f"{RELEASE_KEY}=")]
    if len(hits) != 1:
        raise Red(f"the instance's .env assigns {RELEASE_KEY} {len(hits)} time(s), not once")
    lines[hits[0]] = f"{RELEASE_KEY}={release}"
    env.write_text("\n".join(lines) + "\n", encoding="utf-8")


class Upgraded(NamedTuple):
    installed: lane.Installed
    backup: Path   # the capture `upgrade` took before the candidate migrated
    key: Path      # its config-secret key, kept apart (`--key-into`)


def app_log(project: str, workdir: Path) -> str:
    return run(*lane.by_name(project), "logs", "--no-color", "app", check=False, cwd=workdir,
               env=lane.name_only_env()).stdout


def upgrade(start: Start, candidate: lane.Release, installed: lane.Installed, workdir: Path) -> Upgraded:
    """`VEYQ_RELEASE` set to the candidate, then the STARTING release's own `veyqctl upgrade --unsigned-local` with the
    candidate's files as its source: its backup (the key apart), its template check and its health wait. A red names
    Flyway's refusal when the app's log holds one (`run_web_e2e.flyway_cause`) — the defect class this lane exists for."""
    target = candidate.tag.name[1:]
    set_release(installed.env, target)
    veyqctl = installed.home / "releases" / start.tag[1:] / "veyqctl"
    key = workdir / "upgrade.key"
    status = step("upgrade", [sys.executable, veyqctl, "--home", installed.home, "--source", candidate.source,
                              "upgrade", "--unsigned-local", "--key-into", key], "upgrade.log", workdir,
                  {"VEYQ_WAIT_SECS": str(lane.WAIT_SECS)})
    if status != 0:
        cause = proof.flyway_cause(app_log(installed.project, workdir))
        raise Red(f"`veyqctl upgrade` ({start.tag}'s) exited {status}" + (f": {cause}" if cause else "")
                  + " — upgrade.log in the run directory says where")
    after = lane.addressed(installed.home)
    if after.release != target:
        raise Red(f"after `veyqctl upgrade` the instance runs {after.release}, not the candidate {target}")
    captures = sorted((installed.home / "backups").glob(f"*-before-{target}"))
    if len(captures) != 1 or not key.is_file():
        raise Red(f"`veyqctl upgrade` left {len(captures)} capture(s) before {target} and "
                  f"{'a' if key.is_file() else 'no'} key at {key.name}, not one of each")
    print(f"upgrade: {start.tag}'s veyqctl took the instance to {target}; its backup {captures[0].name}", flush=True)
    return Upgraded(after, captures[0], key)


# The states a reading's `configSecretState` can name (contracts/api/instance.openapi.yaml, an `enum`). What the lane
# prints is one of these constants, never the value it read: a state is no secret, but it is read under a name that
# says "secret", and a log line built from the read value would pass on whatever an altered reader put there. The
# constant's own name avoids that word: CodeQL's clear-text-logging query takes any name holding it for a secret
# (alert 44 on #111 traced the flow from the constant, not from the reading).
KEY_STATES = ("OK", "ABSENT", "MISMATCH")


def shown_state(reading: dict, absent: str) -> str:
    """The reading's `configSecretState` as one of `KEY_STATES`, `absent` when it holds none, or "not a
    state" for anything else."""
    if "configSecretState" not in reading:
        return absent
    value = reading.get("configSecretState")
    return next((state for state in KEY_STATES if state == value), "not a state")


def reader_of(web: Path) -> str:
    """A tree's reader: the `upgrade` group's last member, below its web client's directory."""
    members = sorted(p.relative_to(web).as_posix() for p in proof.discover(web)
                     if proof.group_of(p) == proof.UPGRADE_GROUP)
    if len(members) < 2:
        raise Red(f"{web} carries no reader (the `{proof.UPGRADE_GROUP}` group has {len(members)} member(s))")
    return members[-1]


def read(installed: lane.Installed, tree: Path, workdir: Path, stamp: str, name: str) -> dict:
    """A tree's reader against the instance; its reading (`configSecretState`, the items) kept as `reading-<name>`."""
    web = repo_layout.surface_in(tree, repo_layout.WEB)
    reader = reader_of(web)
    reading_file = workdir / GROUP_DIR / proof.UPGRADE_READING_FILE
    reading_file.unlink(missing_ok=True)
    try:
        suite(installed, tree, reader, workdir, stamp, f"read-{name}.log")
    finally:
        reading = json.loads(reading_file.read_text()) if reading_file.is_file() else {}
        (workdir / f"reading-{name}.json").write_text(json.dumps(reading, indent=2) + "\n")
        READINGS[name] = reading
    print(f"read ({name}): {reader} from {tree} opened every item; configSecretState "
          f"{shown_state(reading, 'none')}", flush=True)
    return reading


def browser(installed: lane.Installed, workdir: Path) -> None:
    """The candidate's one browser spec of this lane (`tests/e2e/tests/upgrade/`), told where the record is."""
    lane.browser(installed.compose, workdir,
                 lane.stack_document(upgradeRecord=f"{lane.CONTAINER_OUT}/{GROUP_DIR}/{proof.UPGRADE_RECORD_FILE}"),
                 [f"--project={BROWSER_PROJECT}"])


def rollback(start: Start, upgraded: Upgraded, workdir: Path) -> lane.Installed:
    """The STARTING release's own `veyqctl rollback` to the backup its upgrade took — the recovery `upgrade` names on a
    failure — `--yes` because stdin is closed."""
    home = upgraded.installed.home
    veyqctl = home / "releases" / start.tag[1:] / "veyqctl"
    status = step("rollback", [sys.executable, veyqctl, "--home", home, "rollback", upgraded.backup,
                               "--key-from", upgraded.key, "--yes"], "rollback.log", workdir,
                  {"VEYQ_WAIT_SECS": str(lane.WAIT_SECS)})
    if status != 0:
        raise Red(f"`veyqctl rollback` ({start.tag}'s) exited {status} — rollback.log in the run directory says where")
    after = lane.addressed(home)
    if after.release != start.tag[1:]:
        raise Red(f"after `veyqctl rollback` the instance runs {after.release}, not {start.tag}")
    print(f"rollback: {start.tag}'s veyqctl took the instance back to {after.release} from {upgraded.backup.name}",
          flush=True)
    return after


def closing_lines(start: Start | None, tree: proof.Predecessor | None, red: list[str], readings: dict[str, dict]) -> list[str]:
    """The lane's own closing lines, as the jar-level proof's: the starting release, both highest migrations, what
    changed between them, each reading's configSecretState, and the outcome with its cause."""
    lines = []
    if start is None:
        return lines
    after = proof.pinned_migrations((lane.ROOT / proof.PIN_FILE).read_text())
    lines.append(f"predecessor: {start.tag} ({start.asked})")
    if tree is not None:
        before = proof.pinned_migrations(tree.pins)
        changed = proof.changed_migrations(before, after)
        lines.append(f"highest migration: {start.tag} V{proof.highest_migration(before)}, candidate "
                     f"V{proof.highest_migration(after)}")
        lines.append(f"migrations changed or removed since {start.tag}: {', '.join(changed) or 'none'}")
    for name, reading in readings.items():
        lines.append(f"configSecretState ({name}): {shown_state(reading, '(never read)')}")
    lines.append("outcome: " + ("GREEN — installed, seeded, upgraded in place, read, opened in a browser, rolled back "
                                "and read again" if not red else f"RED — {red[0]}"))
    return lines


# ---------------------------------------------------------------- what a green leg hands over to its record


def predecessor_of(home: Path | None, start: Start) -> dict:
    """The starting release and the app and admin digests its manifest named — the copy `veyqctl install` verified
    and kept in the instance's `releases/<start>/`, not the mirror's."""
    if home is None:
        raise Red("no instance home to read the verified manifest from")
    manifest = json.loads((home / "releases" / start.tag[1:] / "manifest.json").read_text(encoding="utf-8"))
    images = {}
    for role, name in (("app", "veyq"), ("admin", "veyq-admin")):
        ref = str((manifest.get("images") or {}).get(role) or "")
        digest = ref.rpartition("@")[2]
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise Red(f"{start.tag}'s manifest names its {role} image {ref!r}, with no digest")
        images[name] = digest
    return {"tag": start.tag, "images": images}


def hand_over(args: argparse.Namespace, candidate: lane.Release | None, predecessor: dict | None) -> None:
    """`--asset-set-out` and `--predecessor-out`, for the leg's record (`snapshot.py lane-result --lane L2`)."""
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
    if args.predecessor_out is not None:
        args.predecessor_out.parent.mkdir(parents=True, exist_ok=True)
        args.predecessor_out.write_text(json.dumps(predecessor) + "\n")
        print(f"predecessor: {json.dumps(predecessor)}")


# ---------------------------------------------------------------- the run


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    which = parser.add_mutually_exclusive_group(required=True)
    which.add_argument("--leg", choices=LEGS, help="newest: the newest release; major-entry: the candidate's major entry")
    which.add_argument("--from", dest="tag", metavar="TAG", help="a published release tag, v<major>.<minor>")
    parser.add_argument("--resolve", action="store_true",
                        help="print `predecessor=<tag>` (nothing after `=` when the leg has none) and run nothing")
    parser.add_argument("--app", help="the candidate app image (its keytool writes the app's truststore)")
    parser.add_argument("--admin", help="the candidate admin image")
    parser.add_argument("--keep", action="store_true", help="leave the stack running (no teardown)")
    parser.add_argument("--asset-set-out", type=Path, default=None, metavar="FILE",
                        help="on a green run (or a leg with nothing to start from), the hash of the candidate's asset "
                             "set (release_assets.py --asset-set) — lane L2's record names it, as L1's does")
    parser.add_argument("--predecessor-out", type=Path, default=None, metavar="FILE",
                        help="on a green run, the starting release and the app and admin digests its verified "
                             "manifest named, as JSON; `null` for a leg with nothing to start from")
    args = parser.parse_args()

    tag, why = resolve(args.leg, args.tag)
    print(f"[l2] start: {why}", flush=True)
    if args.resolve:
        print(f"predecessor={tag or ''}")
        return 0
    if not args.app or not args.admin:
        parser.error("--app and --admin name the candidate")
    stamp = time.strftime("%Y%m%d-%H%M%S")
    leg = args.leg or tag
    workdir = lane.RUN_ROOT / f"l2-{leg}-{stamp}"
    if tag is None:
        # Nothing to install, seed or upgrade: the leg passes, and its record says it had no release to start from.
        print("[l2] nothing to start from: this leg passes, building nothing")
        if args.asset_set_out or args.predecessor_out:
            workdir.mkdir(parents=True)
            try:
                hand_over(args, candidate_release(args.app, args.admin, workdir), None)
            except Red as error:
                print(f"RED\n  candidate: {error}")
                return 1
        print("GREEN (nothing to start from)")
        return 0
    workdir.mkdir(parents=True)
    project = f"veyq-l2-{stamp}"
    stage = lane.Stages()

    before = stage("preflight", lambda: (lane.ports_free(), lane.browser_pins(), lane.host_snapshot())[2])
    stage("browser image", lane.browser_image)
    start = stage("start release", start_release, tag, why, workdir)
    override_file = stage("override", override, workdir) if start else None
    values = install_values(start, project, workdir) if start and override_file else {}
    started = False
    installed = first = None
    tree = None
    record = None
    candidate = None
    try:
        if start and override_file:
            started = not stage.red
            pebble_id = stage("pebble", lane.pebble, start.release, workdir, values, args.app, override_file)
            installed = first = stage("install", install, start, workdir, values, pebble_id, override_file)
            stage("certificate", lane.certificate, workdir)
            tree = stage("start tree", start_tree, start, workdir)
            record = stage("seed", seed, installed, tree, workdir, stamp)
            candidate = stage("candidate", candidate_release, args.app, args.admin, workdir)
            stage("candidate tree", candidate_tree)
            upgraded = stage("upgrade", upgrade, start, candidate, installed, workdir)
            if upgraded:
                installed = upgraded.installed
                stage("read", read, installed, lane.ROOT, workdir, stamp, "candidate")
                stage("browser", browser, installed, workdir)
                back = stage("rollback", rollback, start, upgraded, workdir)
                if back:
                    installed = back
                    stage("read again", read, installed, tree.build.root, workdir, stamp, start.tag)
    finally:
        if started:
            stage("evidence", lane.evidence, project, workdir)
            if not args.keep:
                stage("teardown", lane.teardown, project, workdir)
        if tree is not None:
            proof.remove_predecessor(tree)
        if before is not None:
            stage("host", lambda: lane.host_untouched(before, lane.host_snapshot(), project, started and not args.keep))

    print(f"\n== summary — lane L2, {project}, run directory {workdir.relative_to(lane.ROOT)}")
    for name, secs, verdict in stage.stages:
        print(f"  {name:<13} {secs:7.1f}s  {verdict}")
    print(f"  {'total':<13} {stage.total():7.1f}s")
    if start:
        print(f"start: {start.tag} — {start.asked}; images {start.manifest['images']['app']}, "
              f"{start.manifest['images']['admin']}")
    if first:
        print(f"installed by {start.tag}'s own veyqctl, VERIFIED under the release identity: release {first.release} "
              f"into {first.home.relative_to(lane.ROOT)}")
    if record:
        print(f"seeded by {start.tag}'s own seeder: record version {record['version']}")
    print("steps:")
    for done in STEPS:
        print(f"  {done.stage:<18} {done.outcome:<24} {done.command}")
    for line in closing_lines(start, tree, stage.red, READINGS):
        print(line)
    if stage.red:
        print("RED\n  " + "\n  ".join(stage.red))
        return 1
    try:
        hand_over(args, candidate, predecessor_of(installed.home if installed else None, start))
    except Red as error:
        print(f"RED\n  hand-over: {error}")
        return 1
    print("GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
