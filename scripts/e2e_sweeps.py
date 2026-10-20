#!/usr/bin/env python3
"""The ADR 0053 wire-assertion sweeps, shared by both end-to-end orchestrators.

**What it is.** Every sensor that proves a canary — a plaintext a suite handled — is absent where an operator could
read it: the database ([sweep]), the HTTP recording ([sweep_http]), the object store ([sweep_store]), the boot logs
([sweep_logs]), every container log of the stack ([sweep_containers]), every statement the database received
([StatementLog], [sweep_statements]) and every mail the run sent ([sweep_mailbox]); and the second needle set, the
capability tokens a recording shows a suite handled ([token_needles], [db_token_findings]). The oracle, the needles,
the positive controls and the limits of each are documented at the sensor and in the concern
`docs/crosscutting/cc.security.wire-assertion.md`.

**Who imports it.** `scripts/run_web_e2e.py` — web's API-level suites against the dev compose stack, one fresh
database per file — and `scripts/run_stack_e2e.py` — lane L1, the release candidate booted from its own recipe under
a compose project of its own and driven in a browser (ADR 0090 decision 5: the sweeps move into a module both
orchestrators import, parameterised by compose project). Everything a sensor needs to know about WHICH stack it reads
is a [Stack]: the compose command that addresses the project, the database's root credential, the store's service,
internal endpoint, admin token and network, the backend's origins and the mailbox. Nothing here names the dev stack;
`run_web_e2e.py` builds its `Stack` from its own constants and keeps its old entry points as thin wrappers, so its
behaviour — and the guards that import it (`validate_wire_log_matcher.py`, `validate_e2e_discovery.py`,
`validate_e2e_canaries.py`) — did not move.

**Not a `validate_*` guard**, and it runs nothing on import: both orchestrators, and the guards that import them by
path, execute their module bodies.
"""
from __future__ import annotations

import base64
import binascii
import bisect
import calendar
import email
import email.message
import email.policy
import hashlib
import json
import re
import shutil
import smtplib
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from typing import Callable, Iterable, Iterator, NamedTuple

import yaml

# The store sweep mirrors a bucket with the operator tool's own rclone plumbing, never a second copy of it. Importable
# because Python puts the running script's own directory — `scripts/` — on `sys.path`, for either orchestrator and for
# a guard that imports one of them by path.
import instance_backup
# The opacity oracle needs the set of properties a spec DECLARES opaque. It is derived from the
# contracts here, never listed, by reusing the guard's own predicate — one definition of "opaque",
# not two that agree today (changes/README § a guard derives its anchor set from the code).
import contract_specs
import validate_field_visibility

REPO = Path(__file__).resolve().parent.parent


class SweepError(RuntimeError):
    """The wire-assertion sweep could not be performed at all (the dump failed).

    Never a skip: an unperformed sweep is scored exactly like a failed one, for the same reason the orchestrators'
    zero-file and zero-test refusals exist — "we proved nothing" and "we proved absence" must not render identically.
    """


def shown(path: Path) -> Path:
    """A path for a log line: relative to this checkout where it is inside it — `VEYQ_INFRA_DIR` (the directory holding
    the Compose files, `DOCKER_DIR` in `scripts/repo_layout.py`) may point outside."""
    try:
        return path.relative_to(REPO)
    except ValueError:
        return path


def log(msg: str) -> None:
    """A silent orchestrator is unreviewable in CI — every step announces itself."""
    print(f"[e2e] {msg}", flush=True)


class Stack(NamedTuple):
    """Which compose project the sweeps read: everything that differs between the dev stack and a lane's instance.

    `compose` is the command prefix that addresses the project (`docker compose -f infra/docker-compose.yml …` for the
    dev stack, `docker compose -p veyq-e2e-<stamp>` for a lane); every container a sweep reads is reached through it,
    by service name, so no container name is spelled here. The store fields are what an operator's backup reads
    (`instance_backup.Store`): the INTERNAL S3 endpoint on `docker_network`, which the tool's rclone container joins,
    and the admin token the bundled key pair is derived from (ADR 0089). `backend_origins` and `mailpit` are what
    [token_needles] reads a recording by: the calls matched against the contracts, and the mailbox a token may have
    been first read out of.
    """
    compose: tuple[str, ...]
    db_root_password: str
    store_service: str
    store_endpoint: str
    store_admin_token: str
    docker_network: str
    backend_origins: tuple[str, ...] = ()
    mailpit: str | None = None

    def mariadb(self, *args: str) -> list[str]:
        """`<binary> -uroot -p… <args>` inside the project's `mariadb` service — `mariadb`, `mariadb-dump` alike."""
        binary, *rest = args
        return [*self.compose, "exec", "-T", "mariadb", binary, "-uroot", f"-p{self.db_root_password}", *rest]

    def mysql(self, sql: str) -> None:
        """Run SQL as root inside the project's mariadb container."""
        subprocess.run(self.mariadb("mariadb", "-e", sql), check=True)

    def mysql_row(self, sql: str) -> list[str]:
        """Run one SELECT as root and return its single row's columns (batch mode, no header)."""
        proc = subprocess.run(self.mariadb("mariadb", "-N", "-B", "-e", sql), check=True, capture_output=True, text=True)
        return proc.stdout.rstrip("\n").split("\t")

    def store(self, bucket: str) -> instance_backup.Store:
        """An `instance_backup.Store` bound to this bucket — the tool's own rclone plumbing, not a second copy."""
        # The bundled pair is DERIVED from the admin token, by the backup tool's own function — the harness holds no
        # key pair (ADR 0089), and a drift between that function and the backend's would fail here first.
        access_key, secret_key = instance_backup.derive_bundled_credentials(self.store_admin_token)
        return instance_backup.Store(SimpleNamespace(
            store_endpoint=self.store_endpoint,
            store_access_key=access_key,
            store_secret_key=secret_key,
            store_bucket=bucket, store_region="us-east-1",
            rclone_image=instance_backup.RCLONE_IMAGE, rclone_binary=None, docker_network=self.docker_network,
        ))


# How `mariadb-dump` writes a string literal, MEASURED against mariadb:11.4 rather than recalled from the
# `mysql_real_escape_string` docs — a wrong entry here is a silent miss, which is the one failure this whole
# sensor exists to remove. Backslash is first: it must not re-escape the backslashes the later rules introduce.
# TAB and `;` are deliberately absent — the dump leaves both raw.
DUMP_ESCAPES = [("\\", "\\\\"), ("'", "\\'"), ('"', '\\"'), ("\n", "\\n"), ("\r", "\\r"), ("\0", "\\0"), ("\x1a", "\\Z")]

# Every data row of a data-only dump is one of these; nothing else in the file carries a value. The rest is
# comments and `ALTER TABLE ... DISABLE KEYS` — DDL, which cannot carry a leak (the table and column names are
# fixed by the migrations) but DOES contain table names, so a canary like "media" would match `veyq_media` there
# and read as a hit. Anchoring on the INSERT is what scopes the search to the surface decision 3 actually names.
INSERT_LINE = re.compile(r"^INSERT INTO `([^`]+)` VALUES ")


def dump_escape(value: str) -> str:
    """Render a canary the way `mariadb-dump` would render it inside a string literal.

    Escaping the needle rather than un-escaping the haystack is the lossless direction: un-escaping a dump means
    parsing SQL well enough to know which backslashes are ours, and getting that wrong loses a hit. This way the
    transformation is total and one-directional.
    """
    for raw, escaped in DUMP_ESCAPES:
        value = value.replace(raw, escaped)
    return value


