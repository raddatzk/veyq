#!/usr/bin/env python3
"""Fuzz the API from its contracts against a live backend: Schemathesis, pinned by digest (ADR 0091 decision 9).

**What it does.** Boots one fresh fast-jar under `%e2e` — the profile whose rate limits do not refuse a run's volume —
with `run_web_e2e.py`'s own primitives (the shared compose stack's MariaDB and store, a database and a bucket named
by the run's stamp and dropped when it ends [run_names] — two worktrees' runs never share one, inbox #501 — its own
ports), births an instance on it with the real client crypto (`src/web/scripts/fuzz-seed.mts`: an owner with a Memory, a
medium, a bucket and a link in a group of its own, and a PEER, a second member of the tenant), and runs the pinned
Schemathesis image over every spec file `contract_specs.specs()` finds — never a list here — in [INVOCATIONS], in order:

    public-positive   every GET, positive, the seeded ids fixed
    public-negative   every operation, negative (coverage, fuzzing)
    public-writes     every other method, positive, the seeded ids fixed, minus [NEGATIVE_ONLY]
    public-stateful   Schemathesis' stateful phase over the links it infers from the schemas, minus [NEGATIVE_ONLY];
                      only the containers (tenant, owner, scope) fixed, so a sequence reaches what it creates
    admin-positive    every admin GET, positive — an admin write is the instance's configuration: negative only
    admin-negative    every admin operation, negative

Each public positive invocation after the first is fixed to objects of its own (`fuzz-seed.mts --objects`): a negative
run learns ids from the responses it reads, and the writes trash, revoke and purge what they are fixed to.

An operation marked `x-veyq-channel: admin` goes to the admin listener, every other one to the public listener; a spec
file with no operation for an invocation is not run for it. Before each spec file the runner makes sure the access
token still works and signs both identities in again (`fuzz-seed.mts --login`) when it does not: a token lives 15
minutes, a run longer, and a request a server accepted may end a session.

**What a positive run may change** (ADR 0091 decision 9): what the run's own identities own below the tenant — their
account's sealed data, their scopes and what is in them, their links. Not how they sign in, not the tenant's own
configuration and membership ([NEGATIVE_ONLY], a reason per entry; the runner refuses to start when one names no
operation), not the instance (every admin write). A write's ciphertext is opaque to the server, so schema-valid random
bytes are valid data as far as it can know; a write carrying a credential the run cannot forge is still sent, and its
declared 401/403 is a correct answer.

**The contracts as the server reads them.** Schemathesis reads a copy of every spec file ([write_enforced_specs], in
the run directory) in which three of the contracts' own idioms are spelled the way a JSON Schema generator reads them —
`contentEncoding: base64` as the base64 `pattern`, `@NotBlank`/`@NotEmpty` as a non-blank/non-empty string,
`[T, "null"]` with `@NotNull` as `T` ([enforced] says why each is what the contract already means). The contracts are
not changed by it.

**What the image checks** is `tests/fuzz/schemathesis.toml` (the checks, the auth) and `tests/fuzz/hooks.py` (veyq's
three own checks: a refusal by location, a refusal that echoes nothing, and `peer_is_refused` — every positive request
the owner made in its scope and the server accepted is sent again as the peer and must be refused as a scope that does
not exist is). The seed is [SEED], the generation deterministic: a red re-runs to the same request. Changing the seed is
a commit (ADR 0091 decision 9).

**Red when** a failure is not in `tests/fuzz/baseline.json` (Schemathesis' own verdict, its exit code), an invocation
of a spec file exits non-zero for any other reason, an invocation ERRORED on an operation (a network error is no
green), or an invocation **tested no operation** — read from its JSON report, never from the exit code, because "the
guard held" and "nothing ran" exit alike (changes/README.md § a bite test must tell them apart). The baseline's own
rules — a reason per entry, never a 5xx, no expiry — are `scripts/validate_api_fuzz_baseline.py`'s.

**What it prints and keeps.** The run directory (`.fuzz-out/<stamp>/`, gitignored; `VEYQ_FUZZ_OUT` moves the root)
holds the boot log, the seed (`seed.json`, `token`, `token-peer`: mode 0600, a throwaway instance's; `reports/ids.json`,
the ids only), the specs as read (`reports/contracts/`), and per invocation and spec
file Schemathesis' JUnit (with a `curl` line per failure), NDJSON, VCR cassette and JSON report. `summary.txt` — also
printed — names per invocation the operations tested, every failure by check and operation with its `curl` line, and
**the operations that never answered 2xx** in the positive invocations: information, not a verdict — there the
response schema went unchecked and the peer was never sent them, because no seeded object reaches them (a moderation
case, a statement, a task) or no generated request is one the instance accepts; and per spec file whether the stateful
phase inferred any link at all.

**The image is our own** (`tests/fuzz/Dockerfile`, owner decision Q8 of cr-api-fuzzing): a Python base pinned by
digest, Schemathesis from PyPI with every package hash-pinned (`tests/fuzz/requirements.txt`, written by
`tests/fuzz/lock.py`). The official `schemathesis/schemathesis` image also installs TraceCov, a proprietary,
binary-only coverage plugin, and loads it on every run; ours carries only the MIT tool, and `hooks.py` refuses to load
if TraceCov is ever imported. The runner builds it before the first invocation, tagged by the hash of the directory's
build inputs ([IMAGE_INPUTS]), so a changed pin is a new image and an unchanged one a cache hit.

**Networking — the one step that differs between Docker Desktop and a Linux runner**, made the same command on both:
the container reaches the host's listeners through `--add-host <name>:host-gateway`, as `veyq.localhost` (the tenant's
domain, which the backend resolves the tenant from) and `veyq-admin.internal`. The jar binds every interface under
`%e2e` (Quarkus' production default host), and the admin channel admits a non-global peer (`AdminChannel`), which a
bridge or Docker Desktop's gateway is. Measured on Docker Desktop (macOS, arm64) only; a Linux runner is CI's.

**Sharing the developer's compose stack.** Like `run_web_e2e.py`, it uses `infra/docker/docker-compose.yml`'s MariaDB and
store — its own database and bucket only, and `up --no-recreate`, so a running stack is never recreated. From a
git worktree, pin the stack to the main checkout's `infra/docker/` (`VEYQ_INFRA_DIR=<main>/infra/docker`) and the jar to the
worktree's (`VEYQ_JAR`), as that script's `compose_up` explains; `run_pipeline.py` does the first for every job.

**Measured** (2026-10-19, local: macOS arm64, Docker Desktop, a load average of 11-21 from parallel sessions;
`MAX_EXAMPLES` 10; the last two runs, the green one second): the image already built, the boot and the seed about 40 s,
each later fresh set of objects about 10 s; public-positive 23-31 s (54 GETs), public-negative 67-87 s (182 operations),
public-writes 75-129 s (112), public-stateful 49-53 s (40 of 165 operations reached through an inferred link), admin
positive 2-3 s (17), admin-negative 13-19 s (61) — 343-438 s in all, against 159 s for the first slice alone. With the
jar build, `pnpm install` and the image build before it the job still fits its 15 minutes on that machine, with less room; a
CI runner has fewer cores, and its first run replaces these numbers (ADR 0091 decision 1). `MAX_EXAMPLES` is the knob if
it does not.

Usage:
    python3 scripts/run_api_fuzz.py                      # the whole run, as CI calls it
    python3 scripts/run_api_fuzz.py --baseline-update    # also merge this run's failures into tests/fuzz/baseline.json
    python3 scripts/run_api_fuzz.py --only <spec-key>    # one spec file (a local debugging convenience)

Prerequisite: the fast-jar (`cd src/backend && ./gradlew quarkusBuild`) and web's dependencies (`pnpm install` at the root, which
generates web's API client). A stale jar answers for code the run never saw (`run_web_e2e.py`'s docstring).

Environment (all optional):
    VEYQ_JAR                The fast-jar. Default: src/backend/build/quarkus-app/quarkus-run.jar
    VEYQ_INFRA_DIR          The directory holding the Compose files whose stack to use (`DOCKER_DIR` in
                            `scripts/repo_layout.py`; see above).
    VEYQ_FUZZ_PORT          The backend's public port. Default: 18480
    VEYQ_FUZZ_ADMIN_PORT    Its admin-listener port. Default: 18490
    VEYQ_FUZZ_OUT           The root of the run directories. Default: .fuzz-out
    VEYQ_FUZZ_MAX_EXAMPLES  Overrides [MAX_EXAMPLES] (a local sampling knob; CI uses the constant).
    VEYQ_FUZZ_INCLUDE_BY    An extra `--include-by` expression for every invocation (the bites' lever).
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NamedTuple

import yaml

# The backend's ports and the e2e primitives' module-level constants are read at import: this run's own ports are set
# first, so it never answers on (or kills) a developer's 8080/8090 or a web-e2e run's.
os.environ["VEYQ_PORT"] = os.environ.get("VEYQ_FUZZ_PORT", "18480")
os.environ["VEYQ_ADMIN_PORT"] = os.environ.get("VEYQ_FUZZ_ADMIN_PORT", "18490")

import contract_specs  # noqa: E402
import run_web_e2e as e2e  # noqa: E402

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)

REPO = Path(__file__).resolve().parent.parent
WEB = repo_layout.WEB
FUZZ = repo_layout.FUZZ_DIR
CONFIG = FUZZ / "schemathesis.toml"
BASELINE = FUZZ / "baseline.json"

# What the image is built from; their hash is its tag (`veyq-fuzz-schemathesis:<hash>`), so the tag names the pins.
IMAGE_INPUTS = ("Dockerfile", "requirements.txt")
IMAGE_REPO = "veyq-fuzz-schemathesis"
# Fixed, so a red re-runs to the same request (ADR 0091 decision 9). Changing it is a commit.
SEED = 20261018
# Test cases per operation and phase — the sampling knob that fits the job's 15 minutes (ADR 0091 decision 1).
MAX_EXAMPLES = int(os.environ.get("VEYQ_FUZZ_MAX_EXAMPLES", "10"))
PHASES = "examples,coverage,fuzzing"
# A negative invocation has no examples phase: the contract's examples are POSITIVE requests, and sent from there they
# reached every write — the admin listener's and the NEGATIVE_ONLY ones included (measured: the first run of this slice
# created tenants on the admin listener from `createTenant`'s example). The positive invocations send them.
NEGATIVE_PHASES = "coverage,fuzzing"
STATEFUL = "stateful"

# This run's OWN database and bucket, named by its stamp ([run_names]) and dropped when it ends: never `veyq` (the
# developer's), never a `veyq_e2e_*` (a web-e2e run's), never another fuzz run's (inbox #501 — a second worktree's run
# recreated a shared `veyq_fuzz` under the first one's backend).
DATABASE_PREFIX = "veyq_fuzz_"
BUCKET_PREFIX = "veyq-fuzz-"

HOST = "veyq.localhost"
ADMIN_HOST = "veyq-admin.internal"
PORT = e2e.PORT
ADMIN_PORT = e2e.ADMIN_PORT
RUN_ROOT = Path(os.environ.get("VEYQ_FUZZ_OUT", REPO / ".fuzz-out"))
ADMIN = '/x-veyq-channel == "admin"'
NOT_GET = "^(?!GET$)"  # every method but GET
METHODS = ("get", "put", "post", "delete", "patch", "head", "options")
# Operations that end the very session they are called with — run after the rest of their spec file, alone, so the
# operations after them are not all answered `401` for a token the run itself signed out (measured: the first runs'
# `pairing/delegate` and `pairing/seal` "refused" a missing body with 401 because `logout` had run before them).
SESSION_ENDING = ("logout",)
CREDENTIAL = "token"  # the contract's `x-veyq-visibility` class of a credential (validate_field_visibility.py)

# What a positive run may NOT change (ADR 0091 decision 9): the writes that stay negative-only on the public listener,
# each with the reason it may not be sent with valid data. The rule they apply: a positive write may change what the
# run's own identities own below the tenant (their account's sealed data, their scopes and what is in them, their
# links), never how those identities sign in, nor the tenant's own configuration and membership, which every later
# request is answered by. Every admin-listener write is negative-only by its channel, not by this list (the instance's
# configuration). The runner refuses to start when a name here is no operationId of contracts/api.
NEGATIVE_ONLY = {
    # how the run's identities sign in
    "changePassword": "replaces the password the run signs in again with",
    "rotateAccountKeys": "replaces the account's key material with keys nobody holds",
    "addAccountWrap": "adds a Master Key wrap the run cannot open, beside the one it signs in with",
    "deleteAccountWrap": "may delete the PASSWORD wrap the run signs in with",
    "eraseOwnAccount": "erases the owner, and the instance with it",
    "confirmEmailChange": "changes the address the run signs in with",
    "completePasswordRecovery": "replaces the password the run signs in with",
    "revokeSessionFamily": "may revoke the session the run is sending with",
    # the tenant's configuration and membership
    "updateTenantTerms": "new terms make every member accept them again before any other request",
    "setTenantCapabilitySwitch": "switches capabilities off that later requests rely on",
    "setTenantCapabilityRequirement": "adds a requirement later requests do not meet",
    "clearTenantCapabilityRequirement": "the pair of setTenantCapabilityRequirement",
    "updateTenantMailSettings": "points the tenant's mail away from the run's Mailpit",
    "updateTenantStorage": "changes the tenant's storage settings later uploads are admitted by",
    "setTenantMemberRole": "may demote the owner the run sends as",
    "removeTenantMember": "may remove the peer, or the owner",
}



def log(msg: str) -> None:
    print(f"[fuzz] {msg}", flush=True)


class Invocation(NamedTuple):
    name: str
    channel: str  # "public" | "admin"
    mode: str  # "negative" | "positive"
    methods: str  # "all" | "get" | "write" (every method but GET)
    phases: str


# In this order: the positive reads first, on the objects the birth made; every later public positive invocation gets
# objects of its own (`fuzz-seed.mts --objects`); the admin reads before the admin negatives, whose few valid requests
# reach the instance's configuration.
INVOCATIONS = (
    Invocation("public-positive", "public", "positive", "get", PHASES),
    Invocation("public-negative", "public", "negative", "all", NEGATIVE_PHASES),
    Invocation("public-writes", "public", "positive", "write", PHASES),
    Invocation("public-stateful", "public", "positive", "all", STATEFUL),
    Invocation("admin-positive", "admin", "positive", "get", PHASES),
    Invocation("admin-negative", "admin", "negative", "all", NEGATIVE_PHASES),
)


def run_names(stamp: str) -> tuple[str, str, str]:
    """(database, store-init database, bucket) of the run stamped `stamp`."""
    tag = stamp.replace("-", "_")
    return f"{DATABASE_PREFIX}{tag}", f"{DATABASE_PREFIX}{tag}_store_init", f"{BUCKET_PREFIX}{stamp}"


def unknown_negative_only() -> list[str]:
    """The [NEGATIVE_ONLY] names no spec of contracts/api declares as an operationId — a stale entry excludes nothing."""
    declared = set()
    for spec in contract_specs.specs():
        for item in (contract_specs.load(contract_specs.key(spec)).get("paths") or {}).values():
            declared.update(op.get("operationId") for method, op in item.items() if method in METHODS)
    return sorted(set(NEGATIVE_ONLY) - declared)


def image_tag() -> str:
    digest = hashlib.sha256()
    for name in IMAGE_INPUTS:
        digest.update(name.encode() + b"\0" + (FUZZ / name).read_bytes() + b"\0")
    return f"{IMAGE_REPO}:{digest.hexdigest()[:16]}"


def build_image() -> str:
    """Our Schemathesis image (`tests/fuzz/Dockerfile`), built unless this very set of pins is built already."""
    tag = image_tag()
    if subprocess.run(["docker", "image", "inspect", tag], capture_output=True).returncode != 0:
        log(f"building {tag} from tests/fuzz/")
        e2e.run(["docker", "build", "--quiet", "-t", tag, str(FUZZ)])
    return tag


def operations(spec: Path, inv: Invocation, ending: bool = False) -> list[str]:
    """The operations of one spec file this invocation selects — `METHOD /path`, Schemathesis' own label — either the
    ones that end the run's session ([SESSION_ENDING], `ending=True`) or all the others."""
    doc = contract_specs.load(contract_specs.key(spec))
    out = []
    for path, item in (doc.get("paths") or {}).items():
        for method, op in item.items():
            if method not in METHODS:
                continue
            admin = op.get("x-veyq-channel") == "admin"
            if (inv.channel == "admin") != admin:
                continue
            if (inv.methods == "get") != (method == "get") and inv.methods != "all":
                continue
            if inv.mode == "positive" and method != "get" and (admin or op.get("operationId") in NEGATIVE_ONLY):
                continue
            if (op.get("operationId") in SESSION_ENDING) != ending:
                continue
            out.append(f"{method.upper()} {path}")
    return sorted(out)


