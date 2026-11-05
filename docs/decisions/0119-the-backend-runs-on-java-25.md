# ADR 0119 — The backend runs on Java 25

- Status: Accepted
- Built: yes
- Date: 2026-11-05
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

The backend compiled for, tested on and shipped Java 21. V1 ships as an image an operator runs for years, and Java 25
is the current LTS: Adoptium supports Temurin 25 until at least 2031 and Temurin 21 until at least 2029 (remembered,
Adoptium's published support table; no copy of it is in the repository). No instance runs yet, so moving now means no
instance ever has to make the jump.

What had to move, measured on this tree (Gradle 9.8.2, Kotlin 2.4.10, Quarkus 3.40.1.1, 2026-11-05):

- **Gradle** already runs on 25: `./gradlew --version` names the launcher and daemon JVM Temurin 25.0.4.1. No wrapper
  bump.
- **Kotlin 2.4.10** targets 25: `javap` of `kotlin-gradle-plugin-api-2.4.10`'s `JvmTarget` lists `JVM_25` (and
  `JVM_26`), and a compiled class reads `major version: 69` (`javap -v`).
- **Quarkus 3.40.1.1** runs on 25: the whole `./gradlew check` (2730 tests, 0 failures, 6 skipped) and lane L1 against
  the image built on it.
- **PIT does not**: 1.19.4's ASM is 9.7.1, whose highest `Opcodes` constant is `V24`, and `./gradlew :core:pitest`
  failed with `Unsupported class file major version 69` (`ClassReader`, from `DefaultBuildVerifier`). 1.19.6 is the
  first PIT on ASM 9.8 (`asm.version` in `pitest-parent-1.19.6.pom`; ASM 9.8's `Opcodes` has `V25`); with it the gate
  generated 193 mutations and killed all 193. Still above pitest-junit5-plugin 1.2.3's floor of 1.19.4.
- Nothing else in the build reads bytecode: no JaCoCo or Kover is applied, and `buildSrc`'s `ThirdPartyLicenses` reads
  POMs and the license files inside the jars, never classes.

## Decision

1. **One Java major, 25, named once per place that installs a JDK.** The Gradle toolchain of the root and of every
   module (`veyq.module`) reads `java` from `src/backend/gradle/libs.versions.toml`. Outside Gradle the major is
   spelled where the JDK is chosen: the Dockerfile's build stage `eclipse-temurin:25-jdk-jammy` and its
   `javac --release 25` for the image's own commands, the runtime `gcr.io/distroless/java25-debian13:nonroot`
   ([ADR 0118](0118-the-app-image-is-distroless-and-carries-its-own-commands.md)), and every workflow's `setup-java`.
   No guard ties them: every mismatch is loud on its own — Gradle finds no toolchain of the major it asks for, a runtime
   older than the class files refuses to start the server (`UnsupportedClassVersionError`), and an image's commands
   compiled for a newer `--release` do not run in it either. Both base images are pinned by their multi-arch index
   digest, amd64 and arm64 in each (`docker buildx imagetools inspect`, 2026-11-05). The build stage carries Temurin
   `25.0.5+8`, the runtime the same build, `java.version.date` 2026-10-20 (`java -XshowSettings:properties` in both).
2. **CodeQL sets up the JDK too.** Its `java-kotlin` autobuild ran on whatever JDKs the runner image preinstalls; it
   now runs `setup-java` 25 like `ci.yml`'s jobs, so the toolchain does not depend on the runner image's list.
3. **Compact Object Headers stay off** (`-XX:+UseCompactObjectHeaders`, a product flag in 25, off by default — `java
   -XX:+PrintFlagsFinal` in the image). Measured on lane L1, one whole run per side (172 tests, green both), the image's
   own entrypoint plus the flag and a GC log, the app container read once the suite had ended: the heap after a
   collection over the second half of the run had a median of 76 MB without the flag and 69.5 MB with it (the last
   collection 78 and 73 MB); the container's memory was 634.1 MiB without and 645.3 MiB with it (`docker stats`), the
   committed heap 396 MB in both, set by `-XX:InitialRAMPercentage=40` of the 1 GiB limit (2026-11-05, arm64, load
   average about 6, not a quiet machine). So about 6 MB less live data and nothing an operator's memory limit sees:
   the committed heap is what the container holds, and it does not move. A flag that changes the object layout of every
   allocation for that is not worth carrying while it is new. The JVM picked the serial collector on its own at this
   limit (`Using Serial`, the log's first line). Inbox #825 says when to measure again.
4. **The AOT cache is not built here** (JEP 483, 514, 515; Quarkus's own support) — inbox #826 holds what was learnt.

## Consequences

- PIT is pinned at 1.19.6 for the bytecode it can read; a later Java major moves it again, and that failure is the
  loud one above (`Unsupported class file major version`).
- The upgrade proof builds its predecessor commit with that commit's own toolchain, so until this change is its
  predecessor it needs a JDK 21 beside the 25 `setup-java` installs (remembered: GitHub's Ubuntu runners preinstall
  Temurin 21; locally `/usr/libexec/java_home -V` lists 21.0.13). The job is disabled until the first release.
- Unverified: an amd64 build of the image and an amd64 L1 (everything above ran on arm64), and the first CI run of the
  workflows' `setup-java` 25 and CodeQL's new step.

## Related

[ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) (the PIT gate),
[ADR 0057](0057-release-identity-and-deployment-recipe.md) § 6 (pins),
[ADR 0118](0118-the-app-image-is-distroless-and-carries-its-own-commands.md) (the runtime image).
