#!/usr/bin/env python3
"""A `helm upgrade` of the chart from the upgrade predecessor's to this checkout's, checked without a cluster.

**What it asserts** ([ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) § Decision, the chart-upgrade check). It reads the
predecessor's `charts/veyq` out of git (`git archive`, no worktree, no build) and renders it and this checkout's chart
with the same values - each of the CANDIDATE's `ci/*.yaml`, like for like - then compares the two renders object by
object, keyed by `(kind, metadata.name)`. It refuses, each with a reason naming the object and the field:

  1. a Deployment's or a StatefulSet's `spec.selector` changed;
  2. a StatefulSet's spec changed in any field outside the API server's mutable set (`replicas`, `ordinals`,
     `template`, `updateStrategy`, `persistentVolumeClaimRetentionPolicy`, `minReadySeconds`) - `serviceName`,
     `podManagementPolicy`, `revisionHistoryLimit` and whatever field a later API adds (an absent field the server
     defaults - `podManagementPolicy: OrderedReady`, `revisionHistoryLimit: 10` - equals its default spelled out);
  3. a StatefulSet's `volumeClaimTemplates` changed: a template added, removed, renamed or reordered, its
     `accessModes`, its `storageClassName` or its size each by name, and anything else in the template - its
     `metadata` labels included - as the template having changed;
  4. a PersistentVolumeClaim's `accessModes` or `storageClassName` changed, or its size shrank (a growth is left to
     the StorageClass - see the limits);
  5. a RoleBinding's `roleRef` changed;
  6. a kind changed under the same name;
  7. a StatefulSet or a PersistentVolumeClaim of the predecessor is missing from the candidate - the data behind it.

Anything else may change: an image tag, a label, an env var, a pod template - `helm upgrade` applies those. It also
renders the candidate with each of the PREDECESSOR's `ci/*.yaml`: an operator's existing values must still pass the
candidate's schema and render, or the upgrade fails before it starts.

**The predecessor** is the upgrade proof's ([ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md)
Decision 7): `run_web_e2e.default_predecessor_ref()`, so the commit before the one under test while
`scripts/applied_migrations.sha256` says `release: none`, then the newest `v<major>.<minor>` tag the first parent
contains. `--upgrade-from <ref>` names another one, as it does for the proof. A predecessor without `charts/veyq` is said and skipped - nothing to
upgrade from.

**The verdict** is [verdict]'s alone, on the same `release:` line the upgrade proof reads: while it is `none` the
findings are printed with their reasons and the run exits 0, "information only" - a chart before the first release
has no installation to break; from the release on it exits 1, and since `ci.yml`'s `chart` job runs it, a red
gates `publish`, which needs every stage.

**The defect that bought it - none yet.** No release exists, so no upgrade has ever been made. inbox #428 measured
that of these risks only `strategy: Recreate` was checked (`chart_meta_check.py` (iii)) and nothing compared two
versions. Each refusal was bitten by deleting its check against `chart_upgrade_check_selftest.py`, and the real run by
an uncommitted edit of `veyq.selector` and of a claim template's size (cr-helm-upgrade-check).

**Its limits - what a green run does NOT prove.** It renders; it never installs. Not covered, as ADR 0064's Consequences
states: a missing StorageClass or one that cannot expand a growing claim, admission under `restricted`, image pulls,
data surviving a real `helm upgrade`, and a `mariadbPassword` changed on upgrade (the database keeps the old one) -
the kind cluster leg (inbox #430) is what would see those. Beyond that list: the StatefulSet mutable set above is
the API server's refusal message as remembered, not measured against a cluster - a field the server lets change that
is missing from it is a false red, never a false green. A field a render leaves out is equal only to the defaults
named in `STATEFULSET_DEFAULTS`; another default spelled out is reported. It renders only the values
files in the two `ci/` directories, so a value path neither sets is compared at its default alone. It compares
against one predecessor, so an operator upgrading across several releases is checked step by step at best. And it
sees what the chart renders, not what the cluster holds: an object an operator edited by hand is out of reach.

**Why it sits OUTSIDE the `validate_*` glob.** It shells out to `helm` and reads git history, neither of which the
buildless trust chain has (`chart_meta_check.py` says the same for itself). `ci.yml`'s `chart` job runs it after
"Chart checks", on a checkout with the whole history (`fetch-depth: 0`) - a job no run has executed yet (this
repository has no remote). Run it locally with `helm` on the PATH; without `helm` it exits 2, never 0:

    python3 scripts/chart_upgrade_check.py [--upgrade-from <ref>]

Its pure parts - the comparison and the verdict - are pinned by `scripts/chart_upgrade_check_selftest.py`.
"""
from __future__ import annotations  # `X | None` below - the repo's scripts run on Python 3.9

