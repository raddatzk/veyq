#!/usr/bin/env python3
"""Guard the no-Docker half of the log, statement-log, mailbox, token and pii wire assertions: needles, readers, controls.

**What it asserts.** Six things about `scripts/run_web_e2e.py`, imported by path and run over synthetic files in a
temporary directory — no Docker, no jar, nothing written into the repo:
  1. **Every spelling is found, under its own variant name.** For synthetic canaries carrying a space, a quote, a
     backslash, a newline, a non-ASCII character and the characters the two JavaScript URL encoders disagree on,
     each spelling a transport can give them — raw, JSON-escaped (both `ensure_ascii` forms), percent-encoded
     (`%20` and `+`) — is written into a synthetic log, and `needle_hits` must report that canary on that line
     under that variant. The expected spellings are LITERALS in this file, never computed by the code under test:
     a check that asked `canary_needles` what to look for would stay green with a whole branch of it deleted.
  2. **`sweep_logs` fails, naming the control, whenever the sensor is not armed** — an empty log, a log that never
     names its database (or names only a longer one, `veyq_e2e_10` for `veyq_e2e_1`), a log with no shutdown
     line, a log that is not UTF-8, a boot log whose name no rule maps to a database, and a directory with no boot
     log at all — and it reports a planted canary by file, line, variant and canary. Its green path is checked too
     (a complete synthetic run passes, with each boot-log name mapped to the database its boot really used), so
     this guard cannot pass by `sweep_logs` refusing everything.
  3. **The statement log is searched in its literals, in the driver's spelling, and its controls bite.** Each
     canary written the way MariaDB Connector/J writes a bound string (`\\\\`, `\\'`, `\\"` and NUL escaped, a newline
     raw — literals in this file again) is found inside a synthetic general-log entry under its variant, the
     apostrophe, raw-newline and NUL cases only through `sql-literal`; a canary outside any literal (`set
     autocommit`, a comment, a back-ticked identifier) is NOT reported, and an unclosed literal or a comment's
     apostrophe cannot throw the lexer out of step for the next entry. `sweep_statements` is green on a clean
     synthetic log and red, by name, on a planted canary, a missing start or end sentinel, a database no
     `Connect` names, a log that doubles its quotes (`NO_BACKSLASH_ESCAPES`) and a log that was never retained.
  4. **The mailbox is read decoded.** A hand-written multipart mail in the shapes the product's mail travels in — a
     folded `Q` encoded-word subject, a quoted-printable part whose soft line break splits a value, a base64 HTML
     part — is parsed by `mail_texts`, and each synthetic canary is found in the decoded header or part it is in;
     the raw source is checked to hide exactly the encoded ones, so the mail cannot quietly stop testing decoding.
  5. **Capability tokens are derived by marker, searched raw, and reported as `token:<kind>`.** A synthetic
     recording with one call per derivation rule — a body property, a `$ref` to the token-marked scalar
     `InviteBootstrapSecret`, a token-marked header parameter, a refresh-token `Set-Cookie` in `SessionCookie.issue`'s
     order (token first, clearing cookie second), a bare bearer, and a token first seen in a Mailpit read — is run
     through `token_needles` against the REAL contracts, and each token must come back under its literal kind, the
     clearing cookie yielding none and only the mailed token marked mailed; a backend call no operation matches is
     reported. `canary_needles`, `dump_escape` and `sql_literal` leave a base64url and a hex token unchanged (so the
     raw form is the only needle); a presigned URL's needle is its request target; a raw token planted in a boot log
     is reported as `token:accessToken`; and over a synthetic dump the hash control finds a token by its SHA-256
     while a raw copy is a hit.
  6. **The `pii` set is searched and controlled** (ADR 0092 § 4). A clean synthetic run with one address in a
     `pii-*.json` is green and counts it; the same address planted in a boot log is reported led by `pii` and named
     by its file; and `pii_control` accepts an address a retained mail holds, and refuses, by name, a run with no
     address and an address no mail holds.

**The defect that bought it.** No incident — built with cr-wire-assertion-server-logs because the real canaries
cannot exercise most of the matcher: measured across every retained e2e run, 0 of 80 distinct canaries carry a
quote, a backslash, a newline or a non-ASCII character, so the JSON-escape spellings have no live instance to prove
them, and a run's green says nothing about whether those branches work. The statement-log half came with
cr-wire-assertion-history-and-mail for the same reason: no real canary carries an apostrophe, so the driver's
escaping has no live instance either, and the literal scope is what keeps `set autocommit=0` (380 times in one
measured suite) from reddening a canary `auto` on every run. This is changes/README § *split an
expensive live proof so its no-daemon half runs anywhere*: the half that needs no daemon is proven here, on every
trust-chain run. The token half came with cr-wire-assertion-capability-tokens: no suite drives a pairing or a token
refresh, so two of the derivation rules (a header parameter, a token reached only through a `$ref`'d scalar) would
otherwise rest on whichever suites happen to exercise them, and the cookie trap (the clearing cookie sent last) on
nothing at all.

**Its limit.** For tokens it proves the derivation over a synthetic recording against the real contracts — not that
a real recording carries what the recorder should write (`web/src/test/wireRecorder.test.ts` pins that), not that the
server stores a hash where the hash control looks, and not the mailbox or store-log exemptions, which need a live
Mailpit and store. It proves the matcher and the controls over synthetic text. It does not prove that a real run reads
the right logs, that the JVM writes them as UTF-8 (`boot()` pins that; the e2e run is its only proof), that the
container logs are read over the right window, or that the server writes a value in a spelling modelled here —
`json-ascii` is Python's lower-case `\\uXXXX`, and an encoder writing upper-case hex would not be matched by it.
For the mailbox it proves the reader, not `sweep_mailbox`'s paging over Mailpit's API or its planted control —
those need a live Mailpit. For the statement log it does not prove that the server writes the general log in the shape modelled here, that
the backend's driver still prepares client-side, or that the window really spans the run — the run's sentinels
and `Connect` controls prove those, live. Those are the `web-e2e` job's, which is the only enforcement of
everything downstream of these functions.
"""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ORCHESTRATOR = ROOT / "scripts" / "run_web_e2e.py"