def _resolve(key: str, node: object) -> tuple[str, object]:
    while isinstance(node, dict) and isinstance(node.get("$ref"), str):
        target, _, pointer = node["$ref"].partition("#")
        key = contract_specs.ref_key(key, target) if target else key
        node = contract_specs.load(key)
        for part in pointer.strip("/").split("/"):
            node = node[part]
    return key, node


def _carries_credential(key: str, node: object, depth: int = 0) -> bool:
    """Whether a schema holds a property the contract marks `x-veyq-visibility: token`, at any depth."""
    key, node = _resolve(key, node)
    if depth > 12 or not isinstance(node, dict):
        return False
    if node.get("x-veyq-visibility") == CREDENTIAL:
        return True
    children = list((node.get("properties") or {}).values()) + [node.get("items")] + \
        list(node.get("oneOf") or []) + list(node.get("anyOf") or []) + list(node.get("allOf") or [])
    return any(_carries_credential(key, c, depth + 1) for c in children if c is not None)


def credential_operations() -> list[str]:
    """Every operation that takes a credential — a parameter or a request-body property the contract marks
    `x-veyq-visibility: token` (a capability secret, a signature, a refresh token, a code) — as Schemathesis' label.
    Derived from the contracts, never listed: `hooks.py` accepts the operation's declared 401/403 for any negative
    case of these (owner decision Q6), because every request the run sends them carries a credential it cannot forge."""
    found = []
    for spec in contract_specs.specs():
        key = contract_specs.key(spec)
        for path, item in (contract_specs.load(key).get("paths") or {}).items():
            for method, op in item.items():
                if method not in METHODS:
                    continue
                params = [_resolve(key, p)[1] for p in item.get("parameters", []) + op.get("parameters", [])]
                body = ((op.get("requestBody") or {}).get("content") or {}).get("application/json", {}).get("schema")
                if any(isinstance(p, dict) and p.get("x-veyq-visibility") == CREDENTIAL for p in params) or \
                        (body is not None and _carries_credential(key, body)):
                    found.append(f"{method.upper()} {path}")
    return sorted(found)


