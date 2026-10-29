#!/usr/bin/env python3
"""What holds the upload's main thread: S2's runs of `profile_hot_paths.py`, read per file and by source (inbox #220).

A READING, like the driver whose files it reads: nothing here turns red because the product got slower, and it makes no
coverage claim (ADR 0040). What it can refuse is a reading it cannot stand behind — a sampled chunk with no source map,
a trace without the window's marks — and its controls (below) exit 1 when one of them does not hold.

    profile_attribute.py <run-dir> --sourcemaps <dir> [--stem s2-t1-r1 ...] [--json <file>]
    profile_attribute.py --selftest

**What it reads.** Per stem, `<stem>.trace.json` (one event per line, as Chromium writes it) and `<stem>.window.json`.
The window's two marks (`tests/e2e/profile/profiling.ts` `WINDOW_MARKS`) tie the trace's clock to the page's: every
interval below is cut to them. Every number is of the page's renderer, never of the browser process.
  - **M**, the main thread's busy time: the union of its top-level tasks (`RunTask`) inside the window.
  - **M by kind**, from the trace's events by self time: script, style and layout, paint and composite, GC, and other
    (the tasks' own time no event names).
  - **The script by bucket**, from the trace's own V8 sampler (`disabled-by-default-v8.cpu_profiler`, every thread,
    every 0.2 ms — present in an unprofiled run too, and the `Internal` profile of the main thread is the one read, so a
    profiled and an unprofiled run are read alike). Each sample's stack is mapped through the build's source maps
    (`vite build --sourcemap` of the same commit, which emits the same chunk names: a chunk with no map is refused, never
    guessed at by its minified names, `s2-upload.profile.ts`). Its bucket is decided first by context — anything under
    Vue's scheduler flush is `reactivity` (a re-render's stack starts there, never at the write that caused it), anything
    under the pick-time tile probe is `pick-time probe` — then by the leaf-most frame whose source a rule in [RULES]
    names; a stack no rule names is `other`.
  - **M by its tasks' bucket**: each top-level task's whole time, its native part included, goes to the bucket most of
    its sampled script falls in ([task_buckets]). The samples alone see only a task's script: #220's first reading put
    the pump's script at 18.6 % of the throttle-4 upload, while the pump's tasks — a frame arriving from the worker, the
    `fetch` its continuation started — held 56 %.
  - **Per file N**, from the window's API requests: its reserve (`blob-uploads-batch`) and its complete (`media-batch`).
    Pₙ, the on-device stage, runs from N−1's complete's end to N's reserve (P₁ from the window's start, so it holds the
    pick-time burst and is reported apart); Uₙ, the upload stage, from N's reserve to its complete's end; mPₙ and mUₙ are
    M inside each.
  - **G**, what an overlap of N+1's on-device stage with N's upload stage could move: Σ min(Pₙ₊₁, Uₙ, Pₙ₊₁ + Uₙ − mPₙ₊₁ −
    mUₙ) over the pairs, capped by W − M — two stages run together take at least the longer one and their main-thread
    work together, and the upload never ends before the main thread's work in it.
  - **R**, the largest named bucket of M (`other` excluded; GC counts as one) as a share of W.
  - **Each worker's busy time** (its tasks' union), named by the chunk its samples run (`frames.worker-*`, …); the store
    requests by method (`ResourceSendRequest`, which carries it; Resource Timing does not); and per pair whether file
    N+1's digest ran inside file N's upload stage (the overlap's existence measure).

**Its controls** (each prints PASS or FAIL; one FAIL exits 1):
  - **M is what the thread did** (inbox #770): two angles, each within 10 % ([TASK_AGREEMENT]) of what honest runs read
    on this machine. M is wall time; the trace also carries each task's CPU time on its thread's clock (`tdur`), and the
    window's `ThreadTime` delta (`metrics` − `metricsBefore`) is the operating system's count of the thread's CPU time.
      - *Wall against CPU*: M against its tasks' own CPU time ([task_cpu]) reads ×1.31–1.51 ([TASK_WALL_PER_CPU]) — a
        task's wall time also holds the thread's waits inside it, which keep it from other work, so M is busy time, not
        CPU time. Above the range the thread waited for a core inside its tasks, and M overstates the page's work.
      - *CPU accounted*: `ThreadTime` against the tasks' CPU time plus 43–51 ms per second of window
        ([CPU_OUTSIDE_TASKS_MS_PER_S]), the CPU the thread spends outside its tasks. Above it, the thread spent CPU in
        tasks the reader does not count (a task type [TASKS] does not name, a window cut wrong) and M misses work;
        below it, the trace's tasks claim more CPU than the thread spent.
    Not M against `ThreadTime` within 10 %, the control before: it compared wall with CPU time, and the two effects
    above (×1.31–1.51 inside the tasks, a rate per second outside them) cancel only near W ≈ 18 s, so the same work in
    #220's 11-s windows read ×1.122 and ×1.207 and failed on a quiet machine (2026-10-29). Now each angle compares like
    with like, and the part that grows with the window is a rate. The ranges are those of #220's six unprofiled
    throttle-1 runs (W 11–19 s, 2026-10-29); six fresh ones on this machine (an Apple M4 Pro, macOS, Chromium 156,
    a 1-minute load of 2.98 and 2.99 at each batch's start) read ×1.32–1.45 and 43–45 ms/s on the tree before #220
    (W 17.6–19.5 s) and ×1.33–1.46 and 50–51 ms/s on the tree after it (W 10.3–11.8 s), all inside, where the old
    ratio failed two of the six (×1.118, ×1.155). The rate is not quite constant — about 0.2 s at the window's edges plus 33 ms/s fits all six —
    which the tolerance absorbs for windows of about 5–40 s; a reading far outside that measures the two parts first,
    as another machine measures its own ranges. Planted into those six traces, each inconsistency failed its own
    angle and only it: every fourth task dropped read 71–103 ms/s outside the tasks; every task's wall time stretched by
    40 % read ×1.66–1.89.
    Gated at throttle 1 on an unprofiled run only; otherwise both are printed. A throttle spends the thread's CPU time
    outside the page's tasks (`ThreadTime` exceeded M by 11–12 % at throttle 4, 2026-10-28), and so does the CPU
    profiler's sampler, about 0.55 s over 18.8 s (2026-10-29, inbox #220). Not `TaskDuration`: it counted 7.9 s where
    the trace's tasks held 14.5 s and `ThreadTime` 14.1 s (S2 at throttle 1, 2026-10-28) — it leaves out task types the
    trace shows, among them the workers' messages to the page.
  - `other` stays under 15 % of the sampled script, or the buckets decide nothing and [RULES] needs extending.
  - The frame worker is busy at least [FRAME_WORKER_MIN_S]: the 50 photos' frames are sealed there (5.1–5.6 s,
    2026-10-27 and -28), so a reading whose frames went back to the page, or that named another thread the frame
    worker, is red. A floor, not a range: the worker's time grows with the load and with what else it is given.
  - On a tree that runs one file at a time, no file's digest lies inside the previous file's upload stage; read with
    `--expect-overlap` it is the reverse, at least [OVERLAP_MIN] of the pairs.
  - `--selftest`: a synthetic trace and source map with known samples, read through the same functions, and the
    CPU-time control on known numbers: an honest 11-s and 18-s window pass, a missing quarter of the tasks, a starved
    thread and a trace without `tdur` fail, a profiled or throttled run is not gated. Bitten by moving one source path
    to another bucket in [RULES], and by each of: a nested task counted twice, an edge task counted whole, no CPU outside
    the tasks (the old model), either angle dropped, a 30 % tolerance, a profiled run gated (each turns it red).
"""
from __future__ import annotations

