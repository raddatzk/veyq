"""veyq's own Schemathesis checks — loaded by `infra/fuzz/schemathesis.toml` (`hooks`), run by `scripts/run_api_fuzz.py`.

Two rules a generic fuzzer does not know (ADR 0091 decision 9):

- **`refusal_by_location`** — a request the contract does not allow is refused the way veyq refuses: a malformed query,
  header or body with the contract's `400 ErrorResponse{code: invalid_request}`; a malformed path segment with `400` or
  `404` (a path id that is no UUID matches no route — `IdBoundarySliceTest`; a patterned segment may reach bean
  validation, and neither answer leaks anything, owner decision Q1 of cr-api-fuzzing). Which part a negative case
  negated is Schemathesis' own record (`case.meta.components`). The status half reuses the built-in
  `negative_data_rejection` with that per-location status set, so its exclusions — a "negation" that is valid once
  serialized, an additional property on a parameter — are the tool's, not a second copy here.
  **A credential is refused as a credential** (owner decision Q6): on an operation that takes a credential — a
  parameter or a request-body property the contract marks `x-veyq-visibility: token` (`X-Share-Password`,
  `X-Contributor-Token`, the moderation proof headers, a login signature, a refresh token, a mailed code) — the
  operation's own declared `401` or `403` is a correct refusal of any negative case: every request the run sends it
  carries a credential the run cannot forge, authentication runs before shape, and the refusal tells an
  unauthenticated caller nothing about the request's shape. The
  marker is the contract's own (the class `validate_field_visibility.py` holds and the e2e token sweep reads), so the
  rule narrows by what the contract says, never by an operation list kept here.
- **`refusal_echoes_nothing`** — no 4xx body carries a path, query or header value the request sent (six characters or
  more): the refused value is often ciphertext or an opaque id, and the operator-blind boundary applies to errors too
  (developer styleguide §7; `MalformedRequestSliceTest.assertRefused` holds the same rule for its cases).
- **`peer_is_refused`** — the second identity (ADR 0091 decision 9, inbox #495): every positive request the OWNER made
  in its own scope (a group only it is a member of) and the server accepted (2xx) is sent again as the PEER — a member
  of the same tenant, of none of the owner's scopes (`web/scripts/fuzz-seed.mts`) — and must be answered exactly as
  the same request against a scope nobody has: the same `404` and the same `code` (the membership boundary is the
  first guard, so a non-member and a bogus id are indistinguishable — `cc.security.authorization` § 2, ADR 0042), or,
  where the operation answers a bogus scope with a success (declining an invitation nobody sent is a no-op), the same
  success with the same body. Where the path also names one of the owner's objects (its Memory, medium, bucket, blob),
  the request is sent a second time as the peer IN ITS OWN SCOPE, still naming the owner's object, and must be answered
  as the same request naming an object nobody has — the object-level half (an id from another scope is not found, not
  forbidden). Writes are replayed as well as reads: a write the peer is wrongly let through is the finding, and it
  changes only the throwaway instance. Shares and tenant-level paths are out of its reach on purpose: a share id is a
  capability its holder may use, and the peer is a member of the tenant. The peer's token and the ids reach it by the
  environment (`VEYQ_FUZZ_PEER_TOKEN` by name; `VEYQ_FUZZ_IDS`, a path to the run's non-secret ids); without them it
  checks nothing. Bitten 2026-10-19 by hand: the owner's token in the peer's place reds the scope half, and with the
  scope half switched off and the owner's scope as the peer's own, the object half reds too.

This file runs inside the pinned Schemathesis image (its Python), and is still written in the syntax of the host's
Python 3.9 (`from __future__ import annotations`), because the repository's tooling reads every tracked `*.py` with it.
"""
from __future__ import annotations

import json
import os
import re
import sys

import schemathesis
from schemathesis.core.parameters import ParameterLocation
from schemathesis.specs.openapi._auth_retry import clone_case, send_probe
from schemathesis.specs.openapi.checks import negative_data_rejection

# TraceCov — the proprietary coverage plugin the official Schemathesis image loads on every run — is not in our image
# (`infra/fuzz/Dockerfile`); this makes "it never runs" a fact rather than a hope, whatever image runs this file.
if "tracecov" in sys.modules:
    raise RuntimeError("TraceCov was loaded — the API fuzzing runs only the MIT tool (infra/fuzz/Dockerfile)")