# --------------------------------------------------------------------------------------------------------------
# The contracts as the server reads them
# --------------------------------------------------------------------------------------------------------------

# RFC 4648 base64, padded: what `contentEncoding: base64` means (the annotation's own `[A-Za-z0-9+/]*={0,2}` is the
# loose shape it checks; a value the server decodes must also have whole quanta — measured: `D4=` answered 400).
BASE64 = "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$"
# The range of the type the generated server binds an integer to: `int64` a Long, anything else an Int (the Kotlin
# generator's default for `type: integer`) — past it the value does not bind and the request is a 400.
INT64 = (-(2 ** 63), 2 ** 63 - 1)
INT32 = (-(2 ** 31), 2 ** 31 - 1)
ANNOTATED_PATTERN = re.compile(r'constraints\.Pattern\(regexp = "((?:[^"\\]|\\.)*)"\)')


def enforced(node: object) -> object:
    """One schema node with what its own `x-field-extra-annotation` and `contentEncoding` already say made generable.

    A request property's bounds hold on the server only through the annotation beside it (`validate_request_bounds.py`
    holds the two together), and three of veyq's contract idioms say in an annotation what a JSON Schema generator does
    not read: `contentEncoding: base64` declares the base64 `@Pattern` (that guard's rule 1 — JSON Schema makes
    `contentEncoding` an annotation only); `@NotBlank`/`@NotEmpty` declare a non-empty string (rule 1 accepts them for
    `minLength: 1`); and a required primitive is typed `[T, "null"]` with `@NotNull`, so that a missing field is refused
    rather than bound as 0/false (rule 5) — the `null` is the generator's workaround, never a value the server takes.
    Two more are what a format means: base64 is whole quanta, and an integer binds to the generated type's range.
    Read as they are, a positive request mostly carries one of them broken and is refused `400`, whatever else it says
    (measured: 58 of the 111 positive writes of this slice's first run answered 400; each of the 25 read broke one of
    these). The negative invocations read the contracts as they are."""
    if isinstance(node, list):
        return [enforced(x) for x in node]
    if not isinstance(node, dict):
        return node
    out = {k: enforced(v) for k, v in node.items()}
    note = out.get("x-field-extra-annotation")
    note = note if isinstance(note, str) else ""
    if out.get("contentEncoding") == "base64" and "pattern" not in out:
        out["pattern"] = BASE64
    if "pattern" not in out and ANNOTATED_PATTERN.search(note):
        out["pattern"] = f"^(?:{ANNOTATED_PATTERN.search(note).group(1)})$"
    if re.search(r"constraints\.Not(Blank|Empty)\b", note) and out.get("type") in ("string", ["string", "null"]):
        out["minLength"] = max(int(out.get("minLength") or 0), 1)
        if "NotBlank" in note and "pattern" not in out:
            # Not blank as Hibernate reads it: `toString().trim()` drops every character up to U+0020, so a control
            # character alone is blank too — `\S` admitted U+001C and the server refused it (inbox #507). A search, as
            # JSON Schema's `pattern` is.
            out["pattern"] = r"[^\x00-\x20]"
    if "constraints.NotNull" in note and isinstance(out.get("type"), list) and "null" in out["type"]:
        rest = [t for t in out["type"] if t != "null"]
        out["type"] = rest[0] if len(rest) == 1 else rest
    kinds = out.get("type") if isinstance(out.get("type"), list) else [out.get("type")]
    if "integer" in kinds:
        low, high = INT64 if out.get("format") == "int64" else INT32
        out["minimum"] = max(out.get("minimum", low), low)
        out["maximum"] = min(out.get("maximum", high), high)
    return out


