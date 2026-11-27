# CR: the drawer's Recent is each account's, sealed in its store (inbox #1423)

`RecentUses` was one per install, its `UseKey(scopeId, nodeId, ms)` lines in plain prefs (Android `veyq-recent`
`uses`, Apple `veyq.recent`, the bridge's `veyq.recent`): a second account on the device saw the first account's Memory
under Recent, and the ids rested in clear on disk. Recent becomes the account's, kept as #1415's `place` is.

## Phases

1. Core: `KeptRead.RECENT` (`recent`, id `""` per tenant) beside `PLACE` (`KeptRead.SHELL`): protected from eviction,
   Clear cache, the sweep and "Remove all offline content", carried by a rotation, dropped by a wipe;
   `AccountOffline.recent`/`keepRecent`; `VeyqAccount.recentUses(tenant)`; `ShellModel` and `SessionModel` lose their
   device-wide `recent`. Tests: `RecentTest` (composition), `LegacyRecentTest` (bridge).
2. Platforms: Android, Apple (app + TV), the bridge drop their `RecentUses` and remove the legacy entry at start.
3. Harvest: ADR 0122 decision 6, native-apps.md, inbox #1423 done, #1424's owed list; this CR deleted.

## Decision log

- **One entry per account and tenant, in the account's offline store** (kind `recent`), not a per-account prefs key:
  the store already seals under a key of this account on this device, gives opaque file names, is wiped at sign-out /
  Stop trusting / removing the device and carried by a rotation — every property the row asks for, with no new
  mechanism. A per-account prefs key would have kept the ids in clear.
- **An account without a store (not trusted, a TV) keeps Recent in memory only, for as long as its `VeyqAccount` is
  open** (`VeyqAccount.recents`); nothing reaches disk. The cost: an untrusted account's Recent is empty after each
  start — accepted, since such an account is signed in anew at each start anyway.
- **The account holds the list, not the shell**: a `ShellModel` is one per tenant open and is made anew at each switch,
  so the list lives on `VeyqAccount` (one `RecentUses` per tenant) and the shell takes it in `load`. A store that opens
  after the first shell (trust given later) is merged in at the next `recentUses` (`RecentUses.merge`: each object once
  at its latest use, idempotent).
- **Writes run on the account's background scope behind one mutex, each writing the list as it stands then**, so a later
  use's write never loses to an earlier one; a write with no store open writes nothing.
- **"Stop trusting" wipes the stored Recent with the store but leaves the in-memory list for the rest of that session**
  (the account is still open and its own); a sign-out ends the account object and with it the list.
- **`SessionModel` no longer clears Recent at the last sign-out**: the list is the account's and goes with its store
  (wiped at sign-out) and its object; `RecentUses.clear` and `lastUsed` (no caller since #1415) are deleted.
- **Migration: the legacy entry is removed at every start, never read**: Android removes `uses` from `veyq-recent`
  (the file stays: it still holds the hidden spaces), Apple removes `veyq.recent` from the shared defaults (app) and
  `UserDefaults.standard` (TV), the bridge removes `veyq.recent` from the host's store. A one-time flag was not worth
  its own key: removing a missing key is free.
- **The device-wide `Hidden` stays as it is**: hiding a space is a device choice (ADR 0122 decision 3); out of scope.