def dump_database(stack: Stack, database: str) -> str:
    """A data-only dump of one e2e database, one row per line.

    `--no-create-info` removes the entire DDL noise class in one flag and costs nothing, because table and column
    names are fixed by the migrations and can never carry user plaintext (`name` occurs in 8 column names of this
    schema, `cover` in 3 — all of them false hits a full dump would have produced). `--skip-extended-insert` puts
    each row on its own line so a hit can name its table AND its row; rows never span lines, because the dump
    escapes newlines inside literals — the same fact `dump_escape` rests on. Nothing is EXCLUDED beyond that:
    dropping known-noisy rows (a pristine dump holds only `flyway_schema_history`) would make a genuine leak of
    exactly that string invisible, and a security sensor must fail toward noise, never toward silence.
    """
    proc = subprocess.run(
        stack.mariadb("mariadb-dump", "--no-create-info", "--skip-extended-insert", "--single-transaction",
                      "--default-character-set=utf8mb4", database),
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise SweepError(f"mariadb-dump of {database} failed ({proc.returncode}): {proc.stderr.strip()[-400:]}")
    return proc.stdout


def sweep(stack: Stack, database: str, canary_file: Path, rel: str, tokens: dict[str, Token] | None = None,
          retain: Path | None = None, present: Iterable[str] = ()) -> str | None:
    """Prove that nothing this suite sealed is readable in the database it ran against. Returns None, else why not.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). For the values the suite recovered by unsealing
    the server's own responses — its canaries — no plaintext copy exists in any row of any table of that file's
    own `veyq_e2e_<n>`. The oracle is *absence of specific values*, not conformance of everything observable:
    ADR 0053 measured 67 DB columns with no contract at all, so the mapping an allowlist check would need cannot
    be built for the surface that most needs one.

    **The defect that bought it.** No incident on this surface — because until now nothing had ever looked at it.
    `validate_field_visibility.py` (ADR 0044) checks the *contract*, and says so in one line: a field carrying
    `contentEncoding: base64` is PERMITTED to be opaque, and a client that writes plaintext into it satisfies
    that guard completely. The regression this catches is a client that stops sealing something, in a build that
    passes every other check — invisible to a unit test (which sees the client's own intent), invisible to the
    contract guards (which see the schema), and visible only in the stored bytes. The spike that preceded
    ADR 0053 is the closest thing to an incident: it found `e2e.test` three times in a live instance database,
    all of it declared plaintext (`owner_email` is `pii`, the tenant name is `public`) — which is exactly why the
    oracle is derived-canary absence and not "no readable strings".

    **Its limit — what a green sweep does NOT prove.** It proves absence *for the values this flow sealed, on the
    surface this sensor reads*. It does not prove the flow sealed everything it should have: a value the client
    never sealed is not a canary, correctly, and is therefore invisible here — that case belongs to the opacity
    check over the `contentEncoding: base64` fields and to `validate_field_visibility.py`. It does not prove an
    unswept surface is clean: the store, the HTTP recording and the logs are their own sweeps ([sweep_store],
    [sweep_http], [sweep_logs], [sweep_containers]). Its residual gap is coverage, not correctness — the sweep
    sees only flows some suite drives. And passing cannot be arranged from inside the client (the bytes are in a
    database this process does not write), but a canary can still be *removed* — a visible diff, exactly like
    widening an allowlist would be, which is weaker than tamper-proof and should not be stated as more.

    **The second needle set — capability tokens** (`tokens`, [token_needles]). The same rows are searched for every
    token the suite's recording shows it handled, raw, reported as `token:<kind>`; and the hash control
    ([db_token_findings]) must find at least one token's hex SHA-256, or the token half proves nothing. The dump is
    retained (`retain`) so a hit, or a control that failed, can be re-read after the run.

    **`present` — a positive control for a run that has no token set** (lane L1's browser suite records no HTTP, so
    nothing arms the hash control). Each value is DECLARED plaintext the run put into this database (an account's
    email, `pii`), and must be found, dump-escaped, in some data row: proof that the dump is of the database the run
    wrote to and was read whole enough to contain it. Absent, the sweep fails — 0 canary hits proves nothing then.
    """
    if not canary_file.is_file():
        return (f"the suite left no canary file at {canary_file.name} — the sweep would have proved the absence "
                f"of nothing. Every suite must call recordCanaries(); scripts/validate_e2e_canaries.py pins it.")
    canaries = json.loads(canary_file.read_text())
    if not canaries:
        return (f"{canary_file.name} is empty — {rel} recorded no canary, so a green sweep here would mean "
                f"nothing. This is a hard failure, never a skip.")

    try:
        dump = dump_database(stack, database)
    except SweepError as e:
        return str(e)
    if retain is not None:
        retain.write_text(dump, encoding="utf-8")
    tokens = tokens or {}
    token_hits, hashed = db_token_findings(dump, tokens)
    token_failure = None
    if token_hits or (tokens and not hashed):
        log(f"WIRE ASSERTION FAILED for {rel} on capability tokens in {database} — {len(token_hits)} raw hit(s); "
            f"hash control {'met' if hashed else 'NOT met'}:")
        for value, table in token_hits:
            log(f"    {table:<28} <- token:{tokens[value].kind} {value!r}")
        if not hashed:
            log(f"    hash control: none of the {len(tokens)} token(s) ({kinds_of(tokens.values())}) has its "
                f"{TOKEN_HASH().name} in {database} — the dump is not the database these tokens were minted in, or "
                f"was not read, so 0 raw hits proves nothing")
        token_failure = (f"the wire assertion failed on capability tokens: {len(token_hits)} raw hit(s) in "
                         f"{database}{'' if hashed else ', hash control NOT met'} (listed above)")
    elif tokens:
        log(f"token sweep: {len(tokens)} capability token(s) from {rel} ({kinds_of(tokens.values())}) appear raw "
            f"nowhere in {database}; hash control met — {len(hashed)} found by {TOKEN_HASH().name} "
            f"({kinds_of(hashed)})")
    present = list(present)
    rows = [line for line in dump.splitlines() if INSERT_LINE.match(line)]
    absent = [value for value in present if not any(dump_escape(value) in line for line in rows)]
    if absent:
        log(f"WIRE ASSERTION FAILED for {rel} — the control: {len(absent)} declared-plaintext value(s) the run wrote are "
            f"in no data row of {database}: {absent!r} — the dump is not of the database the run wrote to, or was "
            f"not read whole, so 0 canary hits would prove nothing")
        token_failure = "; ".join(filter(None, [
            token_failure, f"the database sweep's control was not met: {len(absent)} value(s) absent from {database}"]))
    elif present:
        log(f"database sweep armed: {len(present)} declared-plaintext value(s) the run wrote found in {database}'s "
            f"data rows")
    needles = [(c, dump_escape(c)) for c in canaries]
    hits: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for line in dump.splitlines():
        match = INSERT_LINE.match(line)
        if not match:
            continue
        table = match.group(1)
        for canary, needle in needles:
            if needle in line and (canary, table) not in seen:
                seen.add((canary, table))
                hits.append((canary, table))

    if hits:
        # Print every hit, not a count: a count cannot be acted on, and the resolution is per-value. A hit means
        # either a real leak, or a derived canary that collides with DECLARED plaintext — in which case the sweep
        # is right and the test data is wrong (make the sealed value distinctive). Never an allowlist.
        log(f"WIRE ASSERTION FAILED for {rel} — {len(hits)} canary/table hit(s) in {database}:")
        for canary, table in hits:
            log(f"    {table:<28} <- {canary!r}")
        return "; ".join(filter(None, [
            f"the wire assertion failed: {len(hits)} canary/table hit(s) in {database} (listed above)", token_failure]))

    log(f"wire assertion: {len(canaries)} canary/canaries from {rel} appear nowhere in {database}")
    return token_failure


def opaque_property_names() -> set[str]:
    """Every property name some spec declares `contentEncoding: base64`.

    Matching the recording by NAME is exact here rather than approximate, and that is a measured property of the
    contracts, not an assumption: across all ten specs the opaque names and the plain ones are **disjoint**
    (38 against 126, intersection empty). `scripts/validate_opaque_names_disjoint.py` keeps it that way — if a
    name ever landed in both, this oracle would quietly stop checking the opaque one.

    The specs come from `contract_specs` (recursive: core's and every application's directory), and the oracle
    refuses to answer unless it read `contract_specs.OPAQUE_LOCATION_CANARY` — no opaque *name* is
    application-only, so a reader that lost an application's specs would return the same name set and look armed.
    """
    names: set[str] = set()
    locations: set[str] = set()
    for spec in contract_specs.specs():
        doc = yaml.safe_load(spec.read_text()) or {}
        for schema_name, schema in ((doc.get("components") or {}).get("schemas") or {}).items():
            for prop, body in (schema.get("properties") or {}).items():
                if validate_field_visibility.is_opaque(body):
                    names.add(prop)
                    locations.add(f"{contract_specs.key(spec)}#{schema_name}.{prop}")
    if contract_specs.OPAQUE_LOCATION_CANARY not in locations:
        raise SystemExit(f"the opaque-name oracle did not read {contract_specs.OPAQUE_LOCATION_CANARY} — it no longer "
                         "sees every spec directory, so the opacity sweep would run unarmed for that application")
    return names


# The shortest thing `symWrap` can emit: a 12-byte nonce and a 16-byte tag, before a single byte of payload.
# Nothing a client seals can be below it, which is what makes a length check an oracle rather than a heuristic.
MIN_SEALED_BYTES = 28


def opacity_failure(value: str) -> str | None:
    """Why this declared-opaque value is not opaque, or None if it is fine.

    **Two orthogonal tests, because one of them alone has a hole this CR walked into.** The obvious test is
    readability: ciphertext does not decode to valid, printable UTF-8. But that test needs a length floor —
    short random strings do decode to printable UTF-8 now and then (measured: 7/5000 at 8 bytes, 2/5000 at 12,
    1/5000 at 16, and **0 in 80 000** at 24 and above) — and a floor is exactly where short *plaintext* hides.
    Biting it proved the point: an unsealed Memory name went over the wire as **23 bytes** and the readability
    test never looked at it.

    So length is the second test, and on this wire it is not a heuristic at all. Measured across every
    declared-opaque field a real suite sends, the shortest is **32 bytes** (`instanceSalt`, `nonce`,
    `kdfParams`, `authPublicKey`, `secretKeyProofPublicKey`, `slugToken`), and the shortest an AEAD *can* be is
    [MIN_SEALED_BYTES]. Anything below that is not a sealed value, whatever it decodes to.

    A value that is not base64 at all is **not** this sweep's business: what shape a field may take is the
    contract's question and `validate_field_visibility.py` answers it.
    """
    for decoder in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            raw = decoder(value + "=" * (-len(value) % 4))
        except (binascii.Error, ValueError):
            continue
        if len(raw) < MIN_SEALED_BYTES:
            return (f"decodes to {len(raw)} bytes — too short to be sealed at all "
                    f"(a nonce and a tag are {MIN_SEALED_BYTES} before any payload)")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return None  # decoded, long enough, not text — the ordinary case
        printable = sum(1 for ch in text if ch.isprintable() or ch in "\n\t")
        if text and printable / len(text) >= 0.9:
            return f"decodes to readable text: {text[:120]!r}"
        return None
    return None


def walk_opaque(value, names: set[str], found: list[tuple[str, str]]) -> None:
    """Collect every (property, reason) pair where a declared-opaque field failed [opacity_failure]."""
    if isinstance(value, dict):
        for key, entry in value.items():
            if key in names and isinstance(entry, str):
                why = opacity_failure(entry)
                if why is not None:
                    found.append((key, why))
            walk_opaque(entry, names, found)
    elif isinstance(value, list):
        for entry in value:
            walk_opaque(entry, names, found)


def sweep_http(record_file: Path, canary_file: Path, rel: str) -> str | None:
    """Prove that what the suite SENT and RECEIVED carries no plaintext. Returns None, else why not.

    **Two oracles over one recording, because the surface has two ways to leak.** A declared-opaque field that
    decodes to readable text is a client that stopped sealing — the opacity check ADR 0044 named and assigned
    to nobody. A canary appearing anywhere in a URL, a header or a body is the same absence oracle the database
    sweep uses, and it is here because this is the only surface where path params, query strings, capability
    headers and presigned URLs exist at all (ADR 0053 §3): by the time anything reaches the store, the URL that
    carried it is gone.

    **A population of zero is a failure, never a pass.** A recorder that never armed and a suite that made no
    calls both produce "no leak found", which is indistinguishable from a clean run — the same trap
    `changes/README` § *a bite test must tell "the guard held" apart from "nothing ran"* records, arriving
    through a measurement instead of a test.

    **A canary is searched in every spelling [canary_needles] models, not only raw.** The recorder stores a URL as
    the client BUILT it — percent-encoded — so a raw match never saw a canary with a space in a path or a query
    string, and most canaries have one. Measured, not read: a suite that sent a space-bearing canary to
    `GET /api/setup?lever=…` passed the raw-only version of this sweep green.
    """
    if not record_file.is_file():
        return (f"{rel} recorded no HTTP traffic at {record_file.name} — the recorder did not arm, so this "
                f"sweep would prove nothing. VEYQ_HTTP_RECORD_FILE must reach the suite process.")
    # `split("\n")`, never `splitlines()`: Python splits the latter on U+0085 and U+2028 as well, which a
    # recorded body can legitimately contain — it cut a JSON record in half the first time this ran.
    entries = [json.loads(line) for line in record_file.read_text().split("\n") if line.strip()]
    if not entries:
        return f"{record_file.name} is empty — {rel} made no request, so a green HTTP sweep here means nothing."

    # A recorder that stopped recording response headers would hide every refresh token (it travels only as a
    # `Set-Cookie`) and every header-borne canary, while every other check here stayed green — so it is a failure.
    headerless = sum(1 for entry in entries if "responseHeaders" not in entry)
    if headerless:
        return (f"{headerless} of {len(entries)} entr(y/ies) in {record_file.name} carry no `responseHeaders` — the "
                f"recorder lost its response-header half, and no Set-Cookie token or header canary can be swept")
    cookies = sum(len(set_cookie_values(entry)) for entry in entries)

    canaries = json.loads(canary_file.read_text()) if canary_file.is_file() else []
    names = opaque_property_names()
    opacity: list[tuple[str, str, str]] = []
    leaks: list[tuple[str, str, str]] = []
    for entry in entries:
        where = f"{entry['method']} {entry['url'].split('?')[0]}"
        for body in (entry.get("requestBody"), entry.get("responseBody")):
            if not body:
                continue
            try:
                parsed = json.loads(body)
            except (json.JSONDecodeError, TypeError):
                continue
            found: list[tuple[str, str]] = []
            walk_opaque(parsed, names, found)
            for prop, why in found:
                opacity.append((where, prop, why))
        haystack = "\n".join(filter(None, [
            entry["url"], json.dumps(entry.get("requestHeaders") or {}),
            entry.get("requestBody") or "", json.dumps(entry.get("responseHeaders") or {}),
            entry.get("responseBody") or "",
        ]))
        for canary in canaries:
            for variant, needle in canary_needles(canary):
                if needle in haystack:
                    leaks.append((where, canary, f"url/header/body [{variant}]"))
                    break

    if opacity or leaks:
        log(f"WIRE ASSERTION FAILED for {rel} on HTTP — {len(opacity)} opacity, {len(leaks)} canary hit(s):")
        for where, prop, why in opacity:
            log(f"    {where:<44} {prop} {why}")
        for where, canary, kind in leaks:
            log(f"    {where:<44} {kind} <- {canary!r}")
        return (f"the wire assertion failed on HTTP: {len(opacity)} declared-opaque field(s) were not opaque, "
                f"{len(leaks)} canary hit(s) (listed above)")

    log(f"wire assertion: {len(entries)} request(s) from {rel} carry no readable opaque field and no canary "
        f"(response headers included; {cookies} non-empty Set-Cookie value(s) recorded)")
    return None


def stored_blob_count(stack: Stack, database: str) -> int:
    """How many completed blobs this suite's own database records — the row side of what the bucket should hold.

    Derived, never listed: it is what tells a suite that *stored nothing* (recovery, scope rotation) apart from
    one whose objects went missing, without this file carrying a list of which suites upload. `veyq_blob` is the
    only table a completed object is recorded in (`instance_backup.Database.blob_index`).
    """
    proc = subprocess.run(
        stack.mariadb("mariadb", "-N", "-B", "-e", "SELECT COUNT(*) FROM veyq_blob;", database),
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise SweepError(f"could not count blobs in {database}: {proc.stderr.strip()[-300:]}")
    return int(proc.stdout.strip() or 0)


def sweep_store(stack: Stack, bucket: str, database: str, canary_file: Path, rel: str, workdir: Path,
                tokens: dict[str, Token] | None = None) -> str | None:
    """Prove that nothing the suite sealed is readable in the object store — keys included.

    **The orchestrator's surface, not the suite's** (ADR 0053 §3): only this side holds the store credentials.
    A client-side hook would see what the client *sent* and never what is *stored* — not an object the server
    wrote itself, and not the object key, which is a name the operator reads without opening anything.

    Mirrors with the operator tool's own rclone plumbing rather than a second copy of it, so the sweep reads the
    bucket the documented backup would capture. Keys and bytes are searched for the suite's capability tokens too
    (`tokens`, [token_needles]), reported as `token:<kind>`.
    """
    if not canary_file.is_file():
        return f"{rel} left no canary file — the store sweep would have proved the absence of nothing."
    canaries = json.loads(canary_file.read_text())
    if not canaries:
        return f"{rel} recorded no canary — a green store sweep would mean nothing."

    mirror = workdir / f"store-{bucket}-{rel.replace('/', '_')}"
    mirror.mkdir(parents=True, exist_ok=True)
    try:
        stack.store(bucket).mirror_out(mirror)
    except instance_backup.BackupError as e:
        return f"could not mirror {bucket} for the store sweep: {e}"

    objects = [path for path in mirror.rglob("*") if path.is_file()]
    if not objects:
        # **A suite that stores nothing is skipped OUT LOUD; a suite whose objects vanished is a failure.** The
        # two look identical from the bucket, so the discriminator comes from the suite's own database rather
        # than from a list of which suites upload — a list would go stale the first time a suite grew a file.
        blobs = stored_blob_count(stack, database)
        if blobs:
            return (f"{rel} recorded {blobs} blob(s) in {database} but {bucket} mirrors empty — the objects are "
                    f"missing, or this sweep is pointed at the wrong bucket. Either way it proves nothing.")
        log(f"store sweep skipped for {rel}: it stored no object ({database}.veyq_blob is empty), so there is "
            f"nothing on this surface to prove absent")
        shutil.rmtree(mirror, ignore_errors=True)
        return None

    hits: list[tuple[str, str, str]] = []
    for path in objects:
        key = str(path.relative_to(mirror))
        blob = path.read_bytes()
        for canary in canaries:
            if canary in key:
                hits.append((key, canary, "object key"))
            if canary.encode("utf-8") in blob:
                hits.append((key, canary, "object bytes"))
        for value, token in (tokens or {}).items():
            if token.needle in key:
                hits.append((key, f"token:{token.kind} {value}", "object key"))
            if token.needle.encode("utf-8") in blob:
                hits.append((key, f"token:{token.kind} {value}", "object bytes"))

    if hits:
        log(f"WIRE ASSERTION FAILED for {rel} on the store — {len(hits)} hit(s) in {bucket}:")
        for key, canary, where in hits:
            log(f"    {key:<44} {where} <- {canary!r}")
        return f"the wire assertion failed on the store: {len(hits)} hit(s) in {bucket} (listed above)"

    log(f"wire assertion: {len(canaries)} canary/canaries and {len(tokens or {})} capability token(s) from {rel} "
        f"appear in none of {len(objects)} object(s) of {bucket}, key or bytes")
    # Clean: the mirror has served its one purpose (this sweep). Left behind it is ~900 MB per suite; a failing sweep
    # keeps its mirror above, because that is the one a reader needs.
    shutil.rmtree(mirror, ignore_errors=True)
    return None


# ----------------------------------------------------------------------------------------------------------
# Capability tokens — the second needle set: every bearer or capability secret the run handled
# ----------------------------------------------------------------------------------------------------------

# What `Tokens.hash` stores and looks a token up by: the hex SHA-256 of its UTF-8 bytes (ADR 0004). The hash control
# finds it in a suite's dump, which proves the dump was read and the token set belongs to that database.
TOKEN_HASH = hashlib.sha256

HTTP_METHODS = validate_field_visibility.HTTP_METHODS
TOKEN_CLASS = "token"
# The kind a bearer credential is reported under when no body or header marker named it first.
BEARER_KIND = "Authorization"


class Operation(NamedTuple):
    method: str
    pattern: re.Pattern
    literal: int  # characters of the path that are not a `{param}` — the more, the more specific the match
    spec: str
    operation: dict
    parameters: list


class Contract:
    """Every operation of `contracts/api/`, indexed to match a recorded call, and the `$ref` resolution its walk needs.

    Read from the contracts, never listed: the token set is whatever the `token` class marks (ADR 0044) — body
    properties, scalar component schemas (`InviteBootstrapSecret`), header parameters and response headers — plus
    the bearer of `Authorization`, the one `securityScheme` shape (`type: http, scheme: bearer`).
    """

    def __init__(self, api: Path) -> None:
        # From `contract_specs`, keyed by the path below `api` and found recursively: an application's specs sit in a
        # directory of their own, and a top-level glob would leave their operations unmatched (their tokens unseen).
        if api.resolve() != contract_specs.API.resolve():
            raise SystemExit(f"Contract reads {contract_specs.API} (contract_specs), not {api}")
        self.specs = {contract_specs.key(p): (yaml.safe_load(p.read_text()) or {}) for p in contract_specs.specs()}
        self.operations: list[Operation] = []
        self.bearer = False
        for name, spec in self.specs.items():
            for scheme in ((spec.get("components") or {}).get("securitySchemes") or {}).values():
                if scheme.get("type") == "http" and str(scheme.get("scheme", "")).lower() == "bearer":
                    self.bearer = True
            for path, item in (spec.get("paths") or {}).items():
                for method in HTTP_METHODS:
                    operation = (item or {}).get(method)
                    if not isinstance(operation, dict):
                        continue
                    pattern = re.compile("^" + re.sub(r"\\\{[^}]*\\\}", "[^/]+", re.escape(path)) + "$")
                    literal = len(re.sub(r"\{[^}]*\}", "", path))
                    parameters = list(item.get("parameters") or []) + list(operation.get("parameters") or [])
                    self.operations.append(Operation(method.upper(), pattern, literal, name, operation, parameters))

    def resolve(self, node, spec: str) -> tuple[dict, str]:
        """Follow `$ref`s — same-file `#/…` and cross-file `x.yaml#/…` — to the node they name, and its spec."""
        while isinstance(node, dict) and "$ref" in node:
            target, _, pointer = node["$ref"].partition("#")
            spec = contract_specs.ref_key(spec, target)
            node = self.specs[spec]
            for part in pointer.strip("/").split("/"):
                node = node[part]
        return (node if isinstance(node, dict) else {}), spec

    def match(self, method: str, path: str) -> Operation | None:
        """The operation a recorded call hit: the most literal path template that matches, so `/x/mine` beats `/x/{id}`."""
        found = [op for op in self.operations if op.method == method and op.pattern.match(path)]
        return max(found, key=lambda op: op.literal) if found else None


_CONTRACT: Contract | None = None


def contract() -> Contract:
    """The repo's contracts, loaded once per run. `__file__`-relative, so a copied tree reads its own copy."""
    global _CONTRACT
    if _CONTRACT is None:
        _CONTRACT = Contract(REPO / "contracts" / "api")
    return _CONTRACT


class Token(NamedTuple):
    """One capability token the run handled: what the contract calls it, and what a sweep searches for."""
    kind: str
    needle: str
    weak: bool = False  # named only by the bearer rule; a marker that names it later wins
    url: bool = False  # a presigned URL: its needle is its request target, and its addressee (the store) verifies it


def token_needle(value: str) -> str:
    """What a sweep searches for: the token itself, or for a token that is a URL, its request target.

    A presigned URL (`PresignedUrl.url`) is the one URL-valued token. Its capability is its query (the signature),
    and the party it addresses logs the URI it was handed, not the origin the client used — so the request target
    (`/bucket/key?X-Amz-…`) is the needle. It is a substring of the URL, so a copy of the whole URL is found too.
    """
    parts = urllib.parse.urlsplit(value)
    if parts.scheme in ("http", "https") and parts.netloc:
        return parts.path + (f"?{parts.query}" if parts.query else "")
    return value


def set_cookie_values(entry: dict) -> list[str]:
    """Every NON-EMPTY cookie value a recorded response set: each `Set-Cookie` cut at the first `;`, then at the first `=`.

    A clearing cookie (`veyq_refresh=; Max-Age=0`) yields nothing, which is why `logout`'s marker is harmless. The
    values are base64url (`Tokens.random()`), so no unquoting is modelled — asserted by
    `scripts/validate_wire_log_matcher.py`, not assumed.
    """
    cookies = (entry.get("responseHeaders") or {}).get("set-cookie") or []
    if isinstance(cookies, str):
        cookies = [cookies]
    values = []
    for cookie in cookies:
        _, sep, value = cookie.split(";", 1)[0].partition("=")
        if sep and value.strip():
            values.append(value.strip())
    return values


def header_value(headers: dict, name: str):
    """A recorded header by name, case-insensitively — the client sends `Authorization`, `Headers` lowercases it."""
    wanted = name.lower()
    return next((value for key, value in (headers or {}).items() if key.lower() == wanted), None)


def walk_tokens(value, schema, spec: str, kind: str, add: Callable[[str, str], None]) -> None:
    """Collect every value a `token` marker covers, walking a recorded JSON value against the schema that declares it."""
    schema, spec = contract().resolve(schema, spec)
    if schema.get(validate_field_visibility.MARKER) == TOKEN_CLASS:
        if isinstance(value, str) and value:
            add(value, kind)
        return
    for key in ("allOf", "oneOf", "anyOf"):
        for alternative in schema.get(key) or []:
            walk_tokens(value, alternative, spec, kind, add)
    if isinstance(value, dict):
        properties = schema.get("properties") or {}
        for name, entry in value.items():
            if name in properties:
                walk_tokens(entry, properties[name], spec, name, add)
    elif isinstance(value, list) and isinstance(schema.get("items"), dict):
        for entry in value:
            walk_tokens(entry, schema["items"], spec, kind, add)


def json_body(text) -> object:
    try:
        return json.loads(text) if text else None
    except (json.JSONDecodeError, TypeError):
        return None


def origin_of(url: str) -> str:
    parts = urllib.parse.urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}"


def token_needles(stack: Stack, record_file: Path) -> tuple[dict[str, Token], list[str], set[str]]:
    """Every capability token one suite's recording shows it handled, derived by marker. Returns (tokens, problems, mailed).

    **What it derives** (cc.security.wire-assertion § Capability tokens). Every backend call of the recording is
    matched to its contract operation; its request and response JSON bodies are walked against the declared schemas
    and every `token`-classed value is taken ([walk_tokens]); so is every request header the operation declares
    `token` (`X-Contributor-Token`, `X-Share-Password`), every response header it declares `token` — a `Set-Cookie`
    contributes its non-empty cookie values ([set_cookie_values]) — and the bearer of `Authorization`. The kind is the
    name of the marked thing. Needles are derived, never listed (ADR 0053 §2): a name list would forget the 20th.

    The calls it reads are the backend's (`stack.backend_origins` — its two listeners); the store and the mailbox are
    not the contract's.

    **`problems`** names every backend call no operation matches — its tokens would be invisible, so the caller
    fails on it. **`mailed`** is the tokens the suite first read out of a mail (the earliest recorded call carrying
    it is a Mailpit read): mail is their channel (ADR 0049/0054), and the mailbox sweep exempts exactly those.
    """
    entries = [json.loads(line) for line in record_file.read_text().split("\n") if line.strip()]
    tokens: dict[str, Token] = {}
    problems: list[str] = []

    def add(value: str, kind: str, weak: bool = False) -> None:
        if value not in tokens or (tokens[value].weak and not weak):
            needle = token_needle(value)
            tokens[value] = Token(kind, needle, weak, needle != value)

    for entry in entries:
        if origin_of(entry["url"]) not in stack.backend_origins:
            continue
        path = urllib.parse.urlsplit(entry["url"]).path
        op = contract().match(entry["method"], path)
        if op is None:
            problems.append(f"{entry['method']} {path} matches no operation in contracts/api — its tokens are unseen")
            continue
        request_headers = entry.get("requestHeaders") or {}
        if contract().bearer:
            authorization = header_value(request_headers, "Authorization") or ""
            if authorization.lower().startswith("bearer ") and authorization[7:].strip():
                add(authorization[7:].strip(), BEARER_KIND, weak=True)
        for parameter in op.parameters:
            parameter, _ = contract().resolve(parameter, op.spec)
            if parameter.get("in") == "header" and parameter.get(validate_field_visibility.MARKER) == TOKEN_CLASS:
                value = header_value(request_headers, parameter["name"])
                if isinstance(value, str) and value:
                    add(value, parameter["name"])
        body, spec = contract().resolve(op.operation.get("requestBody") or {}, op.spec)
        schema = ((body.get("content") or {}).get("application/json") or {}).get("schema")
        if schema is not None:
            walk_tokens(json_body(entry.get("requestBody")), schema, spec, "requestBody", add)
        responses = op.operation.get("responses") or {}
        status = entry.get("status")
        response = next((responses[key] for key in (str(status), f"{str(status)[:1]}XX", "default") if key in responses),
                        None)
        if response is None:
            continue
        response, spec = contract().resolve(response, op.spec)
        schema = ((response.get("content") or {}).get("application/json") or {}).get("schema")
        if schema is not None:
            walk_tokens(json_body(entry.get("responseBody")), schema, spec, "responseBody", add)
        for name, header in (response.get("headers") or {}).items():
            header, _ = contract().resolve(header, spec)
            if header.get(validate_field_visibility.MARKER) != TOKEN_CLASS:
                continue
            if name.lower() == "set-cookie":
                for value in set_cookie_values(entry):
                    add(value, name)
            else:
                value = header_value(entry.get("responseHeaders") or {}, name)
                if isinstance(value, str) and value:
                    add(value, name)

    mailed: set[str] = set()
    texts = [json.dumps(entry, ensure_ascii=False) for entry in entries]
    for value, token in tokens.items():
        first = next((i for i, text in enumerate(texts) if token.needle in text), None)
        if first is not None and origin_of(entries[first]["url"]) == stack.mailpit:
            mailed.add(value)
    return tokens, problems, mailed


def kinds_of(tokens: Iterable[Token]) -> str:
    """`accessToken ×3, Set-Cookie ×2` — the printed count by kind a reader compares across runs."""
    counts: dict[str, int] = {}
    for token in tokens:
        counts[token.kind] = counts.get(token.kind, 0) + 1
    return ", ".join(f"{kind} ×{n}" for kind, n in sorted(counts.items())) or "none"


class RunTokens(NamedTuple):
    """The UNION of a run's token sets, for the run-level surfaces — by needle, with the kind and the recordings."""
    by_needle: dict[str, Token]
    carriers: dict[str, list[str]]
    mailed: set[str]  # needles of tokens some suite first read out of a mail


def run_tokens(stack: Stack, workdir: Path) -> RunTokens:
    """Every recording of the run, derived ([token_needles]) and merged — a recording is found by a recursive glob."""
    by_needle: dict[str, Token] = {}
    carriers: dict[str, list[str]] = {}
    mailed: set[str] = set()
    for record in sorted(workdir.rglob("http-*.jsonl")):
        tokens, _, first_mailed = token_needles(stack, record)
        for value, token in tokens.items():
            if token.needle not in by_needle or (by_needle[token.needle].weak and not token.weak):
                by_needle[token.needle] = token
            carriers.setdefault(token.needle, []).append(f"token:{token.kind} from {record.name}")
            if value in first_mailed:
                mailed.add(token.needle)
    return RunTokens(by_needle, carriers, mailed)


NO_TOKENS = RunTokens({}, {}, set())


def with_tokens(carriers: dict[str, list[str]], tokens: RunTokens) -> dict[str, list[str]]:
    """One needle → carriers map for a run-level surface: the canaries and the tokens, each labelled by its source."""
    merged = {needle: list(labels) for needle, labels in carriers.items()}
    for needle, labels in tokens.carriers.items():
        merged.setdefault(needle, []).extend(labels)
    return merged


def load_pii(pii_files: Iterable[Path]) -> tuple[dict[str, list[str]], list[str]]:
    """The third needle set, `pii` (ADR 0092 § 4): every email address the run's suites signed up with, as needle →
    `pii from <file>` labels, and why any file could not be read. The suites write them (`recordPii`, one
    `pii-<suite>.json` per suite, the canary files' format) where they mint the address they sign up with.

    **Searched only in the logs** — every boot log ([sweep_logs]) and every container log ([sweep_containers]) — and
    nowhere else: the database dump, the statement log, the HTTP recording and the mailbox hold an account's address
    by design (it is declared plaintext, `pii`, the login identifier). That is why it is a set of its own rather than
    a canary, which every surface would search. Its control is [pii_control]."""
    carriers, problems = load_canaries(pii_files)
    return {address: [f"pii from {name}" for name in files] for address, files in carriers.items()}, problems


def with_pii(needles: dict[str, list[str]], pii: dict[str, list[str]] | None) -> dict[str, list[str]]:
    """[with_tokens]' merge, for the `pii` set: one needle → labels map, each label naming its set."""
    merged = {needle: list(labels) for needle, labels in needles.items()}
    for needle, labels in (pii or {}).items():
        merged.setdefault(needle, []).extend(labels)
    return merged


def pii_control(pii_files: Iterable[Path], held_by: Iterable[Path], where: str) -> str | None:
    """The `pii` needle set's positive control: every address is found where it belongs. Returns None, else why not.

    **What it asserts.** The run recorded at least one address — zero is a failure, as zero logs is, because a log
    sweep for no address proves nothing — every `pii-*.json` reads, and each address appears, as a plain substring,
    in some file of `held_by`: the mails the run read (`run_web_e2e.py`, where every signed-up address is invited or
    mailed) or the statement log (lane L1, where the instance wrote every account it created). So a needle is an
    address the instance really handled, in the spelling it handled it, and its absence from the logs means something.

    **The defect that bought it.** The log sweep searched sealed values and tokens, and an address was in no needle
    set: runs of 2026-10-16 passed with the invite mailer's line naming the owner's address in a boot log
    (`tenant invite for a-<stamp>@e2e.test … was NOT delivered`), until the legal audit of 2026-10-18 removed the line
    by hand (inbox #481). Nothing would have turned the next one red.

    **Its limit.** A substring of a file, not a parsed header or literal: an address that only occurs inside another
    one would pass. And it proves the needle is real, not that every address a suite handled was recorded — an
    address minted outside the recording helpers is in no needle set."""
    carriers, problems = load_pii(pii_files)
    if problems:
        return "the pii needle set is unusable: " + "; ".join(problems)
    if not carriers:
        return ("the run recorded no pii — no address a suite signed up with, so a log sweep for addresses would have "
                "proved the absence of nothing")
    texts = [path.read_bytes().decode("utf-8", errors="replace") for path in sorted(held_by) if path.is_file()]
    missing = sorted(address for address in carriers if not any(address in text for text in texts))
    if missing:
        return (f"{len(missing)} of {len(carriers)} pii address(es) are in none of the {len(texts)} file(s) of {where} "
                f"— not an address the instance handled, so its absence from the logs proves nothing: {missing}")
    log(f"pii control: all {len(carriers)} address(es) the suites signed up with are in {where} ({len(texts)} file(s))")
    return None


def db_token_findings(dump: str, tokens: dict[str, Token]) -> tuple[list[tuple[str, str]], list[Token]]:
    """Over a dump's data rows: every (token, table) a raw token lands on, and the tokens whose HASH the dump holds.

    The hash is the control ([TOKEN_HASH]): ADR 0004's "a database dump yields no usable session" means the server
    keeps the hash where it would otherwise keep the token, so finding hashes proves this dump is the one the
    tokens' rows were written to — and that it was read at all. A count of zero raw hits proves neither.
    """
    needles = [(value, dump_escape(token.needle)) for value, token in tokens.items()]
    hashes = {TOKEN_HASH(value.encode("utf-8")).hexdigest(): value for value in tokens}
    hits: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    hashed: set[str] = set()
    for line in dump.splitlines():
        match = INSERT_LINE.match(line)
        if not match:
            continue
        table = match.group(1)
        for value, needle in needles:
            if needle in line and (value, table) not in seen:
                seen.add((value, table))
                hits.append((value, table))
        for digest, value in hashes.items():
            if value not in hashed and digest in line:
                hashed.add(value)
    return hits, [tokens[value] for value in tokens if value in hashed]


# ----------------------------------------------------------------------------------------------------------
# The log surface — what an operator of this stack reads without opening anything
# ----------------------------------------------------------------------------------------------------------

# The two encoders a URL in this stack is built with, as `quote` safe-sets. Every URL a suite sends is built by the
# JavaScript client: `encodeURIComponent` leaves `A-Za-z0-9` and `-_.!~*'()` alone, and the form serializer behind
# `URLSearchParams` leaves only `A-Za-z0-9` and `*-._`, writing a space as `+`. Both write upper-case hex, as
# `quote` does. A store logs the URI as it was SENT (Garage: `<addr> <method> <uri>`, measured on a refused request),
# so a canary crossing a URL is found only in the spelling its encoder chose — which is what these model.
URI_COMPONENT_SAFE = "-_.!~*'()"
FORM_SAFE = "-_.*"


def canary_needles(canary: str) -> list[tuple[str, str]]:
    """Every spelling in which a canary can reach a text surface, as `(variant, needle)`, de-duplicated.

    **One definition of "how a canary can appear", shared by every text surface** — the boot logs, the container
    logs and the HTTP recording. The database keeps [dump_escape] (it models the dump, not a transport) and the
    store keeps raw bytes; everything that is TEXT someone else encoded uses this.

    - `raw` — written as-is (a log line interpolating a value, a body).
    - `json` / `json-ascii` — inside a JSON string, in both of Python's `ensure_ascii` spellings: the escaped
      quote/backslash/control characters, with non-ASCII left as UTF-8 or written as lower-case `\\uXXXX`.
    - `pct` / `pct-plus` — inside a URL, a space as `%20` (`encodeURIComponent`) or as `+` (a form).

    **No base64 variant**: base64 of a plaintext reaches the server only when a client sends an unsealed value in
    a base64 field, which is the HTTP opacity oracle's case ([opacity_failure]); the server never base64-encodes
    plaintext it does not have. A variant equal to an earlier one is dropped — an ASCII canary with no space or
    quote yields `raw` alone, which is what lets a token-shaped value reuse this unchanged.
    """
    forms = (
        ("raw", canary),
        ("json", json.dumps(canary, ensure_ascii=False)[1:-1]),
        ("json-ascii", json.dumps(canary)[1:-1]),
        ("pct", urllib.parse.quote(canary, safe=URI_COMPONENT_SAFE)),
        ("pct-plus", urllib.parse.quote_plus(canary, safe=FORM_SAFE)),
    )
    needles: list[tuple[str, str]] = []
    for variant, needle in forms:
        if needle and all(needle != seen for _, seen in needles):
            needles.append((variant, needle))
    return needles


# How MariaDB Connector/J writes a string parameter into the statement text it sends — it prepares client-side by
# default, so a bound value reaches the server (and its statement log) as a quoted SQL literal. MEASURED from the
# driver the backend ships (3.5.8, `org.mariadb.jdbc.client.socket.Writer.writeStringEscaped`, `javap -c`): with
# backslash escapes on — the server's `sql_mode` carries no `NO_BACKSLASH_ESCAPES` — exactly these four characters
# get a backslash in front, NUL included as the raw byte; a newline, a tab and every non-ASCII character go raw.
# Backslash first, for the reason [DUMP_ESCAPES] gives.
SQL_LITERAL_ESCAPES = [("\\", "\\\\"), ("'", "\\'"), ('"', '\\"'), ("\0", "\\\0")]


def sql_literal(value: str) -> str:
    """Render a value the way the driver writes it inside a string literal (the body, without the quotes)."""
    for raw, escaped in SQL_LITERAL_ESCAPES:
        value = value.replace(raw, escaped)
    return value


def statement_needles(canary: str) -> list[tuple[str, str]]:
    """The statement log's needles: every text-surface spelling ([canary_needles]) plus the driver's literal (`sql-literal`).

    The `sql` form is a needle of its own rather than an un-escaping of the log, for [dump_escape]'s reason: escaping
    the needle is total and one-directional, un-escaping a haystack means knowing which backslashes are whose.
    """
    needles = canary_needles(canary)
    escaped = sql_literal(canary)
    if all(escaped != seen for _, seen in needles):
        needles.append(("sql-literal", escaped))
    return needles


class Hit(NamedTuple):
    """One canary found on one line of one log — everything a reader needs to act on it, from the job log alone."""
    source: str
    line_no: int
    variant: str
    canary: str
    line: str


def occurrences(text: str, needle: str) -> Iterator[int]:
    """Every offset of `needle` in `text`: a plain, case-sensitive substring — the one matcher of the log surface.

    Over the WHOLE text rather than per line, so a needle holding a newline is still found; its line is computed
    from the offset. No word boundary and no length floor HERE: a threshold added against false positives is where
    the false negatives hide (changes/README), so a collision is explained, never excluded. The floor that keeps
    random ciphertext from matching lives where the canaries are made, and it refuses rather than drops: the
    recorder (`web/src/test/canaries.ts`, `MIN_CANARY_LENGTH`) throws on a short base64-alphabet canary, and leaves
    an envelope's closed enum values (`auto`, `range`, `city`) out of the set (inbox #323).
    """
    at = text.find(needle)
    while at != -1:
        yield at
        at = text.find(needle, at + 1)


def needle_hits(source: str, text: str, canaries: Iterable[str],
                needles_of: Callable[[str], list[tuple[str, str]]] = canary_needles) -> list[Hit]:
    """Every (line, canary) of `text` some variant of a canary lands on, first variant winning; in line order."""
    starts = [0] + [m.end() for m in re.finditer("\n", text)]
    hits: dict[tuple[int, str], Hit] = {}
    for canary in canaries:
        for variant, needle in needles_of(canary):
            for at in occurrences(text, needle):
                line_no = bisect.bisect_right(starts, at)
                if (line_no, canary) in hits:
                    continue
                begin = starts[line_no - 1]
                end = text.find("\n", begin)
                hits[(line_no, canary)] = Hit(source, line_no, variant, canary,
                                              text[begin:end if end != -1 else len(text)])
    return sorted(hits.values(), key=lambda h: (h.line_no, h.canary))


ANSI = re.compile(r"\x1b\[[0-9;]*m")


def report_hits(hits: list[Hit], carriers: dict[str, list[str]]) -> None:
    """Print each hit in full. CI uploads nothing, so the job log is the only evidence of a hit that survives."""
    for hit in hits:
        shown_line = ANSI.sub("", hit.line).strip()
        labels = carriers.get(hit.canary) or ["?"]
        # A token leads with its kind, an address with `pii`, so a reader never takes either for a leaked canary.
        what = f"{labels[0].split(' from ')[0]} " if labels[0].startswith(("token:", "pii ")) else ""
        log(f"    {hit.source}:{hit.line_no}  [{hit.variant}]  {what}{hit.canary!r}  "
            f"(carried by {', '.join(labels)})")
        log(f"        | {shown_line[:240]}")


def load_canaries(canary_files: Iterable[Path]) -> tuple[dict[str, list[str]], list[str]]:
    """The UNION of a run's canary files — canary → the files that carry it — and why any file could not be read.

    A union, because the run-level surfaces have no per-suite owner: a restored instance's boot log owes the
    previous member's canaries, and every container is shared by every suite. Attribution is per canary instead.
    """
    carriers: dict[str, list[str]] = {}
    problems: list[str] = []
    for path in sorted(canary_files):
        try:
            values = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            problems.append(f"{path.name} is unreadable ({e}) — its canaries are not swept for")
            continue
        for canary in values:
            carriers.setdefault(canary, []).append(path.name)
    return carriers, problems


# Quarkus' own shutdown line (`veyq-backend stopped in 0.018s`). A framework string, anchored on knowingly: an
# upgrade that rewords it fails this control LOUD, which is the direction this sensor fails in.
SHUTDOWN_NEEDLE = "stopped in"


def names_database(text: str, database: str) -> bool:
    """The armed-sensor control: the JDBC URL Flyway prints at boot (`Database: jdbc:mariadb://…/veyq_e2e_1?…`).

    Matched as `/<name>` followed by a non-identifier character — so `veyq_e2e_1` is not satisfied by a log that
    only names `veyq_e2e_10`, and the schema-qualified lines (`` `veyq_e2e_1` ``) that a restored member also
    prints cannot stand in for the URL. Same matcher as the canaries ([occurrences]).
    """
    needle = f"/{database}"
    for at in occurrences(text, needle):
        after = text[at + len(needle):at + len(needle) + 1]
        if not (after.isalnum() or after == "_"):
            return True
    return False


def read_log(path: Path) -> tuple[str, str | None]:
    """The log as UTF-8 — the encoding [boot] pins — and, if it is not, a reason: a broken pin is never silent."""
    raw = path.read_bytes()
    try:
        return raw.decode("utf-8"), None
    except UnicodeDecodeError as e:
        return raw.decode("utf-8", errors="replace"), f"{shown(path)} is not UTF-8 ({e}) — the encoding pin did not hold"


def sweep_logs(workdir: Path, canary_files: Iterable[Path], booted_database: Callable[[Path], str | None],
               tokens: RunTokens = NO_TOKENS, pii: dict[str, list[str]] | None = None) -> str | None:
    """Prove no canary of the run is readable in any boot log the run retains. Returns None, else why not.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). Run-level, after every group: every
    `backend-*.log` under the run directory (derived by a recursive glob, never listed) is searched for every
    canary of the run's canary files (their union), in every spelling [canary_needles] models. And each log
    must PROVE the sensor was armed, by three positive controls on the same matcher: it is non-empty, it names the
    database its boot was pointed at (`booted_database`, the orchestrator's rule from a log's name to its boot's
    database; [names_database]), and it carries Quarkus' shutdown line
    — so it was read complete, after its JVM exited, which a per-suite sweep inside `run_member` could not be.
    A count of zero hits is never read as success on its own; zero logs is a failure.

    **The defect that bought it.** No incident: until this ran, the log was the one surface ADR 0053 names that
    nothing read. A log is written server-side, where a value is DECODED (a percent-encoded path segment, a
    JSON-escaped string) and then written raw — so a leak there crosses no sensor the other three own.

    **Its limit.** A log records only what the process chose to write, at the level it shipped with (INFO); no
    veyq statement echoes request input today, so a green here is mostly the proof that none started to. The
    attribution of a hit is per canary file, not per log: a restored instance's log owes the previous member's
    values too. And the encoding pin is the e2e boot's — an operator's own locale is theirs.

    The run's capability tokens ([run_tokens]) are searched alongside the canaries, each hit led by `token:<kind>`,
    and so are the addresses the suites signed up with ([load_pii], `pii`), each hit led by `pii` — a log is the one
    surface that must not hold them.
    """
    carriers, problems = load_canaries(canary_files)
    needles = with_pii(with_tokens(carriers, tokens), pii)
    logs = sorted(workdir.rglob("backend-*.log"))
    if not logs:
        return (f"no boot log under {shown(workdir)} — the log sweep would have proved the absence of nothing "
                f"(0 logs, 0 hits is not a pass)")
    if not carriers:
        return "the run's canary files hold no canary — a green log sweep would mean nothing"

    hits: list[Hit] = []
    unmet: list[str] = list(problems)
    for path in logs:
        text, not_utf8 = read_log(path)
        if not_utf8:
            unmet.append(not_utf8)
        database = booted_database(path)
        controls = []
        if not text:
            unmet.append(f"{shown(path)} is EMPTY — nothing was written, so nothing could be found")
        else:
            controls.append("non-empty")
        if database is None:
            unmet.append(f"{shown(path)}: no rule derives which database this boot log belongs to — a new boot "
                         f"log name needs its rule in the orchestrator's BOOT_LOG")
        elif names_database(text, database):
            controls.append(f"database {database}")
        else:
            unmet.append(f"{shown(path)} never names its database {database} — not the log of the boot it is "
                         f"swept as, or not the whole of it")
        if any(True for _ in occurrences(text, SHUTDOWN_NEEDLE)):
            controls.append(f"shutdown line {SHUTDOWN_NEEDLE!r}")
        else:
            unmet.append(f"{shown(path)} has no shutdown line ({SHUTDOWN_NEEDLE!r}) — it was read before its JVM "
                         f"stopped, or the JVM was killed, so it may be incomplete")
        found = needle_hits(shown(path).as_posix(), text, needles)
        hits.extend(found)
        log(f"log sweep: {shown(path)} — {text.count(chr(10))} line(s), {len(carriers)} canary/canaries, "
            f"{len(tokens.by_needle)} token(s), {len(pii or {})} pii, {len(found)} hit(s); armed: "
            f"{', '.join(controls) or 'NOTHING'}")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the server log — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the server log: {len(hits)} canary, token or pii hit(s), {len(unmet)} "
                f"unmet control(s) across {len(logs)} boot log(s) (listed above)")
    log(f"wire assertion: {len(carriers)} canary/canaries, {len(tokens.by_needle)} capability token(s) "
        f"({kinds_of(tokens.by_needle.values())}) and {len(pii or {})} pii address(es) of the run appear in none of "
        f"{len(logs)} boot log(s), each armed")
    return None