def write_enforced_specs(dest: Path) -> None:
    """Every spec file of contracts/api, read through [enforced], at its own relative path below `dest` — so the
    cross-file `$ref`s resolve as they do in the contracts. The contracts themselves are not touched."""
    for spec in contract_specs.specs():
        key = contract_specs.key(spec)
        target = dest / key
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(yaml.safe_dump(enforced(yaml.safe_load(spec.read_text())), sort_keys=False, allow_unicode=True))


# --------------------------------------------------------------------------------------------------------------
# The instance
# --------------------------------------------------------------------------------------------------------------

def stack_up() -> None:
    """MariaDB, the store and Mailpit (the seeded tenant mails through it), as `run_web_e2e.compose_up` brings them —
    but `--no-recreate`: a stack that is already running (a developer's) is used as it is, never recreated onto another
    checkout's files."""
    log(f"compose up --no-recreate mariadb garage mailpit, from {e2e.COMPOSE_FILE}")
    e2e.run(e2e.COMPOSE + ["up", "-d", "--no-recreate", "--wait", "mariadb", "garage", "mailpit"])


def provision_store(workdir: Path, database: str) -> None:
    """The store must carry the backend's key alias before [BUCKET] can be granted to it. A store some backend already
    provisioned is used as it is; a cold one (CI) gets one throwaway boot on its own database, as in
    `run_web_e2e.provision_store`, which proves the provisioner — this run proves the API."""
    if e2e.store_is_provisioned():
        log("store already provisioned")
        return
    log("booting a throwaway backend to provision the store")
    secret = workdir / "config-secret-store-init.key"
    boot_log = workdir / "backend-store-init.log"
    e2e.fresh_database(database)
    proc = e2e.boot(database, secret, boot_log)
    try:
        not_ready = e2e.wait_ready(time.time() + e2e.BOOT_SECS, proc)
        if not_ready:
            e2e.tail(boot_log)
            raise SystemExit(f"[fuzz] STORE BRING-UP BOOT FAILED: {not_ready}")
        deadline = time.time() + 120
        while time.time() < deadline:
            if e2e.store_is_provisioned():
                return
            time.sleep(1)
        e2e.tail(boot_log)
        raise SystemExit("[fuzz] STORE NOT PROVISIONED within 120 s of the boot")
    finally:
        e2e.kill(proc)
        secret.unlink(missing_ok=True)
        e2e.mysql(f"DROP DATABASE IF EXISTS {database};")


