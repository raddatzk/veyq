#!/usr/bin/env python3
"""Signs the debug app on a running emulator in as the seeded dev principal (the app's `?devlogin`, src/web/CLAUDE.md).

Reads VEYQ_EMAIL, VEYQ_PASSWORD, VEYQ_SECRET_KEY and VEYQ_TENANT_DOMAIN from the repository's `.run/.env` (written by
`./gradlew backend:seed`) and hands them to the debug build as launch extras; nothing is printed. The debug build
resolves the `*.localhost` tenant to the developer's machine (DevDns), so the server is `<tenant domain>:<port>`.

Usage: devlogin.py [port] [--remember] [--unlock]   (port default 8080, the dev backend)
  --remember  sign in staying signed in (the DEVICE_KEY trust; the emulator asks for its screen lock)
  --unlock    hand only the password, for the unlock screen of a password-trusted account
"""
from __future__ import annotations

import os
import shlex
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ADB = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"


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
    port = positional[0] if positional else "8080"
    e = env()
    if "--unlock" in flags:
        extras = {"veyq.devUnlockPassword": e["VEYQ_PASSWORD"]}
    else:
        extras = {
            "veyq.devServer": f"{e['VEYQ_TENANT_DOMAIN']}:{port}",
            "veyq.devEmail": e["VEYQ_EMAIL"],
            "veyq.devPassword": e["VEYQ_PASSWORD"],
            "veyq.devSecretKey": e["VEYQ_SECRET_KEY"],
            "veyq.devRemember": "1" if "--remember" in flags else "0",
        }
    args = ["am", "start", "-n", "me.raddatz.veyq/me.raddatz.veyq.mobile.android.MainActivity"]
    for k, v in extras.items():
        args += ["--es", k, shlex.quote(v)]
    r = subprocess.run([str(ADB), "shell", *args], capture_output=True, text=True)
    print("devlogin: started" if r.returncode == 0 else f"devlogin: adb failed ({r.returncode})")


if __name__ == "__main__":
    main()