def store_sentinel(stamp: str) -> tuple[str, str]:
    """The store-log sentinel of a run and the object path to request it under: `(sentinel, /<bucket-less key>)`.

    The container sweep's positive control ([sweep_containers]): the orchestrator sends the store one UNSIGNED
    request for this run-unique key, and the store's log must then carry it, percent-encoded — a sensor that cannot
    find the value it was handed cannot be trusted to find one it was not. The sentinel holds a space and a non-ASCII
    character so the store logs it ENCODED — measured on Garage v2.0.0, which logs `<addr> <method> <uri>` at INFO for
    a request it then refuses (`403 AccessDenied`, anonymous access unsupported) — and the sweep can only find it
    through the `pct` needle. Unsigned on purpose: refused, it reads and writes nothing. Planted at the START of the
    run, so finding it also proves the log window reaches back to where the run began. HOW it is sent is the
    orchestrator's: to the dev store's published port, or through a lane's Traefik on the public origin.
    """
    sentinel = f"veyq wire-log sentinel {stamp} Größe"
    return sentinel, urllib.parse.quote(sentinel, safe=URI_COMPONENT_SAFE)


def compose_services(stack: Stack) -> list[str]:
    """Every service of the compose project the run brought up — derived from Compose, never listed."""
    proc = subprocess.run([*stack.compose, "ps", "--all", "--services"], capture_output=True, text=True)
    if proc.returncode != 0:
        raise SweepError(f"`docker compose ps` failed ({proc.returncode}): {proc.stderr.strip()[-300:]}")
    services = sorted(line.strip() for line in proc.stdout.splitlines() if line.strip())
    if not services:
        raise SweepError("`docker compose ps` reported no service — there is no container log to sweep")
    return services


