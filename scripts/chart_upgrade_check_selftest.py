#!/usr/bin/env python3
"""Pin the chart upgrade check's pure parts on hand-written manifests, without `helm`, git or a chart
(ADR 0064 § Decision, the chart-upgrade check; cr-helm-upgrade-check).

`scripts/chart_upgrade_check.py` renders two charts and hands both renders to `compare`; whether its findings fail the
build is `verdict`'s. This asserts each of them:
  1. every refusal, on a crafted predecessor/candidate pair that differs in exactly that field - a Deployment's and a
     StatefulSet's selector, a StatefulSet's serviceName, podManagementPolicy and a spec field no rule names
     (revisionHistoryLimit), a claim template's name, order, access modes, class, size and metadata labels, a
     claim's access modes and class and a shrinking size, a RoleBinding's roleRef, a kind changed under a name, a
     StatefulSet and a claim missing from the candidate - each found once, naming the object and the field;
  2. the controls: an identical pair, and a pair whose image tag, labels, replicas and env changed, find nothing; a
     claim that grows, a ConfigMap or a Service that disappears, every field of the StatefulSet's mutable set
     changing, and `podManagementPolicy: OrderedReady` / `revisionHistoryLimit: 10` written out where the
     predecessor left them to the default find nothing either;
  3. the quantities a size is compared in (`1Gi` is `1024Mi`, `1G` is not `1Gi`), and an unreadable size refused
     rather than guessed;
  4. the verdict: no finding is OK either way; findings exit 0 "information only" while `release: none`, and 1 once a
     release is set.
  5. where a predecessor's chart is looked for (ADR 0106 decision 3): this layout's path first, `charts/veyq` second,
     and a predecessor carrying neither skipped.

What it cannot show: that the two renders are the predecessor's and the candidate's - the run itself is that proof.
Not a `validate_*.py`: it asserts nothing about the repository, only about the check, and it runs in `ci.yml`'s
`chart` job just before the check. Runnable locally:

    python3 scripts/chart_upgrade_check_selftest.py
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import copy
import pathlib
import sys
from decimal import Decimal

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import chart_upgrade_check as cuc  # noqa: E402

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail and not ok else ''}")
    if not ok:
        failures.append(label)


SELECTOR = {"matchLabels": {"app.kubernetes.io/name": "veyq", "app.kubernetes.io/component": "db"}}


def claim_spec(size: str = "10Gi", modes: list[str] | None = None, cls: str | None = None) -> dict:
    spec: dict = {"accessModes": modes or ["ReadWriteOnce"], "resources": {"requests": {"storage": size}}}
    if cls is not None:
        spec["storageClassName"] = cls
    return spec


def base() -> list[dict]:
    """A predecessor render with one object of every kind the check reads, and two it only lets change."""
    labels = {"app.kubernetes.io/name": "veyq", "app.kubernetes.io/version": "1.0"}
    return [
        {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "app", "labels": dict(labels)},
         "spec": {"replicas": 1, "selector": copy.deepcopy(SELECTOR), "strategy": {"type": "Recreate"},
                  "template": {"metadata": {"labels": dict(labels)},
                               "spec": {"containers": [{"name": "app", "image": "veyq:1.0", "env": []}]}}}},
        {"apiVersion": "apps/v1", "kind": "StatefulSet", "metadata": {"name": "db", "labels": dict(labels)},
         "spec": {"serviceName": "db", "replicas": 1, "selector": copy.deepcopy(SELECTOR),
                  "template": {"spec": {"containers": [{"name": "db", "image": "mariadb:11"}]}},
                  "volumeClaimTemplates": [{"metadata": {"name": "data"}, "spec": claim_spec()},
                                           {"metadata": {"name": "meta"}, "spec": claim_spec("1Gi")}]}},
        {"apiVersion": "v1", "kind": "PersistentVolumeClaim", "metadata": {"name": "key", "labels": dict(labels)},
         "spec": claim_spec("16Mi")},
        {"apiVersion": "rbac.authorization.k8s.io/v1", "kind": "RoleBinding", "metadata": {"name": "app"},
         "roleRef": {"apiGroup": "rbac.authorization.k8s.io", "kind": "Role", "name": "app"},
         "subjects": [{"kind": "ServiceAccount", "name": "app"}]},
        {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "config"}, "data": {"a": "1"}},
        {"apiVersion": "v1", "kind": "Service", "metadata": {"name": "app"}, "spec": {"selector": {"x": "y"}}},
    ]


def obj(render: list[dict], kind: str, name: str) -> dict:
    return next(o for o in render if o["kind"] == kind and o["metadata"]["name"] == name)


def edited(edit) -> list[dict]:
    after = base()
    edit(after)
    return after


def refused_once(label: str, edit, *needles: str) -> None:
    """`edit` applied to a copy of the base finds exactly one reason, and that reason names every needle."""
    found = cuc.compare(base(), edited(edit))
    ok = len(found) == 1 and all(n in found[0] for n in needles)
    check(label, ok, f"found {found}")


def set_in(path: list, value):
    def edit(render: list[dict]) -> None:
        target = obj(render, path[0], path[1])
        for key in path[2:-1]:
            target = target[key]
        target[path[-1]] = value
    return edit


def template(render: list[dict], name: str) -> dict:
    return next(t for t in obj(render, "StatefulSet", "db")["spec"]["volumeClaimTemplates"] if t["metadata"]["name"] == name)


def refusal_cases() -> None:
    other = {"matchLabels": {"app.kubernetes.io/name": "veyq", "app.kubernetes.io/component": "other"}}
    refused_once("a Deployment's selector changed", set_in(["Deployment", "app", "spec", "selector"], other),
                 "Deployment/app", "spec.selector")
    refused_once("a StatefulSet's selector changed", set_in(["StatefulSet", "db", "spec", "selector"], other),
                 "StatefulSet/db", "spec.selector")
    refused_once("a StatefulSet's serviceName changed", set_in(["StatefulSet", "db", "spec", "serviceName"], "db2"),
                 "StatefulSet/db", "spec.serviceName")
    refused_once("a StatefulSet's podManagementPolicy changed",
                 set_in(["StatefulSet", "db", "spec", "podManagementPolicy"], "Parallel"),
                 "StatefulSet/db", "spec.podManagementPolicy")
    refused_once("a StatefulSet spec field beyond the named ones changed (revisionHistoryLimit)",
                 set_in(["StatefulSet", "db", "spec", "revisionHistoryLimit"], 3),
                 "StatefulSet/db", "spec.revisionHistoryLimit")
    refused_once("a claim template's metadata labels changed",
                 lambda r: template(r, "data")["metadata"].__setitem__("labels", {"tier": "db"}),
                 "StatefulSet/db", "spec.volumeClaimTemplates[data] changed")
    refused_once("claim templates reordered",
                 lambda r: obj(r, "StatefulSet", "db")["spec"]["volumeClaimTemplates"].reverse(),
                 "StatefulSet/db", "volumeClaimTemplates order")
    refused_once("a claim template renamed",
                 lambda r: template(r, "meta")["metadata"].__setitem__("name", "metadata"),
                 "StatefulSet/db", "volumeClaimTemplates names")
    refused_once("a claim template added",
                 lambda r: obj(r, "StatefulSet", "db")["spec"]["volumeClaimTemplates"].append(
                     {"metadata": {"name": "snapshots"}, "spec": claim_spec()}),
                 "StatefulSet/db", "volumeClaimTemplates names")
    refused_once("a claim template's accessModes changed",
                 lambda r: template(r, "data")["spec"].__setitem__("accessModes", ["ReadWriteMany"]),
                 "StatefulSet/db", "volumeClaimTemplates[data].accessModes")
    refused_once("a claim template's storageClassName changed",
                 lambda r: template(r, "data")["spec"].__setitem__("storageClassName", "fast"),
                 "StatefulSet/db", "volumeClaimTemplates[data].storageClassName")
    refused_once("a claim template's size changed (a growth too)",
                 lambda r: template(r, "data")["spec"]["resources"]["requests"].__setitem__("storage", "20Gi"),
                 "StatefulSet/db", "volumeClaimTemplates[data] size")
    refused_once("a claim's accessModes changed", set_in(["PersistentVolumeClaim", "key", "spec", "accessModes"],
                                                          ["ReadWriteOncePod"]),
                 "PersistentVolumeClaim/key", "spec.accessModes")
    refused_once("a claim's storageClassName changed",
                 set_in(["PersistentVolumeClaim", "key", "spec", "storageClassName"], "fast"),
                 "PersistentVolumeClaim/key", "spec.storageClassName")
    refused_once("a claim's size shrank",
                 set_in(["PersistentVolumeClaim", "key", "spec", "resources", "requests", "storage"], "8Mi"),
                 "PersistentVolumeClaim/key", "shrank")
    refused_once("a RoleBinding's roleRef changed",
                 set_in(["RoleBinding", "app", "roleRef", "name"], "admin"), "RoleBinding/app", "roleRef")

    def to_deployment(render: list[dict]) -> None:
        obj(render, "StatefulSet", "db")["kind"] = "Deployment"
    refused_once("a kind changed under the same name", to_deployment, "StatefulSet/db", "kind changed", "Deployment/db")

    def drop(kind: str, name: str):
        return lambda render: render.remove(obj(render, kind, name))
    refused_once("a StatefulSet missing from the candidate", drop("StatefulSet", "db"), "StatefulSet/db", "missing")
    refused_once("a claim missing from the candidate", drop("PersistentVolumeClaim", "key"),
                 "PersistentVolumeClaim/key", "missing")


def control_cases() -> None:
    check("an identical pair finds nothing", cuc.compare(base(), base()) == [])

    def everyday(render: list[dict]) -> None:
        for o in render:
            o["metadata"].setdefault("labels", {})["app.kubernetes.io/version"] = "1.1"
            o["metadata"]["labels"]["helm.sh/chart"] = "veyq-1.1"
        app = obj(render, "Deployment", "app")
        app["spec"]["replicas"] = 2
        app["spec"]["template"]["spec"]["containers"][0]["image"] = "veyq:1.1@sha256:" + "0" * 64
        app["spec"]["template"]["spec"]["containers"][0]["env"] = [{"name": "X", "value": "1"}]
        obj(render, "StatefulSet", "db")["spec"]["template"]["spec"]["containers"][0]["image"] = "mariadb:11.4"
    found = cuc.compare(base(), edited(everyday))
    check("an image tag, labels, replicas and env changing find nothing", found == [], f"found {found}")
    found = cuc.compare(base(), edited(set_in(["PersistentVolumeClaim", "key", "spec", "resources", "requests",
                                                "storage"], "1Gi")))
    check("a claim that grows finds nothing (expansion is the StorageClass's)", found == [], f"found {found}")
    # On a claim TEMPLATE, whose size may not change in either direction, so a unit misread either way is a finding.
    found = cuc.compare(base(), edited(
        lambda r: template(r, "meta")["spec"]["resources"]["requests"].__setitem__("storage", "1024Mi")))
    check("the same size written in another unit finds nothing", found == [], f"found {found}")
    found = cuc.compare(base(), edited(lambda r: [r.remove(obj(r, "ConfigMap", "config")), r.remove(obj(r, "Service", "app"))]))
    check("a ConfigMap and a Service disappearing find nothing (no data behind them)", found == [], f"found {found}")
    found = cuc.compare(base(), edited(set_in(["StatefulSet", "db", "spec", "podManagementPolicy"], "OrderedReady")))
    check("the default podManagementPolicy written out finds nothing", found == [], f"found {found}")
    found = cuc.compare(base(), edited(set_in(["StatefulSet", "db", "spec", "revisionHistoryLimit"], 10)))
    check("the default revisionHistoryLimit written out finds nothing", found == [], f"found {found}")

    def mutable(render: list[dict]) -> None:
        spec = obj(render, "StatefulSet", "db")["spec"]
        spec.update({"replicas": 3, "ordinals": {"start": 1}, "updateStrategy": {"type": "OnDelete"},
                     "persistentVolumeClaimRetentionPolicy": {"whenDeleted": "Delete"}, "minReadySeconds": 5})
        spec["template"]["spec"]["containers"][0]["image"] = "mariadb:12"
    found = cuc.compare(base(), edited(mutable))
    check("every field of the StatefulSet's mutable set changing finds nothing", found == [], f"found {found}")


def quantity_cases() -> None:
    q = cuc.quantity
    check("1Gi is 1024Mi", q("1Gi") == q("1024Mi") == Decimal(1024 ** 3))
    check("1G is not 1Gi", q("1G") == Decimal(10 ** 9) and q("1G") != q("1Gi"))
    check("a plain number is bytes, a fraction is read", q("512") == 512 and q("1.5Ki") == Decimal(1536))
    check("a size that is no quantity is None", q("ten gigs") is None and q(None) is None and q("1Zi") is None)
    found = cuc.compare(base(), edited(set_in(["PersistentVolumeClaim", "key", "spec", "resources", "requests",
                                                "storage"], "lots")))
    check("an unreadable size is refused, not guessed", len(found) == 1 and "not a quantity" in found[0], f"{found}")


def verdict_cases() -> None:
    v = cuc.verdict
    check("no finding, release: none -> 0, OK", v("none", [])[0] == 0 and "OK" in v("none", [])[1])
    check("no finding, a release -> 0, OK", v("202610041201", [])[0] == 0 and "OK" in v("202610041201", [])[1])
    code, line = v("none", ["x"])
    check("findings while release: none -> 0, information only", code == 0 and "information only" in line, line)
    code, line = v("202610041201", ["x"])
    check("findings once a release is set -> 1, naming the release", code == 1 and "202610041201" in line, line)


def layout_cases() -> None:
    """ADR 0106 decision 3: a predecessor's chart is found at this layout's path or at the one before it. Spelled with
    a path of its own for "this layout", so the cases hold apart even while the two layouts' paths are one."""
    new, old = "infra/kubernetes/charts/veyq", "charts/veyq"

    def find(*present: str):
        return cuc.predecessor_chart_path(lambda p: p in present, current=new, legacy=old)

    found = find(new)
    check("a predecessor carrying the chart at this layout's path is found there", found == new, f"found {found}")
    found = find(old)
    check("a predecessor carrying the chart at charts/veyq is found there", found == old, f"found {found}")
    found = find(new, old)
    check("a predecessor carrying both is read at this layout's path", found == new, f"found {found}")
    found = find()
    check("a predecessor carrying neither is skipped (None)", found is None, f"found {found}")
    defaults = cuc.predecessor_chart_path.__defaults__
    check("the lookup defaults to this checkout's chart path, then charts/veyq",
          defaults == (cuc.repo_layout.CHART_PATH, "charts/veyq"), f"defaults {defaults}")


refusal_cases()
control_cases()
quantity_cases()
verdict_cases()
layout_cases()

if failures:
    print(f"CHART UPGRADE CHECK SELFTEST FAILED: {len(failures)} case(s)")
    sys.exit(1)
print("chart upgrade check selftest OK")
