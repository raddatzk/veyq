"""Builds `@veyq/core-js`'s `dist/`: the Kotlin core compiled for JavaScript (ADR 0155 decision 6).

Gradle compiles, pnpm installs, bundles and tests; the two meet at `dist/`. This runs the core's one task for it,
`:composition:jsPackage` (src/mobile/composition/build.gradle.kts), which writes each core module's file (the platform's
`kotlin_veyq_mobile_core.mjs`, each application's), their one `.d.mts` and source maps into `dist/` and the platform's conformance
runner into `dist/conformance/`. The package names the task, never a build path.
"""
from __future__ import annotations

import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[3] / "scripts"))
import repo_layout  # noqa: E402

GRADLEW = repo_layout.MOBILE / ("gradlew.bat" if sys.platform == "win32" else "gradlew")


def main() -> int:
    if "--watch" in sys.argv[1:]:
        # The dev server's: the development link (`:composition:jsPackageDev`, no dead-code elimination, no conformance), with
        # the JavaScript compile's incremental caches, which a release build turns off for byte-identical output
        # (src/mobile/gradle.properties) and a dev reload does not need.
        task = ["-t", "-Pkotlin.incremental.js.ir=true", ":composition:jsPackageDev"]
    else:
        task = [":composition:jsPackage"]
    return subprocess.call([str(GRADLEW), "--console=plain", "-q", *task], cwd=repo_layout.MOBILE)


if __name__ == "__main__":
    sys.exit(main())
