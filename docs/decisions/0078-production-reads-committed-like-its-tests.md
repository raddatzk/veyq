# ADR 0078 — Production runs READ COMMITTED, the isolation its tests prove

- Status: Accepted
- Built: yes
- Date: 2026-10-03
- Model: [developer-styleguide](../crosscutting/developer-styleguide.md)

## Context

The backend's concurrency rules ([developer-styleguide §12](../crosscutting/developer-styleguide.md#12-architectural-patterns))
are proven by slice tests that force an overlap: one writer holds its commit back, another runs inside that window.
Those tests run on H2, which reads committed data (READ COMMITTED). Production runs MariaDB, whose default is
REPEATABLE READ: there the first plain read of a transaction fixes a snapshot, and every later plain read sees
that snapshot, even after the transaction has taken a row lock that waited for another writer's commit.

So a check that ran under the right lock still read the state from before it, wherever anything had been read
before the lock. Run on the pinned MariaDB image, the race suite that was green on H2 was red there: a re-activation
by a member being removed still passed its membership check and kept the link live, and two concurrent refreshes of
one token revoked their own session family (ADR 0004's one-generation grace read the replaced token as the current
one). The quota gate, a rotation's job exclusion and an accept's taken-checks had the same shape.

## Decision

Every connection runs READ COMMITTED, set by the application for every profile. The application sets it per
connection, so it holds on the bundled MariaDB, on an operator's own server and in the Helm chart alike, whatever the
server's default is.

A check under a row lock therefore reads what was committed when it runs, on both engines. What a test proves about
an overlap is what production does.

## Consequences

- **The suite's race evidence counts for production.** A test reds if the setting goes, and every race class also
  runs on the pinned MariaDB image (the race stage of
  [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)).
- **Only the inside of a transaction changes.** A read outside one (autocommit) already saw committed data, and the
  slice tests already ran this way.
- **Two reads in one transaction may disagree.** That was always the tests' world. A decision that needs two reads
  to agree takes a lock both writers take, as §12 already requires.
- **No gap locks.** Nothing relies on them: every range read with `FOR UPDATE` runs under a parent row lock, and a
  delete over a range locks only the rows it removes.
- **A statement-format binlog refuses writes at READ COMMITTED.** MariaDB defaults to `MIXED`, and the bundled
  server keeps no binlog. An operator who replicates with `binlog_format=STATEMENT` must switch to `ROW` or `MIXED`
  ([Configuration reference](../../site/src/content/docs/run/configuration.md)).
- **The persistence context is not the isolation level.** A lock taken on an entity this transaction already loaded
  hands back the loaded copy at any isolation; a lock that decides a check refreshes it
  ([developer-styleguide §12](../crosscutting/developer-styleguide.md#12-architectural-patterns)).

**Rejected: "the lock is the first read of its transaction."** It fixes each known
site, but every future lock must remember it, and no H2 test can tell when one forgets. It closes the members of
the class; the isolation level closes the class.