# Headers the transport or the run sets on every request; their values are no generated input.
TRANSPORT_HEADERS = {"content-type", "accept", "accept-encoding", "user-agent", "connection", "content-length",
                     "x-schemathesis-testcaseid"}
MIN_ECHO = 6


class _StatusConfig:
    """`negative_data_rejection`'s config, with the status set this case's location allows."""

    def __init__(self, statuses: list[str]) -> None:
        self.enabled = True
        self.expected_statuses = statuses


class _ChecksConfig:
    def __init__(self, config: object, statuses: list[str]) -> None:
        self._config = config
        self.negative_data_rejection = _StatusConfig(statuses)

    def __getattr__(self, name: str) -> object:
        return getattr(self._config, name)


class _Context:
    """The check context, seen through a narrowed `negative_data_rejection` config."""

    def __init__(self, ctx: object, statuses: list[str]) -> None:
        self._ctx = ctx
        self.config = _ChecksConfig(getattr(ctx, "config"), statuses)

    def __getattr__(self, name: str) -> object:
        return getattr(self._ctx, name)


def _negated(case: object, location: ParameterLocation) -> bool:
    info = case.meta.components.get(location)  # type: ignore[attr-defined]
    return info is not None and info.mode.is_negative


def _credential_operations() -> frozenset:
    """The operations that take a credential, as the runner derived them from the contracts (`x-veyq-visibility:
    token` on a parameter or a request-body property; `run_api_fuzz.credential_operations`)."""
    path = os.environ.get("VEYQ_FUZZ_CREDENTIALS")
    if not path:
        return frozenset()
    with open(path, encoding="utf-8") as f:
        return frozenset(json.load(f))


CREDENTIAL_OPERATIONS = _credential_operations()


def _credential_refusal(case: object, response: object) -> bool:
    """The declared 401/403 of an operation that takes a credential (Q6): every request the run sends it carries a
    credential the run cannot forge — a moderation proof is a signature by a key the run does not hold, a refresh token
    or a mailed code one it never saw — so whatever a case negates, an auth-first server refuses the credential first."""
    status = str(response.status_code)  # type: ignore[attr-defined]
    declared = {str(k) for k in (case.operation.definition.raw.get("responses") or {})}  # type: ignore[attr-defined]
    return status in ("401", "403") and status in declared and case.operation.label in CREDENTIAL_OPERATIONS  # type: ignore[attr-defined]


@schemathesis.check
def refusal_by_location(ctx, response, case):
    """A negated query, header or body answers 400 `invalid_request`; a negated path segment 400 or 404."""
    meta = case.meta
    if meta is None or not meta.generation.mode.is_negative:
        return None
    if _credential_refusal(case, response):
        return None
    statuses = ["400", "404"] if _negated(case, ParameterLocation.PATH) else ["400"]
    negative_data_rejection(_Context(ctx, statuses), response, case)
    if response.status_code == 400:
        try:
            body = json.loads(response.content or b"null")
        except ValueError:
            body = None
        code = body.get("code") if isinstance(body, dict) else None
        if code != "invalid_request":
            # The message names the code only when it is a code: it is the failure's signature in the baseline, so
            # it must not carry anything the response varies by.
            shown = code if isinstance(code, str) and re.fullmatch(r"[a-z_]{1,40}", code) else "no ErrorResponse code"
            raise AssertionError(
                "A refusal for the request's shape is the contract's `400 ErrorResponse{code: invalid_request}` "
                f"(developer styleguide §7); this one answered `{shown}`"
            )
    return None


def _sent_values(case: object) -> set[str]:
    values: set[str] = set()
    for container, skip in ((case.path_parameters, set()), (case.query, set()),  # type: ignore[attr-defined]
                            (case.headers, TRANSPORT_HEADERS)):  # type: ignore[attr-defined]
        for name, value in (container or {}).items():
            if name.lower() in skip:
                continue
            for item in value if isinstance(value, (list, tuple)) else [value]:
                if item is None or isinstance(item, bool):
                    continue
                text = item.decode("utf-8", "replace") if isinstance(item, bytes) else str(item)
                if len(text) >= MIN_ECHO:
                    values.add(text)
    return values


