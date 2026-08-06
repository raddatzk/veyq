# Tiny one-shot init image for the bundled Garage store (cr-0029, ADR 0013). Garage's official image is
# shell-less (scratch), so it can't run the multi-step init script itself. This image is just a shell
# (Alpine) plus the real, statically-linked Garage CLI copied out of the official image — so the
# `garage-init` compose service can drive the node over RPC (no docker.sock needed). Throwaway/dev only.
FROM dxflrs/garage:v2.0.0 AS garage

FROM alpine:3.21
COPY --from=garage /garage /usr/local/bin/garage
ENTRYPOINT ["/bin/sh"]
