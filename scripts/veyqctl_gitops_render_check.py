#!/usr/bin/env python3
"""What `veyqctl init --gitops` writes is the chart's input, checked with the real `helm` (cr-veyqctl-gitops-target).

`veyqctl`'s self-test drives `init` and `upgrade` with a stand-in for `helm` — it shows the order and the refusals, not
that the files it writes are ones the chart accepts. This renders, with the pinned Helm the `chart` job installs:

  * `values.yaml` exactly as `veyqctl.stack_values` writes it, for every TLS choice `init` offers (`acme`, `shared`,
    `none`) with and without the bundled store, against THIS tree's chart — so a chart change that a written
    repository no longer satisfies (a renamed key, a new required value) is red here, in the commit that makes it;
  * `resources/` as `init` writes it for each secrets provider (`sops`' encrypted Secrets aside, which `sops` writes):
    every manifest parses as one YAML document of the kind it names, and the kustomization lists them.

Digests are stand-ins: a render does not pull. Needs `helm` and PyYAML (the `chart` job's venv):

    python3 scripts/veyqctl_gitops_render_check.py
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

import repo_layout

repo_layout.tools_importable()
import veyqctl as vc  # noqa: E402

API_VERSIONS = [arg for api in vc.HELM_API_VERSIONS for arg in ("--api-versions", api)]
PINS = {role: "sha256:" + f"{i:x}" * 64 for i, role in enumerate(vc.PIN_ROLES, start=10)}


def main() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="veyqctl-render-") as tmp:
        for variant in ("acme", "acme-staging", "shared", "none"):
            tls, _, staging = variant.partition("-")
            directory = "staging" if staging else "production"
            for garage in (True, False):
                label = f"tls={variant} bundled-store={'on' if garage else 'off'}"
                values = Path(tmp) / f"values-{tls}-{directory}-{garage}.yaml"
                values.write_text(vc.stack_values(
                    "1.0", PINS, domain="veyq.example.org", store_domain="store.veyq.example.org" if garage else "",
                    garage=garage, gateway_class="traefik", tls=tls, tls_secret="veyq-tls", peer_namespace="traefik",
                    peer_label="app.kubernetes.io/name=traefik",
                    provider="none", acme_directory=directory))
                result = subprocess.run(["helm", "template", "veyq", str(repo_layout.CHART_DIR), "-n", "veyq", "-f",
                                         str(values), *API_VERSIONS], capture_output=True, text=True)
                rendered = list(yaml.safe_load_all(result.stdout)) if result.returncode == 0 else []
                images = [c.get("image", "") for d in rendered if isinstance(d, dict)
                          for c in (d.get("spec", {}).get("template", {}).get("spec", {}).get("containers") or [])]
                servers = [d["spec"]["acme"]["server"] for d in rendered if isinstance(d, dict) and d.get("kind") == "Issuer"]
                ok = result.returncode == 0 and any(PINS["app"] in i for i in images) and (
                    servers == [vc.ACME_DIRECTORIES[directory]] if tls == "acme" else not servers)
                print(f"{'ok  ' if ok else 'FAIL'} veyqctl's values render ({label}), the app image by its pin, the Issuer's directory")
                if not ok:
                    failures.append(label)
                    print((result.stderr or result.stdout)[-1500:])
    texts = {
        "namespace": (vc.namespace_text("veyq"), "Namespace"),
        "backup claim": (vc.backup_claim_text("veyq", "local-path", "20Gi"), "PersistentVolumeClaim"),
        "backup claim, the cluster's class": (vc.backup_claim_text("veyq", "", "20Gi"), "PersistentVolumeClaim"),
        "a Secret (sops' and sealed-secrets' input, none's shape)": (
            vc.secret_text(vc.SECRETS_SECRET, "veyq", {k: "x" for k in vc.SECRET_VALUES[vc.SECRETS_SECRET]}), "Secret"),
        "the config-secret key's Secret": (
            vc.secret_text(vc.CONFIG_SECRET, "veyq", {vc.CONFIG_SECRET_KEY: "eA=="}), "Secret"),
        "veyq's admin through the tunnel (two objects)": (vc.admin_host_access_text("veyq", "10.42.0.1/32"), None),
        "an ExternalSecret": (vc.external_secret_text(vc.SECRETS_SECRET, "veyq", "vault", "ClusterSecretStore", "veyq-"),
                              "ExternalSecret"),
        "the Application": (vc.application_text(name="veyq", argocd_namespace="argocd", project="default",
                                                chart="ghcr.io/raddatzk/charts/veyq", digest=PINS["chart"],
                                                rel="apps/veyq", repo_url="git@example.org:ops/cluster.git",
                                                revision="main", namespace="veyq", values="values.yaml"),
                            "Application"),
    }
    for label, (text, kind) in texts.items():
        docs = [d for d in yaml.safe_load_all(text) if d is not None]
        ok = ([d.get("kind") for d in docs] == ["Service", "NetworkPolicy"] if kind is None
              else len(docs) == 1 and docs[0].get("kind") == kind)
        print(f"{'ok  ' if ok else 'FAIL'} {label}" + (f": one {kind}" if kind else ""))
        if not ok:
            failures.append(label)
    if failures:
        print(f"\nveyqctl GitOps render check FAILED: {len(failures)}")
        return 1
    print("\nveyqctl GitOps render check OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
