---
id: cr-production-backup-and-upgrade-runbook
title: Make the documented backup reach production, prove it, then write the upgrade runbook on top
status: in-progress
satisfies: ["uc.admin.back-up-and-restore-instance"]
adrs: ["0056", "0057"]
invariants:
  - "Self-hostable by default — which CLAUDE.md names as a security guarantee, not only a sovereignty one. A documented operator procedure that cannot execute against the topology it is written for erodes it exactly as much as removing the procedure would."
---

## Goal

`infra/README.md` § *An upgrade begins with a backup* says in its own words that there is no upgrade runbook.
Write it — but not first. [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md)
§6 makes a backup the upgrade's **first step**, and that step is measurably broken in production: the
documented capture resolves to the wrong Compose project and cannot find the database it is told to dump. So
this CR proves the defect, fixes it, and only then writes a procedure that stands on something.

## Context & constraints

- [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) §6 (backup precedes
  upgrade) and Decision 2 (*cold* means the **writer** is stopped).
- [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) Decision 3 (the recipe travels
  inside the image and the image's copy is authoritative for a running instance) and its Consequences, which
  name this runbook as the thing they make possible and explicitly do **not** decide.
- [`uc.admin.back-up-and-restore-instance`](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md).
- `changes/README.md` § *where the defect is still live, order it guard → red → fix → green*, § *promote
  one-shot proofs*, § *before reshaping a symbol, grep `scripts/` for every guard that anchors on it*.
- **Not in scope:** the `curl | bash` management tool (inbox **#121**), which owns the shell that could *verify*
  the cold precondition. This CR takes that remedy's one-line half and leaves the tool to that row.

### The defect, measured

Measured 2026-08-17 against `main` @ `8b677eb`, clean tree.

| Claim | Locator |
|---|---|
| production is documented to run as project `veyq` | `grep -rn -- '-p veyq' infra/` → the command, the rule stated in prose beside it, and the compose file's own header comment |
| the tool emits no `-p` | `grep -n 'compose' scripts/instance_backup.py` → line 164, `["docker", "compose", "-f", str(self.compose_file), "exec", "-T", …]` |
| no compose file declares a project name | `grep -n '^name:' infra/*.yml` → nothing |

Compose therefore derives the project from the compose file's directory — `infra` — so a capture against the
production stack looks for `mariadb` in a project that does not exist.

**Why four rehearsals never caught it, which is the part worth keeping.** `restore.a/b` and `legthree.a/b` run
the tool in **compose mode** (no `--db-host`) against the **dev** stack, and `scripts/run_web_e2e.py:99` brings
that stack up with no `-p` — so its project genuinely *is* `infra`, the one topology where the missing flag is
accidentally correct. The harness's own docstring claims it proves *"the procedure `infra/README.md` prints"*;
for production that procedure names a different project **and** a different network. Nothing has ever brought
`infra/docker-compose.prod.yml` up: `grep -rn 'docker-compose.prod' scripts/ .github/workflows/ web/src`
returns two guards reading it as text, and nothing else.

### Settled decisions, each with the line that settles it

- **The lever is the project name, not the production image.** The defect is Compose project resolution, so the
  rehearsal drives *that* and nothing else: bring the existing dev stack up under an explicit second project
  name and run the documented capture against it. Building a production image to expose a project-name bug
  would test something else at many times the cost, and the e2e harness runs the backend as a host fast-jar
  (`scripts/run_web_e2e.py:116`) with no image-build step at all. **Stated limit, not a silent gap:** this
  leaves the production *topology* (the image, traefik's `80:443`, the six named volumes) unexercised. That is
  a separate proof and this CR does not claim it.
- **`name: veyq` goes in `docker-compose.prod.yml` only — never in `docker-compose.yml`.**
  `scripts/run_web_e2e.py:103` derives `DOCKER_NETWORK` as `f"{COMPOSE_FILE.parent.name}_default"` → the string
  `infra_default`. A `name:` on the dev file would change the real network while that derivation kept naming
  the old one, silently breaking every existing rehearsal.
- **Adding `name: veyq` breaks no guard.** Both files that anchor on this compose (`validate_release_identity.py`,
  `validate_config_secret_persistence.py`) read it with `yaml.safe_load`, not by line. Verified 2026-08-17 by
  injecting the key and running the whole glob — 25/25 green — then restoring byte-identically from a snapshot.
- **The runbook's recipe step is by hand.** ADR 0057 Decision 3 makes the image's copy authoritative, so the
  new recipe must come **out of the new image** before the stack comes up; `ls scripts/*.py` shows exactly two
  non-guard scripts and neither extracts it. `docker create` + `docker cp` is the procedure until #121 ships a
  tool.
- **The cold precondition stays the operator's, stated as a step.** ADR 0056 Decision 2 requires the writer
  stopped, and `scripts/instance_backup.py` says in its own docstring that it cannot check this because it
  cannot know what "the app" is on the host. The runbook makes it a numbered step and says the tool does not
  verify it — rather than wording it so the tool appears to.

## Phases

- [x] **1 e2e — record the red before anything is fixed.** Add a rehearsal that runs the **documented** capture
      command through the same `backup_tool()` path the existing rehearsals use, so it exercises the procedure
      rather than a private one, and holds it to this: *a capture pointed at the production compose file must
      not resolve the dev project.* Two captures **one flag apart** — same decoy database, bucket, key file,
      network, credentials — differing only in `--compose-file`; the dev file is the positive control on the
      same predicate, so a subject that fails anyway failed on the flag that differs. ·
      **Done when:** the new rehearsal fails, and its output names the instance it captured out of the wrong
      project.

      **Three measurements corrected this phase's plan; all three are why it is not the shape written above.**
      (a) *The stated means is not executable.* `infra/docker-compose.yml` pins `container_name:` on every
      service, so it cannot come up under a second project while the dev stack is up — and `run_web_e2e.py`
      brings that exact file up under `infra` on every run (`docker compose -p veyq-probe -f
      infra/docker-compose.yml create mariadb` → `Conflict. The container name "/veyq-mariadb" is already in
      use`). (b) *A stack is not an instance.* `capture` reads `veyq_tenant`, the flyway history and the blob
      index before it dumps and refuses an instance with no tenant, so a second stack would have to be
      **populated** — a capture→restore round trip, the `restore` group rebuilt for a project-name string. The
      decoy replaces it: four tables, instance-shaped and nothing more, so the question *"which project did it
      resolve?"* has an exit code instead of a wording. (c) *The predicted red does not occur here.* On a host
      running the dev stack, `docker compose -f infra/docker-compose.prod.yml ps` lists all four dev containers
      as its own and `… exec -T mariadb true` exits 0 — the production capture does not fail to find its
      database, it **silently captures the wrong one**, which is the worse defect and the one no operator would
      notice. The recorded red is therefore an **exit 0**: `captured database veyq_prod_project_decoy, domains
      decoy.invalid — an instance living in the DEV project`, with `no-app-container` in the same log proving
      it read the production file while resolving the dev project.

      **And the first version of this rehearsal reported a green it had not earned** — the subject "refused"
      because Compose died on `required variable MARIADB_PASSWORD is missing a value` before it resolved any
      project at all, and the one-flag-apart control could not see it, because the production file requires
      nine variables the dev file does not. The fix is in the rehearsal and stays there: the required set is
      **derived from the file** (`${VAR:?}`), and a `docker compose config -q` precondition runs first and
      reports INCONCLUSIVE rather than letting an unresolvable file pass as a verdict.

- [ ] **2 infra — the one-line fix, and the prose it retires.** Add top-level `name: veyq` to
      `infra/docker-compose.prod.yml`. Then supersede the rule it replaces at every site
      (`grep -rn -- '-p veyq' .` outside `.git`), with the shape of each correction named: the command at
      `infra/README.md:106` **loses the flag**; the *"Always name the project"* paragraph at `:109` is
      **deleted, not reworded** — the file now carries what that paragraph asked the reader to remember; the
      header comment at `docker-compose.prod.yml:6` **loses the flag**. Check the inbox and `changes/` in the
      same sweep: a row naming the old rule is a live finding made unreadable by this change. ·
      **Done when:** phase 1's rehearsal is green, `grep -rn -- '-p veyq'` finds only prose that is *about* the
      retired rule, and the full glob is green.

- [ ] **3 scripts — promote the one-shot proof.** The invariant *"the production project name is a property of
      the compose file, not a rule stated in prose"* becomes permanent. Extend
      `scripts/validate_release_identity.py`, which already owns the recipe pair and parses this file, rather
      than adding a near-duplicate guard: assert the production compose declares `name:`, and that no tracked
      file instructs `-p veyq`. Run the predicate against the **untouched** tree before writing it down, and
      extend that guard's docstring — including its **limit**, which must state that a green run says nothing
      about whether the project the operator actually brought the stack up under matches. ·
      **Done when:** the guard is green, and re-injecting the removal of `name:` turns it red.

- [ ] **4 docs — the runbook, and the published claim it corrects.** Replace `infra/README.md`'s
      *"There is no upgrade runbook yet"* stub with the procedure: stop the app service (the cold
      precondition, stated as a step the tool does not verify) → capture → record the release you are on →
      pull the new tag → extract that image's recipe and diff it against the running one → apply → up →
      verify. Rollback is the backup plus the previous `v<major>.<minor>`, both nameable only because of
      ADR 0057. The runbook says **which of its steps nothing has ever executed** — after this CR that is
      every step but the capture. And `wiki/en/running-your-instance.md:17-19` states the upgrade path as
      missing; published copy is a claim like any other, so it is corrected in the same pass, not filed. ·
      **Done when:** `for f in scripts/validate_*.py; do python3 "$f"; done` green over the **committed** tree.

## Definition of done

All four phases green over the committed tree, and the structural invariant carried by
`validate_release_identity.py` rather than by a sentence.

**No coverage change, and that is a decision rather than an omission.** `satisfies:` names
`uc.admin.back-up-and-restore-instance`, whose coverage file is `status: planned` with **zero** refs — built,
but no CI run has ever executed it, which is [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)'s
*"nothing enters the coverage model before a CI run executes it"*. `git remote -v` is empty, so phase 1's
rehearsal cannot make a coverage claim either. The file is left exactly as found and the refs ride on inbox
**#26** item (1), the publish, with every other stuck file.

**What this CR does not deliver, said plainly:** an upgrade that anyone has performed. No image has been
published, so the runbook is a procedure written from the decisions that govern it, not from a rehearsal.
Its capture step is proven; the rest is prose until a publish gives it two releases to run between.

## Retrospective

*(filled at implementation close — planning, execution, and process/collaboration, both directions, ending in
one concrete "next time")*
