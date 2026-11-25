# The toolchain that compiles libwebp into the client's WebP encoder (ADR 0127). Built and run by build.py, never by
# hand: it fetches the pinned libwebp tag inside and writes the encoder to the directory mounted at /out. The digest is
# kept current by Renovate (its `webp encoder` group, with libwebp); a new one changes the output, which the Renovate
# follow rebuilds with `build.py --write` on its pull request before the rebuild check compares it (README.md). Never published; the `runtime-` stage name and the
# non-root USER are every veyq image's (scripts/validate_images_run_unprivileged.py), as for tests/fuzz/Dockerfile.
FROM emscripten/emsdk:7.0.1@sha256:73aece2e0ab748487945ad117b79fc4d277e9f656e54fc800a9b1a0a6562715b AS runtime-webp-encoder
# /work is the fixed path the compile reads from; writable, because the build runs as the caller's uid (build.py).
RUN mkdir -p /work && chmod 0777 /work
COPY build.py encoder.c /work/
USER 10001
ENV HOME=/tmp
ENTRYPOINT ["python3", "/work/build.py"]
