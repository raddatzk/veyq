#!/usr/bin/env python3
"""Take the docs site's screenshots: boot a throwaway instance, fill it with made-up content, and photograph the web client.

**What it does.** Every `<Screenshot id=…>` a Use page places (`scripts/site_shots.py` lists them), and every shot the
project website places (`site/src/components/website/strings.ts`, shown by `README.md` too), is one scenario in
`tests/e2e/shots/site.shots.ts`, or a reasoned entry of `tests/e2e/shots/left-out.json`. This:

  1. **catalogue** — runs the shots suite's catalogue test, which needs no stack: a placed id with no scenario and no
     reason is red here, before anything boots, so a new `<Screenshot>` cannot silently stay a placeholder.
  2. **stack** — boots lane L1's isolated instance through `scripts/run_stack_e2e.py --keep -- --project setup`: the two
     images given, its own compose project `veyq-e2e-<stamp>` with Pebble's certificates, and L1's own first-run setup
     (the Server Owner, the bundled store, the first tenant). Nothing of a developer's dev stack is touched — that
     script's own `host` stage proves it, and so does this one's at the end. It needs host ports 80, 443 and 18081
     (`VEYQ_STACK_ADMIN_PORT` moves the last).
  3. **mail relay** — the run's Mailpit sits on the run's `admin` network only; a container of the overlay's Playwright
     image (already on the host for L1) forwards a loopback port to it, so the shots can read the invitations the
     instance mails.
  4. **shots** — the suite on the HOST, in the installed Google Chrome (`channel: 'chrome'`, headless — nothing is
     downloaded), English then German, each language a space of its own with made-up people and drawn pictures
     (`tests/e2e/shots/world.ts`).
  5. **images** — copies `<id>.<en|de>.png` into `site/src/assets/screenshots/` (the project website's, whose ids are
     `website/<id>`, into `site/src/assets/website/`), and reports per language what was written, what failed and the
     bytes.
  6. **teardown** — stops the relay, `docker compose down -v` the run's project (unless `--keep`), and checks that every
     container and volume that existed before the run is as it was.

A failed shot does not stop the others; the run is red if any shot or step failed, and the images that were taken are
copied all the same (a failed shot leaves its old image, if there was one, in place).

**Not a guard, not part of CI**: it needs Docker, the two images, Chrome and about ten minutes. Run it after a UI change
that a screenshot shows, look at the images, and commit them.

Usage (from the repo root):
    ./gradlew backend:image admin:image                     # the images of this tree: veyq:local, veyq-admin:local
    python3 scripts/site_capture.py                         # every shot, both languages
    python3 scripts/site_capture.py --only depot-rename-dialog,owners-email --lang de
    python3 scripts/site_capture.py --only website/depot,website/pot   # the website's shots carry `website/`
    python3 scripts/site_capture.py --keep                  # leave the instance up; --attach <run dir> reuses it

Options: --app / --admin (default `veyq:local` / `veyq-admin:local`), --only <ids>, --lang en|de, --keep,
--attach <.e2e-stack-out/<stamp>> (a run left up by --keep; nothing is booted or torn down).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

import repo_layout  # where the site, the test harnesses and the deployment files live (ADR 0106)
import run_stack_e2e as l1  # the stack's own helpers: its project by name, the host snapshot and check

ROOT = repo_layout.ROOT
E2E = repo_layout.E2E_DIR
SHOTS_CONFIG = "shots/playwright.config.ts"
IMAGES = ROOT / "site" / "src" / "assets" / "screenshots"
WEBSITE_IMAGES = ROOT / "site" / "src" / "assets" / "website"  # the shots whose id is `website/<id>`
RUN_ROOT = ROOT / ".e2e-stack-out"
RELAY_PORT_IN = 8025


def say(text: str) -> None:
    print(text, flush=True)


def playwright(env: dict[str, str]) -> int:
    cmd = ["pnpm", "exec", "playwright", "test", "--config", SHOTS_CONFIG]
    say("+ " + " ".join(cmd))
    return subprocess.run(cmd, cwd=E2E, env={**os.environ, **env}).returncode


def catalogue() -> None:
    """The suite with no stack: only its catalogue test exists then."""
    env = {k: v for k, v in os.environ.items() if k != "VEYQ_STACK_FILE"}
    status = subprocess.run(["pnpm", "exec", "playwright", "test", "--config", SHOTS_CONFIG], cwd=E2E, env=env).returncode
    if status != 0:
        raise SystemExit("catalogue: a placed screenshot has no scenario and no reason in tests/e2e/shots/left-out.json "
                         "(or a scenario names an id no page places) — see the assertion above")


def boot(app: str, admin: str) -> Path:
    before = {p.name for p in RUN_ROOT.iterdir()} if RUN_ROOT.is_dir() else set()
    cmd = [sys.executable, str(ROOT / "scripts" / "run_stack_e2e.py"), "--app", app, "--admin", admin, "--keep",
           "--", "--project", "setup"]
    say("+ " + " ".join(cmd))
    status = subprocess.run(cmd, cwd=ROOT).returncode
    new = sorted({p.name for p in RUN_ROOT.iterdir()} - before)
    if not new:
        raise SystemExit("stack: run_stack_e2e.py left no run directory")
    workdir = RUN_ROOT / new[-1]
    if status != 0:
        teardown(workdir, relay=None)
        raise SystemExit(f"stack: run_stack_e2e.py exited {status} — its summary above says which stage; "
                         f"the run directory is {workdir.relative_to(ROOT)}")
    return workdir


def project_of(workdir: Path) -> str:
    return f"veyq-e2e-{workdir.name}"


def start_relay(workdir: Path) -> tuple[str, int]:
    project = project_of(workdir)
    name = f"{project}-shots-mail-relay"
    image = l1.overlay_image("browser")
    forward = ("require('net').createServer(c=>{const u=require('net').connect(8025,'mail.relay.test');"
               "c.pipe(u).pipe(c);c.on('error',()=>u.destroy());u.on('error',()=>c.destroy())}).listen(8025,'0.0.0.0')")
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    l1.run("docker", "run", "-d", "--rm", "--name", name, "--network", f"{project}_admin", "--user", "65532:65532",
           "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
           "-p", f"127.0.0.1::{RELAY_PORT_IN}", "--entrypoint", "node", image, "-e", forward)
    port = l1.run("docker", "port", name, f"{RELAY_PORT_IN}/tcp").stdout.strip().splitlines()[0].rsplit(":", 1)[1]
    return name, int(port)


def host_stack(workdir: Path, relay_port: int) -> Path:
    """The suite's stack file for the host: the run's own (written for its container) with the host's addresses."""
    stack = json.loads((workdir / "stack.json").read_text(encoding="utf-8"))
    stack.update({
        "traefikHost": "127.0.0.1",
        "adminOrigin": f"http://localhost:{l1.ADMIN_PORT}",
        "adminUpstream": f"127.0.0.1:{l1.ADMIN_PORT}",
        "mailApi": f"http://127.0.0.1:{relay_port}",
        "outDir": str(workdir),
    })
    path = workdir / "stack-host.json"
    path.write_text(json.dumps(stack, indent=2) + "\n", encoding="utf-8")
    return path


