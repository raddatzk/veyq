#!/usr/bin/env node
// Puts the OpenAPI generator JAR in place and proves it is the pinned one — before the generator runs.
//
// Run by `generate:api` in src/web/ and src/admin-web/ (one copy, reached as ../scripts/ from both, and COPY'd into both
// image builders before their `npm ci`). @openapitools/openapi-generator-cli downloads its JAR with no checksum
// and treats any file already at <storageDir>/<version>.jar as installed, so a poisoned download — or a file
// planted in the storage dir — would run as our code generator, and its output (the untracked generated client)
// is never reviewed. This script closes that: it downloads the pinned version from Maven Central when the file is
// absent, and ALWAYS hashes the file against scripts/openapi_generator_jar.sha256, including when it was already
// there. Anything else fails closed; it never deletes a mismatching file (it is the evidence).
//
// Node, not Python (the repo rule), because it runs inside `npm ci`'s `prepare` in the node:22-bookworm-slim
// builder stages, which carry no python3 (measured 2026-09-27: `command -v python3` → nothing).

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

if (!existsSync(jar)) {
  const url = `${CENTRAL}/${version}/openapi-generator-cli-${version}.jar`;
  mkdirSync(storage, { recursive: true });
  // A per-process partial file renamed into place only after it matched: src/web/ and src/admin-web/ can run this at the
  // same time during one `npm ci`, and neither may ever see a half-written or unverified <version>.jar.
  const partial = path.join(storage, `.${version}.jar.${process.pid}.partial`);
  let response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(300_000) });
  } catch (e) {
    fail(`download of ${url} failed: ${e.message}`);
  }
  if (!response.ok || !response.body) fail(`download of ${url} answered HTTP ${response.status}`);
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
