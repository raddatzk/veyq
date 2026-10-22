---
id: cr-bundled-store-adopts-itself
title: Where the deployment brings its Garage, the server sets and checks the bundled store itself, and the check can reach it
status: planned
satisfies: ["uc.admin.configure-object-store", "uc.admin.tenant-reachability"]
adrs: ["0089", "0013", "0064", "0067"]
invariants:
  - only a passing check puts a store setting in use (ADR 0089 decision 5); the automatic adoption runs the very check the Server Owner's "Check store" runs, never a shortcut of it
  - a store setting the Server Owner saved (in use or pending) is never replaced by the automatic adoption, and a restored setting wins
  - the boot never fails because of the store (ADR 0067): adoption and its check run in the background, like the provisioning they follow
  - nothing about content confidentiality moves; the store still holds ciphertext only
---

## Goal

After a fresh install, admin-web's **Server → Object store** pre-selects *Use the bundled store*, says "there is
nothing to enter", and still reports that nothing is set or that the saved store has not been checked (owner,
2026-10-21). The owner expects the bundled store to need no step of theirs, because the deployment preconfigured it.
ADR 0089 decision 3 now says so (amended 2026-10-22). Where the hint exists and no store setting is saved, the server
saves the bundled store as pending after its boot provisioning and runs the ordinary check itself. A pass puts it in
use, so the Server Owner opens the page on *passed* and can create the first tenant straight away.

While grounding this, a live defect turned up that blocks both the manual flow and this one. Since #520
(`854eb40d`, merged 2026-10-21) the same-origin route to the bundled bucket exists only while the bundled store is
the store **in use**. A **pending** bundled store's check uploads its probe through that route, so on Compose the
check can never pass. Phase 1 fixes this first.

## Context & constraints