import copy
import io
import pathlib
import re
import subprocess
import sys
import tarfile
import tempfile
from decimal import Decimal, InvalidOperation

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import chart_render  # noqa: E402 - the one `helm template` both chart scripts render through
import run_web_e2e as e2e  # noqa: E402 - the upgrade proof's predecessor and its pin line

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHART_PATH = "charts/veyq"
CHART = ROOT / CHART_PATH
WHO = "chart upgrade check"

# The kinds whose loss loses data: a StatefulSet's claims and a standalone claim (refusal 7).
DATA_KINDS = ("StatefulSet", "PersistentVolumeClaim")
# The StatefulSet spec fields the API server lets an update change. It refuses every other one ("updates to
# statefulset spec for fields other than 'replicas', 'ordinals', 'template', 'updateStrategy',
# 'persistentVolumeClaimRetentionPolicy' and 'minReadySeconds' are forbidden"), so everything else is compared.
STATEFULSET_MUTABLE = {"replicas", "ordinals", "template", "updateStrategy", "persistentVolumeClaimRetentionPolicy",
                       "minReadySeconds"}
# The server's defaults for fields a render may leave out: spelling one out is no change.
STATEFULSET_DEFAULTS = {"podManagementPolicy": "OrderedReady", "revisionHistoryLimit": 10}
# Kubernetes quantity suffixes - binary, decimal, and milli.
SUFFIXES = {
    "Ki": 1024, "Mi": 1024 ** 2, "Gi": 1024 ** 3, "Ti": 1024 ** 4, "Pi": 1024 ** 5, "Ei": 1024 ** 6,
    "k": 1000, "M": 1000 ** 2, "G": 1000 ** 3, "T": 1000 ** 4, "P": 1000 ** 5, "E": 1000 ** 6,
    "": 1, "m": Decimal("0.001"),
}
QUANTITY = re.compile(r"^([0-9]+(?:\.[0-9]*)?|\.[0-9]+)([A-Za-z]{0,2})$")


# --- the pure part: two renders in, the reasons out ---

def quantity(value: object) -> Decimal | None:
    """A Kubernetes quantity (`10Gi`, `500M`, `1`) as a number of bytes, or None where it is not one."""
    found = QUANTITY.match(str(value).strip()) if value is not None else None
    if found is None or found.group(2) not in SUFFIXES:
        return None
    try:
        return Decimal(found.group(1)) * SUFFIXES[found.group(2)]
    except InvalidOperation:
        return None


def size_of(spec: dict) -> object:
    return (((spec or {}).get("resources") or {}).get("requests") or {}).get("storage")


def index(rendered: list[dict]) -> dict[tuple[str, str], dict]:
    """The render's objects keyed by `(kind, metadata.name)`."""
    return {(str(o.get("kind")), str((o.get("metadata") or {}).get("name"))): o for o in rendered}


def claim_templates(spec: dict) -> dict[str, dict]:
    """A StatefulSet's `volumeClaimTemplates` by name -> the whole template, metadata included."""
    return {str((t.get("metadata") or {}).get("name")): t
            for t in (spec.get("volumeClaimTemplates") or []) if isinstance(t, dict)}


def comparable(claim_template: dict) -> dict:
    """A claim template with its resource quantities read as numbers - the server compares `1Gi` and `1024Mi` as
    equal, so the whole-template comparison must too."""
    out = copy.deepcopy(claim_template)
    for bucket in (((out.get("spec") or {}).get("resources") or {}).values()):
        if isinstance(bucket, dict):
            for key, value in bucket.items():
                read = quantity(value)
                bucket[key] = read if read is not None else value
    return out


def compare(before: list[dict], after: list[dict]) -> list[str]:
    """Every reason a `helm upgrade` from the render `before` to the render `after` cannot be applied or would lose
    data - empty where it can. Each reason names the object and the field."""
    old, new = index(before), index(after)
    problems: list[str] = []
    for (kind, name), obj in sorted(old.items()):
        at = f"{kind}/{name}"
        if (kind, name) not in new:
            now = sorted(k for (k, n) in new if n == name and (k, n) not in old)
            if now:
                problems.append(f"{at}: the kind changed under the same name (the candidate renders {', '.join(now)}/"
                                f"{name}) - `helm upgrade` deletes the {kind} and creates the other")
            elif kind in DATA_KINDS:
                problems.append(f"{at}: missing from the candidate - `helm upgrade` would delete it and the data "
                                f"behind its claims")
            continue
        problems.extend(compare_object(at, kind, obj, new[(kind, name)]))
    return problems


