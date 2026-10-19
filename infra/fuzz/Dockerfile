# The API fuzzing's Schemathesis (ADR 0091 decision 9), built by `scripts/run_api_fuzz.py` from this directory.
#
# Our own image rather than the official `schemathesis/schemathesis`: that one also installs TraceCov, a proprietary,
# binary-only coverage plugin, and loads it on every run unless switched off. This one is the MIT tool from PyPI and
# what it installs, nothing else: every package pinned by version and wheel hash (`requirements.txt`, written by
# `lock.py`), installed with `--require-hashes`, wheels only — no compiler runs. `hooks.py` still refuses to load if
# TraceCov is ever imported.
#
# CPython 3.13: Schemathesis needs 3.11 or newer, which the host scripts (3.9) are not — hence an image at all.
FROM python:3.13-slim@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f AS runtime-fuzz

COPY requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --disable-pip-version-check --require-hashes --only-binary :all: \
        -r /tmp/requirements.txt \
    && rm /tmp/requirements.txt

# Non-root, as every veyq image; the runner passes its own uid/gid so the reports it writes are the caller's.
USER 10001
ENV HOME=/tmp
ENTRYPOINT ["schemathesis"]