errors: list[str] = []


def load(path: pathlib.Path):
    spec = importlib.util.spec_from_file_location("veyq_run_web_e2e_log_matcher", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"WIRE LOG MATCHER VALIDATION FAILED:\n  - cannot import {path.relative_to(ROOT)}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


orchestrator = load(ORCHESTRATOR)

# (canary, a spelling of it a transport produces, the variant that spelling must be reported under).
# Written by hand. `json` is absent where it equals `raw` (no quote, backslash or control character), and the
# de-duplication reports such a spelling as `raw`, which is listed instead.
SPELLINGS = [
    ("Grüße aus Köln", "Grüße aus Köln", "raw"),
    ("Grüße aus Köln", "Gr\\u00fc\\u00dfe aus K\\u00f6ln", "json-ascii"),
    ("Grüße aus Köln", "Gr%C3%BC%C3%9Fe%20aus%20K%C3%B6ln", "pct"),
    ("Grüße aus Köln", "Gr%C3%BC%C3%9Fe+aus+K%C3%B6ln", "pct-plus"),
    ('say "cheese"', 'say "cheese"', "raw"),
    ('say "cheese"', 'say \\"cheese\\"', "json"),
    ('say "cheese"', "say%20%22cheese%22", "pct"),
    ('say "cheese"', "say+%22cheese%22", "pct-plus"),
    ("C:\\temp dir", "C:\\temp dir", "raw"),
    ("C:\\temp dir", "C:\\\\temp dir", "json"),
    ("C:\\temp dir", "C%3A%5Ctemp%20dir", "pct"),
    ("line one\nline two", "line one\nline two", "raw"),
    ("line one\nline two", "line one\\nline two", "json"),
    ("line one\nline two", "line%20one%0Aline%20two", "pct"),
    # The characters `encodeURIComponent` leaves alone and a form encodes: the two URL variants must disagree here.
    ("it's (fine)!", "it's%20(fine)!", "pct"),
    ("it's (fine)!", "it%27s+%28fine%29%21", "pct-plus"),
]


def check_spellings() -> int:
    """1 — every hand-written spelling is found, on its line, under its variant."""
    for canary, spelling, variant in SPELLINGS:
        text = f"2026-10-01 INFO first line\n2026-10-01 WARN value={spelling} end\n2026-10-01 INFO last line\n"
        hits = orchestrator.needle_hits("synthetic.log", text, [canary])
        matching = [h for h in hits if h.canary == canary and h.line_no == 2]
        if not matching:
            errors.append(f"variant {variant!r} of {canary!r}, written as {spelling!r}, was NOT found "
                          f"(needle_hits returned {[(h.line_no, h.variant) for h in hits]})")
        elif matching[0].variant != variant:
            errors.append(f"{spelling!r} was found as {matching[0].variant!r}, expected variant {variant!r}")
    # And a canary that is not in the text is not reported: a matcher that returns everything also "finds" it.
    if orchestrator.needle_hits("synthetic.log", "nothing to see here\n", ["Grüße aus Köln"]):
        errors.append("needle_hits reported a canary that is not in the text")
    return len(SPELLINGS)


def boot_log(database: str, extra: str = "", *, shutdown: bool = True) -> str:
    """A boot log shaped like a real one: Flyway's JDBC line, a schema line, the shutdown line."""
    lines = [
        "2026-10-01 08:22:54,110 INFO  [org.flywaydb.core.FlywayExecutor] (main) "
        f"Database: jdbc:mariadb://127.0.0.1/{database}?user=veyq&password=******** (MariaDB 11.4)",
        f"2026-10-01 08:22:54,180 INFO  [org.flywaydb.core.internal.command.DbMigrate] (main) Current version of "
        f"schema `{database}`: << Empty Schema >>",
        "2026-10-01 08:22:55,001 INFO  [io.quarkus] (main) veyq-backend started in 2.3s — admin listener up",
    ]
    if extra:
        lines.append(extra)
    if shutdown:
        lines.append("2026-10-01 08:23:12,818 INFO  [io.quarkus] (Shutdown thread) veyq-backend stopped in 0.018s")
    return "\n".join(lines) + "\n"


def sweep(workdir: pathlib.Path) -> tuple[str | None, str]:
    """Run the real `sweep_logs` over `workdir`, returning its verdict and everything it printed."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        verdict = orchestrator.sweep_logs(workdir, sorted(workdir.rglob("canaries-*.json")))
    return verdict, out.getvalue()


def synthetic_run(root: pathlib.Path) -> pathlib.Path:
    """A complete, clean run directory: every boot-log shape the orchestrator writes, each naming its database."""
    run = root / "run"
    group = run / "group-2-restore"
    group.mkdir(parents=True)
    (run / "backend-store-init.log").write_text(boot_log("veyq_e2e_store_init"), encoding="utf-8")
    (run / "backend-1.log").write_text(boot_log("veyq_e2e_1"), encoding="utf-8")
    (run / "backend-2.log").write_text(boot_log("veyq_e2e_2"), encoding="utf-8")
    (group / "backend-install-2.log").write_text(boot_log("veyq_e2e_restore_2"), encoding="utf-8")
    (run / "backend-2-2.log").write_text(boot_log("veyq_e2e_restore_2"), encoding="utf-8")
    (run / "canaries-1.json").write_text(json.dumps(["Reisen 1790576564", "auto"]), encoding="utf-8")
    (run / "canaries-2.json").write_text(json.dumps(["Grüße aus Köln"]), encoding="utf-8")
    return run


def expect_red(case: str, mutate, *must_name: str) -> None:
    """Apply `mutate` to a fresh synthetic run; the sweep must fail and its verdict + output must name each string."""
    with tempfile.TemporaryDirectory(prefix="veyq-wire-log-guard-") as tmp:
        run = synthetic_run(pathlib.Path(tmp))
        target = mutate(run)
        verdict, printed = sweep(target or run)
        if verdict is None:
            errors.append(f"{case}: sweep_logs passed — the unarmed sensor was scored as a clean log")
            return
        for name in must_name:
            if name not in verdict + printed:
                errors.append(f"{case}: sweep_logs failed without naming {name!r} — verdict: {verdict!r}")


def check_sweep() -> int:
    """2 — the green path, then every control red by name."""
    with tempfile.TemporaryDirectory(prefix="veyq-wire-log-guard-") as tmp:
        verdict, printed = sweep(synthetic_run(pathlib.Path(tmp)))
        if verdict is not None:
            errors.append(f"a complete, clean synthetic run did not pass: {verdict!r}\n{printed}")
        for name in ("veyq_e2e_store_init", "veyq_e2e_1", "veyq_e2e_2", "veyq_e2e_restore_2"):
            if f"database {name}" not in printed:
                errors.append(f"the clean run never reported the database control {name!r} as met")

    def plant(run: pathlib.Path) -> None:
        with (run / "backend-1.log").open("a", encoding="utf-8") as f:
            f.write("2026-10-01 08:23:00,000 ERROR [me.raddatz.veyq] (worker) failed for Reisen%201790576564\n")

    def write(name: str, body: bytes):
        def mutate(run: pathlib.Path) -> None:
            (run / name).write_bytes(body)
        return mutate

    def empty_directory(run: pathlib.Path) -> pathlib.Path:
        """The one case whose lever is WHERE the sweep points, not what a log holds."""
        empty = run.parent / "empty"
        empty.mkdir()
        return empty

    def strip(name: str, marker: str):
        def mutate(run: pathlib.Path) -> None:
            path = run / name
            path.write_text("".join(l for l in path.read_text(encoding="utf-8").splitlines(keepends=True)
                                    if marker not in l), encoding="utf-8")
        return mutate

    cases = [
        ("a planted canary", plant, "backend-1.log:5", "[pct]", "'Reisen 1790576564'", "canaries-1.json"),
        ("an empty log", write("backend-1.log", b""), "backend-1.log", "EMPTY"),
        ("a log without its Database: line", strip("backend-1.log", "Database:"),
         "backend-1.log", "never names its database veyq_e2e_1"),
        ("a log naming only a longer database", write("backend-1.log", boot_log("veyq_e2e_10").encode()),
         "never names its database veyq_e2e_1"),
        ("a log without its shutdown line", strip("backend-2-2.log", "stopped in"),
         "backend-2-2.log", "no shutdown line"),
        ("a log that is not UTF-8", write("backend-1.log", boot_log("veyq_e2e_1").encode() + b"Gr\xf6\xdfe\n"),
         "backend-1.log", "not UTF-8"),
        ("a boot log no rule maps", write("backend-extra.log", boot_log("veyq_e2e_1").encode()),
         "backend-extra.log", "no rule derives"),
        ("a directory with no boot log", empty_directory, "no boot log"),
    ]
    for case, mutate, *names in cases:
        expect_red(case, mutate, *names)
    return len(cases) + 1


# ---- 3 · the statement log ---------------------------------------------------------------------------------

# A general-log preamble and entry shapes copied from a real mariadb:11.4 log (tabs and the right-aligned id
# included): the first entry of a second carries the time, the rest two tabs.
GENERAL_LOG_HEAD = ("mariadbd, Version: 11.4.13-MariaDB-ubu2404 (mariadb.org binary distribution). started with:\n"
                    "Tcp port: 3306  Unix socket: /run/mysqld/mysqld.sock\n"
                    "Time\t\t    Id Command\tArgument\n")
STATEMENT_START = "veyq statement-log sentinel 1790879108 start: it's Größe"
STATEMENT_END = "veyq statement-log sentinel 1790879108 end: it's Größe"


def entry(command: str, argument: str, conn: int = 11542, time: str = "") -> str:
    return f"{time}\t\t {conn} {command}\t{argument}\n" if not time else f"{time}\t {conn} {command}\t{argument}\n"


# (canary, how the DRIVER writes it inside a literal, the variant it must be reported under). Hand-written from
# the measured escaping (`\\`, `\'`, `\"`, NUL), never computed by `sql_literal`. Where the driver's form equals
# the JSON one (a quote, a backslash), the de-duplication reports it as `json`; the apostrophe, the raw newline
# and the raw NUL are the cases only `sql-literal` can find.
LITERALS = [
    ("it's (fine)!", "it\\'s (fine)!", "sql-literal"),
    ("line one\nit's two", "line one\nit\\'s two", "sql-literal"),
    ("nul\0byte", "nul\\\0byte", "sql-literal"),
    ("Grüße aus Köln", "Grüße aus Köln", "raw"),
    ('say "cheese"', 'say \\"cheese\\"', "json"),
    ("C:\\temp dir", "C:\\\\temp dir", "json"),
]


def check_literals() -> int:
    """3a — the driver's spelling of each canary, inside a literal, is found on its line under its variant."""
    for canary, written, variant in LITERALS:
        text = (GENERAL_LOG_HEAD + entry("Connect", "veyq@192.168.65.1 on veyq_e2e_1 using TCP/IP", time="261001 18:26:17")
                + entry("Query", "set autocommit=0")
                + entry("Query", f"update veyq_tenant set name='{written}' where id='b77e'")
                + entry("Query", "commit"))
        hits, _ = orchestrator.literal_hits("general.log", text, [canary])
        if not hits:
            errors.append(f"statement log: variant {variant!r} of {canary!r}, written by the driver as {written!r}, "
                          f"was NOT found")
        elif hits[0].line_no != 6:
            errors.append(f"statement log: {canary!r} reported on line {hits[0].line_no}, expected 6")
        elif hits[0].variant != variant:
            errors.append(f"statement log: {written!r} was found as {hits[0].variant!r}, expected {variant!r}")
    return len(LITERALS)


def check_literal_scope() -> int:
    """3b — only literals are searched, and the lexer cannot be thrown out of step by what surrounds them."""
    text = (GENERAL_LOG_HEAD
            + entry("Query", "set autocommit=0", time="261001 18:26:17")                      # line 4: SQL, not a value
            + entry("Query", "-- keeps the range, it's a comment\nCREATE TABLE `city` (x INT) /* auto */")
            + entry("Query", "select 'Boulder gym' from dual # it's a comment")              # line 7: a literal
            + entry("Query", "insert into t values ('never closed")                          # line 8: left open
            + entry("Query", "select auto from t where c = \"double quoted city\""))        # line 9: a "-literal
    hits, literals = orchestrator.literal_hits("general.log", text, ["auto", "range", "city", "Boulder gym"])
    found = sorted((h.line_no, h.canary) for h in hits)
    expected = [(7, "Boulder gym"), (9, "city")]
    if found != expected:
        errors.append(f"statement log scope: expected only {expected} (literals), got {found} — SQL outside a "
                      f"literal was searched, or a literal was missed")
    if literals != 3:
        errors.append(f"statement log scope: expected 3 literals, the lexer found {literals} — an unclosed literal "
                      f"ran past its entry, or a comment's apostrophe opened one")
    return 2


def statement_run(root: pathlib.Path, *, start: bool = True, end: bool = True, plant: str = "", quote: str = "\\'",
                  databases: tuple[str, ...] = ("veyq_e2e_store_init", "veyq_e2e_1")) -> pathlib.Path:
    """A complete, clean statement log and the canary file of its run."""
    run = root / "run"
    run.mkdir()
    body = GENERAL_LOG_HEAD
    if start:
        body += entry("Query", f"DO '{STATEMENT_START.replace(chr(39), quote)}'", 11503, "261001 18:25:11")
    for i, database in enumerate(databases):
        body += entry("Connect", f"veyq@192.168.65.1 on {database} using TCP/IP", 11505 + i)
        body += entry("Query", "set autocommit=0", 11505 + i)
    if plant:
        body += entry("Query", f"UPDATE veyq_tenant SET name = '{plant}'", 11560)
    if end:
        body += entry("Query", f"DO '{STATEMENT_END.replace(chr(39), quote)}'", 11600)
    (run / "mariadb-general.log").write_text(body, encoding="utf-8")
    (run / "canaries-1.json").write_text(json.dumps(["E2E place 1790879177219", "auto"]), encoding="utf-8")
    return run


def sweep_statements(run: pathlib.Path, databases=("veyq_e2e_store_init", "veyq_e2e_1"),
                     sentinels=(STATEMENT_START, STATEMENT_END)) -> tuple[str | None, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        verdict = orchestrator.sweep_statements(run / "mariadb-general.log", sorted(run.glob("canaries-*.json")),
                                                list(sentinels), list(databases))
    return verdict, out.getvalue()


def check_statement_sweep() -> int:
    """3c — `sweep_statements` green on a clean log, red by name on a hit and on every unarmed control."""
    cases = [
        ("a planted canary", dict(plant="E2E place 1790879177219"), {},
         ("mariadb-general.log:", "[raw]", "'E2E place 1790879177219'", "canaries-1.json")),
        ("no start sentinel", dict(start=False), {}, ("sentinel", "start", "NOT in the statement log")),
        ("no end sentinel", dict(end=False), {}, ("sentinel", "end", "NOT in the statement log")),
        ("a database no Connect names", dict(databases=("veyq_e2e_store_init", "veyq_e2e_10")), {},
         ("no `Connect` entry names veyq_e2e_1",)),
        # The server under `NO_BACKSLASH_ESCAPES` doubles a quote instead: the escape model no longer holds.
        ("a log doubling its quotes", dict(quote="''"), {}, ("NOT in the statement log", "start", "end")),
        ("no statement log at all", None, {}, ("no statement log was retained",)),
    ]
    with tempfile.TemporaryDirectory(prefix="veyq-wire-statement-guard-") as tmp:
        verdict, printed = sweep_statements(statement_run(pathlib.Path(tmp)))
        if verdict is not None:
            errors.append(f"a clean synthetic statement log did not pass: {verdict!r}\n{printed}")
        elif "through sql-literal" not in printed or "a Connect for each of 2 database(s)" not in printed:
            errors.append(f"the clean statement log never reported its controls as met:\n{printed}")
    for case, shape, sweep_args, must_name in cases:
        with tempfile.TemporaryDirectory(prefix="veyq-wire-statement-guard-") as tmp:
            run = statement_run(pathlib.Path(tmp), **(shape or {}))
            if shape is None:
                (run / "mariadb-general.log").unlink()
            verdict, printed = sweep_statements(run, **sweep_args)
            if verdict is None:
                errors.append(f"statement log, {case}: sweep_statements passed")
                continue
            for name in must_name:
                if name not in verdict + printed:
                    errors.append(f"statement log, {case}: failed without naming {name!r} — {verdict!r}")
    return len(cases)


# ---- 4 · the mailbox ---------------------------------------------------------------------------------------

# A multipart mail written BY HAND in the shapes the product's mail travels in (measured on a recovery mail from
# Vert.x mail): a `Q` encoded-word subject folded over two lines, a quoted-printable part whose soft line break
# splits an ASCII value, and a base64 HTML part. Never generated by the `email` package the reader uses, so the
# reader cannot agree with itself.
SYNTHETIC_MAIL = (
    b"Return-Path: <noreply@veyq.localhost>\r\n"
    b"MIME-Version: 1.0\r\n"
    b"Subject: =?UTF-8?Q?Gr=C3=BC=C3=9Fe_aus_K=C3=B6ln_/_say_=22cheese=22?=\r\n"
    b" =?UTF-8?Q?_tail?=\r\n"
    b"From: noreply@veyq.localhost\r\n"
    b"To: a-1790879499@e2e.test\r\n"
    b"Content-Type: multipart/alternative; boundary=\"b1\"\r\n"
    b"\r\n"
    b"--b1\r\n"
    b"Content-Type: text/plain; charset=utf-8\r\n"
    b"Content-Transfer-Encoding: quoted-printable\r\n"
    b"\r\n"
    b"Open this link: https://veyq.localhost/x#E2E place 17908=\r\n"
    b"79177219 and C:\\temp dir, line one\r\n"
    b"it's two\r\n"
    b"--b1\r\n"
    b"Content-Type: text/html; charset=utf-8\r\n"
    b"Content-Transfer-Encoding: base64\r\n"
    b"\r\n"
    b"PHA+VHJhdXpldWdpbiBNYXRoaWxkZTwvcD4=\r\n"
    b"--b1--\r\n"
)
# (canary, the decoded section it must be found in, whether a raw search finds it too)
MAIL_CANARIES = [
    ("Grüße aus Köln", "header Subject", False),                 # encoded-word, non-ASCII
    ('say "cheese"', "header Subject", False),                    # encoded-word, a quote as =22
    ("E2E place 1790879177219", "text/plain", False),             # split by a quoted-printable soft line break
    ("C:\\temp dir", "text/plain", True),                         # a backslash, plain ASCII on one line
    ("line one\r\nit's two", "text/plain", True),                 # a newline (CRLF on the wire) and a quote
    ("Trauzeugin Mathilde", "text/html", False),                  # base64
]


def check_mail() -> int:
    """4 — the decoded read finds what only decoding can reveal; the raw source alone does not."""
    texts = orchestrator.mail_texts(SYNTHETIC_MAIL)
    for canary, section, in_raw in MAIL_CANARIES:
        where = [w for w, text in texts if orchestrator.needle_hits(w, text, [canary])]
        if not any(section in w for w in where):
            errors.append(f"mail: {canary!r} was NOT found decoded in the {section} (found in {where}) — the "
                          f"MIME reader missed what the mail carries")
        raw = bool(orchestrator.needle_hits("raw", SYNTHETIC_MAIL.decode("utf-8"), [canary]))
        if raw != in_raw:
            errors.append(f"mail: {canary!r} {'was' if raw else 'was NOT'} found in the raw source, expected "
                          f"{'found' if in_raw else 'NOT found'} — the synthetic mail no longer encodes what it claims")
    return len(MAIL_CANARIES)


# ---- 5 · capability tokens ----------------------------------------------------------------------------------

# Hand-written token values in the shapes the server mints (`Tokens.random()`: 32 bytes, base64url, no padding) — and
# one hex value, the other alphabet a token-classed value has. Literals, never computed by the code under test.
ACCESS = "aCc3sS-t0ken_vAlue-0123456789abcdefghijklmno"
ADMIN_ACCESS = "aDm1n-bearer_0123456789abcdefghijklmnopqrstu"
REFRESH = "r3fResh-c00kie_0123456789abcdefghijklmnopqr"
BOOTSTRAP = "b00tStrap-s3cret_0123456789abcdefghijklmnop"
RECOVERY = "rEc0very-t0ken_0123456789abcdefghijklmnopqr"
CONTRIBUTOR = "c0ntrib-hdr_0123456789abcdefghijklmnopqrstu"
HEX_TOKEN = "02f271ff9c3d4e5a6b7c8d9e0f1a2b3c"

# A synthetic recording in the recorder's own shape, one call per derivation rule, written in `SessionCookie.issue`'s
# cookie order. The recovery token is first seen in a Mailpit read — the mailbox exemption's case.
RECORDING = [
    {"method": "GET", "url": "http://127.0.0.1:18025/api/v1/message/m1", "requestHeaders": {},
     "requestBody": None, "status": 200, "responseHeaders": {"content-type": "application/json"},
     "responseBody": json.dumps({"Text": f"Reset: https://veyq.localhost/recover#{RECOVERY}"})},
    {"method": "POST", "url": "http://veyq.localhost:8080/api/recovery/release", "requestHeaders": {},
     "requestBody": json.dumps({"token": RECOVERY}), "status": 200, "responseHeaders": {}, "responseBody": "{}"},
    {"method": "POST", "url": "http://veyq.localhost:8080/api/login/verify", "requestHeaders": {},
     "requestBody": "{}", "status": 200,
     "responseHeaders": {"content-type": "application/json", "set-cookie": [
         f"veyq_refresh_session={REFRESH}; Path=/api/token/refresh; HttpOnly; SameSite=Strict",
         "veyq_refresh=; Path=/api/token/refresh; Max-Age=0; HttpOnly; SameSite=Strict"]},
     "responseBody": json.dumps({"accessToken": ACCESS, "familyId": "f1"})},
    {"method": "GET", "url": "http://veyq.localhost:8080/api/scopes", "requestHeaders": {
        "Authorization": f"Bearer {ACCESS}"}, "requestBody": None, "status": 200, "responseHeaders": {},
     "responseBody": "[]"},
    {"method": "POST", "url": "http://127.0.0.1:8090/api/tenants", "requestHeaders": {
        "Authorization": f"Bearer {ADMIN_ACCESS}"}, "requestBody": "{}", "status": 201, "responseHeaders": {},
     "responseBody": json.dumps({"tenantId": "t1", "bootstrapSecret": BOOTSTRAP, "delivery": "SENT"})},
    {"method": "DELETE", "url": "http://veyq.localhost:8080/api/shares/s1/media/m1", "requestHeaders": {
        "X-Contributor-Token": CONTRIBUTOR}, "requestBody": None, "status": 204, "responseHeaders": {},
     "responseBody": None},
    {"method": "PUT", "url": "http://localhost:19000/blobs/k1?X-Amz-Signature=abc", "requestHeaders": {},
     "requestBody": "[binary 10 bytes — swept at the store, not here]", "status": 200, "responseHeaders": {},
     "responseBody": None},
]
# (token, the kind it must be derived under) — what each rule above must yield, and nothing else.
EXPECTED_TOKENS = {
    RECOVERY: "token",                       # a body property marked token
    REFRESH: "Set-Cookie",                   # a token-marked response header: the cookie value, the clearing one not
    ACCESS: "accessToken",                   # a body property; its later bearer use does not rename it
    ADMIN_ACCESS: "Authorization",           # seen only as a bearer
    BOOTSTRAP: "bootstrapSecret",            # a `$ref` to the token-marked scalar `InviteBootstrapSecret`
    CONTRIBUTOR: "X-Contributor-Token",      # a token-marked header parameter
}


def check_tokens() -> int:
    """5 — the second needle set: its derivation by marker, its needle shape, and the sweeps reporting it as `token:`."""
    for token in (ACCESS, REFRESH, HEX_TOKEN):
        # D1: a token has no space, quote or backslash, so every text surface's spelling of it IS the raw form.
        if orchestrator.canary_needles(token) != [("raw", token)]:
            errors.append(f"tokens: canary_needles({token!r}) is {orchestrator.canary_needles(token)!r}, not the raw "
                          f"form alone — a token would be searched in spellings no transport gives it")
        if orchestrator.dump_escape(token) != token or orchestrator.sql_literal(token) != token:
            errors.append(f"tokens: {token!r} is escaped by the dump or the driver — the raw needle would miss it")
    # D2a: the cookie values are base64url, so the value is the token as minted; the clearing cookie yields none.
    if orchestrator.set_cookie_values(RECORDING[2]) != [REFRESH]:
        errors.append(f"tokens: set_cookie_values gave {orchestrator.set_cookie_values(RECORDING[2])!r}, expected "
                      f"[{REFRESH!r}] — the token cookie first, the clearing cookie dropped")
    if orchestrator.token_needle("http://localhost:19000/blobs/k1?X-Amz-Signature=abc") != "/blobs/k1?X-Amz-Signature=abc":
        errors.append("tokens: a presigned URL's needle is not its request target — the store logs only that")

    with tempfile.TemporaryDirectory(prefix="veyq-wire-token-guard-") as tmp:
        run = synthetic_run(pathlib.Path(tmp))
        record = run / "http-1.jsonl"
        record.write_text("".join(json.dumps(e) + "\n" for e in RECORDING), encoding="utf-8")
        tokens, problems, mailed = orchestrator.token_needles(record)
        derived = {value: token.kind for value, token in tokens.items()}
        if derived != EXPECTED_TOKENS:
            errors.append(f"tokens: derived {derived!r}, expected {EXPECTED_TOKENS!r}")
        if problems:
            errors.append(f"tokens: a recording whose backend calls all exist in the contract reported {problems!r}")
        if mailed != {RECOVERY}:
            errors.append(f"tokens: first-read-out-of-a-mail is {mailed!r}, expected only the recovery token")
        with record.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"method": "GET", "url": "http://veyq.localhost:8080/api/no-such-operation",
                                "requestHeaders": {}, "requestBody": None, "status": 404, "responseHeaders": {},
                                "responseBody": None}) + "\n")
        if not any("/api/no-such-operation" in p for p in orchestrator.token_needles(record)[1]):
            errors.append("tokens: a backend call no operation matches was not reported — its tokens would be unseen")

        run_tokens = orchestrator.run_tokens(run)
        with (run / "backend-1.log").open("a", encoding="utf-8") as f:
            f.write(f"2026-10-01 08:23:00,000 WARN  [me.raddatz.veyq] (worker) refused bearer {ACCESS}\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            verdict = orchestrator.sweep_logs(run, sorted(run.glob("canaries-*.json")), run_tokens)
        if verdict is None or "token:accessToken" not in out.getvalue():
            errors.append(f"tokens: a raw access token in a boot log was not reported as token:accessToken "
                          f"({verdict!r})")

    digest = hashlib.sha256(ACCESS.encode()).hexdigest()
    dump = (f"INSERT INTO `veyq_access_token` VALUES ('a1','{digest}','2026-10-01');\n"
            f"INSERT INTO `veyq_note` VALUES ('n1','{BOOTSTRAP}');\n")
    hits, hashed = orchestrator.db_token_findings(dump, {v: orchestrator.Token(k, v) for v, k in EXPECTED_TOKENS.items()})
    if hits != [(BOOTSTRAP, "veyq_note")]:
        errors.append(f"tokens: the dump's raw hits are {hits!r}, expected the planted bootstrap secret in veyq_note")
    if [t.kind for t in hashed] != ["accessToken"]:
        errors.append(f"tokens: the hash control found {[t.kind for t in hashed]!r}, expected the access token's SHA-256")
    return len(EXPECTED_TOKENS)


def check_pii() -> int:
    """6 — the `pii` set: a planted address is a hit led by `pii` and named by its file; its control bites three ways."""
    address = "a-1790576564@e2e.test"
    checks = 0
    with tempfile.TemporaryDirectory(prefix="veyq-wire-log-guard-") as tmp:
        run = synthetic_run(pathlib.Path(tmp))
        (run / "pii-1.json").write_text(json.dumps([address]), encoding="utf-8")
        pii, problems = orchestrator.sweeps.load_pii(sorted(run.glob("pii-*.json")))
        canaries = sorted(run.glob("canaries-*.json"))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            clean = orchestrator.sweep_logs(run, canaries, orchestrator.NO_TOKENS, pii)
        if problems or clean is not None or "1 pii" not in out.getvalue():
            errors.append(f"pii: a clean run with one address was not green, or did not count it ({problems!r}, {clean!r})")
        with (run / "backend-1.log").open("a", encoding="utf-8") as f:
            f.write(f"2026-10-01 08:23:00,000 WARN  [me.raddatz.veyq] (worker) invite for {address} was NOT delivered\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            verdict = orchestrator.sweep_logs(run, canaries, orchestrator.NO_TOKENS, pii)
        if verdict is None or f"pii {address!r}" not in out.getvalue() or "pii-1.json" not in out.getvalue():
            errors.append(f"pii: an address in a boot log was not reported led by `pii` and named by its file ({verdict!r})")
        checks += 2

        mail = run / "mailbox" / "m1.eml"
        mail.parent.mkdir()
        mail.write_text(f"To: {address}\nSubject: x\n\nbody\n", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            found = orchestrator.sweeps.pii_control(sorted(run.glob("pii-*.json")), [mail], "the mails")
            empty = orchestrator.sweeps.pii_control([], [mail], "the mails")
            mail.write_text("To: someone-else@e2e.test\n\nbody\n", encoding="utf-8")
            missing = orchestrator.sweeps.pii_control(sorted(run.glob("pii-*.json")), [mail], "the mails")
        if found is not None:
            errors.append(f"pii control: an address the mails hold was not accepted ({found!r})")
        if empty is None or "no pii" not in empty:
            errors.append(f"pii control: a run with no address was not refused ({empty!r})")
        if missing is None or address not in missing:
            errors.append(f"pii control: an address no mail holds was not refused by name ({missing!r})")
        checks += 3
    return checks


spellings = check_spellings()
cases = check_sweep()
pii_checks = check_pii()
literals = check_literals() + check_literal_scope()
statement_cases = check_statement_sweep()
mails = check_mail()
token_rules = check_tokens()

if errors:
    print("WIRE LOG MATCHER VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"wire log matcher OK: {spellings} spelling(s) found under their variant; sweep_logs green on a clean "
      f"synthetic run and red, by name, on {cases - 1} unarmed or leaking case(s); statement log: {literals} "
      f"literal check(s), sweep_statements green when clean and red, by name, on {statement_cases} case(s); mailbox: {mails} "
      f"canaries found by the decoded read; tokens: {token_rules} derivation rule(s) by marker, raw-only needles, "
      f"the hash control and a token: hit; pii: {pii_checks} check(s) of the address set and its control")