- **Refined state:** [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  decision 3 (second paragraph, and the Rejected line); [uc.admin.configure-object-store](../docs/features/admin/use-cases/uc.admin.configure-object-store.md)
  main flow step 1 and **A8**; [uc.admin.tenant-reachability](../docs/features/admin/use-cases/uc.admin.tenant-reachability.md)
  main flow step 3 (the pending half of the route).
- **The route defect (phase 1). Read from the code; no fresh Compose stack was run to see it fail.**
  - `TenantIngressSinkProducer.kt` hands the Traefik sink `bucket = { store.bundledRouteBucket() }`.
  - `StoreSettings.bundledRouteBucket()` resolves only `current()`, the setting in use, so with nothing in use the
    sink renders no `-blobs` router (`TraefikFileIngressSink.render`).
  - On Compose the hint's public endpoint is `https://${VEYQ_DOMAIN}` (`infra/docker/docker-compose.prod.yml`). The
    check's `PROBE_PUT` (`StoreCheck.kt`) therefore goes to `https://<domain>/<bucket>/veyq-check/probe`. That
    reaches the priority-1 app router, not Garage, and fails at `PROBE_PUT`.
  - `StoreHandler.set` saves a pending setting without re-rendering the ingress (only `check`'s promotion calls
    `projector.regenerate()`), so even a route that knew about the pending setting would not appear until something
    else re-rendered.
  - The dev stack never shows this, because its hint signs for the Vite origin and Vite proxies `/blobs/`
    (`application-dev.properties`). #520's tests render the route and never send a check through it. Whether the
    stack e2e has run since #520 was not checked; it is the suite that would show the defect.
- **Boot path (measured by reading).**
  - `StoreProvisioningRunner.onStart` observes `StartupEvent`, returns under `%test`, and runs
    `StoreProvisionerFactory.forBoot()` on its own daemon thread with the bounded retry `runBounded` (8 attempts,
    2 s doubling to 30 s).
  - With **no** setting, `forBoot()` already provisions the **hint's** Garage. Today it writes no setting and runs no
    check. That worker lambda is the seam: after `PROVISIONED`, with nothing set, adopt and check.
  - `forBoot()` cannot currently tell its caller that it took the hint branch.
- **`StoreHandler.check` is callable off-request.** It is `@ApplicationScoped`, and auth lives only on
  `InstanceResource` (`@RolesAllowed(SERVER_OWNER)`). Signing passes `domain = null`, so it never reads a `Host`.
  It has **no lock**: promotion is guarded by comparing the pending row by value. Two checks of the same pending row
  could both promote, and on a fresh install the loser writes active id 1 a second time. This was reasoned, not
  measured.
- **One backend per instance.** The chart has `replicas: 1` with `strategy: Recreate` and no `replicas` value
  (`values.yaml`), and `StoreSetting.kt` states it. The existing singleton-row lock is
  `VeyqInstance.findById(SINGLETON_ID, PESSIMISTIC_WRITE)` (`CapabilitySwitches.kt`, `CapabilityRequirementValues.kt`).
- **Restore cannot race it.**
  - `veyq_store_setting` is in `NOT_DATA` (`src/tools/instance_backup.py`), so a row does not trip the emptiness gate.
  - The restore replaces that table wholesale, and `veyqctl restore` stops the app around it.
  - A rollback starts the app on an emptied database first, which adopts, and the restore then overwrites the
    adoption.
  - The probe object `veyq-check/probe` is one the empty-store gate already ignores.
- **Who already saves and checks the bundled store.** These become redundant or wrong once the server does it:
  - `src/web/src/test/e2eBootstrap.ts` (PUT `{bundled:true}`, then check);
  - `src/web/scripts/instanceBirth.mts` `ensureBundledStore`, which already returns early on `lastCheck.passed`;
  - `tests/e2e/tests/setup/first-run.setup.ts`, which clicks *Use the bundled store*, saves, and expects "saved but
    not in use yet";
  - `scripts/run_stack_e2e.py`, whose comment says "nothing here pre-chooses it";
  - `scripts/run_web_e2e.py` `provision_store`, whose throwaway backend will now also adopt, harmlessly, on its
    throwaway database.

## Decisions this CR takes

1. **The bundled bucket is routed while the bundled store is in use *or* pending.**
   - `bundledRouteBucket` answers the bucket of the ready setting in use if that one is bundled, else the bucket of
     the pending setting if *that* one is bundled and resolves ready.
   - `StoreHandler.set` and `discardPending` invalidate the settings cache and re-render the ingress, as `check`'s
     promotion does.
   - #520's reason still holds for every other case: an own store, none, or a bundled one without a hint renders no
     route.
   - A pending bundled store is the one about to be used, and serving its bucket for the minutes before its check
     is what the check needs.
   - This is independent of the adoption: the Server Owner's manual *Check store* needs it too (Q1).
2. **Adoption happens at boot, after provisioning, on the runner's thread.**
   - When `runBounded` returns `PROVISIONED` and the instance holds no setting, the worker adopts the bundled store
     and then calls `StoreHandler.check()`.
   - `forBoot()` returns which branch it took, so the worker knows it provisioned the hint's Garage for an empty
     instance, rather than inferring that afterwards.
   - The adoption is one transaction under the `VeyqInstance` singleton lock. It re-reads "nothing in use, nothing
     pending" there and saves the bundled reference as pending only then, so a Server Owner's save that landed first
     wins.
   - Nothing marks the adoption as done (ADR 0089 decision 3), so every boot reads the condition again.
3. **The adoption's check retries; the owner's does not.**
   - The first boot's check races the outside world: Traefik's file provider picking up the new router, the ACME
     certificate for the domain, DNS. The worker therefore re-runs the check on its own bounded schedule while it
     fails at `PROBE_PUT`, `PROBE_GET` or `CORS`, the steps the outside world can still fix.
   - It stops on a pass, on the budget's end, or as soon as the pending setting is no longer the one it adopted
     (the owner saved, discarded, or checked it into use).
   - A failure at `CREDENTIALS`, `PROVISION` or `BUCKET` is not retried: the deployment's own configuration is wrong,
     and waiting does not fix it.
   - The budget is Q2.
   - Each attempt goes through `StoreHandler.check()`, so the console shows the latest attempt, exactly as an owner's
     check would.
4. **`check()` serialises its promotion.** It takes the same singleton lock in its write transaction, so an owner's
   check and the background check of the same pending row cannot both promote. The loser sees the setting already
   in use and records nothing.
5. **The console says what the server does; it adds no new contract field.**
   - With `bundledAvailable`, no setting and nothing pending, which is only the seconds before the boot worker
     adopts, the result area says the server is putting the bundled store in use after its start, and to reload.
     It no longer says "no store is set".
   - `bundledNote` stops saying "choose it, save, and check": it says the server sets the bundled store and checks
     it on its own, and that *Check store* runs the check again.
   - The two first-setting texts this conversation found wrong are corrected in the same phase. `pendingUnchecked`
     and `pendingCheckFailedAt` speak of "the store in use", which does not exist while `setting` is null; they get
     first-setting variants, chosen as `pendingNote` / `pendingFirst` already are. Both locales are changed.
   - The CLI keeps `store set --bundled`: it is how a Server Owner returns to the bundled store after trying their
     own.
6. **The server log says it once.**
   - One INFO line when it adopts ("no store setting; adopting the bundled store").
   - One line per finished check: the pass, or the step that failed and whether it will try again.
   - Nothing is logged per retry beyond that.
7. **Callers that save and check the bundled store themselves change as follows:**
   - `e2eBootstrap.ts` and `instanceBirth.mts` keep working unchanged, because saving the bundled store while it is
     pending re-saves an equal row. `e2eBootstrap.ts` is reduced to waiting for `lastCheck.passed` with a bounded
     poll, and the explicit save goes.
   - `first-run.setup.ts` asserts that the page opens on the bundled store **in use and passed** and no longer
     clicks it. That click was the only browser proof of the manual path; the manual path keeps its backend slice
     tests and the CLI's.
   - The comment in `run_stack_e2e.py` is rewritten to match.

## Open questions (owner)

**Decided (Q1–Q3).** Owner 2026-10-22: every recommendation below is taken.

- **Q1: one CR or two?** Decision 1 fixes a defect that is live on every fresh Compose install, whether or not the
  adoption is ever built. **Recommendation:** keep it as this CR's phase 1 but let phase 1 land and merge on its own,
  before phase 2 starts. A separate CR would restate the same grounding.
- **Q2: the adoption check's retry budget.** **Recommendation:** about 10 minutes, at 10 s at first and backing off
  to 60 s. ACME's first certificate and Traefik's file watch normally take seconds to a minute; DNS that is still
  propagating can take longer. When the budget runs out, the console's *Check store* is the way to try again. The
  provisioning's own 8 attempts (about 2 minutes) are too short for a certificate.
- **Q3: does a restart check a failed pending bundled store again?** A bundled store whose adoption check ran out
  of budget stays pending, so the next boot does not adopt; it only finds a pending setting. **Recommendation:** yes.
  When the pending setting is the bundled one and has not passed, the boot worker runs decision 3's loop again after
  provisioning. A restart is what an operator does after fixing DNS or the proxy, and this never touches a pending
  store of the operator's own.

## Phases

- [ ] **1 route (backend)**: decision 1 and decision 4.
  - Bite first: a `TraefikFileBlobsRouteTest` case (pure, no hint needed) where the resolution in use is
    `NotSet` and the pending one is a ready bundled connection must render the `-blobs` routers. It is red on the
    untouched tree.
  - Its positive control: a pending store of the operator's own still renders none.
  - A slice case in `InstanceStoreSliceTest`: saving a bundled store re-renders the ingress, and discarding it
    removes the route again.
  - Done when: `./gradlew check` is green; `validate_coverage.py` is green with
    uc.admin.tenant-reachability's criterion for the pending route written beside its tests; and
    `git grep -n "routeBucket\|bundledRouteBucket" -- src/backend` shows no other reader left on the old meaning.
  - Commit before phase 2: phase 2 edits `StoreHandler.kt` too.
- [ ] **2 adoption (backend)**: decisions 2, 3 and 6.
  - The runner's worker, `forBoot()`'s branch result, and the adopt transaction under the lock.
  - The adoption's check loop, as a class of its own whose clock and sleep are injected, so the loop is unit-tested
    off `StartupEvent`, which `%test` skips.
  - Also: a boot that finds the bundled store still pending and unpassed re-runs its check after provisioning
    (Q3).
  - Tests:
    - adopt with nothing set saves the bundled reference as pending;
    - adopt with an owner's pending setting, or with one in use, changes nothing (each with its positive control:
      the same call on an empty instance adopts);
    - the loop stops on a pass, on a non-retryable step, on a changed pending row, and on the budget's end;
    - two concurrent checks promote once.
  - Done when: `./gradlew check` is green, and the criterion `the-bundled-store-is-adopted-and-checked-without-a-click`
    is written in `uc.admin.configure-object-store.yaml` beside these tests.