def drop_run_state(database: str, bucket: str) -> None:
    """This run's database and bucket, gone with the run: a per-run name must not leave one behind per run."""
    e2e.mysql(f"DROP DATABASE IF EXISTS {database};")
    try:
        e2e._store_for(bucket).purge()
    except e2e.instance_backup.BackupError:
        pass  # never created — a run that failed before it
    e2e.garage("bucket", "delete", "--yes", bucket)


def seed_cmd(workdir: Path, *extra: str) -> list[str]:
    return ["pnpm", "exec", "tsx", "scripts/fuzz-seed.mts", "--out", str(workdir), *extra]


def seed_env() -> dict[str, str]:
    return {
        **os.environ,
        "VEYQ_BASE": f"http://127.0.0.1:{PORT}",
        "VEYQ_ADMIN_BASE": f"http://127.0.0.1:{ADMIN_PORT}",
        "VEYQ_HOST": HOST,
    }


def seed(workdir: Path, *extra: str) -> None:
    result = subprocess.run(seed_cmd(workdir, *extra), cwd=str(WEB), env=seed_env(), text=True, capture_output=True)
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stdout.write(result.stderr)
        raise SystemExit(f"[fuzz] SEED FAILED ({' '.join(extra) or 'birth'}), exit {result.returncode}")