def changed(at: str, field: str, a: object, b: object) -> str:
    return f"{at}: {field} changed ({a!r} -> {b!r}) - immutable, `helm upgrade` cannot apply it"


def compare_object(at: str, kind: str, a: dict, b: dict) -> list[str]:
    """The immutable fields of one object present in both renders."""
    sa, sb = a.get("spec") or {}, b.get("spec") or {}
    problems: list[str] = []
    if kind in ("Deployment", "StatefulSet") and sa.get("selector") != sb.get("selector"):
        problems.append(changed(at, "spec.selector", sa.get("selector"), sb.get("selector")))
    if kind == "StatefulSet":
        # Every spec field outside the mutable set; the selector above and the claim templates below by their own,
        # more precise reasons. serviceName and podManagementPolicy are two of the fields this loop compares.
        for field in sorted((set(sa) | set(sb)) - STATEFULSET_MUTABLE - {"selector", "volumeClaimTemplates"}):
            value_a = sa.get(field, STATEFULSET_DEFAULTS.get(field))
            value_b = sb.get(field, STATEFULSET_DEFAULTS.get(field))
            if value_a != value_b:
                problems.append(changed(at, f"spec.{field}", value_a, value_b))
        problems.extend(compare_claim_templates(at, sa, sb))
    if kind == "PersistentVolumeClaim":
        problems.extend(compare_claim(at, "spec", sa, sb, growth_allowed=True))
    if kind == "RoleBinding" and a.get("roleRef") != b.get("roleRef"):
        problems.append(changed(at, "roleRef", a.get("roleRef"), b.get("roleRef")))
    return problems


def compare_claim_templates(at: str, sa: dict, sb: dict) -> list[str]:
    ta, tb = claim_templates(sa), claim_templates(sb)
    problems: list[str] = []
    if sorted(ta) != sorted(tb):
        problems.append(changed(at, "spec.volumeClaimTemplates names", sorted(ta), sorted(tb)))
    elif list(ta) != list(tb):
        problems.append(changed(at, "spec.volumeClaimTemplates order", list(ta), list(tb)))
    for name in sorted(set(ta) & set(tb)):
        field = f"spec.volumeClaimTemplates[{name}]"
        found = compare_claim(at, field, ta[name].get("spec") or {}, tb[name].get("spec") or {}, growth_allowed=False)
        # The template is immutable as a whole: what the named fields above do not explain is still a change.
        if not found and comparable(ta[name]) != comparable(tb[name]):
            found = [changed(at, field, ta[name], tb[name])]
        problems.extend(found)
    return problems


def compare_claim(at: str, field: str, a: dict, b: dict, growth_allowed: bool) -> list[str]:
    """A claim's (or a claim template's) access modes, class and size. A standalone claim may grow - the API server
    accepts it where the StorageClass allows expansion; a claim template's size is immutable either way."""
    problems: list[str] = []
    if a.get("accessModes") != b.get("accessModes"):
        problems.append(changed(at, f"{field}.accessModes", a.get("accessModes"), b.get("accessModes")))
    if a.get("storageClassName") != b.get("storageClassName"):
        problems.append(changed(at, f"{field}.storageClassName", a.get("storageClassName"), b.get("storageClassName")))
    size_a, size_b = size_of(a), size_of(b)
    bytes_a, bytes_b = quantity(size_a), quantity(size_b)
    if size_a != size_b and (bytes_a is None or bytes_b is None):
        problems.append(f"{at}: {field} size changed ({size_a!r} -> {size_b!r}) and is not a quantity this check "
                        f"reads, so a shrink cannot be ruled out")
    elif bytes_a is not None and bytes_b is not None and bytes_a != bytes_b and not (growth_allowed and bytes_b > bytes_a):
        what = "shrank" if bytes_b < bytes_a else "changed"
        problems.append(f"{at}: {field} size {what} ({size_a!r} -> {size_b!r}) - "
                        + ("a claim never shrinks" if what == "shrank" else "immutable, `helm upgrade` cannot apply it"))
    return problems