def container_log(stack: Stack, service: str, since: str) -> str:
    """One service's log from `since` (the run's start, a unix time) to now. A non-zero exit is an error, never ''."""
    proc = subprocess.run(
        [*stack.compose, "logs", "--no-color", "--no-log-prefix", "--timestamps", "--since", since, service],
        capture_output=True,
    )
    if proc.returncode != 0:
        raise SweepError(f"`docker compose logs {service}` failed ({proc.returncode}): "
                         f"{proc.stderr.decode('utf-8', errors='replace').strip()[-300:]}")
    return proc.stdout.decode("utf-8", errors="replace")


def sweep_containers(stack: Stack, workdir: Path, canary_files: Iterable[Path], since: str, sentinel: str,
                     tokens: RunTokens = NO_TOKENS,
                     controls: dict[str, Callable[[str], str | None]] | None = None,
                     pii: dict[str, list[str]] | None = None) -> str | None:
    """Prove no canary of the run is readable in any container log of the stack, over the run's window.

    **What it asserts.** Every service `docker compose ps` reports (MariaDB, Garage, Traefik, Mailpit today) has its
    log read from the run's start to now, retained as `container-<service>.log`, and searched for the union of the
    run's canaries through [canary_needles]. The store is the container that matters — Garage logs every request
    URI, query string included, so its log is the only surviving record of an object key whose object was deleted
    or never completed — and it is armed by the sentinel [plant_store_sentinel] sent, which must be found through
    the `pct` needle.

    **The defect that bought it.** No incident; the same gap as the boot log's — nothing read these logs at all.

    **Its limit.** Only the store carries a control. Traefik carries no e2e traffic (the suites call the backend
    and the store directly) and has no access log; MariaDB writes its general log to a file ([sweep_statements])
    and Mailpit logs deliveries, not bodies ([sweep_mailbox]) — those three are swept because the rule that includes them is derived, not because anything proves
    they could show a leak. The logs are shared by every suite of the run and by a developer's own traffic in the
    same window, so a hit's suite is inferred from the canary, not from the log.

    **Tokens, with one exemption.** The run's capability tokens are searched too ([run_tokens]) — except a presigned
    URL in the STORE's own log: the store is that URL's verifier and logs the request target it was handed, and the
    capability expires with the presign TTL. Such hits are printed as exempted, never dropped; the same URL is a hit
    in every other container's log.

    **`controls`** — a further positive control per service, for a stack whose other logs CAN be armed: each is
    called with the service's log and returns why it is not met, or None (lane L1 requires the app's own log to name
    its database, [names_database] — the backend's log is a container log there, not a boot log). A service a
    control names that the project does not run is unmet. Its line then reads `armed: <what it found>`.

    **`pii`** — the addresses the suites signed up with ([load_pii]), searched in every container log with no
    exemption: none of these processes has a reason to write a person's address (ADR 0092 § 3).
    """
    carriers, problems = load_canaries(canary_files)
    if not carriers:
        return "the run's canary files hold no canary — a green container-log sweep would mean nothing"
    needles = with_pii(with_tokens(carriers, tokens), pii)
    exempted: list[Hit] = []
    try:
        services = compose_services(stack)
    except SweepError as e:
        return str(e)

    hits: list[Hit] = []
    unmet: list[str] = list(problems)
    store_service = stack.store_service
    controls = controls or {}
    if store_service not in services:
        unmet.append(f"the store's service `{store_service}` is not among {services} — its control cannot be met")
    unmet += [f"`{service}` has a control but is not among {services}" for service in controls if service not in services]
    for service in services:
        try:
            text = container_log(stack, service, since)
        except SweepError as e:
            unmet.append(str(e))
            continue
        (workdir / f"container-{service}.log").write_text(text, encoding="utf-8")
        found = needle_hits(f"container {service}", text, needles)
        if service == store_service:
            verified = [h for h in found if h.canary in tokens.by_needle and tokens.by_needle[h.canary].url]
            exempted.extend(verified)
            found = [h for h in found if h not in verified]
        hits.extend(found)
        armed = "no control (see the concern's § What a green sweep does not prove)"
        if service == store_service:
            planted = needle_hits(f"container {service}", text, [sentinel])
            if not planted:
                unmet.append(f"the planted sentinel {sentinel!r} is NOT in the `{service}` log — the window, the "
                             f"log level or the matcher is wrong, and this sweep proves nothing about the store")
                armed = "planted sentinel NOT found"
            elif planted[0].variant != "pct":
                unmet.append(f"the planted sentinel was found through `{planted[0].variant}`, not `pct` — the store "
                             f"no longer logs the URI as sent, so the percent-encoded needle is unproven this run")
                armed = f"planted sentinel found only through {planted[0].variant}"
            else:
                armed = f"planted sentinel found through pct (line {planted[0].line_no})"
        if service in controls:
            why = controls[service](text)
            if why is None:
                armed = "its control met"
            else:
                unmet.append(f"`{service}`: {why}")
                armed = "its control NOT met"
        log(f"container log sweep: {service} — {text.count(chr(10))} line(s) since {since}, {len(pii or {})} pii, "
            f"{len(found)} hit(s); {armed}")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the container logs — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the container logs: {len(hits)} canary, token or pii hit(s), "
                f"{len(unmet)} unmet control(s) across {len(services)} service(s) (listed above)")
    exempt_urls = {h.canary for h in exempted}
    log(f"container log sweep: {len(exempt_urls)} presigned-URL token(s) found in the `{store_service}` log, its own "
        f"verifier, and exempted there ({len(exempted)} line(s)) — swept on every other surface")
    log(f"wire assertion: {len(carriers)} canary/canaries, {len(tokens.by_needle)} capability token(s) and "
        f"{len(pii or {})} pii address(es) of the run appear in none of {len(services)} container log(s); the store's "
        f"armed by its planted sentinel")
    return None