def copy_images(workdir: Path, langs: list[str]) -> tuple[dict[str, int], int, list[str]]:
    shots = workdir / "shots"
    written: dict[str, int] = {lang: 0 for lang in langs}
    size = 0
    failed: list[str] = []
    IMAGES.mkdir(parents=True, exist_ok=True)
    for lang in langs:
        results_file = shots / f"results-{lang}.json"
        results = json.loads(results_file.read_text(encoding="utf-8")) if results_file.is_file() else {"results": {}}
        for shot_id, verdict in sorted(results["results"].items()):
            if verdict != "ok":
                failed.append(f"{shot_id}.{lang}: {verdict}")
                continue
            src = shots / f"{shot_id}.{lang}.png"
            target = WEBSITE_IMAGES if shot_id.startswith("website/") else IMAGES
            target.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, target / src.name)
            written[lang] += 1
            size += src.stat().st_size
        for finding in results.get("foreign", []):
            say(f"shots [{lang}]: a page asked outside the instance: {finding}")
    return written, size, failed


def teardown(workdir: Path, relay: str | None) -> None:
    if relay:
        subprocess.run(["docker", "rm", "-f", relay], capture_output=True)
    l1.teardown(project_of(workdir), workdir)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app", default="veyq:local", help="the app image (default veyq:local, ./gradlew backend:image)")
    parser.add_argument("--admin", default="veyq-admin:local", help="the admin image (default veyq-admin:local)")
    parser.add_argument("--only", default="", help="comma-separated ids: take only these shots")
    parser.add_argument("--lang", choices=("en", "de"), help="one language only")
    parser.add_argument("--keep", action="store_true", help="leave the instance up afterwards")
    parser.add_argument("--attach", type=Path, help="a run directory left up by --keep: reuse its instance")
    args = parser.parse_args()
    langs = [args.lang] if args.lang else ["en", "de"]
    started = time.monotonic()

    say("== catalogue")
    catalogue()

    before = None if args.attach else l1.host_snapshot()
    if args.attach:
        workdir = args.attach.resolve()
        if not (workdir / "instance.json").is_file():
            raise SystemExit(f"--attach: {workdir} holds no instance.json — not a run left up by --keep")
        say(f"== stack (attached: {workdir.relative_to(ROOT)})")
    else:
        say("== stack")
        workdir = boot(args.app, args.admin)
    relay = None
    status = 1
    try:
        say("== mail relay")
        relay, port = start_relay(workdir)
        say(f"mail relay: 127.0.0.1:{port} -> mail.relay.test:{RELAY_PORT_IN} ({relay})")
        env = {"VEYQ_STACK_FILE": str(host_stack(workdir, port)), "VEYQ_SHOTS_LANGS": ",".join(langs)}
        if args.only:
            env["VEYQ_SHOTS_ONLY"] = args.only
        # A second run against an attached instance needs spaces of its own: their domains are unique per instance.
        if args.attach:
            env["VEYQ_SHOTS_TENANT_SUFFIX"] = time.strftime("-%H%M%S")
        say("== shots")
        status = playwright(env)
        say("== images")
        written, size, failed = copy_images(workdir, langs)
        for lang in langs:
            say(f"images: {written[lang]} written for {lang}")
        say(f"images: {size} bytes ({size / 1024 / 1024:.1f} MiB) copied into {IMAGES.relative_to(ROOT)}")
        for line in failed:
            say(f"FAILED {line}")
        left = json.loads((E2E / "shots" / "left-out.json").read_text(encoding="utf-8"))
        for shot_id, reason in left.items():
            if not shot_id.startswith("//"):
                say(f"left out {shot_id}: {reason}")
        if failed:
            status = status or 1
    finally:
        if args.attach or args.keep:
            if relay:
                subprocess.run(["docker", "rm", "-f", relay], capture_output=True)
            say(f"== instance left up: {workdir.relative_to(ROOT)} (project {project_of(workdir)}); "
                f"`docker compose -p {project_of(workdir)} down -v` removes it")
        else:
            say("== teardown")
            teardown(workdir, relay)
            if before is not None:
                l1.host_untouched(before, l1.host_snapshot(), project_of(workdir), True)
    say(f"\n{'GREEN' if status == 0 else 'RED'} in {time.monotonic() - started:.0f}s — run directory {workdir.relative_to(ROOT)}")
    return 0 if status == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
