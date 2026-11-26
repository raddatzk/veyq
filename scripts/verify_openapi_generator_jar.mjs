#!/usr/bin/env node
// Puts the OpenAPI generator JAR in place and proves it is the pinned one — before the generator runs.
//
// Run by `generate:api` in src/web/ and src/admin-web/ (one copy, reached as ../scripts/ from both, and COPY'd into both
// image builders before their `pnpm install`). @openapitools/openapi-generator-cli downloads its JAR with no checksum
// and treats any file already at <storageDir>/<version>.jar as installed, so a poisoned download — or a file
// planted in the storage dir — would run as our code generator, and its output (the untracked generated client)
// is never reviewed. This script closes that: it downloads the pinned version from Maven Central when the file is
// absent, and ALWAYS hashes the file against scripts/openapi_generator_jar.sha256, including when it was already
// there. Anything else fails closed; it never deletes a mismatching file (it is the evidence).
//
// Node, not Python (the repo rule), because it runs inside `pnpm install`'s `prepare` in the node:<major>-bookworm-slim
// builder stages, which carry no python3 (measured 2026-09-27 on node:22: `command -v python3` → nothing).

import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, renameSync, unlinkSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const PIN_FILE = fileURLToPath(new URL("./openapi_generator_jar.sha256", import.meta.url));
const CENTRAL = "https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli";

function fail(message) {
  console.error(`[verify-openapi-generator-jar] ${message}`);
  process.exit(1);
}

// The wrapper's own rule for where it looks (ConfigService: `process.env.PWD || process.env.INIT_CWD ||
// process.cwd()`, then openapitools.json there and storageDir relative to it, `~` expanded). Copied rather than
// approximated: if this script resolved a different path than the wrapper, it would verify one file and the
// wrapper would run another.
const cwd = process.env.PWD || process.env.INIT_CWD || process.cwd();
const configPath = path.resolve(cwd, "openapitools.json");
let cli;
try {
  cli = JSON.parse(readFileSync(configPath, "utf8"))["generator-cli"] ?? {};
} catch (e) {
  fail(`cannot read ${configPath}: ${e.message}`);
}
const { version, storageDir, useDocker } = cli;
if (!version) fail(`${configPath} names no generator-cli.version`);
if (!storageDir) fail(`${configPath} names no generator-cli.storageDir — without it the wrapper reads a JAR inside its own package, which this check does not look at`);
if (useDocker) fail(`${configPath} sets useDocker — the wrapper would then run an unpinned image instead of the JAR`);

const pins = new Map(
  readFileSync(PIN_FILE, "utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => line.split(/\s+/)),
);
const expected = pins.get(version);
if (!expected || !/^[0-9a-f]{64}$/.test(expected)) fail(`no SHA-256 pin for generator ${version} in ${PIN_FILE}`);

const storage = path.resolve(cwd, storageDir.replace("~", homedir()));
const jar = path.join(storage, `${version}.jar`);

async function sha256Of(stream) {
  const hash = createHash("sha256");
  for await (const chunk of stream) hash.update(chunk);
  return hash.digest("hex");
}

// Maven Central rate-limits: CI starts several installing jobs at once, and on 2026-11-22 it answered HTTP 429 to both
// packages' download in one job. CI restores the JAR from a cache first (checks.yml's `frontend` says how), so this runs
// only on a cold key; there an answer that says "later" (429, 5xx) or a dropped connection is asked again, at most
// RETRY_DELAYS_MS.length more times, waiting the server's Retry-After when it names one (capped) or the next delay.
// Bounded on purpose: a timeout is not retried (it already waited five minutes), and the bytes of whichever attempt
// answers are hashed below exactly as a first answer's are — a retry never weakens the check, it only re-asks.
const RETRY_DELAYS_MS = [10_000, 30_000];
const RETRY_AFTER_CAP_MS = 60_000;

function retryable(status) {
  return status === 429 || (status >= 500 && status <= 599);
}

function delayFor(response, attempt) {
  const header = response?.headers.get("retry-after");
  const seconds = header && /^\d+$/.test(header.trim()) ? Number(header.trim()) : NaN;
  return Number.isFinite(seconds) ? Math.min(seconds * 1000, RETRY_AFTER_CAP_MS) : RETRY_DELAYS_MS[attempt];
}

async function fetchWithRetry(url) {
  for (let attempt = 0; ; attempt++) {
    const last = attempt >= RETRY_DELAYS_MS.length;
    let response;
    try {
      response = await fetch(url, { signal: AbortSignal.timeout(300_000) });
    } catch (e) {
      if (last || e.name === "TimeoutError" || e.name === "AbortError") fail(`download of ${url} failed: ${e.message}`);
      console.warn(`[verify-openapi-generator-jar] download of ${url} failed (${e.message}); asking again`);
      await new Promise((resolve) => setTimeout(resolve, RETRY_DELAYS_MS[attempt]));
      continue;
    }
    if (response.ok && response.body) return response;
    if (last || !retryable(response.status)) fail(`download of ${url} answered HTTP ${response.status}`);
    const wait = delayFor(response, attempt);
    await response.body?.cancel();
    console.warn(`[verify-openapi-generator-jar] ${url} answered HTTP ${response.status}; asking again in ${wait / 1000}s`);
    await new Promise((resolve) => setTimeout(resolve, wait));
  }
}

if (!existsSync(jar)) {
  const url = `${CENTRAL}/${version}/openapi-generator-cli-${version}.jar`;
  mkdirSync(storage, { recursive: true });
  // A per-process partial file renamed into place only after it matched: src/web/ and src/admin-web/ can run this at the
  // same time during one `pnpm install`, and neither may ever see a half-written or unverified <version>.jar.
  const partial = path.join(storage, `.${version}.jar.${process.pid}.partial`);
  const response = await fetchWithRetry(url);
  const hash = createHash("sha256");
  const body = Readable.fromWeb(response.body);
  body.on("data", (chunk) => hash.update(chunk));
  try {
    await pipeline(body, createWriteStream(partial));
  } catch (e) {
    if (existsSync(partial)) unlinkSync(partial);
    fail(`download of ${url} failed: ${e.message}`);
  }
  const actual = hash.digest("hex");
  if (actual !== expected) {
    unlinkSync(partial);
    fail(`${url} hashes to ${actual}, but the pin for ${version} is ${expected} — refusing to install it`);
  }
  renameSync(partial, jar);
}

const actual = await sha256Of(createReadStream(jar));
if (actual !== expected) {
  fail(
    `${jar} hashes to ${actual}, but the pin for ${version} is ${expected} — refusing to run it. ` +
      `Nothing here overwrites it: find out how it got there, then delete it to re-download.`,
  );
}
console.log(`[verify-openapi-generator-jar] ${version} verified: ${jar}`);
