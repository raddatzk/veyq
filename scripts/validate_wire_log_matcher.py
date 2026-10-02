#!/usr/bin/env python3
"""Guard the no-Docker half of the log and history wire assertions: their needles and armed-sensor controls.

**What it asserts.** Three things about `scripts/run_web_e2e.py`, imported by path and run over synthetic files in a
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

**The defect that bought it.** No incident — built with cr-wire-assertion-server-logs because the real canaries
cannot exercise most of the matcher: measured across every retained e2e run, 0 of 80 distinct canaries carry a
quote, a backslash, a newline or a non-ASCII character, so the JSON-escape spellings have no live instance to prove
them, and a run's green says nothing about whether those branches work. The statement-log half came with
cr-wire-assertion-history-and-mail for the same reason: no real canary carries an apostrophe, so the driver's
escaping has no live instance either, and the literal scope is what keeps `set autocommit=0` (380 times in one
measured suite) from reddening a canary `auto` on every run. This is changes/README § *split an
expensive live proof so its no-daemon half runs anywhere*: the half that needs no daemon is proven here, on every
trust-chain run.

**Its limit.** It proves the matcher and the controls over synthetic text. It does not prove that a real run reads
the right logs, that the JVM writes them as UTF-8 (`boot()` pins that; the e2e run is its only proof), that the
container logs are read over the right window, or that the server writes a value in a spelling modelled here —
`json-ascii` is Python's lower-case `\\uXXXX`, and an encoder writing upper-case hex would not be matched by it.
For the statement log it does not prove that the server writes the general log in the shape modelled here, that
the backend's driver still prepares client-side, or that the window really spans the run — the run's sentinels
and `Connect` controls prove those, live. Those are the `web-e2e` job's, which is the only enforcement of
everything downstream of these functions.
"""
from __future__ import annotations

import contextlib
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
            f.write("2026-10-01 08:23:00,000 ERROR [com.veyq] (worker) failed for Reisen%201790576564\n")

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


spellings = check_spellings()
cases = check_sweep()
literals = check_literals() + check_literal_scope()
statement_cases = check_statement_sweep()

if errors:
    print("WIRE LOG MATCHER VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"wire log matcher OK: {spellings} spelling(s) found under their variant; sweep_logs green on a clean "
      f"synthetic run and red, by name, on {cases - 1} unarmed or leaking case(s); statement log: {literals} "
      f"literal check(s), sweep_statements green when clean and red, by name, on {statement_cases} case(s)")