def token(workdir: Path, name: str = "token") -> str:
    return (workdir / name).read_text().strip()


def token_works(workdir: Path) -> bool:
    """The account-level read every signed-in member may make; anything but 200 means the token is spent."""
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/account/key-material",
                                 headers={"Authorization": f"Bearer {token(workdir)}", "Host": HOST})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status == 200
    except (urllib.error.URLError, OSError):
        return False


def fresh_token(workdir: Path) -> None:
    if not token_works(workdir):
        log("the access token no longer works — signing in again")
        seed(workdir, "--login")
        if not token_works(workdir):
            raise SystemExit("[fuzz] a fresh sign-in yields no working token")


# --------------------------------------------------------------------------------------------------------------
# The invocations
# --------------------------------------------------------------------------------------------------------------

def write_positive_configs(workdir: Path) -> dict[str, Path]:
    """The positive invocations' configs and `ids.json` from the seed's current objects."""
    seeded = json.loads((workdir / "seed.json").read_text())
    ids = seeded["ids"]
    # The ids `hooks.py` `peer_is_refused` compares with — no secret: the tokens travel by the environment only.
    (workdir / "reports" / "ids.json").write_text(json.dumps({"owner": ids, "peer": seeded["peerIds"]}, indent=1))
    return {
        "positive": positive_config(workdir, ids),
        "stateful": positive_config(workdir, {k: v for k, v in ids.items() if k in CONTAINERS}, "stateful"),
    }


# The containers a stateful sequence works inside: fixed, so what it creates is the owner's; the objects below them
# come from the sequence's own links, which a fixed parameter would override.
CONTAINERS = ("tenantId", "userId", "scopeId")


def positive_config(workdir: Path, ids: dict[str, str], name: str = "positive") -> Path:
    """`schemathesis.toml` plus the seeded ids as fixed parameters — the positive invocations' copy only."""
    lines = [CONFIG.read_text(), "", "# Appended by scripts/run_api_fuzz.py: the seeded ids (fuzz-seed.mts).",
             "[parameters]"]
    lines += [f'"path.{key}" = "{value}"' for key, value in sorted(ids.items())]
    path = workdir / "reports" / f"{name}.toml"
    path.write_text("\n".join(lines) + "\n")
    return path


class Result(NamedTuple):
    inv: Invocation
    spec: str
    expected: list[str]
    exit_code: int
    report: dict | None
    report_dir: Path
    seconds: float


def run_one(image: str, inv: Invocation, spec: Path, workdir: Path, config: Path, baseline: Path, update: bool,
            expected: list[str], ending: bool = False) -> Result:
    key = contract_specs.key(spec)
    rel = f"{inv.name}/{key.replace('/', '__').removesuffix('.openapi.yaml')}" + ("-session-ending" if ending else "")
    out = workdir / "reports" / rel
    out.mkdir(parents=True, exist_ok=True)
    reports = workdir / "reports"
    url = f"http://{HOST}:{PORT}" if inv.channel == "public" else f"http://{ADMIN_HOST}:{ADMIN_PORT}"
    container_config = f"/out/{config.relative_to(reports).as_posix()}" if config.is_relative_to(reports) \
        else config.relative_to(REPO).as_posix()
    container_baseline = f"/out/{baseline.relative_to(reports).as_posix()}" if baseline.is_relative_to(reports) \
        else baseline.relative_to(REPO).as_posix()
    cmd = [
        "docker", "run", "--rm",
        "--add-host", f"{HOST}:host-gateway", "--add-host", f"{ADMIN_HOST}:host-gateway",
        "--user", f"{os.getuid()}:{os.getgid()}", "-e", "HOME=/tmp",
        "-v", f"{REPO}:/repo:ro", "-v", f"{reports}:/out", "-w", "/repo",
        # The token by NAME only: its value travels in this process' environment, never on a command line.
        "-e", "VEYQ_FUZZ_TOKEN", "-e", "VEYQ_FUZZ_CREDENTIALS=/out/credential-operations.json",
        *(["-e", "VEYQ_FUZZ_PEER_TOKEN", "-e", "VEYQ_FUZZ_IDS=/out/ids.json"] if inv.mode == "positive" else []),
        image,
        "--config-file", container_config, "--no-color",
        "run", f"/out/contracts/api/{key}" if inv.mode == "positive" else f"contracts/api/{key}", "--url", url,
        "--mode", inv.mode, "--phases", inv.phases,
        "--max-examples", str(MAX_EXAMPLES), "--seed", str(SEED), "--generation-deterministic",
        "--continue-on-failure",
        "--baseline", container_baseline,
        "--report", "junit,ndjson,vcr,json", "--report-dir", f"/out/{rel}",
        "--report-json-path", f"/out/{rel}/report.json",
    ]
    cmd += ["--include-by", ADMIN] if inv.channel == "admin" else ["--exclude-by", ADMIN]
    # Exclusions, not inclusions: Schemathesis ORs its inclusions, so beside the admin channel's `--include-by` an
    # inclusion would select every admin operation (measured: the first run's admin-positive invocation tested 59
    # operations where 15 are GETs). Exclusions are ANDed.
    if inv.methods == "get":
        cmd += ["--exclude-method-regex", NOT_GET]
    elif inv.methods == "write":
        cmd += ["--exclude-method-regex", "^GET$"]
    if inv.mode == "negative":
        # A negative invocation's coverage phase also sends a few schema-valid requests, built from the contracts as
        # they are, idioms and all ([enforced]); whether a valid request is accepted is the positive invocations' to say,
        # on the contracts as the server reads them.
        cmd += ["--exclude-checks", "positive_data_acceptance"]
    if inv.mode == "positive":
        for operation_id in sorted(NEGATIVE_ONLY):
            cmd += ["--exclude-operation-id", operation_id]
    for operation_id in SESSION_ENDING:
        cmd += ["--include-operation-id" if ending else "--exclude-operation-id", operation_id]
    if os.environ.get("VEYQ_FUZZ_INCLUDE_BY"):
        cmd += ["--include-by", os.environ["VEYQ_FUZZ_INCLUDE_BY"]]
    if update:
        cmd += ["--baseline-update"]
    env = {**os.environ, "VEYQ_FUZZ_TOKEN": token(workdir), "VEYQ_FUZZ_PEER_TOKEN": token(workdir, "token-peer")}
    started = time.time()
    with (out / "schemathesis.log").open("w") as handle:
        code = subprocess.run(cmd, env=env, stdout=handle, stderr=subprocess.STDOUT).returncode
    seconds = time.time() - started
    try:
        report = json.loads((out / "report.json").read_text())
    except (OSError, ValueError):
        report = None
    return Result(inv, key, expected, code, report, out, seconds)


