#!/usr/bin/env python3
"""Launches the debug Apple app in a booted simulator, signed in as the seeded dev principal (the app's `?devlogin`).

Reads VEYQ_EMAIL, VEYQ_PASSWORD, VEYQ_SECRET_KEY and VEYQ_TENANT_DOMAIN from the repository's `.run/.env` (written by
`./gradlew backend:seed`) and hands them to the app as its environment through `simctl launch` (`SIMCTL_CHILD_…`);
nothing is printed. The simulator resolves the `*.localhost` tenant as the Mac does.

Usage: devlogin.py <device name or UDID> [port] [--remember] [--unlock]   (default port 8080, the dev backend)
  --remember  sign in staying signed in (the DEVICE_KEY trust; the simulator asks for Face ID)
  --unlock    hand only the password, for the unlock screen of a password-trusted account
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BUNDLE = "me.raddatz.veyq"


def env() -> dict[str, str]:
    out: dict[str, str] = {}
    for line in (ROOT / ".run/.env").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def main() -> None:
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    positional = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not positional:
        sys.exit(__doc__)
    device = positional[0]
    port = positional[1] if len(positional) > 1 else "8080"
    e = env()
    child = dict(os.environ)
    if "--unlock" in flags:
        child["SIMCTL_CHILD_VEYQ_DEV_UNLOCK_PASSWORD"] = e["VEYQ_PASSWORD"]
    else:
        child.update({
            "SIMCTL_CHILD_VEYQ_DEV_SERVER": f"{e['VEYQ_TENANT_DOMAIN']}:{port}",
            "SIMCTL_CHILD_VEYQ_DEV_EMAIL": e["VEYQ_EMAIL"],
            "SIMCTL_CHILD_VEYQ_DEV_PASSWORD": e["VEYQ_PASSWORD"],
            "SIMCTL_CHILD_VEYQ_DEV_SECRET_KEY": e["VEYQ_SECRET_KEY"],
            "SIMCTL_CHILD_VEYQ_DEV_REMEMBER": "1" if "--remember" in flags else "0",
        })
    subprocess.run(["xcrun", "simctl", "terminate", device, BUNDLE], capture_output=True)
    r = subprocess.run(["xcrun", "simctl", "launch", device, BUNDLE], env=child, capture_output=True, text=True)
    print("devlogin: launched" if r.returncode == 0 else f"devlogin: simctl failed ({r.returncode}): {r.stderr.strip()[:200]}")


if __name__ == "__main__":
    main()