import argparse
import bisect
import json
import re
import statistics
import sys
import tempfile
from pathlib import Path

WINDOW_MARKS = ("veyq-profile-window-start", "veyq-profile-window-end")  # tests/e2e/profile/profiling.ts WINDOW_MARKS
RESERVE = re.compile(r"^/api/scopes/[^/]+/blob-uploads-batch$")
COMPLETE = re.compile(r"^/api/scopes/[^/]+/media-batch$")
TASKS = ("RunTask", "ThreadControllerImpl::RunTask")
FRAME_WORKER_MIN_S = 5.0
TASK_AGREEMENT = 0.10
# What an honest unprofiled throttle-1 run read on this one machine (module docstring, *Its controls*): the main thread's
# tasks' wall time per second of their own CPU time, and the thread's CPU time outside its tasks, ms per second of window.
TASK_WALL_PER_CPU = (1.31, 1.51)
CPU_OUTSIDE_TASKS_MS_PER_S = (43.0, 51.0)
OTHER_MAX = 0.15
OVERLAP_MIN = 45 / 49
# A pair's digest counts as inside the previous file's upload stage from this much digest-worker time on (ms): a
# photo's SHA-256 takes 86–110 ms there (4.3–5.5 s for 50, 2026-10-28), while the worker's own housekeeping inside one
# upload stage reached 10.7 ms (small tasks a few ms apart, no digest).
DIGEST_INSIDE_MS = 40.0
# A sample stands for the time to the next one, at most this long (µs): a longer gap is a thread the sampler did not
# visit, not work.
SAMPLE_CAP_US = 5_000

# Vue's scheduler: everything a flush runs is the re-render a reactive write caused (CR candidate 2).
SCHEDULER_SOURCE = "node_modules/@vue/runtime-core/dist/runtime-core.esm-bundler.js"
SCHEDULER_FUNCTIONS = ("flushJobs", "flushPreFlushCbs", "flushPostFlushCbs")
# The context rules, checked over the whole stack before the leaf-first rules.
PICK_PROBE = re.compile(r"^src/features/memories/useOptimisticTiles\.ts$")
# The leaf-first rules: the first frame from the leaf whose normalised source matches decides. Keyed on the code the CR's
# candidates name, never on a guess: 1 the pump's requests, 3 the EXIF read, 4 each finished file's reconcile, 5 the
# on-device stage's page side (derivation and digest clients), plus the page's own crypto and Vue's reactive writes.
RULES: list[tuple[str, re.Pattern[str]]] = [
    ("pump", re.compile(r"^src/core/blob/(transport|frameWorker|frameOps|stream|readScheduler|queue)\.ts$")),
    ("exif", re.compile(r"node_modules/exifr/")),
    ("reconcile", re.compile(
        r"^src/(features/memories/(useMediaGalleryLive|mediaRevisionPolling|listingOpener|listingOpenerCore|openedListing"
        r"|useMemoryDate|memoryDate|memoryDateRange|useMemoryLocation|memoryLocation|useThumbnailPipeline|mediaOpen)\.ts"
        r"|core/items/itemsLive\.ts|shared/state/livePoller\.ts)$")),
    ("on-device page side", re.compile(r"^src/(shared/image/(mediaDeriveWorker|mediaDeriver)\.ts|core/blob/digest(Worker)?\.ts)$")),
    ("crypto (page)", re.compile(r"^packages/crypto/|node_modules/@noble/")),
    ("reactivity", re.compile(r"node_modules/@vue/")),
]
BUCKETS = ["pump", "reactivity", "exif", "pick-time probe", "reconcile", "on-device page side", "crypto (page)", "gc"]
SPECIAL = {"(idle)": "idle", "(program)": "program", "(garbage collector)": "gc", "(root)": "root"}

KIND_SCRIPT = {"FunctionCall", "EvaluateScript", "v8.compile", "v8.compileModule", "v8.evaluateModule", "RunMicrotasks",
               "TimerFire", "EventDispatch", "FireAnimationFrame", "FireIdleCallback", "v8.run", "v8.callFunction",
               "V8.Execute", "XHRReadyStateChange", "XHRLoad"}
KIND_STYLE = {"UpdateLayoutTree", "RecalculateStyles", "Layout", "HitTest", "ParseAuthorStyleSheet",
              "IntersectionObserverController::computeIntersections"}
KIND_PAINT = {"Paint", "PrePaint", "Layerize", "UpdateLayer", "UpdateLayerTree", "CompositeLayers", "Commit",
              "PaintImage", "Decode Image", "ImageDecodeTask", "Decode LazyPixelRef"}
KINDS = ("script", "style and layout", "paint and composite", "gc", "other")


class Refused(Exception):
    """A reading this reader will not stand behind, and why."""


def kind_of(name: str) -> str | None:
    if name in KIND_SCRIPT:
        return "script"
    if name in KIND_STYLE:
        return "style and layout"
    if name in KIND_PAINT:
        return "paint and composite"
    if name in ("MinorGC", "MajorGC") or name.startswith(("V8.GC", "BlinkGC")):
        return "gc"
    return None


# ---------------------------------------------------------------- source maps