# --------------------------------------------------------------------------------------------------------------
# The verdict
# --------------------------------------------------------------------------------------------------------------

def junit_failures(out: Path) -> list[tuple[str, str]]:
    """(operation, message) per failed test case of a JUnit report — the message carries the check, the curl line."""
    found = []
    for xml in out.glob("*.xml"):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for child in case:
                if child.tag in ("failure", "error"):
                    found.append((case.get("name") or "?", (child.get("message") or "") + "\n" + (child.text or "")))
    return found


def statuses_by_operation(out: Path) -> dict[str, set[int]]:
    """Every status each operation answered, read from the NDJSON event stream (its recorded interactions)."""
    seen: dict[str, set[int]] = {}

    def walk(node: object, label: str | None) -> None:
        if isinstance(node, dict):
            here = node.get("label") if isinstance(node.get("label"), str) else label
            response = node.get("response")
            if isinstance(response, dict) and isinstance(response.get("status_code"), int) and here:
                seen.setdefault(here, set()).add(response["status_code"])
            for value in node.values():
                walk(value, here)
        elif isinstance(node, list):
            for value in node:
                walk(value, label)

    for nd in out.glob("*.ndjson"):
        for line in nd.read_text(errors="replace").splitlines():
            try:
                walk(json.loads(line), None)
            except ValueError:
                continue
    return seen


