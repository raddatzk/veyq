# ADR 0078 — Production runs READ COMMITTED, the isolation its tests prove

- Status: Accepted
- Date: 2026-10-03

## Context

The backend's concurrency rules ([developer-styleguide §12](../crosscutting/developer-styleguide.md#12-architectural-patterns))
are proven by slice tests that force an overlap: one writer holds its commit back, another runs inside that window.
Those tests run on H2, which reads committed data (READ COMMITTED). Production runs MariaDB, whose default is
REPEATABLE READ: there the first plain read of a transaction fixes a snapshot, and every later plain read sees
that snapshot, even after the transaction has taken a row lock that waited for another writer's commit.

So a check that ran under the right lock still read the state from before it, wherever anything had been read
before the lock. A sweep found seven such transactions. Run on the pinned `mariadb:11.4`, the race suite that was
green on H2 was red on two of them: a re-activation by a member being removed still passed its membership check
and kept the link live, and two concurrent refreshes of one token revoked their own session family (ADR 0004's
one-generation grace read the replaced token as the current one). The quota gate, a rotation's job exclusion and an
accept's taken-checks had the same shape.

## Decision

Every connection runs READ COMMITTED: `quarkus.datasource.jdbc.transaction-isolation-level=read-committed`, in
`application.properties`, for every profile. The application sets it per connection, so it holds on the bundled
MariaDB, on an operator's own server and in the Helm chart alike, whatever the server's default is.

A check under a row lock therefore reads what was committed when it runs, on both engines. What a test proves about
an overlap is what production does.

## Consequences

- **The suite's race evidence counts for production.** `DatasourceIsolationTest` reds in `./gradlew check` if the
  line goes. `scripts/run_backend_races_on_mariadb.py` runs every race class on the pinned image, as `ci.yml`'s
  `backend-races` job. No run has executed that job yet, so it admits nothing until one has (ADR 0040); a hand run
  is the proof until then.
- **Only the inside of a transaction changes.** A read outside one (autocommit) already saw committed data, and the
  slice tests already ran this way.
- **Two reads in one transaction may disagree.** That was always the tests' world. A decision that needs two reads
  to agree takes a lock both writers take, as §12 already requires.
- **No gap locks.** Nothing relies on them: every range read with `FOR UPDATE` runs under a parent row lock, and a
  delete over a range now locks only the rows it removes (the next-key stalls `SessionPurge` worked around lose
  their cause).
- **A statement-format binlog refuses writes at READ COMMITTED.** MariaDB defaults to `MIXED`, and the bundled
  server keeps no binlog. An operator who replicates with `binlog_format=STATEMENT` must switch to `ROW` or `MIXED`.
- **The persistence context is not the isolation level.** A lock taken on an entity this transaction already loaded
  hands back the loaded copy at any isolation. `ScopeEntity.lockRow` refreshes it, and every scope-row lock goes
  through it.

**Rejected: "the lock is the first read of its transaction"** (styleguide §12 said this briefly). It fixes each known
site, but every future lock must remember it, and no H2 test can tell when one forgets. It closes the members of
the class; the isolation level closes the class.
