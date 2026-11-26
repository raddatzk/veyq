#!/usr/bin/env python3
"""Lane L2 of ADR 0091: a published release, installed by its own `veyqctl` and written by its own seeder (ADR 0091
decision 6, amended 2026-11-26).

**What it does.** One leg: a starting release — the newest release (`--leg newest`, `run_web_e2e.py`'s
`default_predecessor_ref`), the candidate's major entry (`--leg major-entry`, `major_entry_proof_ref`) or a tag named
with `--from` — installed on the production recipe exactly as a Server Owner installs it, and written into by that
release's own API seeder. Its stages:

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
  7. **evidence**, **teardown**, **host** — as L1's; the worktree is removed last.

**The run directory** (`.e2e-stack-out/l2-<leg>-<stamp>/`, gitignored, kept after the run): `mirror/`, `home/` (the
instance), `override.yml`, `install.log`, `seed.log`, `group/upgrade-record.json`, `compose.log`, `ps.json`.

**Not a `validate_*` guard.** It needs Docker, cosign (`veyqctl`'s floor, 3.1.3), `gh`, the network to GitHub and
GHCR, host ports 80 and 443, and the root `pnpm install`.

**Its limits.** Pebble, the overlay and the host check are L1's, with L1's limits. The seeder writes what a
person writes through the API (the seeder's header says what), not through the browser. A green run proves that the
starting release installs from its published files and takes that release's own writes on this host; it says nothing
of a release other than the one it installed.

Usage:
    python3 scripts/run_stack_upgrade.py --app veyq:local --admin veyq-admin:local --leg newest [--keep]
    python3 scripts/run_stack_upgrade.py --app … --admin … --from v1.318
    python3 scripts/run_stack_upgrade.py --leg major-entry --resolve     # print `predecessor=<tag>` (or nothing), run nothing

Environment: lane L1's (`VEYQ_STACK_WAIT_SECS`, `VEYQ_STACK_CERT_SECS`, `VEYQ_STACK_ADMIN_PORT`), and `GH_TOKEN` for
`gh` where it is not signed in (a CI job's `github.token`).
"""
from __future__ import annotations

import argparse
import json
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
    installed = lane.install(start.release, workdir, values, pebble_id, verified=True, override=override_file,
                             flags=flags)
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
    if status != 0:
        raise Red(f"{test} from {tree.relative_to(lane.ROOT)} exited {status} — {log_name} in the run directory says why")


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
    args = parser.parse_args()

    tag, why = resolve(args.leg, args.tag)
    print(f"[l2] start: {why}", flush=True)
    if args.resolve:
        print(f"predecessor={tag or ''}")
        return 0
    if tag is None:
        print("[l2] nothing to start from: this leg passes, building nothing")
        return 0
    if not args.app or not args.admin:
        parser.error("--app and --admin name the candidate")

    stamp = time.strftime("%Y%m%d-%H%M%S")
    leg = args.leg or tag
    workdir = lane.RUN_ROOT / f"l2-{leg}-{stamp}"
    workdir.mkdir(parents=True)
    project = f"veyq-l2-{stamp}"
    stage = lane.Stages()

    before = stage("preflight", lambda: (lane.ports_free(), lane.browser_pins(), lane.host_snapshot())[2])
    stage("browser image", lane.browser_image)
    start = stage("start release", start_release, tag, why, workdir)
    override_file = stage("override", override, workdir) if start else None
    values = install_values(start, project, workdir) if start and override_file else {}
    started = False
    installed = None
    tree = None
    record = None
    try:
        if start and override_file:
            started = not stage.red
            pebble_id = stage("pebble", lane.pebble, start.release, workdir, values, args.app, override_file)
            installed = stage("install", install, start, workdir, values, pebble_id, override_file)
            stage("certificate", lane.certificate, workdir)
            tree = stage("start tree", start_tree, start, workdir)
            record = stage("seed", seed, installed, tree, workdir, stamp)
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
    if installed:
        print(f"installed by {start.tag}'s own veyqctl, VERIFIED under the release identity: release {installed.release} "
              f"into {installed.home.relative_to(lane.ROOT)}")
    if record:
        print(f"seeded by {start.tag}'s own seeder: record version {record['version']}")
    if stage.red:
        print("RED\n  " + "\n  ".join(stage.red))
        return 1
    print("GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