def verdict(release: str, problems: list[str]) -> tuple[int, str]:
    """(exit code, closing line) - the ONE place the switch to the release happens, on the pin file's `release:` line
    the upgrade proof reads (`run_web_e2e.read_release`). Findings inform while it is `none` and fail from then on."""
    if not problems:
        return 0, "chart upgrade check OK: nothing the predecessor's chart renders is changed beyond `helm upgrade`"
    if release == "none":
        return 0, (f"chart upgrade check: {len(problems)} finding(s) above - information only while "
                   f"`release: none`; from the first release they fail the build")
    return 1, f"CHART UPGRADE CHECK FAILED: {len(problems)} finding(s) above, against `release: {release}`"


# --- the impure part: git, helm and the files ---

def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(ROOT), capture_output=True)


def predecessor(ref: str | None) -> tuple[str, str]:
    """(how the predecessor was chosen, its commit). Exits 2 where it cannot be found - a setup fault, not a finding."""
    if ref is None:
        try:
            ref, asked = e2e.default_predecessor_ref()
        except ValueError as e:  # a set release with no release tag to find: refused, never guessed
            print(f"{WHO}: {e}", file=sys.stderr)
            sys.exit(2)
    else:
        asked = ref
    found = git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
    if found.returncode != 0:
        print(f"{WHO}: the predecessor {asked} names no commit of this checkout (a shallow checkout needs "
              f"fetch-depth: 0)", file=sys.stderr)
        sys.exit(2)
    return asked, found.stdout.decode().strip()


def extract_chart(commit: str, into: pathlib.Path) -> pathlib.Path | None:
    """The predecessor's `charts/veyq`, unpacked from git into `into`; None where that commit has no chart."""
    if not git("ls-tree", "--name-only", commit, "--", f"{CHART_PATH}/Chart.yaml").stdout.strip():
        return None
    archive = git("archive", "--format=tar", commit, "--", CHART_PATH)
    if archive.returncode != 0:
        print(f"{WHO}: `git archive {commit[:12]} -- {CHART_PATH}` failed: {archive.stderr.decode().strip()}",
              file=sys.stderr)
        sys.exit(2)
    with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as tar:
        tar.extractall(into)
    return into / CHART_PATH


def render(chart: pathlib.Path, values: pathlib.Path, what: str, problems: list[str]) -> list[dict] | None:
    out = chart_render.template(WHO, chart, values)
    if out.returncode != 0:
        stderr = out.stderr.strip().replace("\n", "\n      ")
        problems.append(f"{what}: `helm template` failed:\n      {stderr}")
        return None
    return chart_render.objects(out.stdout)


def main(argv: list[str]) -> int:
    ref = None
    if argv:
        if len(argv) != 2 or argv[0] != "--upgrade-from":
            print(f"usage: {pathlib.Path(__file__).name} [--upgrade-from <ref>]", file=sys.stderr)
            return 2
        ref = argv[1]
    chart_render.helm(WHO)  # no helm, no run - before any work, so a missing tool is never a skip
    release = e2e.read_release((ROOT / e2e.PIN_FILE).read_text())
    asked, commit = predecessor(ref)
    print(f"{WHO}: predecessor {asked} = {commit[:12]}")
    candidate_values = sorted((CHART / "ci").glob("*.yaml"))
    if not candidate_values:
        print(f"{WHO}: {CHART_PATH}/ci holds no values file - nothing would be rendered", file=sys.stderr)
        return 2
    problems: list[str] = []
    with tempfile.TemporaryDirectory(prefix="chart-upgrade-") as tmp:
        old_chart = extract_chart(commit, pathlib.Path(tmp))
        if old_chart is None:
            print(f"{WHO}: the predecessor carries no {CHART_PATH} - there is no chart to upgrade from; skipped")
            return 0
        # Like for like: both charts with each of the candidate's values files.
        for values in candidate_values:
            before = render(old_chart, values, f"the predecessor's chart with the candidate's ci/{values.name}", problems)
            after = render(CHART, values, f"the candidate's chart with its ci/{values.name}", problems)
            if before is not None and after is not None:
                found = compare(before, after)
                print(f"{WHO}: ci/{values.name}: {len(before)} -> {len(after)} objects, {len(found)} finding(s)")
                problems.extend(f"ci/{values.name}: {p}" for p in found)
        # An operator's existing values: the candidate must still accept and render each of the predecessor's.
        old_values = sorted((old_chart / "ci").glob("*.yaml"))
        for values in old_values:
            if render(CHART, values, f"the candidate's chart with the predecessor's ci/{values.name}", problems) is not None:
                print(f"{WHO}: the candidate renders the predecessor's ci/{values.name}")
        if not old_values:
            print(f"{WHO}: the predecessor's chart has no ci/ values file - no operator values to carry over")
    for problem in problems:
        print(f"  - {problem}")
    code, line = verdict(release, problems)
    print(line)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