def verdict(results: list[Result]) -> tuple[list[str], list[str]]:
    """(problems, summary lines)."""
    problems: list[str] = []
    lines: list[str] = []
    by_inv: dict[str, list[Result]] = {}
    for r in results:
        by_inv.setdefault(r.inv.name, []).append(r)
    never_2xx: dict[str, list[str]] = {}
    for name, rs in by_inv.items():
        tested = sum((r.report or {}).get("operations", {}).get("tested", 0) or 0 for r in rs)
        expected = sum(len(r.expected) for r in rs)
        seconds = sum(r.seconds for r in rs)
        lines.append(f"== {name}: {tested} of {expected} operations tested, {len(rs)} spec files, {seconds:.0f} s")
        for r in rs:
            ops = (r.report or {}).get("operations") or {}
            where = f"{name} {r.spec}"
            if r.report is None:
                problems.append(f"{where}: no JSON report (exit {r.exit_code}) — {r.report_dir / 'schemathesis.log'}")
                continue
            stateful = ((r.report.get("phases") or {}).get("stateful") or {})
            if r.inv.phases == STATEFUL and stateful.get("skip_reason") == "not_applicable":
                # No link inferred between this file's operations: nothing to sequence, which is a fact, not a red.
                lines.append(f"   {r.spec}: no link inferred — no stateful sequence (information, no verdict)")
                continue
            if not ops.get("tested"):
                problems.append(f"{where}: tested no operation (selected {ops.get('selected')}, expected "
                                f"{len(r.expected)}) — an invocation that ran nothing is no green")
            if ops.get("errored"):
                problems.append(f"{where}: {ops['errored']} operation(s) errored — "
                                f"{[e.get('title') for e in r.report.get('errors') or []]}")
            new = (r.report.get("baseline") or {}).get("new")
            failures = r.report.get("failures") or []
            if r.exit_code != 0:
                problems.append(f"{where}: exit {r.exit_code}"
                                + (f", {new} failure(s) not in the baseline" if new else ""))
            for group in failures:
                lines.append(f"   {r.spec}: [{group.get('code') or group.get('type')}] {group.get('title')} "
                             f"x{group.get('count')} on {', '.join(group.get('operations') or [])}")
            for op, message in junit_failures(r.report_dir):
                curl = next((ln.strip() for ln in message.splitlines() if ln.strip().startswith("curl ")), None)
                lines.append(f"      {op}: {message.strip().splitlines()[0][:160] if message.strip() else ''}"
                             + (f"\n         {curl}" if curl else ""))
            if r.inv.mode == "positive" and r.inv.phases != STATEFUL:
                statuses = statuses_by_operation(r.report_dir)
                for op in r.expected:
                    if not any(200 <= s < 300 for s in statuses.get(op, set())):
                        never_2xx.setdefault(r.inv.name, []).append(f"{op} ({sorted(statuses.get(op, set()))})")
    for name, ops in never_2xx.items():
        lines.append(f"== {name}: operations that never answered 2xx — their response schemas went unchecked, and the "
                     f"peer was never sent them (information, no verdict): {len(ops)}")
        lines += [f"   {x}" for x in sorted(ops)]
    return problems, lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--baseline-update", action="store_true",
                        help="merge this run's failures into tests/fuzz/baseline.json (each needs a reason by hand)")
    parser.add_argument("--only", action="append", default=[], help="run only this spec key (repeatable)")
    args = parser.parse_args(argv)

    if not e2e.JAR.is_file():
        raise SystemExit(f"[fuzz] no fast-jar at {e2e.JAR} — "
                         f"`cd {repo_layout.rel(repo_layout.BACKEND)} && ./gradlew quarkusBuild` first")
    if e2e.port_is_taken():
        raise SystemExit(f"[fuzz] something already answers on {e2e.READY_URL} — not ours to fuzz")
    stale = unknown_negative_only()
    if stale:
        raise SystemExit(f"[fuzz] NEGATIVE_ONLY names no operation of contracts/api: {stale}")
    specs = [p for p in contract_specs.specs() if not args.only or contract_specs.key(p) in args.only]
    if not specs:
        raise SystemExit("[fuzz] no spec file selected")

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    workdir = RUN_ROOT / stamp
    (workdir / "reports").mkdir(parents=True)
    log(f"run directory {workdir}")
    started = time.time()

    image = build_image()

    database, store_init_database, bucket = run_names(stamp)
    stack_up()
    results: list[Result] = []
    proc = None
    try:
        provision_store(workdir, store_init_database)
        e2e.fresh_bucket(bucket)
        e2e.fresh_database(database)
        secret = workdir / "config-secret.key"
        boot_log = workdir / "backend.log"
        proc = e2e.boot(database, secret, boot_log, bucket=bucket)
        not_ready = e2e.wait_ready(time.time() + e2e.BOOT_SECS, proc)
        if not_ready:
            e2e.tail(boot_log)
            raise SystemExit(f"[fuzz] BOOT FAILED: {not_ready}")
        log(f"backend ready on {PORT} (admin {ADMIN_PORT}) after {time.time() - started:.0f} s")
        seed(workdir)
        (workdir / "reports" / "credential-operations.json").write_text(json.dumps(credential_operations(), indent=1))
        write_enforced_specs(workdir / "reports" / "contracts" / "api")
        baseline = BASELINE
        if args.baseline_update:
            baseline = workdir / "reports" / "baseline.json"
            shutil.copyfile(BASELINE, baseline)
        fresh = True  # the birth's objects: nobody has sent a request yet
        for inv in INVOCATIONS:
            if inv.mode == "positive" and inv.channel == "public":
                # Objects no earlier invocation could have changed: a negative run learns ids from the responses it
                # reads and sends some valid requests too (measured: the seeded link was gone before the first positive
                # run, when the negative one ran first), and the writes trash, revoke and purge what they are fixed to.
                if not fresh:
                    fresh_token(workdir)
                    seed(workdir, "--objects")
                configs = write_positive_configs(workdir)
            fresh = False
            for spec in specs:
                # A stateful sequence never ends its own session: the session-ending operations are not part of it.
                for ending in (False,) if inv.phases == STATEFUL else (False, True):
                    expected = operations(spec, inv, ending)
                    if not expected:
                        continue
                    fresh_token(workdir)
                    config = CONFIG if inv.mode == "negative" else configs["stateful" if inv.phases == STATEFUL else "positive"]
                    r = run_one(image, inv, spec, workdir, config, baseline, args.baseline_update, expected, ending)
                    ops = (r.report or {}).get("operations") or {}
                    log(f"{inv.name} {r.spec}{' (session-ending)' if ending else ''}: exit {r.exit_code}, "
                        f"tested {ops.get('tested')}/{len(expected)}, {r.seconds:.0f} s")
                    results.append(r)
        if args.baseline_update:
            shutil.copyfile(baseline, BASELINE)
            log(f"baseline merged into {BASELINE.relative_to(REPO)} — give every new entry its `reason`")
    finally:
        if proc is not None:
            e2e.kill(proc)
        (workdir / "config-secret.key").unlink(missing_ok=True)
        drop_run_state(database, bucket)

    problems, lines = verdict(results)
    lines.append(f"== total {time.time() - started:.0f} s, max-examples {MAX_EXAMPLES}, seed {SEED}")
    summary = "\n".join(lines) + "\n"
    (workdir / "summary.txt").write_text(summary + "".join(f"RED: {p}\n" for p in problems))
    print(summary, flush=True)
    if problems:
        for p in problems:
            print(f"[fuzz] RED: {p}", flush=True)
        return 1
    log("GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