B64 = {c: i for i, c in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")}


def vlq(segment: str) -> list[int]:
    """One mapping segment's base64 VLQ values (source map v3)."""
    out, value, shift = [], 0, 0
    for char in segment:
        digit = B64[char]
        value += (digit & 31) << shift
        if digit & 32:
            shift += 5
            continue
        out.append(-(value >> 1) if value & 1 else value >> 1)
        value, shift = 0, 0
    return out


def normalise(source: str) -> str:
    """A map's source path, relative to the web client (`src/core/blob/transport.ts`) or to the crypto package's parent
    (`packages/crypto/src/v1.ts`): from its last `node_modules/`, else after the checkout's `src/web/` or `src/` — Vite
    writes the build's absolute paths — else without its leading `../`."""
    if "node_modules/" in source:
        return source[source.rindex("node_modules/"):]
    for root, keep in (("/src/web/", ""), ("/src/packages/", "packages/")):
        if root in source:
            return keep + source[source.rindex(root) + len(root):]
    parts = [p for p in source.split("/") if p not in ("", ".", "..")]
    return "/".join(parts)


class SourceMap:
    """A chunk's map: generated (line, column) to (normalised source, original line), and the scheduler's line ranges."""

    def __init__(self, doc: dict) -> None:
        self.sources = [normalise(s) for s in doc["sources"]]
        self.lines: list[list[tuple[int, int, int]]] = []
        src = line = col = 0
        for text in doc["mappings"].split(";"):
            gen_col = 0
            segments = []
            for seg in text.split(","):
                if not seg:
                    continue
                v = vlq(seg)
                gen_col += v[0]
                if len(v) >= 4:
                    src += v[1]
                    line += v[2]
                    col += v[3]
                    segments.append((gen_col, src, line))
            self.lines.append(segments)
        self.scheduler: dict[int, list[tuple[int, int]]] = {}
        for i, (name, content) in enumerate(zip(self.sources, doc.get("sourcesContent") or [])):
            if name == SCHEDULER_SOURCE and content:
                self.scheduler[i] = function_ranges(content, SCHEDULER_FUNCTIONS)

    def lookup(self, line: int, column: int) -> tuple[int, int] | None:
        """(source index, original line) of the last segment at or before the column, or None."""
        if line >= len(self.lines):
            return None
        best = None
        for gen_col, src, orig in self.lines[line]:
            if gen_col > column:
                break
            best = (src, orig)
        return best

    def in_scheduler(self, src: int, line: int) -> bool:
        return any(a <= line <= b for a, b in self.scheduler.get(src, ()))


def function_ranges(content: str, names: tuple[str, ...]) -> list[tuple[int, int]]:
    """The 0-based line ranges of the top-level `function <name>(` declarations in a source, by brace count."""
    lines = content.split("\n")
    ranges = []
    for start, text in enumerate(lines):
        if not any(text.startswith(f"function {n}(") for n in names):
            continue
        depth, opened = 0, False
        for end in range(start, len(lines)):
            depth += lines[end].count("{") - lines[end].count("}")
            opened = opened or "{" in lines[end]
            if opened and depth <= 0:
                ranges.append((start, end))
                break
    return ranges


class Maps:
    """The source maps of one build, loaded on first use, by the chunk's file name."""

    def __init__(self, directory: Path) -> None:
        self.directory = directory
        self.loaded: dict[str, SourceMap] = {}

    def of(self, url: str) -> SourceMap:
        name = url.rsplit("/", 1)[-1].split("?", 1)[0]
        if name not in self.loaded:
            path = self.directory / f"{name}.map"
            if not path.is_file():
                raise Refused(f"no source map for the sampled chunk {name} in {self.directory} — read a build of the same "
                              "commit (`vite build --sourcemap`), never the minified names")
            self.loaded[name] = SourceMap(json.loads(path.read_text(encoding="utf-8")))
        return self.loaded[name]


def is_chunk(url: str) -> bool:
    """A frame of the app's own built code: a `.js` under `/assets/`, which a source map must name."""
    return "/assets/" in url and url.split("?", 1)[0].endswith(".js")


# ---------------------------------------------------------------- the trace


class Trace:
    """What the reader keeps of one trace: thread names, the main thread's events, every thread's tasks, the profiles'
    samples, the marks and the store requests. Read line by line — S2's traces are 180–270 MB."""

    def __init__(self) -> None:
        self.threads: dict[tuple[int, int], str] = {}
        self.events: dict[tuple[int, int], list[tuple[float, float, str]]] = {}  # X events per thread: (ts, dur, name)
        self.profiles: dict[str, dict] = {}  # id -> {pid, tid, source, start, nodes, samples: [(t, node)]}
        self.marks: dict[str, float] = {}
        self.sends: list[tuple[float, str, str]] = []  # (ts, method, url)
        # Where Chromium posted each task from (`src_func`, e.g. `PostMessageToWorkerObject`): what a task's own time,
        # which no event names, was spent on.
        self.origins: dict[tuple[tuple[int, int], float, str], str] = {}
        # Each task's CPU time on its own thread's clock (`tdur`, µs), keyed like `origins`: what the task cost the
        # thread, against its wall time (`dur`), which also holds the time the thread waited inside it.
        self.cpu: dict[tuple[tuple[int, int], float, str], float] = {}

    def add(self, e: dict) -> None:
        ph, name = e.get("ph"), e.get("name", "")
        key = (e.get("pid", 0), e.get("tid", 0))
        if ph == "M" and name == "thread_name":
            self.threads[key] = e["args"]["name"]
        elif ph == "X" and "dur" in e:
            self.events.setdefault(key, []).append((float(e["ts"]), float(e["dur"]), name))
            if name in TASKS:
                if "tdur" in e:
                    self.cpu[(key, float(e["ts"]), name)] = float(e["tdur"])
                args = e.get("args") or {}
                origin = (args.get("task") or args).get("src_func")
                if origin:
                    self.origins[(key, float(e["ts"]), name)] = origin
        elif name == "Profile" and ph == "P":
            self.profiles[e["id"]] = {"pid": key[0], "tid": key[1], "source": e["args"]["data"].get("source"),
                                      "start": float(e["args"]["data"]["startTime"]), "nodes": {}, "raw": []}
        elif name == "ProfileChunk" and ph == "P":
            profile = self.profiles.setdefault(e["id"], {"pid": key[0], "tid": None, "source": None, "start": None,
                                                         "nodes": {}, "raw": []})
            data = e["args"]["data"]
            cpu = data.get("cpuProfile", {})
            for node in cpu.get("nodes", []):
                profile["nodes"][node["id"]] = node
            profile["raw"].append((cpu.get("samples", []), data.get("timeDeltas", [])))
        elif name in WINDOW_MARKS and "blink.user_timing" in e.get("cat", ""):
            self.marks.setdefault(name, float(e["ts"]))
        elif name == "ResourceSendRequest":
            data = (e.get("args") or {}).get("data") or {}
            self.sends.append((float(e["ts"]), data.get("requestMethod", "?"), data.get("url", "")))

    def samples(self, profile: dict) -> list[tuple[float, int]]:
        """The profile's samples as (trace µs, node id), in time order."""
        t = profile["start"]
        out = []
        for samples, deltas in profile["raw"]:
            for node, delta in zip(samples, deltas):
                t += delta
                out.append((t, node))
        out.sort()
        return out

    def main_thread(self) -> tuple[int, int]:
        """The page's main thread: the `CrRendererMain` whose tasks take the longest."""
        renderers = [k for k, v in self.threads.items() if v == "CrRendererMain"]
        if not renderers:
            raise Refused("the trace names no CrRendererMain")
        return max(renderers, key=lambda k: sum(d for _, d, n in self.events.get(k, ()) if n == "RunTask"))


def read_trace(path: Path) -> Trace:
    trace = Trace()
    with path.open(encoding="utf-8") as lines:
        for line in lines:
            line = line.strip().rstrip(",")
            if not line.startswith("{"):
                continue
            try:
                trace.add(json.loads(line))
            except (json.JSONDecodeError, KeyError, TypeError):
                continue
    return trace


def union(intervals: list[tuple[float, float]]) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    for a, b in sorted(intervals):
        if out and a <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        else:
            out.append((a, b))
    return out


def inside(spans: list[tuple[float, float]], a: float, b: float) -> float:
    """How much of [a, b] the (disjoint) spans cover."""
    return sum(max(0.0, min(y, b) - max(x, a)) for x, y in spans)


def tasks(trace: Trace, thread: tuple[int, int]) -> list[tuple[float, float]]:
    return union([(ts, ts + d) for ts, d, n in trace.events.get(thread, ()) if n in TASKS])


def task_cpu(trace: Trace, thread: tuple[int, int], a: float, b: float) -> float | None:
    """The CPU time (`tdur`) of the thread's top-level tasks inside [a, b] (µs), or None when the trace carries no
    `tdur`. A task nested in another (`RunTask` inside `ThreadControllerImpl::RunTask`) is its parent's, never counted
    twice; a task cut by an edge counts by the share of its wall time inside; a task without `tdur` counts nothing, so
    the CPU-time control sees it as a gap."""
    if not any(k[0] == thread for k in trace.cpu):
        return None
    cpu = 0.0
    end = float("-inf")
    for ts, d, n in sorted((e for e in trace.events.get(thread, ()) if e[2] in TASKS), key=lambda e: (e[0], -e[1])):
        if ts + d <= end:
            continue
        end = ts + d
        clipped = max(0.0, min(ts + d, b) - max(ts, a))
        if clipped and d:
            cpu += trace.cpu.get((thread, ts, n), 0.0) * clipped / d
    return cpu


def by_kind(trace: Trace, thread: tuple[int, int], a: float, b: float) -> tuple[dict[str, float], dict[str, float]]:
    """Self time per kind inside [a, b] (µs): each event's own time minus its categorised children's; a task's own time
    is `other`. Events of no kind are transparent — their time is their nearest categorised ancestor's. Second, `other`
    by the place its task was posted from (the innermost task that names one)."""
    events = sorted(((ts, d, n) for ts, d, n in trace.events.get(thread, ()) if n in TASKS or kind_of(n)),
                    key=lambda e: (e[0], -e[1]))
    total = dict.fromkeys(KINDS, 0.0)
    origins: dict[str, float] = {}
    stack: list[list] = []  # [end, kind, self, origin]

    def close(entry: list) -> None:
        total[entry[1]] += max(0.0, entry[2])
        if entry[1] == "other":
            origins[entry[3]] = origins.get(entry[3], 0.0) + max(0.0, entry[2])

    for ts, d, n in events:
        end = ts + d
        while stack and stack[-1][0] <= ts:
            close(stack.pop())
        clipped = max(0.0, min(end, b) - max(ts, a))
        origin = trace.origins.get((thread, ts, n)) or (stack[-1][3] if stack else "(unnamed)")
        if stack:
            stack[-1][2] -= clipped
        stack.append([end, kind_of(n) or "other", clipped, origin])
    while stack:
        close(stack.pop())
    return total, origins


# ---------------------------------------------------------------- buckets


def bucket_of(stack: list[dict], maps: Maps) -> str:
    """A sample's bucket from its stack (root first), by the context rules and then leaf first by [RULES]."""
    mapped: list[str] = []
    for frame in stack:
        url = frame.get("url", "")
        if not is_chunk(url):
            mapped.append("")
            continue
        source_map = maps.of(url)
        hit = source_map.lookup(int(frame.get("lineNumber", -1)), int(frame.get("columnNumber", -1)))
        if hit is None:
            mapped.append("")
            continue
        src, line = hit
        if source_map.in_scheduler(src, line):
            return "reactivity"
        mapped.append(source_map.sources[src])
    if any(PICK_PROBE.search(s) for s in mapped if s):
        return "pick-time probe"
    for source in reversed(mapped):
        if not source:
            continue
        for bucket, rule in RULES:
            if rule.search(source):
                return bucket
    return "other"


def script_buckets(trace: Trace, thread: tuple[int, int], a: float, b: float, maps: Maps,
                   timeline: list[tuple[float, str, float]] | None = None) -> dict[str, float]:
    """The main thread's sampled time inside [a, b] by bucket (µs), from its `Internal` profile; `idle` and `program`
    (native work outside JS) are kept apart from the script. Each sample is also appended to `timeline` as
    (trace µs, bucket, weight) when one is given."""
    profiles = [p for p in trace.profiles.values() if (p["pid"], p["tid"]) == thread and p["start"] is not None]
    internal = [p for p in profiles if p["source"] == "Internal"] or profiles
    if not internal:
        raise Refused("the trace holds no sampled profile of the main thread (is disabled-by-default-v8.cpu_profiler on?)")
    profile = internal[0]
    nodes = profile["nodes"]
    stacks: dict[int, list[dict]] = {}

    def stack_of(node_id: int) -> list[dict]:
        if node_id not in stacks:
            chain = []
            node = nodes.get(node_id)
            while node is not None:
                chain.append(node["callFrame"])
                node = nodes.get(node.get("parent"))
            stacks[node_id] = list(reversed(chain))
        return stacks[node_id]

    found: dict[str, float] = {}
    cache: dict[int, str] = {}
    samples = trace.samples(profile)
    for (t, node_id), nxt in zip(samples, samples[1:] + [(samples[-1][0], 0)] if samples else []):
        if t < a or t > b:
            continue
        weight = min(max(0.0, nxt[0] - t), SAMPLE_CAP_US)
        if node_id not in cache:
            leaf = nodes.get(node_id, {}).get("callFrame", {}).get("functionName", "")
            cache[node_id] = SPECIAL.get(leaf) or bucket_of(stack_of(node_id), maps)
        found[cache[node_id]] = found.get(cache[node_id], 0.0) + weight
        if timeline is not None:
            timeline.append((t, cache[node_id], weight))
    return found


def task_buckets(trace: Trace, thread: tuple[int, int], a: float, b: float,
                 timeline: list[tuple[float, str, float]]) -> dict[str, float]:
    """M by the bucket of each top-level task's script (µs): a task's whole time, its native part included (the
    `fetch` a pump's continuation starts, the message a worker's answer arrives in, the layout a re-render forces),
    goes to the bucket most of its sampled script falls in; a task with no sampled script is `no script`. The trace's
    events cannot name what a task's native part did; the script that ran in the same task can."""
    spans = [(ts, ts + d) for ts, d, n in trace.events.get(thread, ()) if n == "RunTask"]
    starts = [t for t, _, _ in timeline]
    out: dict[str, float] = {}
    for s, e in union(spans):
        clipped = max(0.0, min(e, b) - max(s, a))
        if not clipped:
            continue
        weights: dict[str, float] = {}
        i = bisect.bisect_left(starts, s)
        while i < len(timeline) and timeline[i][0] < e:
            bucket = timeline[i][1]
            if bucket not in ("idle", "program", "root"):
                weights[bucket] = weights.get(bucket, 0.0) + timeline[i][2]
            i += 1
        owner = max(weights, key=weights.get) if weights else "no script"
        out[owner] = out.get(owner, 0.0) + clipped
    return out


# ---------------------------------------------------------------- one run


def stages(window: dict) -> list[dict]:
    """Per file, its reserve and complete (window ms from t0), paired in order."""
    api = sorted(window["apiRequests"], key=lambda r: r["start"])
    reserves = [r for r in api if RESERVE.match(r["path"])]
    completes = [r for r in api if COMPLETE.match(r["path"])]
    if not reserves or len(reserves) != len(completes):
        raise Refused(f"{len(reserves)} reserve(s) against {len(completes)} complete(s) — no file edges to read")
    files = []
    for i, (r, c) in enumerate(zip(reserves, completes)):
        if c["start"] < r["start"]:
            raise Refused(f"file {i + 1}'s complete starts before its reserve")
        files.append({"reserve": r["start"], "end": c["start"] + c["duration"]})
    return files


def worker_name(trace: Trace, thread: tuple[int, int]) -> str:
    """A worker thread's name: the chunk most of its sampled frames run (`frames.worker`, without the hash)."""
    counts: dict[str, int] = {}
    for p in trace.profiles.values():
        if (p["pid"], p["tid"]) != thread:
            continue
        for node in p["nodes"].values():
            url = node["callFrame"].get("url", "")
            if is_chunk(url):
                chunk = re.sub(r"-[A-Za-z0-9_-]{6,}\.js$", "", url.rsplit("/", 1)[-1])
                counts[chunk] = counts.get(chunk, 0) + 1
    return max(counts, key=counts.get) if counts else f"worker {thread[1]}"


def read_run(trace: Trace, window: dict, maps: Maps) -> dict:
    missing = [m for m in WINDOW_MARKS if m not in trace.marks]
    if missing:
        raise Refused(f"the trace carries no mark {', '.join(missing)} — a run from before the marks cannot be cut")
    t0, t1 = trace.marks[WINDOW_MARKS[0]], trace.marks[WINDOW_MARKS[1]]
    at = lambda ms: t0 + ms * 1000.0  # noqa: E731 - window ms to trace µs
    main = trace.main_thread()
    busy = tasks(trace, main)
    w_us = t1 - t0
    m_us = inside(busy, t0, t1)
    kinds, task_origins = by_kind(trace, main, t0, t1)
    timeline: list[tuple[float, str, float]] = []
    sampled = script_buckets(trace, main, t0, t1, maps, timeline)
    by_task = task_buckets(trace, main, t0, t1, timeline)
    script = {k: v for k, v in sampled.items() if k not in ("idle", "program", "root", "gc")}
    buckets = {k: script.get(k, 0.0) for k in BUCKETS if k != "gc"}
    buckets["gc"] = kinds["gc"]
    other = script.get("other", 0.0)

    files = stages(window)
    per_file = []
    for i, f in enumerate(files):
        p_from = t0 if i == 0 else at(files[i - 1]["end"])
        p_to, u_to = at(f["reserve"]), at(f["end"])
        per_file.append({"P": p_to - p_from, "U": u_to - p_to, "mP": inside(busy, p_from, p_to),
                         "mU": inside(busy, p_to, u_to), "from": p_from, "reserve": p_to, "end": u_to})
    g = sum(max(0.0, min(n1["P"], n["U"], n1["P"] + n["U"] - n1["mP"] - n["mU"]))
            for n, n1 in zip(per_file, per_file[1:]))
    g = min(g, max(0.0, w_us - m_us))

    workers = {}
    for thread, name in trace.threads.items():
        if name == "DedicatedWorker thread" and thread[0] == main[0]:
            spans = tasks(trace, thread)
            workers[thread] = {"name": worker_name(trace, thread), "busy": inside(spans, t0, t1), "spans": spans}
    digest = next((w for w in workers.values() if w["name"].startswith("digest.worker")), None)
    overlapped = None
    if digest is not None:
        overlapped = sum(1 for n in per_file[:-1] if inside(digest["spans"], n["reserve"], n["end"]) >= DIGEST_INSIDE_MS * 1000)

    store: dict[str, int] = {}
    for ts, method, url in trace.sends:
        if t0 <= ts <= t1 and re.search(r"[?&]X-Amz-", url, re.IGNORECASE):
            store[method] = store.get(method, 0) + 1

    before, after = window.get("metricsBefore"), window.get("metrics")
    thread_delta = (after["ThreadTime"] - before["ThreadTime"]) * 1e6 if before and after else None
    cpu_us = task_cpu(trace, main, t0, t1)

    named = {k: v for k, v in buckets.items()}
    top = max(named, key=named.get)
    ms = lambda us: round(us / 1000.0, 1)  # noqa: E731
    return {
        "W": ms(w_us), "operationMs": round(window["operationMs"], 1), "M": ms(m_us), "W-M": ms(w_us - m_us),
        "kinds": {k: ms(v) for k, v in kinds.items()},
        "otherByTaskOrigin": {k: ms(v) for k, v in sorted(task_origins.items(), key=lambda kv: -kv[1])[:8]},
        "script sampled": ms(sum(script.values())), "program sampled": ms(sampled.get("program", 0.0)),
        "buckets": {k: ms(v) for k, v in buckets.items()}, "other": ms(other),
        "mByTaskBucket": {k: ms(v) for k, v in sorted(by_task.items(), key=lambda kv: -kv[1])},
        "otherShare": round(other / sum(script.values()), 3) if script else None,
        "R": {"bucket": top, "ms": ms(named[top]), "shareOfW": round(named[top] / w_us, 3)},
        "G": {"ms": ms(g), "shareOfW": round(g / w_us, 3)},
        "files": len(per_file),
        "P1": ms(per_file[0]["P"]),
        "perFileMedian": {k: ms(statistics.median(f[k] for f in per_file[1:] or per_file)) for k in ("P", "U", "mP", "mU")},
        "workers": {w["name"]: ms(w["busy"]) for w in workers.values()},
        "storeRequests": store,
        "digestInsidePreviousUpload": overlapped,
        "threadTimeDelta": ms(thread_delta) if thread_delta is not None else None,
        "taskCpu": ms(cpu_us) if cpu_us is not None else None,
        "cpuProfile": window.get("cpuProfile", True),
    }


def cpu_controls(stem: str, run: dict) -> list[tuple[str, bool]]:
    """The two angles on M (module docstring, *Its controls*): its tasks' wall time against their own CPU time, and
    their CPU time, plus the measured CPU outside them per second of window, against the thread's `ThreadTime`."""
    cpu, thread, m, w_s = run.get("taskCpu"), run["threadTimeDelta"], run["M"], run["W"] / 1000.0
    if not cpu:
        return [(f"{stem}: the trace carries no CPU time (`tdur`) of the main thread's tasks — M cannot be checked", False)]
    lo, hi = TASK_WALL_PER_CPU[0] / (1 + TASK_AGREEMENT), TASK_WALL_PER_CPU[1] * (1 + TASK_AGREEMENT)
    ratio = m / cpu
    wall = (f"{stem}: M {m:.0f} ms against its tasks' own CPU time, {cpu:.0f} ms (×{ratio:.3f}; ×{TASK_WALL_PER_CPU[0]}–"
            f"{TASK_WALL_PER_CPU[1]} measured, within {TASK_AGREEMENT:.0%}: ×{lo:.2f}–{hi:.2f})", lo <= ratio <= hi)
    low, high = (cpu + rate * w_s for rate in CPU_OUTSIDE_TASKS_MS_PER_S)
    outside = (thread - cpu) / w_s
    accounted = (f"{stem}: the main thread's CPU time, {thread:.0f} ms, against its tasks' {cpu:.0f} ms plus "
                 f"{CPU_OUTSIDE_TASKS_MS_PER_S[0]:.0f}–{CPU_OUTSIDE_TASKS_MS_PER_S[1]:.0f} ms a second of the {w_s:.1f}-s "
                 f"window outside them, {low:.0f}–{high:.0f} ms (read: {outside:.0f} ms/s; within {TASK_AGREEMENT:.0%})",
                 low / (1 + TASK_AGREEMENT) <= thread <= high * (1 + TASK_AGREEMENT))
    return [wall, accounted]


def controls(stem: str, run: dict, expect_overlap: bool) -> list[tuple[str, bool]]:
    out = []
    if run["threadTimeDelta"] is not None:
        if re.search(r"-t1-", stem) and not run.get("cpuProfile", True):
            out += cpu_controls(stem, run)
        else:
            why = "with the CPU profiler" if run.get("cpuProfile", True) else "under a throttle"
            for text, _ in cpu_controls(stem, run):
                print(f"INFO {text}; {why}, not gated")
    if run["otherShare"] is not None:
        out.append((f"{stem}: other is {run['otherShare']:.1%} of the sampled script (under {OTHER_MAX:.0%})",
                    run["otherShare"] < OTHER_MAX))
    frames = next((v for k, v in run["workers"].items() if k.startswith("frames.worker")), None)
    out.append((f"{stem}: the frame worker is busy {frames} ms (at least {FRAME_WORKER_MIN_S} s)",
                frames is not None and frames >= FRAME_WORKER_MIN_S * 1000))
    pairs = run["files"] - 1
    inside_count = run["digestInsidePreviousUpload"]
    if expect_overlap:
        out.append((f"{stem}: {inside_count} of {pairs} digests inside the previous file's upload stage (at least "
                    f"{OVERLAP_MIN:.0%})", inside_count is not None and inside_count >= OVERLAP_MIN * pairs))
    else:
        out.append((f"{stem}: {inside_count} of {pairs} digests inside the previous file's upload stage (0 expected)",
                    inside_count == 0))
    return out


def medians(runs: dict[str, dict]) -> dict:
    """The medians per throttle of the numbers the exit rule reads."""
    by_throttle: dict[str, list[dict]] = {}
    for stem, run in runs.items():
        t = re.search(r"-t(\d+)-", stem)
        by_throttle.setdefault(f"t{t.group(1) if t else '?'}", []).append(run)
    out = {}
    for throttle, rs in sorted(by_throttle.items()):
        med = lambda f: round(statistics.median(f(r) for r in rs), 1)  # noqa: E731
        out[throttle] = {
            "runs": len(rs), "W": med(lambda r: r["W"]), "M": med(lambda r: r["M"]), "W-M": med(lambda r: r["W-M"]),
            "kinds": {k: med(lambda r, k=k: r["kinds"][k]) for k in KINDS},
            "buckets": {k: med(lambda r, k=k: r["buckets"][k]) for k in BUCKETS},
            "mByTaskBucket": {k: med(lambda r, k=k: r["mByTaskBucket"].get(k, 0.0))
                              for k in sorted({k for r in rs for k in r["mByTaskBucket"]})},
            "other": med(lambda r: r["other"]),
            "bucketShareOfW": {k: round(statistics.median(r["buckets"][k] / r["W"] for r in rs), 3) for k in BUCKETS},
            "G": med(lambda r: r["G"]["ms"]), "GShareOfW": round(statistics.median(r["G"]["shareOfW"] for r in rs), 3),
            "RShareOfW": round(statistics.median(r["R"]["shareOfW"] for r in rs), 3),
            "P1": med(lambda r: r["P1"]),
            "perFile": {k: med(lambda r, k=k: r["perFileMedian"][k]) for k in ("P", "U", "mP", "mU")},
        }
    return out


# ---------------------------------------------------------------- the selftest


def selftest() -> int:
    """A synthetic trace and source map with known samples, read through the same functions."""
    def enc(n: int) -> str:
        chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        v = (-n << 1) | 1 if n < 0 else n << 1
        out = ""
        while True:
            digit = v & 31
            v >>= 5
            out += chars[digit | (32 if v else 0)]
            if not v:
                return out

    sources = ["/home/someone/veyq/src/web/src/core/blob/transport.ts", "../../../../node_modules/exifr/dist/full.esm.mjs",
               "../../src/features/memories/useMediaGalleryLive.ts", "../../src/features/memories/useOptimisticTiles.ts",
               "../../../../" + SCHEDULER_SOURCE, "../../src/features/memories/components/MediaGrid.vue",
               "../../src/app/somethingElse.ts"]
    scheduler = "let x\nfunction flushJobs(seen) {\n  run()\n}\nfunction other() {}\n"
    # One generated line; column 10*i maps to source i, original line 0 — except source 4 (the scheduler), mapped to
    # its line 2 (inside flushJobs).
    segs, prev = [], (0, 0, 0)
    for i in range(len(sources)):
        line = 2 if i == 4 else 0
        col, src, orig = 10 * i, i, line
        segs.append(enc(col - prev[0]) + enc(src - prev[1]) + enc(orig - prev[2]) + enc(0))
        prev = (col, src, orig)
    doc = {"version": 3, "sources": sources, "mappings": ",".join(segs),
           "sourcesContent": [""] * 4 + [scheduler] + [""] * 2, "names": []}
    url = "https://veyq.test/assets/index-AbCdEf12.js"
    frame = lambda i, name="f": {"url": url, "lineNumber": 0, "columnNumber": 10 * i + 1, "functionName": name}  # noqa: E731
    native = {"url": "", "lineNumber": -1, "columnNumber": -1, "functionName": "fetch"}
    cases = [
        ([frame(6), frame(0), native], "pump"),                 # a native leaf under the pump is the pump's
        ([frame(6), frame(1)], "exif"),                         # exifr at the leaf
        ([frame(0), frame(2)], "reconcile"),                    # leaf-first: the reconcile, not the pump above it
        ([frame(4), frame(5), frame(0)], "reactivity"),         # under the scheduler's flush, whatever runs below
        ([frame(3), frame(1)], "pick-time probe"),              # the probe's context outranks exifr at the leaf
        ([frame(6)], "other"),                                  # nothing named
    ]
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "index-AbCdEf12.js.map").write_text(json.dumps(doc), encoding="utf-8")
        maps = Maps(Path(tmp))
        for stack, expected in cases:
            got = bucket_of(stack, maps)
            if got != expected:
                failures.append(f"stack {[f.get('functionName') for f in stack]} read as {got}, expected {expected}")
        try:
            bucket_of([{"url": "https://veyq.test/assets/unmapped-XyZ12345.js", "lineNumber": 0, "columnNumber": 0}], maps)
            failures.append("a chunk without a map was read instead of refused")
        except Refused:
            pass

        # A trace of one main thread: a 10 ms task holding a 6 ms FunctionCall with a 2 ms MinorGC inside, and samples
        # every 1 ms: 3 in the pump, 2 in exifr, 1 under the flush, 4 idle.
        events = [
            {"ph": "M", "name": "thread_name", "pid": 1, "tid": 1, "args": {"name": "CrRendererMain"}},
            {"ph": "I", "name": WINDOW_MARKS[0], "cat": "blink.user_timing", "pid": 1, "tid": 1, "ts": 1000},
            {"ph": "I", "name": WINDOW_MARKS[1], "cat": "blink.user_timing", "pid": 1, "tid": 1, "ts": 21000},
            {"ph": "X", "name": "RunTask", "pid": 1, "tid": 1, "ts": 2000, "dur": 10000},
            {"ph": "X", "name": "FunctionCall", "pid": 1, "tid": 1, "ts": 3000, "dur": 6000},
            {"ph": "X", "name": "MinorGC", "pid": 1, "tid": 1, "ts": 4000, "dur": 2000},
            {"ph": "P", "name": "Profile", "id": "0x1", "pid": 1, "tid": 1, "args": {"data": {"source": "Internal", "startTime": 2000}}},
        ]
        nodes = [{"id": 1, "callFrame": {"functionName": "(root)"}},
                 {"id": 2, "parent": 1, "callFrame": frame(6)}, {"id": 3, "parent": 2, "callFrame": frame(0)},
                 {"id": 4, "parent": 2, "callFrame": frame(1)}, {"id": 5, "parent": 1, "callFrame": frame(4)},
                 {"id": 6, "parent": 1, "callFrame": {"functionName": "(idle)"}}]
        samples = [3, 3, 3, 4, 4, 5, 6, 6, 6, 6, 6]
        events.append({"ph": "P", "name": "ProfileChunk", "id": "0x1", "pid": 1, "tid": 9, "args": {"data": {
            "cpuProfile": {"nodes": nodes, "samples": samples}, "timeDeltas": [0] + [1000] * (len(samples) - 1)}}})
        trace = Trace()
        for e in events:
            trace.add(e)
        main = trace.main_thread()
        timeline: list[tuple[float, str, float]] = []
        sampled = script_buckets(trace, main, 1000, 21000, maps, timeline)
        expect = {"pump": 3000.0, "exif": 2000.0, "reactivity": 1000.0, "idle": 4000.0}
        if {k: v for k, v in sampled.items()} != expect:
            failures.append(f"samples read as {sampled}, expected {expect}")
        # The task's whole 10 ms goes to the bucket most of its script falls in.
        owned = task_buckets(trace, main, 1000, 21000, timeline)
        if owned != {"pump": 10000.0}:
            failures.append(f"the task read as {owned}, expected all of it the pump's")
        kinds, _ = by_kind(trace, main, 1000, 21000)
        expect_kinds = {"script": 4000.0, "style and layout": 0.0, "paint and composite": 0.0, "gc": 2000.0, "other": 4000.0}
        if kinds != expect_kinds:
            failures.append(f"kinds read as {kinds}, expected {expect_kinds}")
        if inside(tasks(trace, main), 1000, 21000) != 10000.0:
            failures.append("M is not the task's 10 ms")
    checks = len(cases) + 7

    # The tasks' CPU time: a task nested in another counts once, one cut by the window's edge by its share inside, one
    # without `tdur` not at all; a trace without any `tdur` reads None.
    thread = (1, 1)
    timed = Trace()
    for e in [{"ph": "X", "name": "ThreadControllerImpl::RunTask", "pid": 1, "tid": 1, "ts": 0, "dur": 1000, "tdur": 600},
              {"ph": "X", "name": "RunTask", "pid": 1, "tid": 1, "ts": 100, "dur": 500, "tdur": 400},
              {"ph": "X", "name": "RunTask", "pid": 1, "tid": 1, "ts": 2000, "dur": 1000, "tdur": 500},
              {"ph": "X", "name": "RunTask", "pid": 1, "tid": 1, "ts": 2100, "dur": 100}]:
        timed.add(e)
    if task_cpu(timed, thread, 0, 2500) != 850.0:
        failures.append(f"the tasks' CPU time read {task_cpu(timed, thread, 0, 2500)} µs, expected 600 + 500 / 2 = 850")
    untimed = Trace()
    untimed.add({"ph": "X", "name": "RunTask", "pid": 1, "tid": 1, "ts": 0, "dur": 1000})
    if task_cpu(untimed, thread, 0, 2500) is not None:
        failures.append("a trace without `tdur` read a CPU time")
    checks += 2

    # The CPU-time control (inbox #770), gated on an unprofiled throttle-1 run. Honest: tasks ×1.43 of their CPU time and
    # 47 ms/s outside them, in an 11-s and an 18-s window — the old M-against-ThreadTime ratio read ×1.146 (red) and
    # ×1.018 (green) for the same work; both must pass now. Then three inconsistencies, each failing its own angle.
    run = {"otherShare": 0.01, "workers": {"frames.worker-x": 6000.0}, "files": 2, "digestInsidePreviousUpload": 0,
           "cpuProfile": False}
    cases_cpu = [
        ("an honest 11-s window", {"W": 11000.0, "M": 3000.0, "taskCpu": 2100.0, "threadTimeDelta": 2617.0}, [True, True]),
        ("an honest 18-s window", {"W": 18000.0, "M": 3000.0, "taskCpu": 2100.0, "threadTimeDelta": 2946.0}, [True, True]),
        # A quarter of the tasks unread (a task type the reader does not name): M and its CPU time shrink alike, the
        # thread's CPU time does not.
        ("a quarter of the tasks missing", {"W": 11000.0, "M": 2250.0, "taskCpu": 1575.0, "threadTimeDelta": 2617.0},
         [True, False]),
        # A thread that waited for a core inside its tasks: their wall time grows, their CPU time does not.
        ("a starved thread", {"W": 11000.0, "M": 3990.0, "taskCpu": 2100.0, "threadTimeDelta": 2617.0}, [False, True]),
        ("a trace without `tdur`", {"W": 11000.0, "M": 3000.0, "taskCpu": None, "threadTimeDelta": 2617.0}, [False]),
    ]
    for name, numbers, expected in cases_cpu:
        got = [ok for text, ok in controls("s2-t1-r1", {**run, **numbers}, False) if "CPU time" in text]
        if got != expected:
            failures.append(f"{name}: the CPU-time control read {got}, expected {expected}")
    # A profiled run's sampler and a throttle both spend the thread's CPU time outside its tasks: printed, never gated.
    starved = {**run, **cases_cpu[3][1]}
    for stem, profiled in (("s2-t1-r1", True), ("s2-t4-r1", False)):
        if any("CPU time" in text for text, _ in controls(stem, {**starved, "cpuProfile": profiled}, False)):
            failures.append(f"{stem} {'profiled' if profiled else 'unprofiled'} was gated by the CPU-time control")
    checks += len(cases_cpu) + 2
    for f in failures:
        print(f"FAIL {f}")
    print(f"selftest: {checks - len(failures)} of {checks} checks hold")
    return 1 if failures else 0