# ----------------------------------------------------------------------------------------------------------
# The statement log — every SQL statement the database received during the run, not only what it ended with
# ----------------------------------------------------------------------------------------------------------

# MariaDB's general query log is written INSIDE the container, under `/tmp` — outside the data volume
# (`/var/lib/mysql`), so it never grows the developer's data and is gone with the container. The run's own name
# is in it, which is what lets a later run recognise a log a killed run left switched on ([StatementLog.open]).
STATEMENT_LOG_PREFIX = "/tmp/veyq-e2e-"


class StatementLog:
    """MariaDB's general query log, on for EXACTLY the run's window, restored on every exit path Python can see.

    `general_log`, `general_log_file` and `log_output` are dynamic globals (measured on mariadb:11.4: set at runtime,
    no restart, nothing in either compose file or the chart), so the window is three `SET GLOBAL`s at its start and
    their inverse at its end. The container is the developer's own `veyq-mariadb`, so the log also records whatever
    else reaches it in the window — their IntelliJ backend, a database tool — and two rules contain that:
      - **[close] runs from a `finally`** (and SIGTERM/SIGHUP are turned into an exit that runs it), switching the
        log off, copying the file into the run's own directory and DELETING it in the container. Only a SIGKILL
        skips it; a container restart resets the globals to off on its own.
      - **[open] heals a killed run's leftovers**: a log found ON writing one of this harness's files is switched
        off, the file deleted, and OFF taken as the state to restore — never restored back to ON, which would keep
        recording the developer's statements into a file nobody reads.

    The retained copy (`mariadb-general.log`) is the developer's data on their disk, in a gitignored directory —
    stated in the concern, not filtered: a filter by connection would be a second parser to be wrong in.
    """

    def __init__(self, stack: Stack, stamp: str, workdir: Path) -> None:
        self.stack = stack
        self.stamp = stamp
        self.path = f"{STATEMENT_LOG_PREFIX}{stamp}.log"
        self.retained = workdir / "mariadb-general.log"
        self.prior: list[str] | None = None
        self.closed = False
        # What [close] could not do, reported by the sweep as an unmet control rather than raised mid-cleanup.
        self.problems: list[str] = []

    def open(self) -> None:
        general_log, log_file, log_output = self.stack.mysql_row(
            "SELECT @@global.general_log, @@global.general_log_file, @@global.log_output")
        self.prior = [general_log, log_file, log_output]
        if general_log == "1" and log_file.startswith(STATEMENT_LOG_PREFIX):
            log(f"found the statement log ALREADY ON, writing {log_file} — a run was killed before it could switch "
                f"it off. Switching it off, deleting that file, and restoring OFF at the end of this run")
            self.stack.mysql("SET GLOBAL general_log = OFF; SET GLOBAL general_log_file = DEFAULT")
            self.prior = ["0"] + self.stack.mysql_row("SELECT @@global.general_log_file, @@global.log_output")
        elif general_log == "1":
            log(f"found the statement log already ON, writing {log_file} — not this harness's file; it is restored "
                f"to exactly that at the end of the run")
        # A file a killed run left behind after switching off holds the same developer statements: delete it too.
        subprocess.run([*self.stack.compose, "exec", "-T", "mariadb", "sh", "-c", f"rm -f {STATEMENT_LOG_PREFIX}*.log"],
                       capture_output=True)
        self.stack.mysql(f"SET GLOBAL log_output = 'FILE'; SET GLOBAL general_log_file = '{self.path}'; "
              f"SET GLOBAL general_log = ON")
        log(f"statement log ON for the run's window: {self.path} in the mariadb container "
            f"(prior: general_log={self.prior[0]}, file={self.prior[1]}, output={self.prior[2]})")

    def plant(self, where: str) -> str:
        """One statement of the orchestrator's own, carrying a run-unique sentinel — [sweep_statements]' control.

        The sentinel holds an apostrophe, written the way the driver writes one (`\\'`), so it can only be found
        through the `sql-literal` needle: a sentinel any spelling found would arm nothing in particular.
        """
        sentinel = f"veyq statement-log sentinel {self.stamp} {where}: it's Größe"
        self.stack.mysql(f"DO '{sql_literal(sentinel)}'")
        log(f"planted the statement-log sentinel {sentinel!r}")
        return sentinel

    def close(self) -> None:
        """Switch the log off and restore the prior globals FIRST, then retain the file and delete it. Idempotent."""
        if self.closed or self.prior is None:
            return
        self.closed = True
        general_log, log_file, log_output = self.prior
        try:
            self.stack.mysql(f"SET GLOBAL general_log = OFF; SET GLOBAL general_log_file = '{log_file}'; "
                  f"SET GLOBAL log_output = '{log_output}'; SET GLOBAL general_log = {'ON' if general_log == '1' else 'OFF'}")
            log(f"statement log restored: general_log={general_log}, file={log_file}, output={log_output}")
        except subprocess.CalledProcessError as e:
            self.problems.append(f"restoring the statement-log globals failed ({e}) — check "
                                 f"`SELECT @@global.general_log` on the mariadb container by hand")
            log(f"RESTORING THE STATEMENT LOG FAILED: {e}")
        copied = subprocess.run([*self.stack.compose, "exec", "-T", "mariadb", "cat", self.path], capture_output=True)
        if copied.returncode == 0:
            self.retained.write_bytes(copied.stdout)
            log(f"statement log retained as {shown(self.retained)} ({len(copied.stdout)} bytes)")
        else:
            self.problems.append(f"the container holds no statement log at {self.path} — it was never switched on, "
                                 f"or something removed it")
        removed = subprocess.run([*self.stack.compose, "exec", "-T", "mariadb", "rm", "-f", self.path],
                                 capture_output=True)
        if removed.returncode != 0:
            self.problems.append(f"deleting {self.path} in the mariadb container failed: "
                                 f"{removed.stderr.decode('utf-8', errors='replace').strip()[-200:]}")


