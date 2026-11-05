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
4. **The image carries no AOT cache** (JEP 483, 514, 515), neither for the server nor for the healthcheck's probe:
   measured, neither earns what it costs. Each cache was written by the runtime image's own `java`
   (`-XX:AOTCacheOutput`, in a `docker run` of the image) and copied into an image built on it; arm64, 2026-11-05,
   load average 7–11 throughout (the IDE indexing beside the runs, not a quiet machine), the variants interleaved run
   by run so the load fell on all of them alike.
   - **The server.** Time from `docker start` to the first 200 on `/api/setup`, polled every 10 ms from the host, a
     fresh container of lane L1's kept instance per run (its env, volumes, networks and 1 GiB limit; Flyway up to
     date), seven runs each, medians: **2.60 s without a cache, 2.09 s with one a build could train.** A build has no
     database, and Quarkus's own training mode (`-Dquarkus.appcds.generate=true`, what `quarkus.package.jar.aot.*`
     runs) does not stop at static init here: the boot goes on until Hibernate asks for the datasource and fails with
     exit 1 after 4.5 s. That cache is 89 MB (20 MB gzipped) on an image of 334 MB, and it serves 9,896 of the
     20,252 classes a full boot loads (`-Xlog:class+load`; 1,447 come from the JDK's default CDS archive without
     it) — custom-loader classes included, so Quarkus's fast-jar loader does not shut it out. The ceiling, a cache
     trained by a full boot against the database and store (which a build cannot have): 1.54 s, 160 MB.
     Quarkus's `aot-jar` packaging (every jar on the JDK's class path) is 0.14 s slower without a cache (2.74 s),
     1.89 s with a build-trainable one and 1.17 s with the full-boot one, 166 MB. Half a second off a start that
     happens once per deploy is nothing an operator waits on; a quarter more image on every pull is.
   - **The probe.** CPU per run in a holder container's cgroup (`cpu.stat` `usage_usec` across 20 `docker exec`s of
     the Compose healthcheck's command, read from the VM's cgroup tree), the probe answered by the live app, three
     rounds each: **53.0 ms with today's flags, 42.8 ms with a cache** (10.9 MB, 2.9 MB gzipped), a wall time of
     67 against 61 ms including `docker exec`. `-Xint` added to today's flags does more for no bytes:
     39.6 ms, and 34.7 ms with the cache on top — inbox #827. A probe that cannot reach the app exits 1, so a
     training run of it in the image's last stage needs a command of its own to exit 0 (as the server's would).
   - **What a cache would risk, measured:** a cache that does not match is reported and ignored, never fatal unless
     `-XX:AOTMode=on` — another class path ("shared class paths mismatch"), another JVM build (the build stage's JDK:
     "This file is not the one used while building the AOT cache: …/lib/modules, size has changed"), an absent file,
     each exit 0; ZGC cannot use it in 25 ("Unable to use AOT cache"), G1 maps a cache the serial collector wrote.
     Written in the image's last stage after the `COPY` of the jars, it could not go stale inside one image: any
     change to a jar or the base invalidates the layer that writes it (BuildKit's layer cache, remembered), and the
     JVM checks the files it was built from besides (their size, as the JDK case shows).

   Measure again when a start becomes something someone waits on (a Kubernetes rollout that scales on demand), or
   when Quarkus can train without a datasource.

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
