#!/usr/bin/env python3
"""Guard the no-Docker half of the server-log wire assertion: its needles and its armed-sensor controls.

**What it asserts.** Two things about `scripts/run_web_e2e.py`, imported by path and run over synthetic files in a
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

**The defect that bought it.** No incident — built with cr-wire-assertion-server-logs because the real canaries
cannot exercise most of the matcher: measured across every retained e2e run, 0 of 80 distinct canaries carry a
quote, a backslash, a newline or a non-ASCII character, so the JSON-escape spellings have no live instance to prove
them, and a run's green says nothing about whether those branches work. This is changes/README § *split an
expensive live proof so its no-daemon half runs anywhere*: the half that needs no daemon is proven here, on every
trust-chain run.

**Its limit.** It proves the matcher and the controls over synthetic text. It does not prove that a real run reads
the right logs, that the JVM writes them as UTF-8 (`boot()` pins that; the e2e run is its only proof), that the
container logs are read over the right window, or that the server writes a value in a spelling modelled here —
`json-ascii` is Python's lower-case `\\uXXXX`, and an encoder writing upper-case hex would not be matched by it.
Those are the `web-e2e` job's, which is the only enforcement of everything downstream of these functions.
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


spellings = check_spellings()
cases = check_sweep()

if errors:
    print("WIRE LOG MATCHER VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"wire log matcher OK: {spellings} spelling(s) found under their variant; sweep_logs green on a clean "
      f"synthetic run and red, by name, on {cases - 1} unarmed or leaking case(s)")
