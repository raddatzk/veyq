"""The one way the chart scripts render a chart: `helm template` of a chart directory with one values file.

`chart_meta_check.py` renders the checkout's `infra/kubernetes/charts/veyq` and asserts what one render must hold;
`chart_upgrade_check.py` renders two charts - the upgrade predecessor's, read out of git, and the candidate's - and
compares them. Both go through here, so the two never disagree on the release name, the flags or how a rendered
stream is split into objects (cr-helm-upgrade-check Decision 1: one rendering helper).

Not a guard and not runnable: a module the two import. Without `helm` on the PATH it exits 2 and says so, never 0 -
a missing tool is not a green.
"""
from __future__ import annotations  # `list[str] | None` below - the repo's scripts run on Python 3.9

import pathlib
import shutil
import subprocess
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The release name every render uses. The chart's object names are fixed, not release-prefixed (_helpers.tpl), but
# the selector carries `app.kubernetes.io/instance: <release>`, so two renders compare only under one name.
RELEASE = "veyq"


def helm(who: str) -> str:
    """The `helm` binary on the PATH; `who` names the caller in the refusal when there is none."""
    found = shutil.which("helm")
    if found is None:
        print(f"{who}: `helm` is not on PATH — this guard cannot run, and a missing tool is not a green", file=sys.stderr)
        sys.exit(2)
    return found


def template(who: str, chart: pathlib.Path, values: pathlib.Path,
             extra: list[str] | None = None) -> subprocess.CompletedProcess:
    """`helm template` of `chart` with `values` (and any extra flags); the completed process, never raised on."""
    return subprocess.run(
        [helm(who), "template", RELEASE, str(chart), "-f", str(values), *(extra or [])],
        cwd=ROOT, capture_output=True, text=True,
    )


def objects(rendered: str) -> list[dict]:
    """The rendered stream's objects - every YAML document that is a mapping with a `kind`."""
    return [doc for doc in yaml.safe_load_all(rendered) if isinstance(doc, dict) and doc.get("kind")]
