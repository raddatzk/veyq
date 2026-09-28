---
id: cr-build-inputs-pinned
title: What builds the release is checked before it runs — generator JAR, Gradle distribution, backend dependencies
status: in-progress
satisfies: []
adrs: ["0057"]
invariants: [API-first, self-hostable by default]
---

## Goal

Four build inputs are fetched with no check at all.
- The OpenAPI generator JAR. `@openapitools/openapi-generator-cli` downloads it with **zero** checksum logic (no
  `createHash`, `sha*` or `integrity` in its bundle), and a JAR already sitting in its storage directory is used
  unchecked. It runs in CI and inside **both** image builds, and the clients it generates are untracked, so a
  poisoned generator's output is never reviewed.
- The Gradle distribution: `gradle-wrapper.properties` carries no `distributionSha256Sum`.
- The backend's whole dependency graph: there is no `verification-metadata.xml`.
- The generator itself runs at two versions: 7.21.0 in the backend, 7.23.0 in `web`/`admin-web`.

After this CR the JAR is verified against a pinned SHA-256 on every generation, the wrapper verifies its
distribution, Gradle verifies every dependency, one generator version serves all three surfaces, and a guard
holds the pins together. This is inbox #163's build-time half, with the three items the owner folded in on
2026-09-23.

## Context & constraints

- **The decision:** [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) §6 (*at build
  time …*) and its Consequences: checksums are trusted on first write, the same trust the JAR pin has, and
  Dependabot is remembered not to regenerate Gradle's metadata.
- **Measured at refine**, in the installed `@openapitools/openapi-generator-cli` 2.39.0:
  - `download()` streams to a temp file and moves it into place, with no checksum;
  - `isDownloaded()` is a file-exists check on `<storageDir>/<version>.jar`;
  - the default URL is Maven Central (`config.schema.json:31-34`).

  The local `node_modules/@openapitools/openapi-generator-cli/versions/7.23.0.jar` hashes to
  `cb087e40001e31eb08ef6140dd5de10938dbeb89016a1fe0481eaa25cd569026`. That is **not** cross-checked against
  Maven Central, and phase 1 does that before it pins anything.
- **Why the verifier is Node, not Python** (the repo rule is Python for scripts): it runs inside `npm ci`'s
  `prepare` in both builder stages (`Dockerfile:8,30`, `admin-web/Dockerfile:13,30`), which are node images.
  It is remembered, not measured, that the slim node images carry no `python3`, so confirm it before relying
  on it. If they do carry it, write the verifier in Python.
- **Where Gradle runs**, and therefore which configurations the metadata must cover. Locator:
  `grep -rn -E 'gradlew' .github/workflows Dockerfile`. At refine that found `check`, `quarkusBuild` (JVM, and
  native with `-Dquarkus.native.enabled=true` in the Mandrel stage), `pitest` and `help`.
- **The owner works in IntelliJ.** An IDE sync fetches `-sources`/`-javadoc` artifacts no task resolves, and
  unlisted artifacts fail verification. Metadata that breaks the IDE is a defect of this CR, not a local quirk.
- **Out of scope:** the runtime images and the Dockerfile `FROM` lines (`cr-image-provenance-verifiable`). npm
  packages are already integrity-pinned by the root `package-lock.json`.

## Phases

- [x] 1 generator JAR — one verifier, used by both `web` and `admin-web` (one copy, reachable from both Docker
      build stages before their `npm ci`; check the `COPY` lines with `grep -n COPY Dockerfile admin-web/Dockerfile`).
      Its behaviour:
      - it fixes the generator's `storageDir` in both `openapitools.json` files;
      - it downloads the pinned version from Maven Central when absent;
      - it **always** checks the file's SHA-256 against the pinned value, **including when the file was
        already there**;
      - it fails closed.

      `generate:api` runs it first, so the wrapper then finds a present, verified JAR and never downloads. The
      pin lives in one file keyed by version, and its header says how the value was obtained: downloaded from
      Maven Central and cross-checked against Maven Central's published checksum (and its `.asc` signature if
      the signing key can be checked). Locator:
      `grep -rn -E 'openapi-generator|generate:api|storageDir' web/openapitools.json admin-web/openapitools.json web/package.json admin-web/package.json`.
      **Bite it** twice: flip one hex digit of the pin; then restore the pin and overwrite the stored JAR with
      any other file. Both must make `npm run generate:api` fail, and the second is the case the wrapper alone
      never catches.
      · Done when: `npm ci` from the root plus `npm run check` in `web/` and `admin-web/` are green.
- [x] 2 one generator version — raise `backend/gradle/openapi.gradle.kts:4` to the version the clients use, or
      move all three to one newer version. Generated code is untracked, so generate the backend models before
      and after and diff the two trees: a changed server interface is reviewed, not waved through.
      · Done when: `./gradlew check` green, and the diff of the generated trees is read and summarised in the
        retro.
- [x] 3 Gradle distribution — `distributionSha256Sum` in `backend/gradle/wrapper/gradle-wrapper.properties`,
      from Gradle's published `.sha256` for the exact `distributionUrl`. **Bite it:** a wrong sum makes
      `./gradlew help` refuse, with an empty `~/.gradle/wrapper/dists/gradle-9.5.1*` so it actually downloads.
      Snapshot and restore that directory rather than deleting your cache blind.
      · Done when: `./gradlew help` green with the correct sum.