- [ ] **3 console (admin-web)**: decision 5.
  - `StorePanel.vue`'s texts and branches, in `en` and `de`, and `StorePanel.test.ts` for each new branch (no
    setting with the hint; first pending unchecked; first pending failed).
  - Locator for the texts: `grep -n "store\.\(bundledNote\|notSet\|pendingUnchecked\|pendingCheckFailedAt\)" src/admin-web/src`.
  - Done when: `npm run check` in `src/admin-web/` is green, plus a browser pass on the dev instance in the two
    states the dev stack can reach: a fresh database after a backend restart (adopted, passed), and an own store
    saved first (not replaced).
- [ ] **4 harnesses and published docs**: decision 7 and every page that still tells the operator to choose, save
  and check the bundled store.
  - Locator: `git grep -nE "Use the bundled store|store set --bundled|choose the bundled|choose it, save|pre-chooses" -- site infra scripts tests src docs`,
    and `git grep -ln "uc.admin.configure-object-store.md\|0089-instance-settings\|uc.admin.tenant-reachability.md" -- site/src/content/docs`.
  - Run at refine, it names:
    - `site/src/content/docs/run/first-run.mdx` (§ Connect the object store, the CLI table's `store set --bundled`);
    - `storage-and-email.md`, `kubernetes.md`, `configuration.md`, `troubleshooting.md`, `operations.md`;
    - `infra/README.md` ("A fresh instance must also choose the bundled store");
    - the comment in `infra/docker/docker-compose.prod.yml`, `infra/kubernetes/charts/veyq/templates/NOTES.txt` and
      the comment in `values.yaml`;
    - the comment in `application-dev.properties`;
    - `tests/e2e/tests/setup/first-run.setup.ts`, `scripts/run_stack_e2e.py`, `src/web/src/test/e2eBootstrap.ts`.
  - Every hit is either changed or spared by name in the retrospective.
  - The site's Use pages are untouched: no member sees the store.
  - Done when: the site check and every `scripts/validate_*.py` except `validate_guard_gaps.py` are green.
    The stack e2e (`first-run.setup.ts`) is the only proof of the whole path on Compose; per the V1 plan it runs at
    the heavy stage, so it is listed there and not run here, and the retrospective says so.

## Definition of done

- On a fresh Compose install, the object-store page opens on the bundled store in use with a passed check, without a
  click. This is proven by the stack e2e when it next runs; until then, by phases 1–2's tests and phase 3's dev pass.
- A store the Server Owner saved first is never replaced.
- A pending bundled store's check can reach its bucket through the public endpoint.
- ADR 0089's status line no longer says decision 3's amendment is unbuilt; the two use-cases' *planned* markers are
  gone.
- Every phase's Done-when holds.

## Retrospective

_Filled at implementation._