# ---------------------------------------------------------------- main


def table(runs: dict[str, dict], med: dict) -> str:
    rows = []
    for stem, r in runs.items():
        rows.append(f"{stem}: W {r['W']:.0f} ms (operation {r['operationMs']:.0f}), M {r['M']:.0f}, W−M {r['W-M']:.0f}, "
                    f"G {r['G']['ms']:.0f} ({r['G']['shareOfW']:.1%}), R {r['R']['bucket']} {r['R']['ms']:.0f} "
                    f"({r['R']['shareOfW']:.1%}), P1 {r['P1']:.0f}, per file {r['perFileMedian']}")
        rows.append(f"    kinds {r['kinds']}; other by task origin {r['otherByTaskOrigin']}")
        rows.append(f"    buckets {r['buckets']}, other {r['other']} ({r['otherShare']:.1%} of script)")
        rows.append(f"    M by task bucket {r['mByTaskBucket']}")
        rows.append(f"    workers {r['workers']}; store {r['storeRequests']}; digests inside: {r['digestInsidePreviousUpload']}")
    for throttle, m in med.items():
        rows.append(f"median {throttle} ({m['runs']} runs): {json.dumps(m)}")
    return "\n".join(rows)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_dir", nargs="?", type=Path)
    parser.add_argument("--sourcemaps", type=Path, help="the dist/assets of `vite build --sourcemap` of the same commit")
    parser.add_argument("--stem", action="append", help="read only these stems (default: every s2-* window)")
    parser.add_argument("--json", type=Path, help="also write the reading here")
    parser.add_argument("--expect-overlap", action="store_true",
                        help="the tree prepares the next file during an upload: the existence control expects overlap")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if not args.run_dir or not args.sourcemaps:
        parser.error("a run directory and --sourcemaps are required (or --selftest)")
    stems = args.stem or sorted(p.name[: -len(".window.json")] for p in args.run_dir.glob("s2-*.window.json"))
    maps = Maps(args.sourcemaps)
    runs, checks = {}, []
    try:
        for stem in stems:
            window = json.loads((args.run_dir / f"{stem}.window.json").read_text(encoding="utf-8"))
            runs[stem] = read_run(read_trace(args.run_dir / f"{stem}.trace.json"), window, maps)
            checks += controls(stem, runs[stem], args.expect_overlap)
            print(f"read {stem}", flush=True)
    except Refused as error:
        print(f"REFUSED: {error}")
        return 2
    med = medians(runs)
    print(table(runs, med))
    for text, ok in checks:
        print(f"{'PASS' if ok else 'FAIL'} {text}")
    if args.json:
        args.json.write_text(json.dumps({"runs": runs, "medians": med,
                                         "controls": [{"check": t, "ok": ok} for t, ok in checks]}, indent=2),
                             encoding="utf-8")
    return 0 if all(ok for _, ok in checks) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
