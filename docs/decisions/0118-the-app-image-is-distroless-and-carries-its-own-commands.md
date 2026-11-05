# ADR 0118 — The app image is distroless and carries its own commands

- Status: Accepted
- Built: yes
- Date: 2026-11-05
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

The app image's runtime stage was `eclipse-temurin:21-jre-jammy`, a whole Ubuntu 22.04: 7666 filesystem entries and
about 470 executables on the `PATH` — `sh`, `bash`, `curl`, `wget`, `apt`, `dpkg`, `tar`, `perl`, `openssl`, `gpg` among
them — at 380.4 MB on arm64 (`docker export` of `veyq:local` read with Python's `tarfile`, `docker image inspect`,
2026-11-05). The server needs one of them: `java`. Everything else is attack surface and scanner findings an operator
has to triage, for an image that runs as uid 10001 on a read-only root anyway
([ADR 0071](0071-runtime-hardening-on-both-targets.md)).

What a deployment ran in the image besides the server, found by reading `src/tools/`, `scripts/`, `infra/`, the chart,
`tests/e2e/stack/` and the Run pages:

- the Compose healthcheck, `curl -fsS http://127.0.0.1:8080/api/setup`;
- `veyqctl`'s key placement and erasure-ledger read, a one-off `app` container with `--entrypoint /bin/sh` running
  `cat`, `mv` and `test` (and the Run page's by-hand copy of both);
- lane L1's truststore, written with the image's `keytool` found on the `PATH`;
- on Kubernetes, `kubectl cp` of the config-secret key out of the pod (the export, the upgrade's step 0) and into it
  (the hand restore) — `kubectl cp` runs `tar` inside the container.

`docker cp` out of a stopped container (`veyqctl backup`, the capture's step 3) runs nothing in the image, and the
chart's probes are `httpGet`.

## Decision

1. **The runtime is `gcr.io/distroless/java21-debian13:nonroot`**, pinned by its multi-arch index digest (amd64,
   arm64, s390x, ppc64le, riscv64 — the registry's index read with `curl`, 2026-11-05). It carries Debian 13's glibc, CA
   certificates and tzdata and the **Eclipse Temurin** 21 JRE that Google's distroless project packages from Adoptium's
   builds: `21.0.13+9`, `java.version.date` 2026-10-20 — the October update in the image sixteen days later
   (`java -XshowSettings:properties` in the pulled image). The vendor is the one the image had before, so nothing about
   the JVM's behaviour moves with the base; the module set is a full JRE's, `jdk.localedata` included, which
   `RecipientCountriesTest` depends on (`java --list-modules`). Nothing in the backend uses AWT or fonts
   (`grep -rn -E 'java\.awt|javax\.imageio|ImageIO|BufferedImage' --include='*.kt' src/backend` finds none). The image is 300.8 MB, 79.6 MB less;
   2600 entries; on the `PATH` only `java`, and no `sh`, `bash`, `dash`, `busybox`, `curl`, `wget`, `apt`, `dpkg`, `tar`,
   `cat`, `mv` or `ls` anywhere (the same export and read, of `veyq:dl-agent`). Debian 13 rather than 12: both variants
   are published, and 13 is Debian's stable release, so its security support runs the longer. Dependabot's `docker`
   ecosystem proposes the `nonroot` tag's new digest like every other pin's (remembered, like the rest of
   `.github/dependabot.yml`, which no Dependabot has read yet).
2. **The user stays uid and gid 10001**, now with a `veyq` entry in the image's `/etc/passwd` and `/etc/group` (home
   `/nonexistent`), so `user.name` is `veyq` as before. The distroless `nonroot` user (65532) is as unprivileged, and
   moving to it would change the owner of every key a volume or a claim already holds: a key born `10001:10001 0600` is
   unreadable to 65532, and the instance would read `ABSENT` after an upgrade. The chart's `fsGroup`, the guards and the
   server's own fix hint name 10001 too.
3. **What needs a shell happens in a build stage, `image-prep`, and travels as files.** The runtime stage has no
   `RUN`: each directory a volume covers is an empty directory copied with `--chown=10001:10001` and its mode, which
   BuildKit gives the destination itself — `/var/lib/veyq` `0700`, `/etc/traefik/dynamic` and `/work` `0755`, all
   `10001:10001`, `/etc/traefik` root's. A fresh named volume on `/var/lib/veyq` came up `10001:10001 0700`
   (`docker cp` of the volume read with `tarfile`). `validate_app_volume_ownership.py` reads that `COPY` shape.
4. **The image carries its own commands**: `/opt/veyq/image-commands.jar`, one class on the JDK alone
   (`src/backend/image/ImageCommands.java`), run by the image's `java`:
   - `health [URL]` — `curl -fsS`'s verdict over a plain socket: a 2xx or 3xx is healthy, a 4xx, a 5xx or no answer is
     not;
   - `place-key PATH` — stdin into a sibling born `0600`, renamed over the key; an empty stdin writes nothing;
   - `read-file PATH` — the file to stdout, exit 3 when there is none.

   `image-prep` compiles it with `-Werror` and runs every refusal and the placement before the image can exist. A
   static `curl` or a `busybox` would have done the same and brought back exactly what this removes. `docker cp` into a
   container would need no command at all, but whose file it makes depends on the daemon resolving the container's
   user, which is not measured across the Docker versions an operator runs. A subcommand of the server would load its
   whole classpath for every probe.
5. **The Compose healthcheck is `java -jar /opt/veyq/image-commands.jar health`**, with the JVM held small
   (`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx16m -XX:-UsePerfData`), every 15 s as before. One run costs about
   47 ms of CPU — 0.3 % of one core at that interval — and 36 MB committed, then exits (the container's `cpu.stat`
   across 20 runs, and `-XX:NativeMemoryTracking=summary`, 2026-11-05). The status is load-bearing: `veyqctl` waits on it
   (`up --wait` in `install`, `start`, `upgrade`, `restore`, `rollback`) and `status` reports it, lane L1 waits through
   `veyqctl install`, and `scripts/measure_volume.py` and `scripts/profile_hot_paths.py` read
   `.State.Health.Status`. No service `depends_on` the app being healthy (the admin waits for `service_started`).
   Dropping the healthcheck instead would let `up --wait` return the moment the JVM starts, before Flyway has migrated,
   and nothing on the host can poll the app from outside: its port is published only through Traefik, behind TLS and
   the domain's DNS. Kubernetes keeps its `httpGet` probes.
6. **On Kubernetes the operator can give the key as a Secret**: `app.configSecret.existingSecret` (and `key`, default
   `config-secret.key`), ADR 0050's "supply your own" in the chart's form. The chart mounts that one key read-only at
   `/run/secrets/veyq-config-secret/config-secret.key`, `defaultMode: 0440`, and points
   `VEYQ_SECURITY_CONFIG_SECRET_FILE` at it. The server reads a key file that is there and never writes it
   (`ConfigSecretSeal.readOrGenerate`), so a read-only, group-readable file is all it needs; the kubelet gives a
   Secret's files the pod's `fsGroup`, 10001, which the app reads it by (remembered, not run on a cluster). The claim
   stays rendered: the erasure ledger defaults to the key's directory, which a Secret's mount cannot hold, so the chart
   sets `VEYQ_ERASURE_LEDGER_FILE=/var/lib/veyq/erasures.log` on the claim. The hand restore puts the restored key into
   that Secret instead of `kubectl cp`-ing it into the pod, and the key export reads it with the image's `read-file`
   through `kubectl exec`, which needs no `tar`. **Compose's counterpart is the volume**: the operator's own key goes in
   with the image's `place-key`, as a restore puts one, before first-run setup seals anything. A Compose `secrets:` file
   would serve the server too (`validate_config_secret_persistence.py` accepts `/run/secrets/`), but `veyqctl restore`
   places the key into the volume and reads the ledger beside it, so that is the one path documented.
7. **Debugging without a shell**: `docker compose exec app java -jar /opt/veyq/image-commands.jar read-file …` and
   its `kubectl exec` twin for a file; for more, a container of the operator's choosing beside the app — `kubectl
   debug --target=app` (an ephemeral container in the app's process namespace), or on Compose `docker run` with
   `--pid container:<app>` — never a tool baked into the image. The Run area's troubleshooting page says how.

## Consequences

- `docker compose exec app sh` and `kubectl exec … -- sh` stop working. That is the point, and the one habit it breaks
  has its answer in decision 7.
- Every place that ran something in the image now runs `java`: the healthcheck, `veyqctl`'s two one-off containers
  (`veyqctl_selftest.py` holds them to `--entrypoint java` and no shell), lane L1's `keytool`
  (`java -m java.base/sun.security.tools.keytool.Main`, the main class keytool's own launcher runs), and the Run
  pages' by-hand commands.
- `tools`, `admin` and the third-party services keep their images; this decides the app image only. The admin image is
  nginx on Alpine and the tools image needs a shell, a MariaDB client and rclone by design
  ([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)).
- Unverified: an amd64 build (the measurements are arm64's), a cluster mounting the Secret (rendered and checked by
  `chart_meta_check.py` (ii) only), `kubectl debug` against this pod, Dependabot reading the new pin, and the first CI
  run of all of it.

### Alternatives

- **`jlink` a runtime from the build JDK onto `gcr.io/distroless/cc-debian13`** — smaller, one Temurin pin for build
  and runtime. Rejected: the module list is ours to get right, and a module a path lane L1 never walks is missing
  only in production; the distroless image already follows Temurin's quarterly updates.
- **Alpine with a Temurin JRE** — the owner chose distroless: Alpine still ships a shell and a package manager, and musl
  is a second libc for the JVM to be tested on.
- **No healthcheck, waiters polling from outside** — decision 5.
- **The distroless `nonroot` user** — decision 2.

## Related

[ADR 0050](0050-operator-config-secret-encryption.md) (the key and "supply your own"),
[ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) (leg (c), the ledger),
[ADR 0057](0057-release-identity-and-deployment-recipe.md) § 6 (pins), [ADR 0071](0071-runtime-hardening-on-both-targets.md)
(the posture this narrows), [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) (`veyqctl`).

For Server Owners, on the docs site: [Hardening](../../site/src/content/docs/run/hardening.md),
[Storage and email](../../site/src/content/docs/run/storage-and-email.md) (your own key),
[Backup and restore](../../site/src/content/docs/run/backup-and-restore.md),
[Upgrade](../../site/src/content/docs/run/upgrade.mdx), [Kubernetes](../../site/src/content/docs/run/kubernetes.md),
[Troubleshooting](../../site/src/content/docs/run/troubleshooting.md) (looking inside the container).
