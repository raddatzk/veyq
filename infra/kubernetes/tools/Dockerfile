# The tools image, `ghcr.io/raddatzk/veyq-tools` (ADR 0098): the one capture implementation,
# `scripts/instance_backup.py`, with the binaries its in-pod mode needs on PATH — the MariaDB client of the server's
# own pinned version, `rclone`, Python. It runs the chart's `backup-gate` init container and a restore Job; Compose
# never runs it (`veyqctl` fetches the tool as a release asset). Released and signed with the app and admin images
# (ADR 0057), versioned with them: the gate refuses an app release that is not this image's own.
#
# Built from the repository root, like the other two: `docker build -f infra/kubernetes/tools/Dockerfile --target runtime-tools .`

# The client binaries of the server's own version (`mariadb:11.4`, the pin validate_image_pins.py holds): Ubuntu's
# archive carries 10.11, so they are copied from the pinned server image, which is Ubuntu 24.04 like the base below
# — the same C library, measured with `ldd`.
FROM mariadb:11.4@sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b AS mariadb-client

# The rclone the tool already runs as a container (`RCLONE_IMAGE`), as a binary: a static Go build.
FROM rclone/rclone:1.75.0@sha256:b06aed988cf5967de7c25be5925240983981c757f4ed1ac9d2fa659d51d60548 AS rclone

FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac AS runtime-tools
# python3 for the tool; libedit2, libncurses6 and libbsd0 for the `mariadb` client, which links them (`ldd` on the
# pinned image), libssl3t64 and zlib1g for both client binaries; ca-certificates for an operator's own store over TLS.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 libedit2 libncurses6 libbsd0 libssl3t64 zlib1g ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=mariadb-client /usr/bin/mariadb /usr/bin/mariadb-dump /usr/bin/
COPY --from=rclone /usr/local/bin/rclone /usr/local/bin/rclone
COPY src/tools/instance_backup.py src/tools/instance_backup_selftest.py /opt/veyq/

# What the gate holds a release change to (instance_backup.py `gate`): this image's release, and the release a new
# major is entered from — the predecessor this release's own upgrade proof and chart check ran from
# (`scripts/release_assets.py --major-entry`), empty when it lies in no previous major. Stamped here and only here.
ARG VEYQ_VERSION=""
ARG VEYQ_REVISION=""
ARG VEYQ_MAJOR_ENTRY=""
RUN python3 -c 'import json, os; json.dump({"release": os.environ["VEYQ_VERSION"], "majorEntry": os.environ["VEYQ_MAJOR_ENTRY"] or None}, open("/opt/veyq/release.json", "w")); print(open("/opt/veyq/release.json").read())'
LABEL org.opencontainers.image.title="veyq-tools" \
      org.opencontainers.image.version="${VEYQ_VERSION}" \
      org.opencontainers.image.revision="${VEYQ_REVISION}"
ENV VEYQ_VERSION=${VEYQ_VERSION} \
    VEYQ_REVISION=${VEYQ_REVISION} \
    PATH="/usr/local/bin:/usr/bin:/bin"

# Non-root, as every veyq image (validate_images_run_unprivileged.py); nothing listens.
USER 10001
ENTRYPOINT ["python3", "/opt/veyq/instance_backup.py"]
CMD ["gate"]