# One entry of the general log: `<YYMMDD HH:MM:SS>` on the first entry of a second, else empty, then a tab, the
# connection id right-aligned, the command, a tab, and the argument — which runs on, raw newlines and all, until the
# next entry's header. MEASURED on mariadb:11.4 (the phase that built this pasted the lines into the concern).
STATEMENT_HEADER = re.compile(r"^(?:\d{6} [ \d]\d:\d\d:\d\d)?\t+ *(?P<id>\d+) (?P<command>[A-Z][A-Za-z_ ]*?)\t", re.M)
# `<user>@<host> on <schema> using <transport>` — the schema is empty for a session that names none.
CONNECT_ARGUMENT = re.compile(r"\S+ on (?P<schema>\S*) using ")

# The tokens of MariaDB's lexer that can contain a quote without opening a string: a literal of either quote (no
# `ANSI_QUOTES`, so `"` delimits a string too), a back-ticked identifier, and the three comment forms. A literal or
# comment left open runs to the end of its entry — never past it, so a malformed statement cannot desynchronise
# the rest of the file.
SQL_TOKEN = re.compile(r"""
      '(?P<single>(?:[^'\\]+|\\.|'')*)(?:'|\Z)
    | "(?P<double>(?:[^"\\]+|\\.|"")*)(?:"|\Z)
    | `[^`]*(?:`|\Z)
    | /\*.*?(?:\*/|\Z)
    | (?:\#|--(?=[ \t\r\n]|\Z))[^\n]*
""", re.S | re.X)

