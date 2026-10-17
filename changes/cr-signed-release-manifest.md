---
id: cr-signed-release-manifest
title: A signed manifest binds every release asset, whatever the source
status: planned
satisfies: ["uc.admin.install-and-upgrade-instance"]
adrs: ["0096", "0057", "0040"]
invariants:
  - "ADR 0057: nothing unverified is pulled or started — after this CR that includes the recipe, not only the images"
  - "Self-hostable by default: a mirror or a local directory serves a release as GitHub does, and is verified the same way; GitHub stays the default, never a requirement"
  - "One identity: what signs the manifest is what signs the images, and veyqctl, the Run page and the guard state it once (validate_release_identity.py assertion 7)"
  - "`--unsigned-local` stays the one way past verification, refused unless both images are local builds"
---

## Goal

Inbox #469, owner decision 2026-10-16 (*"jetzt A und B als Folge-CR"*). A — `veyqctl.bundle` signs the `veyqctl` file
alone, for the operator's first download by hand — is built by cr-veyqctl phase 5 and stays. This CR builds **B**:
the publish signs `manifest.json` keylessly with `cosign sign-blob`, under the identity that signs the images (ADR
0057 §3), into `manifest.json.bundle`, an asset of the same GitHub Release; and `veyqctl install`, `upgrade`, `verify`
and `self-update` verify that bundle against that identity **before** they trust any sha256 or digest the manifest
names — from GitHub, an HTTP mirror or `--source <dir>` alike. Today the manifest is unsigned, so whoever controls the
source can serve a forged manifest with a forged compose file that matches it: the images stay safe (their own
signatures), the recipe does not, and a compose file can mount the host or add an unverified container.

**Entry condition: after cr-veyqctl closes** (its phase 6, the Run pages, is being written in parallel). This CR
depends on nothing in that file; but phase 6 rewrites pages this CR's phase 3 edits (`run/verify-a-release.md`) or
re-reads (`run/new-instance.mdx`, `run/upgrade.mdx`), so phase 3 starts from phase 6's committed text, never beside it.

## Context & constraints

- **Use-case:** [uc.admin.install-and-upgrade-instance](../docs/features/admin/use-cases/uc.admin.install-and-upgrade-instance.md),
  coverage `planned` for good (a Python host tool is not a coverage surface, ADR 0040; its coverage file says what
  proves it instead). Phase 3 widens criterion `an-unverified-release-is-never-started` to the manifest and edits A2
  and A5; no ref is added.
- **The decision is the owner's row, not yet an ADR sentence.** ADR 0057 §3 says today *"The recipe carries no
  signature of its own … what makes that manifest veyq's is the source"* (`grep -n "no signature of its own"
  docs/decisions/0057-*.md`) and ADR 0096 decision 7 *"trusting that mirror is the operator's own decision"*. Both are
  rewritten in phase 3, one sentence each (as inbox #469 scopes it), so until phase 3 lands the ADRs describe the
  pre-CR state. Phases 1–2 build what inbox #469 records the owner decided.
- **There is no remote and no release** — measured: `git remote -v` → empty, `git tag -l | wc -l` → 0. So the publish
  step is provable only by the guard that reads it and by a local run of its shell with stand-ins (as cr-veyqctl phase
  5 did), and **no instance exists that was installed from a signed release**: every instance today is a
  `--unsigned-local` one (the stack lane, `run_stack_e2e.py`'s `install` stage — `grep -n "unsigned-local"
  scripts/run_stack_e2e.py`) or a hand-run dev one. Nothing to migrate; see Open for the owner, Q3.
- **Reuse the image check's identity and call style.** `verify_images` (`scripts/veyqctl.py`, `grep -n "def
  verify_images" scripts/veyqctl.py`) runs `[cosign, "verify", target, "--certificate-oidc-issuer",
  COSIGN_OIDC_ISSUER, "--certificate-identity-regexp", COSIGN_IDENTITY_REGEXP]` through `Host.run(check=False)`,
  refuses with `Nothing was pulled, written or started.`, and refuses a missing `cosign` before anything else. The
  manifest check is the same two constants in the same argv order, through the same seam — no second identity, no
  new constant for it (assertion 7 reads exactly those two).
- **The publish runs cosign v2.5.2**, measured: `ci.yml` pins `sigstore/cosign-installer@398d4b0e… # v3.9.1`
  (`grep -n "cosign-installer" .github/workflows/ci.yml`), whose `action.yml` at `v3.9.1` says `default: 'v2.5.2'`
  (WebFetch, raw.githubusercontent.com/sigstore/cosign-installer/v3.9.1/action.yml). The operator's host runs whatever
  cosign they installed; this Mac's is `v3.1.3` (`cosign version | grep GitVersion`), the newest release
  (`api.github.com/repos/sigstore/cosign/releases/latest` → `v3.1.3`). The two differ on the bundle format — Q2.