@schemathesis.check
def refusal_echoes_nothing(ctx, response, case):
    """No 4xx body contains a path, query or header value the request sent (six characters or more)."""
    if not 400 <= response.status_code < 500:
        return None
    body = (response.content or b"").decode("utf-8", "replace")
    if not body:
        return None
    for value in sorted(_sent_values(case)):
        # As sent, and as a JSON string would carry it (quotes and backslashes escaped).
        if value in body or json.dumps(value)[1:-1] in body:
            raise AssertionError(
                f"A {response.status_code} refusal echoes a value the request sent ({len(value)} characters) — "
                "a refusal echoes nothing it was sent (developer styleguide §7)"
            )
    return None


# --- the second identity -----------------------------------------------------------------------------------------

# An id nobody has: the reference a refusal to the peer is compared with. Fixed, so a run re-sends the same requests.
NOBODY = "00000000-0000-4000-8000-00000000f022"
# The owner's objects a path may name below its scope (the seeder's `ids`): the object-level half's targets.
OWNER_OBJECTS = ("nodeId", "mediaId", "bucketId", "blobId")


def _ids() -> dict:
    path = os.environ.get("VEYQ_FUZZ_IDS")
    if not path or not os.environ.get("VEYQ_FUZZ_PEER_TOKEN"):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


IDS = _ids()


def _code(response: object) -> object:
    try:
        body = json.loads(response.content or b"null")  # type: ignore[attr-defined]
    except ValueError:
        return None
    return body.get("code") if isinstance(body, dict) else None


def _as_peer(ctx: object, case: object, **path: str) -> object:
    """`case` sent again with the peer's token, and with the given path parameters replaced."""
    probe = clone_case(case)
    probe.headers["Authorization"] = f"Bearer {os.environ['VEYQ_FUZZ_PEER_TOKEN']}"  # type: ignore[attr-defined]
    probe.path_parameters.update(path)  # type: ignore[attr-defined]
    return send_probe(ctx, case, probe)  # type: ignore[arg-type]


def _answered_alike(got: object, reference: object) -> bool:
    """The same status, and the same `code` for a refusal or the same body for an acceptance: nothing tells them apart."""
    status = got.status_code  # type: ignore[attr-defined]
    if status != reference.status_code:  # type: ignore[attr-defined]
        return False
    if 200 <= status < 300:
        return (got.content or b"") == (reference.content or b"")  # type: ignore[attr-defined]
    return _code(got) == _code(reference)


@schemathesis.check
def peer_is_refused(ctx, response, case):
    """What the owner may do in its own scope, the peer is refused as if the scope (or the object) did not exist."""
    meta = case.meta
    if not IDS or meta is None or not meta.generation.mode.is_positive or not 200 <= response.status_code < 300:
        return None
    owner, peer = IDS["owner"], IDS["peer"]
    path = case.path_parameters or {}
    if path.get("scopeId") != owner["scopeId"]:
        return None
    got = _as_peer(ctx, case)
    reference = _as_peer(ctx, case, scopeId=NOBODY)
    if not _answered_alike(got, reference):
        raise AssertionError(
            f"The owner's request was accepted ({response.status_code}); sent again by a member of the tenant who is no "
            f"member of the owner's scope it answered {got.status_code} `{_shown(got)}`, where a scope nobody has "
            f"answers {reference.status_code} `{_shown(reference)}` — a non-member is refused like a bogus id, with a "
            "404, or the same no-op where the operation is one (cc.security.authorization § 2)"
        )
    named = {name: NOBODY for name in OWNER_OBJECTS if path.get(name) == owner.get(name)}
    if named:
        got = _as_peer(ctx, case, scopeId=peer["scopeId"])
        reference = _as_peer(ctx, case, scopeId=peer["scopeId"], **named)
        if not _answered_alike(got, reference):
            raise AssertionError(
                f"Sent by the peer in its own scope but naming the owner's {', '.join(sorted(named))}, the request "
                f"answered {got.status_code} `{_shown(got)}`, where the same request naming an object nobody has "
                f"answers {reference.status_code} `{_shown(reference)}` — an object of another scope is not found here"
            )
    return None


def _shown(response: object) -> str:
    """The `code` as the failure message may name it — never a value the response varies by (the baseline signature)."""
    code = _code(response)
    return code if isinstance(code, str) and re.fullmatch(r"[a-z_]{1,40}", code) else "no ErrorResponse code"