- [ ] 4 Gradle dependency verification — `backend/gradle/verification-metadata.xml`, written with
      `--write-verification-metadata sha256` over **every task the locator above found**, so no configuration
      a workflow or the Dockerfile resolves is left out. The native build resolves in the Mandrel stage. Either
      run that stage and fold its artifacts in, or show that it resolves nothing the JVM build does not, and
      say which in the retro. Add a trust rule for `-sources.jar`/`-javadoc.jar`, which never execute, so that
      an IntelliJ sync passes: confirm it with a real **Reload Gradle Project** in the owner's IDE setup. It is
      a manual step, and the retro says it was done. **Bite it:** alter one checksum and see `./gradlew check`
      refuse, naming the artifact.
      · Done when: `./gradlew check` and `./gradlew quarkusBuild` green with verification on.
- [x] 5 guard — `scripts/validate_build_input_pins.py` asserts:
      - the generator version in both `openapitools.json` files and in `openapi.gradle.kts` is one value;
      - the JAR pin file has an entry for that value;
      - the wrapper carries a `distributionSha256Sum`;
      - `verification-metadata.xml` exists with `verify-metadata` on.

      Derive each site from the files. **Bite each** by snapshot-and-restore: bump one generator version alone,
      delete the pin entry, drop the wrapper sum, flip `verify-metadata` off.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Definition of done

- Every phase is done, and every bite above has been seen red.
- `./gradlew check`, `./gradlew quarkusBuild`, and `npm run check` in `web/` and `admin-web/` are green.
- The trust-chain guards are green.

**Not proven by the above, named:** the Docker builds and the CI jobs that run these steps. No workflow has run,
and `docker build` can stall on this Mac's keychain credential helper (local memory; this holds for this CR's
shape). The local `npm ci` and Gradle runs exercise the same scripts.

## Retrospective

**State at hand-off (2026-09-27, on main 2e74f97):** phases 1, 2, 3 and 5 are done; phase 4 is done except its
one manual step. Every bite was seen red, and every gate is green on the merged tree. **Open, and the reason
this file still exists:** the owner's IntelliJ *Reload Gradle Project* against the new metadata. The
implementing session could not perform it. What stands in for it is a command-line proof: an init script ran an
`ArtifactResolutionQuery` for sources and javadoc over the compile classpaths, which is what an IDE sync
issues. With the trust rule, 372 components and 726 files resolved with 0 failures. With the rule removed, the
same query failed: `726 artifacts failed verification`. That proves the rule is load-bearing. It does not prove
that IntelliJ issues nothing else. Tick phase 4 and delete this file once the owner's reload is clean.

- **`quarkusDev` was not covered after all (found 2026-09-28 by the coordinator, starting the dev backend).** `./gradlew quarkusDev` failed at once: `Dependency verification failed for configuration ':quarkusBootstrapResolverConfiguration'`, checksums *missing* (not mismatched) for `jboss-parent-37.pom` and `slf4j-jboss-logmanager-1.1.0.Final.pom`. That configuration is resolved only when the `quarkusDev` task *executes*, so neither the resolve-every-configuration write nor a write over `help check quarkusBuild pitest` reaches it — the retro's "measured away" below was measured over the wrong set. Fixed on main by adding both SHA-256s, each cross-checked against Maven Central's published `.sha1`; `quarkusDev` then started. **Next time:** the task list a metadata write runs over includes the dev-mode task, run long enough to start.
- **Phase 2's diff, read:** generator 7.21.0 → 7.23.0 changes no server interface. The `openapi-api/` tree
  differs only in blank lines and the `@Generated(... "Generator version")` stamp (`diff -r -B -w -I 'Generator
  version'` → only the `VERSION` file). In the models, 7.23.0 adds a KDoc description where a `$ref` property
  carries a sibling `description:`. It also corrects every enum's `decode` KDoc: `decode` already threw, and the
  old KDoc claimed it returned null. And it adds an unused lenient `decodeOrNull`. `admin-channel/` is
  byte-identical. Maven Central's newest release is 7.25.0. All three surfaces moved to 7.23.0, because 7.23.0
  touched only one surface's generated code, while 7.25.0 would have touched all three. Bumping to 7.25.0 is a
  release act, not part of this CR.
- **Native stage, run rather than just shown, with one limit.** The real Dockerfile native command ran in the
  pinned Mandrel image with a cold Gradle home. Every Gradle step finished, and `native-image` then died of OOM
  (exit 137) at 5 GB inside this Mac's 8 GB Docker VM. That is a machine limit, not a verification failure. The
  same image then ran `-Dquarkus.native.sources-only=true` to BUILD SUCCESSFUL in write mode, and the metadata
  was unchanged. So the native path resolves nothing the JVM build does not.
- **What went wrong — the CR's "run it over every task" was necessary, not sufficient.** The first write came
  from a warm `~/.gradle` and missed `okhttp-bom-5.3.2.module`, which a cold cache fetches. A clean Linux
  container found it first. A cold macOS write reproduced it, so the cause is the cold cache and not the OS.
  Every Docker build and fresh CI runner would have been red. Conversely, a cold write over only `help check
  quarkusBuild pitest` produced the identical 1670 artifacts as the resolve-every-configuration write. The
  worry that the owner's `quarkusDev` classpaths were uncovered was measured away, not assumed away. Root
  cause: the CR named the tasks and not the cache state. **Next time:** a CR that writes a lockfile-like
  artifact names the substrate it is written from (empty cache, the release's OS) as part of the done-when.
  Harvested: the command is in `backend/CLAUDE.md`, and the general rule is in `changes/README.md`.
- **Process:** commit signing (1Password) failed at the probe before phase 1 and at every later probe, so
  nothing is committed. The per-phase path lists went to the coordinating session. And `cp`'s interactive alias
  hung one background step on an overwrite prompt, the trap the local memory already names. `command cp -f`
  would have avoided it; the rule existed and was not applied.