- **The cosign form, measured against the docs and this host's cosign** (not yet against a real bundle — that is
  phase 2's live proof):
  - cosign `main` docs, `doc/cosign_verify-blob.md` (WebFetch of the raw file): usage
    `cosign verify-blob --bundle <path> --certificate-identity <identity> --certificate-oidc-issuer <issuer> <blob>`;
    flags `--bundle string  path to bundle FILE`, `--certificate-identity-regexp string  A regular expression
    alternative to --certificate-identity`, `--certificate-oidc-issuer string`. No `--new-bundle-format` and no
    `--offline` listed on `main`. `doc/cosign_sign-blob.md` on `main`: `--bundle string  write everything required
    to verify the blob to a FILE`.
  - cosign `v2.5.2` docs: `sign-blob --new-bundle-format  output bundle in new format that contains all verification
    material`; `verify-blob --new-bundle-format  expect the signature/attestation to be packaged in a Sigstore
    bundle`, `--trusted-root … Requires --new-bundle-format to be set`, `--offline`.
  - Host cosign v3.1.3: `cosign verify-blob --bogus-flag x` → `unknown flag: --bogus-flag`, while
    `--new-bundle-format=false` and `--new-bundle-format` parse (a hidden flag: absent from `--help`); without an
    identity flag → `--certificate-identity or --certificate-identity-regexp is required for verification in keyless
    mode`; a missing bundle file → `reading /nonexistent: open /nonexistent: no such file or directory`.
  - Sigstore's v3 announcement (blog.sigstore.dev/cosign-3-0-available, WebFetch): the 2.6 opt-in flags
    (`--new-bundle-format`, `--trusted-root`, `--use-signing-config`) are **on by default** in v3, and *"soon we will
    start removing the old functionality for the initial release of Cosign v4"*. *Remembered* (a search-result
    summary of the v2.6.4 release notes, not opened): v3's verify commands detect and accept both formats.
  - So the form veyqctl runs is
    `cosign verify-blob <manifest.json> --bundle <manifest.json.bundle> --certificate-oidc-issuer
    https://token.actions.githubusercontent.com --certificate-identity-regexp '<COSIGN_IDENTITY_REGEXP>'` — plus or
    minus a format flag, which Q2 settles.