# Joins the literals into one haystack. A needle can only straddle two literals if it contains this character.
LITERAL_JOIN = "\x00"


def literal_spans(text: str) -> list[tuple[int, int]]:
    """Every string literal's body in a general log, as `(start, end)` offsets into the whole text.

    **Why only literals** — the statement log's answer to [INSERT_LINE]. A value the backend sends the database
    travels as a literal (the driver prepares client-side, [SQL_LITERAL_ESCAPES]); everything outside one is SQL
    the code wrote: keywords, table and column names, the driver's own session statements, and every comment of
    every Flyway migration. Searched whole, that is noise a canary cannot avoid — measured: `set autocommit=0`
    precedes every transaction and the run's canaries include `auto`, and the migrations' comments carry `range`
    and `auto`. Scoping to literals removes the noise class without excluding a single value.
    """
    heads = list(STATEMENT_HEADER.finditer(text))
    spans: list[tuple[int, int]] = []
    for i, head in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        for token in SQL_TOKEN.finditer(text, head.end(), end):
            for group in ("single", "double"):
                if token.group(group) is not None:
                    spans.append(token.span(group))
    return spans


def literal_hits(source: str, text: str, canaries: Iterable[str]) -> tuple[list[Hit], int]:
    """Every (line, canary) of a general log some [statement_needles] spelling lands on INSIDE a literal.

    Returns the hits in line order and how many literals were searched — a count the sweep prints, because a log
    whose literals were never found would otherwise sweep nothing and pass.
    """
    spans = literal_spans(text)
    starts: list[int] = []
    pieces: list[str] = []
    at = 0
    for begin, end in spans:
        starts.append(at)
        pieces.append(text[begin:end])
        at += end - begin + len(LITERAL_JOIN)
    joined = LITERAL_JOIN.join(pieces)
    line_starts = [0] + [m.end() for m in re.finditer("\n", text)]
    hits: dict[tuple[int, str], Hit] = {}
    for canary in canaries:
        for variant, needle in statement_needles(canary):
            for found in occurrences(joined, needle):
                k = bisect.bisect_right(starts, found) - 1
                offset = spans[k][0] + (found - starts[k])
                line_no = bisect.bisect_right(line_starts, offset)
                if (line_no, canary) in hits:
                    continue
                begin = line_starts[line_no - 1]
                end = text.find("\n", begin)
                hits[(line_no, canary)] = Hit(source, line_no, variant, canary,
                                              text[begin:end if end != -1 else len(text)])
    return sorted(hits.values(), key=lambda h: (h.line_no, h.canary)), len(spans)


def connected_schemas(text: str) -> set[str]:
    """Every schema a `Connect` entry of the log names — the backend's pool opens its connections ON its database."""
    schemas: set[str] = set()
    for head in STATEMENT_HEADER.finditer(text):
        if head.group("command") == "Connect":
            argument = CONNECT_ARGUMENT.match(text, head.end())
            if argument and argument.group("schema"):
                schemas.add(argument.group("schema"))
    return schemas


def sweep_statements(retained: Path, canary_files: Iterable[Path], sentinels: Iterable[str],
                     databases: Iterable[str], problems: Iterable[str] = (),
                     tokens: RunTokens = NO_TOKENS, written: Iterable[str] = ()) -> str | None:
    """Prove no canary of the run was ever SENT to the database — every statement of the run, not its end state.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). MariaDB's general log, on for the run's window
    ([StatementLog]), holds every statement the server received — writes, reads, and the writes a later statement
    undid. Each string literal in it ([literal_spans]) is searched for the union of the run's canaries in every
    spelling [statement_needles] models, the driver's own literal escaping included. Armed by two controls on the
    same matcher, never by a count of zero: the run's sentinel statements — planted at the window's start and just
    before it closed — must both be found, through `sql-literal`, so the file is the one being written and nothing
    switched the log off in between; and every database a boot of the run was pointed at must appear in a
    `Connect` entry ([connected_schemas]; `databases`, which the orchestrator derives — from its boot logs, in
    `run_web_e2e.py`), so the BACKEND's traffic was captured, not only the
    orchestrator's own.

    **The defect that bought it.** No incident; the gap the database sweep states about itself — it reads each
    suite's end state, and a plaintext written and then overwritten or deleted inside the suite leaves no trace
    there. Measured before this was built: one run carries 7 node PATCHes, 18 PUTs of upserted state and 2
    DELETEs, before what the server rewrites on its own.

    **Its limit.** It sees statements, so a value the backend computes and never sends to the database is not
    there by construction; it searches literals, so a value the code splices into SQL text outside a quote is
    not searched (nothing does today — every value is a bound parameter). A literal NUL-joined to its neighbour
    can produce a hit only for a canary that itself contains NUL. And the window is shared: it also holds the
    statements of anything else that reached the same MariaDB during the run, a developer's own backend included.

    **`written` — the backend's traffic, for a stack whose backend connected before the window opened** (lane L1: the
    app's pool connects at `up`, so no `Connect` entry falls inside the window and `databases` is empty there). Each
    value is DECLARED plaintext the backend must have sent the database during the window (an account's email, `pii`)
    and must be found in a literal: the same proof the `Connect` entries give, that the backend's statements were
    captured and not only the orchestrator's own.

    **Tokens.** The run's capability tokens are searched in the same literals ([run_tokens]). This is the one surface
    that sees a lookup BY the raw token — a query whose `WHERE` carries it — which no end-state dump can show.
    """
    carriers, unreadable = load_canaries(canary_files)
    if not carriers:
        return "the run's canary files hold no canary — a green statement-log sweep would mean nothing"
    needles = with_tokens(carriers, tokens)
    unmet: list[str] = list(unreadable) + list(problems)
    if retained.is_file():
        text = retained.read_bytes().decode("utf-8", errors="replace")
    else:
        text = ""
        unmet.append(f"no statement log was retained at {shown(retained)}")
    source = shown(retained).as_posix()
    hits, literals = literal_hits(source, text, needles)

    controls: list[str] = []
    for sentinel in sentinels:
        planted, _ = literal_hits(source, text, [sentinel])
        if planted:
            controls.append(f"sentinel line {planted[0].line_no} through {planted[0].variant}")
        else:
            # Its apostrophe makes `sql-literal` the only needle that can find it, so this also fails when the
            # server stops writing a literal the way the needles model — `NO_BACKSLASH_ESCAPES` doubles the quote.
            unmet.append(f"the planted sentinel {sentinel!r} is NOT in the statement log — the log was not on, was "
                         f"switched off or moved mid-run, or a literal is no longer escaped the way `sql-literal` "
                         f"models; this sweep proves nothing")
    connected = connected_schemas(text)
    required = sorted(databases)
    for database in required:
        if database not in connected:
            unmet.append(f"no `Connect` entry names {database} — the boot pointed at it ran outside the window, "
                         f"or its connections were not captured")
    if required and all(db in connected for db in required):
        controls.append(f"a Connect for each of {len(required)} database(s)")
    for value in written:
        found, _ = literal_hits(source, text, [value])
        if found:
            controls.append(f"the backend's literal {value!r} (line {found[0].line_no})")
        else:
            unmet.append(f"{value!r}, which the backend must have written during the window, is in no literal of the "
                         f"statement log — the backend's statements were not captured; this sweep proves nothing")
    log(f"statement-log sweep: {source} — {text.count(chr(10))} line(s), {len(STATEMENT_HEADER.findall(text))} "
        f"entr(y/ies), {literals} literal(s), {len(carriers)} canary/canaries, {len(tokens.by_needle)} token(s), "
        f"{len(hits)} hit(s); armed: "
        f"{', '.join(controls) or 'NOTHING'}")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the statement log — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the statement log: {len(hits)} canary or token hit(s), {len(unmet)} unmet "
                f"control(s) (listed above)")
    log(f"wire assertion: {len(carriers)} canary/canaries and {len(tokens.by_needle)} capability token(s) "
        f"({kinds_of(tokens.by_needle.values())}) of the run appear in none of {literals} literal(s) the database "
        f"received during the run; armed by {', '.join(controls)}")
    return None


