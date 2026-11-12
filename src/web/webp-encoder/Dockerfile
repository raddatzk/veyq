# The toolchain that compiles libwebp into the client's WebP encoder (ADR 0127). Built and run by build.py, never by
# hand: it fetches the pinned libwebp tag inside and writes the encoder to the directory mounted at /out. The digest is
# kept current by Dependabot's docker ecosystem; a new one changes the output, so its pull request is red at the
# rebuild check until `build.py --write` is run on it (README.md). Never published; the `runtime-` stage name and the
# non-root USER are every veyq image's (scripts/validate_images_run_unprivileged.py), as for tests/fuzz/Dockerfile.
FROM emscripten/emsdk:7.0.0@sha256:6bd7aef93408b10bf7b36642cb7c91119b302f720b9ec2d20439f73cf07fe894 AS runtime-webp-encoder
# /work is the fixed path the compile reads from; writable, because the build runs as the caller's uid (build.py).
RUN mkdir -p /work && chmod 0777 /work
COPY build.py encoder.c /work/
USER 10001
ENV HOME=/tmp
ENTRYPOINT ["python3", "/work/build.py"]
