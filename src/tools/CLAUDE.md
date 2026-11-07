# tools (Python)

The two operator tools a release ships as assets ([ADR 0106](../../docs/decisions/0106-deployment-under-infra-tests-under-tests.md)
decision 8): `veyqctl.py` ([ADR 0096](../../docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md))
and `instance_backup.py` ([ADR 0056](../../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md)), which
`veyqctl` runs from a release's directory and the `veyq-tools` image runs as its entrypoint
([ADR 0098](../../docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)). Each module's
docstring owns what it does. Loaded in addition to the root `CLAUDE.md` when working here.

## What binds here
- **Each file ships alone** — fetched with `curl` onto an operator's host, or copied flat into the image's `/opt/veyq/`.
  So: Python 3.9 and the standard library only, and no import of anything under `scripts/`. A path into the checkout
  (`instance_backup.py`'s two defaults) is spelled in the file, and its selftest holds it to `scripts/repo_layout.py`.
- **`scripts/` reaches the tools through `repo_layout`**: `VEYQCTL`, `INSTANCE_BACKUP`, and `tools_importable()` before
  an `import`. A new reader there does the same, never spells `src/tools/`.

## Prove your work
`python3 src/tools/veyqctl_selftest.py` and `python3 src/tools/instance_backup_selftest.py` — Docker-free, run by
`pipeline.yml`'s `web-e2e` job; the backup selftest also runs inside the built image (`build` job), where the cases that
read the checkout skip. A change to `veyqctl.py`'s cosign identity, floor or asset names also runs
`python3 scripts/validate_release_identity.py`, which reads them.