# ----------------------------------------------------------------------------------------------------------
# The mailbox — every mail the run sent, the one surface a third party (the tenant's SMTP provider) reads
# ----------------------------------------------------------------------------------------------------------

# The page size the mailbox is read with — explicit, because a default page is exactly the number that truncates a
# read silently.
MAILBOX_PAGE = 100


def plant_mail_sentinel(smtp_address: tuple[str, int], stamp: str) -> str:
    """Deliver one mail of the orchestrator's own whose subject and body carry a run-unique sentinel.

    [sweep_mailbox]'s positive control. The sentinel holds a space and a non-ASCII character, and travels the way
    the product's own mail does (measured on a recovery mail: Vert.x writes the subject as a `Q` encoded-word and
    the body quoted-printable, soft line breaks included) — so a raw substring search over the message source
    cannot find it, and only the DECODED read can. Delivered at the start, so finding it also proves the read
    reaches back to where the run began.
    """
    sentinel = f"veyq mailbox sentinel {stamp} Größe"
    message = email.message.EmailMessage()
    message["From"] = "e2e-orchestrator@veyq.localhost"
    message["To"] = f"mailbox-sentinel-{stamp}@veyq.localhost"
    message["Subject"] = sentinel
    message.set_content(f"The e2e run's mailbox control.\n{sentinel}\n", cte="quoted-printable")
    try:
        with smtplib.SMTP(*smtp_address, timeout=10) as smtp:
            smtp.send_message(message)
    except (OSError, smtplib.SMTPException) as e:
        raise SystemExit(f"[e2e] MAILBOX SMTP UNREACHABLE at {smtp_address[0]}:{smtp_address[1]}: the mailbox "
                         f"sweep's control could not be planted ({e})") from e
    log(f"planted the mailbox sentinel {sentinel!r}: one mail over SMTP, subject encoded-word, body quoted-printable")
    return sentinel


def mailpit_json(mailpit: str, path: str) -> dict:
    with urllib.request.urlopen(f"{mailpit}{path}", timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def run_mail_ids(mailpit: str, since: float) -> list[str]:
    """Every message Mailpit received from `since` (a unix time) on — paged with an explicit size, newest first.

    Mailpit lists newest first, so paging stops at the first message older than the run; a page that comes back
    short is the end of the mailbox.
    """
    ids: list[str] = []
    start = 0
    while True:
        page = mailpit_json(mailpit, f"/api/v1/messages?start={start}&limit={MAILBOX_PAGE}").get("messages") or []
        for message in page:
            # `Created` is UTC (`2026-10-01T18:09:34.732Z`); seconds are the grain the run's stamp has anyway.
            created = calendar.timegm(time.strptime(message["Created"][:19], "%Y-%m-%dT%H:%M:%S"))
            if created < since:
                return ids
            ids.append(message["ID"])
        if len(page) < MAILBOX_PAGE:
            return ids
        start += MAILBOX_PAGE


def mail_texts(raw: bytes) -> list[tuple[str, str]]:
    """A message's DECODED text, as `(where, text)`: each header, then each text part — what a reader would see.

    Parsed by the standard library (`email`, `policy.default`), so an encoded-word is decoded, a quoted-printable or
    base64 part is decoded, and a soft line break is joined back up; no hand-written MIME parser to be wrong in.
    """
    message = email.message_from_bytes(raw, policy=email.policy.default)
    texts = [(f"header {name}", str(value)) for name, value in message.items()]
    for number, part in enumerate(message.walk(), start=1):
        if part.get_content_maintype() == "text":
            try:
                texts.append((f"part {number} {part.get_content_type()}", part.get_content()))
            except (LookupError, ValueError) as e:
                texts.append((f"part {number} {part.get_content_type()} (undecodable: {e})",
                              part.get_payload(decode=True).decode("utf-8", errors="replace")))
    return texts


def sweep_mailbox(stack: Stack, workdir: Path, canary_files: Iterable[Path], since: float, sentinel: str,
                  tokens: RunTokens = NO_TOKENS) -> str | None:
    """Prove no canary of the run is readable in any mail the run sent — read decoded, and raw.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). Every message Mailpit received from the run's start
    ([run_mail_ids]) is fetched raw, retained under `mailbox/`, and searched for the union of the run's canaries
    through [canary_needles] — in each decoded header and text part ([mail_texts]) and in the raw source. Armed by
    a mail the run plants itself ([plant_mail_sentinel]) whose sentinel must be found in the DECODED text; that it
    is absent from the raw source is printed, because that absence is what makes the decoded read load-bearing.
    Nothing in a run deletes mail (the suites take a cursor, `web/src/test/mailpit.ts`), so the read is complete.

    **The defect that bought it.** No incident. Mail is closed by construction — both kinds interpolate only
    `MailContext(link, domain, expiry)` (ADR 0049) — and the HTTP sweep already searches every mail a suite reads
    back. What this closes is the rest: mail no suite reads, and the one surface ADR 0049's boundary has no
    traffic-level sensor for — the one a third party, the tenant's SMTP provider, reads. A future mail kind is
    where a sealed name would slip in.

    **Its limit.** It sees what reached Mailpit, not what a production relay adds or keeps. The mailbox is shared
    with a developer's own mail of the same window, and Mailpit prunes its oldest messages past its cap — a run
    that pushed its own plant out fails on the control, loud. Non-text parts are searched only raw.

    **Tokens, with one exemption.** The run's capability tokens are searched too ([run_tokens]) — except a token the
    recording shows the suite FIRST read out of a mail (`RunTokens.mailed`): mail is that token's channel
    (member invite, recovery; ADR 0049/0054). Those hits are printed as exempted with the tokens they exempted;
    every other token in a mail is a hit.
    """
    carriers, problems = load_canaries(canary_files)
    if not carriers:
        return "the run's canary files hold no canary — a green mailbox sweep would mean nothing"
    needles = with_tokens(carriers, tokens)
    exempted: list[Hit] = []
    unmet: list[str] = list(problems)
    mailpit = stack.mailpit
    if mailpit is None:
        return "this stack names no mailbox — the mailbox sweep has nothing to read and proved nothing"
    try:
        ids = run_mail_ids(mailpit, since)
    except (urllib.error.URLError, OSError, KeyError, ValueError) as e:
        return f"the mailbox could not be listed at {mailpit} ({e}) — the mailbox sweep proved nothing"
    retained = workdir / "mailbox"
    retained.mkdir(exist_ok=True)
    hits: list[Hit] = []
    sentinel_decoded: list[str] = []
    sentinel_raw = False
    for message_id in ids:
        try:
            with urllib.request.urlopen(f"{mailpit}/api/v1/message/{message_id}/raw", timeout=10) as r:
                raw = r.read()
        except (urllib.error.URLError, OSError) as e:
            unmet.append(f"mail {message_id} could not be read raw ({e}) — it was not swept")
            continue
        (retained / f"{message_id}.eml").write_bytes(raw)
        found: list[Hit] = []
        for where, text in mail_texts(raw):
            found.extend(needle_hits(f"mail {message_id} {where}", text, needles))
            if needle_hits(where, text, [sentinel]):
                sentinel_decoded.append(where)
        source = raw.decode("utf-8", errors="replace")
        found.extend(needle_hits(f"mail {message_id} raw", source, needles))
        exempted.extend(h for h in found if h.canary in tokens.mailed)
        hits.extend(h for h in found if h.canary not in tokens.mailed)
        sentinel_raw = sentinel_raw or bool(needle_hits("raw", source, [sentinel]))
    if sentinel_decoded:
        armed = (f"planted sentinel found decoded in {', '.join(sentinel_decoded)}; in the raw source: "
                 f"{'FOUND — the transport did not encode it' if sentinel_raw else 'not found (the decoding is load-bearing)'}")
    else:
        armed = "planted sentinel NOT found"
        unmet.append(f"the planted mail's sentinel {sentinel!r} is NOT in any decoded mail of the run — the read "
                     f"missed it (window, paging, pruning) or the decoding is broken; this sweep proves nothing")
    log(f"mailbox sweep: {len(ids)} mail(s) since {int(since)}, {len(carriers)} canary/canaries, "
        f"{len(tokens.by_needle)} token(s), {len(hits)} hit(s); {armed}")
    exempt = sorted({h.canary for h in exempted})
    log(f"mailbox sweep: {len(exempt)} token(s) exempted — first read out of a mail, which is their channel "
        f"({kinds_of(tokens.by_needle[n] for n in exempt)}; {len(tokens.mailed)} such token(s) in the run):")
    for needle in exempt:
        log(f"    exempted {tokens.by_needle[needle].kind} {needle!r} ({', '.join(tokens.carriers.get(needle, []))})")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the mailbox — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the mailbox: {len(hits)} canary or token hit(s), {len(unmet)} unmet "
                f"control(s) across {len(ids)} mail(s) (listed above)")
    log(f"wire assertion: {len(carriers)} canary/canaries of the run appear in none of {len(ids)} mail(s), decoded "
        f"or raw; armed by the planted mail")
    return None