- **Spared, each with its locator:**
  - `veyqctl check` lists releases and fetches no manifest (`grep -n "def cmd_check" -A 25 scripts/veyqctl.py`): it
    trusts no hash, so it verifies nothing; `releases.json` and the GitHub listing stay unsigned (residual below).
  - The backend's release check reads `manifest.json` for `unscheduled` only (`grep -n "manifest"
    backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/ReleaseFeed.kt`): a notice, not an install, and
    the JVM has no cosign. Not touched.
  - `backup` / `restore` run the release's `instance_backup.py` checked against the manifest **on disk**
    (`verified_asset`, `grep -n "def verified_asset" scripts/veyqctl.py`) — that manifest was verified when it was
    fetched, and the home is `0700`. Not re-verified per run (cosign and possibly the network during a disaster
    restore would be a new dependency); `self-update` is the exception below, because it replaces the verifier.
  - Kubernetes: no manifest (`git grep -n "manifest.json" -- charts/` → nothing); the chart is signed and deployed
    by digest (ADR 0096 decision 8).
  - `web/`'s `.veyq/manifest.json` (`git grep -n "manifest.json" -- web/src`) is the export archive's, unrelated.
- **Residual, stated not fixed:** the source still decides *which* releases exist. A mirror can withhold a newer
  release or serve the listing of an older signed one (a freeze); it can no longer serve a forged one. `upgrade`
  already refuses a release older than the running one. Freshness (TUF-style signed listing) is out of scope; phase 3
  writes it as veyqctl's limit and on the Run page.
- **Rejected — GitHub's release attestation instead of our own signature.** Measured (GitHub docs, *Immutable
  releases*, WebFetch): an immutable release *"automatically generates a release attestation"*. It binds assets
  published on GitHub under GitHub's identity; a mirror or a local directory has no such attestation to serve, and
  verifying it means GitHub's tooling — the opposite of *GitHub is the default, never a requirement*. Our
  `sign-blob` bundle is a file that travels with the mirror. (Its verification command and format were not measured.)
- **Rejected — sign each asset instead of the manifest.** N bundles, N cosign calls per install, and the manifest's
  image digests and `release` would still be unsigned; one signature over the manifest covers every asset through the
  sha256 it already lists, and the images twice (their own signature and the signed digest).

### Inventory — the locators

| What | Locator |
|---|---|
| The publish's release step (producer, `sign-blob`, `gh release create`) | `sed -n '/- name: Create the GitHub Release/,$p' .github/workflows/ci.yml` |
| Assertion 11's `sign-blob` check — note `argv_of` returns the FIRST matching line, so two `sign-blob` lines need all of them collected | `grep -n -e "def argv_of" -e "sign-blob" scripts/validate_release_identity.py` |
| Assertion 4's asset-name check (`*_ASSET` in veyqctl must be a name the producer writes) | `grep -n -e "veyqctl_constants(r" -e "produced = " scripts/validate_release_identity.py` |
| Assertion 7's page parse (takes `cosign verify` argv only, not `verify-blob`) | `grep -n -e "def verify_commands" -e '\["cosign", "verify"\]' scripts/validate_release_identity.py` |
| The producer's reserved names (`VEYQCTL_BUNDLE`, the `fixed` set a recipe file may not take) | `grep -n -e "VEYQCTL_BUNDLE" -e "fixed = " scripts/release_assets.py` |
| Every place veyqctl reads a manifest (each becomes "verified first") | `grep -n -e "fetch_manifest(" -e "read_release_manifest(" -e "verified_asset(" scripts/veyqctl.py scripts/veyqctl_selftest.py` |
| veyqctl's prose that says the manifest is unsigned / the source is trusted | `grep -n -e "veyqctl.bundle" -e "Trusting a mirror" -e "cannot verify a mirror" -e "checks no signature" scripts/veyqctl.py` |
| The self-test's cosign stand-in | `grep -n -e "class FakeHost" -e '"verify"' -e "signed=" scripts/veyqctl_selftest.py` |
| Every other sentence that says what is signed, or that the manifest/source is trusted (two angles: the claim, then the signed-things list) | `git grep -n -i -e "no signature of its own" -e "manifest veyq's" -e "trusting that mirror" -e "Trusting a mirror" -e "cannot verify a mirror" -- . ':!changes/'` and `git grep -n -e "sign-blob" -e "veyqctl.bundle" -e "signs the images, the Helm chart" -- . ':!changes/'` |
| The published pages that speak for those docs | `git grep -ln -e "0057-release-identity" -e "0096-veyqctl" -e "uc.admin.install-and-upgrade-instance" -- site/src/content/docs` |

## Phases

- [ ] **1 The publish signs the manifest; the guard holds it.** In the `github-release` step, after the producer and
  before `gh release create`: `cosign sign-blob --yes [format flag per Q2] --bundle
  "${out}/v${VERSION}/manifest.json.bundle" "${out}/v${VERSION}/manifest.json"`; the `veyqctl` signing gets the same
  format flag (Q2); the release notes name both bundles; the step comment says the manifest bundle is what `veyqctl`
  verifies before it trusts any hash. `scripts/release_assets.py`: `MANIFEST_BUNDLE = "manifest.json.bundle"` beside
  `VEYQCTL_BUNDLE`, in the `fixed` set, docstring's publish paragraph. `scripts/validate_release_identity.py`:
  - **assertion 11** collects every `sign-blob` line of the step (not `argv_of`'s first) and requires exactly one
    whose blob is `$out/v$VERSION/veyqctl` with `--bundle` `$out/v$VERSION/<release_assets.VEYQCTL_BUNDLE>`, and one
    whose blob is `$out/v$VERSION/manifest.json` with `--bundle` `$out/v$VERSION/<release_assets.MANIFEST_BUNDLE>`;
    both after the producer line and before `gh release create`; nothing between the manifest's `sign-blob` and `gh
    release create` runs the producer again (a re-written manifest would no longer be the signed one); and, if Q2
    takes the flag, both carry it;
  - **assertion 4**: veyqctl's `MANIFEST_BUNDLE_ASSET` (phase 2 adds it) must equal `release_assets.MANIFEST_BUNDLE`
    — the `produced` set gains the producer's reserved bundle names, so veyqctl fetches what the publish writes;
  - **assertion 7** also takes `cosign verify-blob` commands from the page: each pins the issuer and the identity the
    `cosign verify` lines pin (cr-veyqctl phase 6 documents one for `veyqctl`; phase 3 adds the manifest's).
  - Docstring: assertion 11's and 7's text, and the defect that bought it (inbox #469: an unsigned manifest made the
    recipe exactly as trustworthy as its source).
  - **Bites** — all *injections* (nothing is live: there is no publish to observe), snapshot-and-restore on a detached
    worktree of `HEAD`, each red on its own message, the untouched tree green: (a) the manifest's `sign-blob` removed;
    (b) its `--bundle` written outside `$out/v$VERSION/` (`$RUNNER_TEMP/m.bundle`); (c) it moved after `gh release
    create`; (d) a second `release_assets.py` run between the signing and the upload; (e) `veyqctl`'s `sign-blob` removed,
    the manifest's kept → red; control (e'): the two `sign-blob` lines swapped in order → GREEN — under today's
    first-match lookup that correct step reads red, so (e') is what proves the collect-all; (f) `release_assets.MANIFEST_BUNDLE` renamed to `manifest.bundle` while
    `ci.yml` keeps `manifest.json.bundle` — red on assertion 11; (g) a `cosign verify-blob` on the page with
    `--certificate-identity-regexp '^https://github.com/raddatzk/veyq/'` — red on assertion 7 (lever: the page as
    cr-veyqctl phase 6 left it; if it carries no `verify-blob` yet, the bite adds one).
  - **Local run of the step**: its shell extracted from `ci.yml` and run with stand-ins for `cosign` (writes a
    placeholder file at `--bundle`, records argv) and `gh` (records argv), fake digests: the `gh release create` argv
    lists eight files — the five manifest assets, `manifest.json`, `manifest.json.bundle`, `veyqctl.bundle` — and the
    recorded `sign-blob` blob of `manifest.json` is `cmp`-equal to the uploaded `manifest.json`.
  - **Done when:** the bites read; the local run's argv read; `python3 scripts/validate_release_identity.py` and every
    `scripts/validate_*.py` green, `validate_guard_gaps.py --force` included (this phase changes a guard); committed.
    Not provable further without a remote — the docstring and phase 3's Run page say so.

- [ ] **2 `veyqctl` verifies the manifest before it trusts it.** In `scripts/veyqctl.py`:
  - `MANIFEST_BUNDLE_ASSET = "manifest.json.bundle"`. `fetch_manifest` becomes the one entry that verifies:
    without `--unsigned-local` it refuses a missing `cosign` first (as `verify_images` does), fetches the manifest's
    raw bytes and the bundle (a missing bundle is refused naming `manifest.json.bundle` and the source — with a hint
    that a release produced locally takes `--unsigned-local`), writes both into a private temporary directory, runs
    the cosign form above with `COSIGN_OIDC_ISSUER` / `COSIGN_IDENTITY_REGEXP` through `Host.run(check=False)`, and
    refuses a non-zero exit with cosign's stderr tail and *"Nothing was pulled, written or started."* — and only then
    calls `parse_manifest`. Under `--unsigned-local` no bundle is fetched and the parse runs as today; the local-ref
    refusal in `verify_images` stays exactly as it is (a published release is refused there, as today).
  - Callers in the order *manifest verified → images verified → assets fetched*: `install`, `verify`, `upgrade` (step
    2). `verify`'s closing line names the manifest too.
  - `fetch_release` keeps the bundle verbatim beside `manifest.json` in `releases/<release>/` (`Manifest` gains the
    bundle's bytes, empty under `--unsigned-local`). Its already-there branch also requires the `manifest.json` on
    disk to be byte-identical to the verified one (else refused, *move it aside*, as for an asset) and writes the
    bundle when it is absent.
  - `self-update`: unless `state.json` says `unsignedLocal`, it verifies `releases/<running>/manifest.json` against
    the bundle beside it with the same call **before** reading the `veyqctl` sha256 from it; a missing bundle on disk
    is refused. Under `unsignedLocal` it behaves as today and says NOT VERIFIED. This closes the owner question
    cr-veyqctl phase 3 and 5 left (*self-update checks the installed `veyqctl` against its manifest only*).
  - Docstring: § Nothing unverified (the manifest is verified first, then the images), § `self-update`, § limits
    (the freeze residual replaces *"cannot verify a mirror's `releases.json` or a manifest beyond its shape"*), and
    the source paragraph's *"Trusting a mirror is the operator's decision"* narrowed to availability and freshness.
  - `scripts/veyqctl_selftest.py`: `FakeHost` answers `cosign verify-blob` — rc 0 iff the bundle file's content is a
    stand-in token the test computes from the blob file's sha256 and the argv's identity flags are the two constants
    — and `write_release` gains `signed=True` to write that token as `manifest.json.bundle`. New checks (section 5
    and 7 of its docstring, and 10):
    - the argv handed to cosign is exactly `[cosign, "verify-blob", <manifest>, "--bundle", <bundle>,
      "--certificate-oidc-issuer", COSIGN_OIDC_ISSUER, "--certificate-identity-regexp", COSIGN_IDENTITY_REGEXP]` (+ Q2's
      flag), the blob file byte-identical to what the source served;
    - a signed `install` end to end with digest references: `verify-blob` before any `cosign verify`, both before any
      write under the home or any `docker` command; the bundle kept beside the manifest.
  - **Bites** — *live defects* first (each reddens on the untouched `veyqctl.py`, because the verification does not
    exist yet: write the checks, record the red, then build): (a) one byte of the served `manifest.json` changed
    after the bundle was made → refused at `verify-blob`, no `docker`/`cosign verify` in the recorded commands, the
    home not created; (b) the bundle absent from the mirror → refused naming `manifest.json.bundle`, no cosign call;
    (c) a manifest that is both unsigned and malformed (names another release) → the refusal is the signature's, not
    the parse's — proving verification precedes trust; (d) `self-update` with `releases/<r>/manifest.json` edited on
    disk (its `veyqctl` sha256 changed to match a swapped `veyqctl`) → refused, nothing replaced; and with the bundle
    deleted → refused; (e) `upgrade` with (a)'s mirror → refused at step 2, every recorded command before it a read.
    Controls, green: the same release unaltered installs; `--unsigned-local` with a producer-made mirror (no bundle)
    installs and fetches no bundle; `--unsigned-local` with registry references is refused as today. Then *injection*
    bites on a copy of `veyqctl.py` (the cr-veyqctl way: mutants, the copy's self-test run against each): the
    `verify-blob` call skipped; `parse_manifest` moved before it; the blob written from `json.dumps(parse(...))`
    instead of the raw bytes; `self-update`'s verification skipped — each red, the unmutated copy green.
  - **Live** (the cosign form against a real bundle, which this refine could not run: it is a download): two public
    files of cosign's own `v3.1.3` GitHub Release, `cosign_checksums.txt` and `cosign_checksums.txt.sigstore.json`
    (measured: both listed in that release's assets), into a fresh directory, verified with the host's cosign by the
    exact argv veyqctl builds — (i) with veyq's identity → refused (*a bundle from another identity*), (ii) with the
    identity its certificate carries (read it from (i)'s error or the bundle) → accepted, the positive control, (iii)
    (ii) with one byte appended to the blob → refused. Ask the owner before downloading, as the host's rules require;
    if refused, say the form is proven against docs and flag parsing only. Then the stack lane: `python3
    scripts/run_stack_e2e.py --app <built> --admin <built>` green (its `install --unsigned-local` path is unchanged and
    must stay so).
  - **Done when:** the live-defect reds recorded before the build, then green; the mutants read; the live (i)–(iii)
    read or its refusal recorded; the stack lane green; `python3 scripts/veyqctl_selftest.py` and every guard green
    (assertion 4 now sees `MANIFEST_BUNDLE_ASSET`); committed.

- [ ] **3 The docs say the manifest is signed.** One sentence each, edited in place:
  - ADR 0057 §3 — *"The recipe carries no signature of its own … what makes that manifest veyq's is the source"*
    becomes: the manifest is signed with `cosign sign-blob` under the images' identity, its bundle an asset, and
    `veyqctl` verifies it before it trusts any hash in it, so a source decides which releases are offered, never what
    one contains. ADR 0096 decision 3 (the manifest signed, `manifest.json.bundle` among the assets) and decision 7
    (trusting a mirror is about availability and freshness, not integrity). Both status lines say what is built.
  - [uc.admin.install-and-upgrade-instance](../docs/features/admin/use-cases/uc.admin.install-and-upgrade-instance.md):
    § What a release is here (the manifest's bundle), main flow step 1 (*verifies the manifest's signature, then both
    images'*), A2 (a manifest that does not verify: nothing fetched beyond it), A5 (the mirror is verified like
    GitHub); its coverage file's `an-unverified-release-is-never-started` text gains the manifest. No ref (planned).
  - Run area: `run/verify-a-release.md` gains the manifest's `cosign verify-blob` by hand, beside the one cr-veyqctl
    phase 6 wrote for `veyqctl`, then the sha256 of each asset against it (the steps `veyqctl` runs, so they can be
    done by hand); the freeze residual and *"not run against a real publish"*. Re-read `run/new-instance.mdx` and
    `run/upgrade.mdx` as phase 6 left them and edit only a sentence that says the manifest or the source is trusted
    (fragment sweep above). Security area: `security/verify-it-yourself.md` § Verifying a signed release (*"signs the
    images, the Helm chart and `veyqctl`"* gains the manifest).
  - `docs/inbox.md` #469 → done, one line naming what holds and the freeze residual. Q2's cosign-installer note, if
    the owner takes it, becomes its own row.
  - Re-run the fragment sweep from the inventory over the whole tree after the edits; every remaining hit is spared by
    name here.
  - **Done when:** `npm run check` in `site/` green; `python3 scripts/validate_release_identity.py` green (assertion 7
    now reads the page's `verify-blob` lines); every `scripts/validate_*.py` green; committed.

## Definition of done

`veyqctl install`, `upgrade`, `verify` and `self-update` refuse a manifest whose bundle does not verify against the
one identity that publishes veyq, before any hash or digest in it is used, for every source — proven by
`scripts/veyqctl_selftest.py`'s live-defect bites and mutants, the live cosign run of phase 2 (or its recorded
refusal), and the stack lane unchanged on `--unsigned-local`. The publish signs the manifest into an asset, and
`validate_release_identity.py` assertions 4, 7 and 11 hold the publish, the producer, `veyqctl` and the Run page to one
bundle name and one identity. Coverage: the use-case stays `planned` (ADR 0040), its criterion text widened. Published
docs: phase 3's pages. **Baseline** — before the first edit, on a detached worktree of `HEAD`:
`for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`, plus
`python3 scripts/validate_guard_gaps.py --force` (phase 1 changes a guard) and `python3 scripts/veyqctl_selftest.py`.

## Decided with the owner

Owner 2026-10-17 ("Passt", to "alles wie empfohlen"): Q1 no unsigned mirror — only `--unsigned-local`; Q2 the new bundle format on both `sign-blob`s, `veyqctl`
needs cosign ≥ 3 (the installer bump to v4 is its own inbox row); Q3 no migration code. The download of cosign
v3.1.3's public release bundles for the foreign-identity bite is allowed. The options stay below as the record.

- **Q1 — May a mirror serve an UNSIGNED manifest behind a flag?** (a) **No** — only `--unsigned-local` skips the
  manifest's signature, and only for local image builds, as today. A mirror of a published release copies
  `manifest.json.bundle` like any other file, so there is no legitimate unsigned published manifest — and a mirror
  that re-built the images would fail their check anyway. (b) An `--unsigned-source` flag that skips the manifest's
  signature but keeps the images': restores exactly the hole this CR closes, behind one flag an install guide will
  copy. **Recommendation: (a).**
- **Q2 — Which bundle format do the publish and `veyqctl` use?** The publish's cosign v2.5.2 writes the legacy format
  unless `--new-bundle-format`; cosign v3 (the current release, and this host's) writes the Sigstore bundle by default
  and Sigstore announced that v4 removes the old functionality. A GitHub Release is immutable, so a bundle published
  in a format a later cosign drops can never be re-signed. (a) **`--new-bundle-format` on both `sign-blob` calls**
  (veyqctl's too — A's step, so this touches one line A built), and `veyqctl` passes no format flag and requires cosign
  v3 or newer (read from `cosign version`, refused below 3 by name): the form the current docs print, and no hidden
  flag v4 may remove. The Run page states the floor. (b) Same publish flag, `veyqctl` passes `--new-bundle-format`
  explicitly so cosign v2.x hosts work too (measured: v3.1.3 still parses it) — until v4 drops the flag. (c) Legacy
  format (no flag): matches today's `veyqctl.bundle`, and is the format scheduled for removal. Separately, worth its
  own inbox row: bump `sigstore/cosign-installer` (pinned v3.9.1, latest v4.1.2 measured) so the publish signs with
  the cosign line operators run — it also changes how the images are signed, so it is not this CR's.
  **Recommendation: (a)**, and the installer bump as a row.
- **Q3 — An instance installed before this lands.** Measured: no remote, no tag, no release, so no instance has ever
  been installed from a signed (or any published) release; every existing one is `--unsigned-local`, which
  `state.json` records and which this CR leaves as it is (its `self-update` keeps saying NOT VERIFIED). Nothing to
  migrate — the same reason ADR 0096 decision 6 gives. **Recommendation:** no migration code; if the owner wants it
  anyway, it is one refusal in `self-update`/`upgrade` for a signed-mode release directory without a bundle, which
  phase 2 already writes.

Implementation has the owner's go (2026-10-17); `cr-veyqctl` has closed, so its entry condition holds.
