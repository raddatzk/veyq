---
id: cr-time-columns-are-datetime
title: Every stored time is a DATETIME holding UTC — no column ends in 2038
status: in-progress
satisfies: ["uc.sharing.share-public-viewer"]
adrs: ["0078", "0091", "0056"]
invariants:
  - an applied migration is never edited; the conversion is a new migration per module, above main's highest version
  - every stored time keeps its precision (whole seconds stay whole seconds, milliseconds stay milliseconds) and its nullability
  - a time is written and read as its UTC wall clock whatever the server's, the session's or the JVM's zone
---

## Goal

Inbox #509 (owner go 2026-10-19, V1). Every time column is MariaDB's `TIMESTAMP`, which on the pinned 11.4 holds
1970-01-01T00:00:01Z to 2038-01-19T03:14:07Z: a share's `expiresAt` in 2577 answered 500, #495 then refused such a
date-time `400` while binding (`StrictJsonTypes`), and the server's own clock reaches the end in 2038. Convert every
time column to `DATETIME`, keep it UTC by convention, widen the request range to what the columns and the binding hold,
and keep a `TIMESTAMP` column from coming back.

## Measured substrate (worktree at main `7d3d7ce7`)

| Fact | Locator |
|---|---|
| 88 time columns survive the migrations, all `TIMESTAMP`: 25 plain (whole seconds), 63 `TIMESTAMP(3)`; none has a default or `ON UPDATE` | `python3 scripts/validate_time_columns.py` on the untouched tree (red, one line per column); second angle: the `TIMESTAMP` column-definition lines across the migrations, minus the one table recreated (`veyq_tenant_terms_acceptance`) |
| Hibernate 7.3 binds an `Instant` as `TIMESTAMP_UTC`: on MariaDB `setTimestamp(…, UTC calendar)` / `getTimestamp(…, UTC calendar)`, on H2 `setObject(instant)` converted by the H2 session's zone | `Dialect.contributeTypes`, `TimestampUtcAsJdbcTimestampJdbcType`, `H2Dialect` (hibernate-core 7.3.4 sources) |
| Connector/J 3.5.8 sets no session `time_zone` unless `forceConnectionTimeZoneToSession=true`, and its default calendar is the JVM's | `StandardClient.addTimeZoneCommand`, `BaseContext.getDefaultCalendar` (driver sources) |
| No SQL in main code calls a clock function (`NOW()`, `CURRENT_TIMESTAMP`, …); the one raw JDBC time read is `HoldSnapshot.text` (`getTimestamp(i)`, JVM calendar) | `grep -rnE "NOW\(\)|CURRENT_TIMESTAMP|getTimestamp" backend/*/src/main backend/apps/*/src/main` |
| `java.sql.Timestamp` renders an instant before 1582-10-15 in the Julian calendar: `1000-01-01T00:00:00Z` is written as `0999-12-27`, which `DATETIME` refuses | a one-off `Timestamp.from(Instant.parse(…))` formatted in UTC |
| Request date-times: 8 fields in 6 schemas (`CreateShareRequest`/`UpdateShareRequest`/`ReissueShareRequest.expiresAt`, `AuthorityOrderRequest.receivedAt`/`deferStatementUntil`, `ModerationStatementDeferral.until`, `ModerationHoldExtension.until`) | a walk of every operation's request body through its `$ref`s |
| Schemathesis 4.32 draws `date-time` from years 0001–9999 with offsets up to ±23:59 | `schemathesis/specs/openapi/formats.py` in the `veyq-fuzz-schemathesis` image |

## Decisions

1. **`DATETIME`, precision kept.** A plain `TIMESTAMP` becomes `DATETIME`, a `TIMESTAMP(3)` `DATETIME(3)`, nullability
   as declared. Not `DATETIME(3)` everywhere: the published Security pages say the server stores its times "to the
   second, some to the millisecond", and raising 25 columns to the millisecond would raise what the operator sees for
   no reader. One migration per module (core never names a Memories table, ADR 0079), one `MODIFY COLUMN` per column.
2. **UTC by convention, without forcing the session zone.** Hibernate already writes and reads an `Instant` as its UTC
   wall clock on MariaDB; `DATETIME` stores that text unconverted, so the session zone stops mattering. The session is
   deliberately **not** forced to UTC: the conversion reads each `TIMESTAMP` in the session's zone, and only the
   server's own zone (the one every writer's session had) converts an operator's non-UTC server correctly. H2 gets
   `TIME ZONE=UTC` on the test URLs so it stores the same UTC wall clock; `HoldSnapshot` reads a time as the
   `LocalDateTime` it is, at UTC, instead of through the JVM's calendar.
3. **The request range is what a column and the binding hold:** 1582-10-15T00:00:00Z to 9999-12-31T23:59:59.999Z.
   The lower end is the Gregorian cutover, not `DATETIME`'s 1000-01-01, because the bind goes through
   `java.sql.Timestamp` (row above). Outside it `400 invalid_request`, said in each request date-time's description.
4. **The fuzz baseline entries stay, re-pointed to #507.** Schemathesis still draws years before 1583 and offsets past
   ±18:00 (which `java.time` cannot read), and a description is prose: the refusal is now exactly #507's case — a rule
   the schema states only in prose. Removing them would turn the next fuzz run red.
5. **The guard:** `scripts/validate_time_columns.py` replays the migrations and is red on any `TIMESTAMP` column the
   schema ends with. The engine's own answer, plus the round trips, is `StoredTimeSliceTest` (`:core`), run on H2 by
   `./gradlew check` and on MariaDB by the race runner's default set.

## Done when

- [ ] the two migrations pinned in `scripts/applied_migrations.sha256`; `validate_migration_versions.py` and
      `validate_time_columns.py` green, the latter bitten (a new `TIMESTAMP` column; a dropped conversion line)
- [ ] `StoredTimeSliceTest`: a 2577 expiry and both range ends round-trip; on MariaDB a value written under a session
      zone of -07:00 reads back unchanged and its stored text is the UTC wall clock; no `timestamp` column in
      `INFORMATION_SCHEMA` — bitten against the `TIMESTAMP` schema
- [ ] `StrictJsonTypes` range widened, `MalformedRequestSliceTest` moved to it; the 8 descriptions say the range
- [ ] `./gradlew check`, `python3 scripts/instance_backup_selftest.py`, the quick `scripts/validate_*.py`, one MariaDB
      run (`run_backend_races_on_mariadb.py` with the time, schema and precision classes); `validate_guard_gaps.py --force` once
- [ ] docs that name `TIMESTAMP` corrected; inbox #509 deleted; this CR deleted
