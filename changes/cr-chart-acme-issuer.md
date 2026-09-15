---
id: cr-chart-acme-issuer
title: An ACME issuer opt-in for charts/veyq
status: planned
satisfies: [uc.admin.tenant-reachability]
adrs: ["0064", "0063"]
invariants:
  - self-hostable by default — an ACME CA stays optional central infrastructure, as under compose
  - nothing cluster-scoped from the chart, no ClusterRole (ADR 0064)
  - the chart generates nothing — the ACME account key is cert-manager's Secret, never a chart value (ADR 0064)
  - no deprecated / unmaintained dependencies — cert-manager's health is measured below
---

## Goal

Give the Helm chart the one-block certificate path the production compose file already has. Under compose one
variable, `VEYQ_ACME_EMAIL`, feeds Traefik's ACME resolver and every tenant domain gets a certificate as it
appears; on Kubernetes the operator must today install cert-manager, hand-write an `Issuer`, set
`ingress.tls.mode: per-domain` and put the issuer annotation into `ingress.annotations` by hand. This CR adds
`ingress.tls.acme.*`: opted in, the chart templates one namespaced cert-manager `Issuer` (ACME HTTP-01) and
stamps `cert-manager.io/issuer` on its own `Ingress` objects and — through the existing annotation passthrough —
on every tenant `Ingress` the backend projects at runtime. The backend is untouched; the chart's guard learns to
render the opt-in.

## Context & constraints

- Decision: [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) § Decision, the TLS
  paragraph — edited in place at refine: the reference/configure cut runs between the *sink* and the *recipe*,
  so the chart (the recipe's counterpart) may configure an issuer while the sink keeps stamping references.
  The rejected alternatives (subchart, `ClusterIssuer`, backend speaks ACME) and the no-`.Capabilities` call
  are settled there; **do not re-argue them here, re-measure them** (the two measurements are in § Decisions).
- Use-case: [uc.admin.tenant-reachability](../docs/features/admin/use-cases/uc.admin.tenant-reachability.md)
  main-flow step 3 + E2; coverage
  [`uc.admin.tenant-reachability.yaml`](../docs/coverage/features/admin/use-cases/uc.admin.tenant-reachability.yaml),
  criterion `a-tenant-domain-gets-a-certificate-by-a-named-mode`.
- The passthrough the opt-in rides on: `KubernetesIngressSink.kt` (`spec.tls` per mode, annotations verbatim,
  list-by-label reconcile) and `charts/veyq/templates/app-configmap.yaml` (values → properties file). Neither
  changes.
- Runbook: `infra/README.md` § Running on Kubernetes owns the commands for both targets (`CLAUDE.md`
  § Repository shape); the compose recipe is `infra/docker-compose.prod.yml` — a change to one checks the other
  (ADR 0064 § Consequences).
- Guard: `scripts/chart_meta_check.py` — outside the `validate_*` glob because it needs `helm`, **and wired
  into no CI workflow at all** (`grep -rn -i 'chart\|helm' .github/` → nothing). It is hand-run; this CR
  extends it, it does not change that.

## Decisions

Every settled decision carries the line or command that settles it; check the one your work leans on hardest.

### Settled

1. **Opt-in, default off.** `ingress.tls.mode: none` is the default "so that nothing moves"
   (`charts/veyq/values.yaml`, the `mode:` line and ADR 0064's "`none` is the default for continuity"); an
   issuer needs an e-mail and an installed cert-manager, neither of which the chart can supply.
2. **The block is `ingress.tls.acme`, not a top-level `letsencrypt`.** Vendor-neutral like compose's
   `VEYQ_ACME_*` prefix (`infra/docker-compose.prod.yml`, the `certificatesresolvers` lines), and nested where
   the operator already decides TLS — one TLS block, as the brief asked, rather than two that must agree.
   Shape:
   ```yaml
   ingress:
     tls:
       mode: per-domain
       acme:
         enabled: true
         email: ops@example.org
         server: https://acme-v02.api.letsencrypt.org/directory   # the default; staging: acme-staging-v02
   ```
   The `Issuer` is named `veyq-acme`, fixed like every other object name (`_helpers.tpl` header: names are
   part of the backend's config contract, not release-prefixed).
3. **`acme.enabled` requires an explicit `mode: per-domain`; the schema refuses any other mode.** Never a
   silent override of an operator's `shared`. Settles it: the sink's `when (tlsMode)` is total over three
   modes with no `else` (`KubernetesIngressSink.kt`, the `secretName =` block) — ACME is a way of *obtaining*
   per-domain certificates, not a fourth mode. Idiom to copy: the existing `if/then` in
   `charts/veyq/values.schema.json` that requires `secretName` under `mode: shared` (three `if/then` today,
   zero `dependentRequired`). The same `then` requires a non-empty `email` and forbids
   `cert-manager.io/issuer` / `cert-manager.io/cluster-issuer` keys in `ingress.annotations` (two issuer
   annotations on one `Ingress` is an ambiguity ingress-shim resolves for you; refuse it at lint).
4. **E-mail required.** Upstream it is optional (`pkg/apis/acme/v1/types_issuer.go`: *"optional, but it is
   strongly recommended"*); compose makes it mandatory (`${VEYQ_ACME_EMAIL:?…}`). Parity wins: one contract
   on both targets, and the account contact is where a CA sends revocation notices.
5. **`server` is a URL, default Let's Encrypt production.** Parity with `VEYQ_ACME_CASERVER`'s default
   (`infra/docker-compose.prod.yml`, the `caserver=` line); a URL rather than a `staging: bool` keeps any ACME
   CA usable (self-hostable by default: an internal `step-ca` is one URL). Staging is documented beside it in
   `values.yaml`, NOTES and the README. Both directories answer today [M: `curl` of both, 2026-09-15, each a
   directory with `newOrder` + `renewalInfo`]. **See Open 1** — the brief leaned the other way.
6. **HTTP-01 only.** Works for any tenant domain without DNS-provider credentials (which would be a `Secret`
   the chart references — the one thing it must not need); wildcards are already `mode: shared`; compose is
   HTTP-01 only (`httpchallenge=true`). The solver's `ingressClassName` mirrors `ingress.className` when set
   and is omitted otherwise (cert-manager: *"the recommended way of configuring the ingress class"*;
   `class` is the legacy `kubernetes.io/ingress.class` annotation, `ingress-gce` only).
7. **One annotation helper feeds both consumers.** `ingress.annotations` is read at exactly two sites —
   `_helpers.tpl` (`veyq.ingressMetadata`) and `app-configmap.yaml` (the `range`) — and the opt-in merges
   `cert-manager.io/issuer: veyq-acme` into that map at **one** place both read, so a projected `Ingress` gets
   it for free (measured on the built fast-jar in the last CR: the ConfigMap path delivers `tls.mode` and
   annotations to the producer). Note the `{{- with }}` in `veyq.ingressMetadata`: an empty map emits no
   `annotations:` key at all, so the merge must happen before that `with`.
8. **No `.Capabilities.APIVersions.Has` check.** Measured on helm v4.0.1: `Has` is exact-string membership
   (`--api-versions cert-manager.io/v1` satisfies `Has "cert-manager.io/v1"` but **not**
   `Has "cert-manager.io/v1/Issuer"`, and vice versa); `helm lint` has no `--api-versions` flag and a template
   `fail` is logged at INFO and does **not** fail lint (`1 chart(s) linted, 0 chart(s) failed`); `helm
   template` without the flag fails outright. The alternative already exists: `helm install` builds the whole
   manifest before creating anything (`pkg/action/install.go`, *"unable to build kubernetes objects from
   release manifest"*) and the mapper wraps an unknown kind as *"resource mapping not found … ensure CRDs are
   installed first"* (`k8s.io/cli-runtime pkg/resource/mapper.go`). Loud, atomic, and offline renders keep
   working. NOTES.txt names cert-manager as the precondition instead.
9. **cert-manager's solver `Ingress` objects are safe from the sink's reconcile — no new backend test.** The
   sink lists **by** `app.kubernetes.io/managed-by=veyq-tenant-ingress` (`KubernetesIngressSink.kt`, the
   `withLabel(OWNER_LABEL, OWNER_VALUE).list()` line); cert-manager labels its solver objects
   `acme.cert-manager.io/http01-solver`, `acme.cert-manager.io/http-domain`, `acme.cert-manager.io/http-token`
   (`pkg/apis/acme/v1/types.go` constants, applied by `podLabels` in `pkg/issuer/acme/http/pod.go`) and never
   veyq's value. `KubernetesIngressSinkTest.anIngressThisSinkDoesNotOwnSurvivesARegenerate` already pins the
   predicate with a `managed-by: Helm` stranger; a second stranger with cert-manager's labels asserts the same
   predicate twice. Instead: **one comment at that test** naming cert-manager's `cm-acme-http-solver-*`
   `Ingress` as the live example of the stranger (a code trap lives at the line it bites).
10. **Lifecycle, stated not solved.** `helm uninstall` removes the `Issuer`. The `Certificate` ingress-shim
    creates carries a controller `OwnerReference` to its `Ingress` (`pkg/controller/certificate-shim/sync.go`,
    `NewControllerRef(ingLike, …)`), so it goes when the sink withdraws a domain; the issued `Secret` stays
    unless cert-manager runs with `--enable-certificate-owner-ref` (cert-manager docs, *"By default, cert-manager
    does not delete the Secret"*) — the residue ADR 0064 already states, unchanged. The account-key `Secret`
    (`veyq-acme-account-key`) is cert-manager's; whether it survives uninstall is **not measured** (no cluster
    here) — the README says "may remain", not more.
11. **HTTP-01 preconditions go into NOTES.txt (acme branch) and the README:** port 80 reachable through the
    ingress controller for every tenant host, the tenant's DNS pointing here **before** the tenant is created
    (each creation is an order), cert-manager's own reachability self-check runs first. Staging certificates
    are not browser-trusted — an instance on staging boots and every browser refuses it.
12. **Rate-limit prose is corrected on both targets, with the upstream numbers.** Measured 2026-09-15 from
    `letsencrypt.org/docs/rate-limits` (page dated 2026-08-05): **5 authorization failures per identifier per
    account per hour**; **50 new certificates per registered domain per 7 days**; **5 per exact identifier set
    per 7 days**; 300 new orders per account per 3 hours; renewals exempt; staging *"significantly higher"*
    (200 failures/hour, 30 000 per registered domain). The compose comment (*"failed production orders … rate-
    limited out for a week"*) and `infra/README.md`'s *"the production rate limit is a week"* have the wrong
    shape — failures lock an identifier for an **hour**, the week is a cap on **issued** certificates. Correct
    both; do not paste the table, point at the page and name the two numbers that bite a fresh deployment.
13. **No profile knob.** Let's Encrypt offers `classic` (90 days, the default when none is requested),
    `tlsserver` (45 days) and `shortlived` (160 h) — measured from both directories' `meta.profiles`.
    cert-manager renews at 2/3 of duration whichever applies; nothing here depends on it. Add `profile` when
    an operator asks, not before.
14. **Coverage: no new criterion.** The coverage surfaces are `backend` and `web`, and *nothing enters the
    coverage model before a CI run executes it* (`docs/ai/README.md`); the chart's proof is
    `chart_meta_check.py`, which runs in no CI. Criterion `a-tenant-domain-gets-a-certificate-by-a-named-mode`
    is about what the **backend emits** and does not change; only its comment (*"the certificate itself is the
    operator's issuer's"*) narrows to *"the operator's issuer — or the one the chart templates on opt-in"*.
15. **cert-manager is alive** [M 2026-09-15]: GitHub API — `v1.21.2` 2026-09-11, `v1.21.1` 2026-07-29,
    `v1.21.0` 2026-07-08, `v1.20.3` 2026-06-25; `cert-manager.io/docs/releases`: 1.21 + 1.20 supported, *"always
    at least two supported versions"*; CNCF: graduated 2024-09-29. `Issuer` HTTP-01 fields verified against
    `master` `types_issuer.go`. Re-run the GitHub call at implement (`gh api
    repos/cert-manager/cert-manager/releases?per_page=3`): a cost premise expires separately from its decision.

### Open — the user decides before the go

1. **Production or staging as the `server` default?** Refined as **production** (Decision 5) against the
   brief's lean to staging, on two measured grounds: compose defaults to production, and a chart that defaults
   the other way is exactly the two-target drift ADR 0064 § Consequences forbids; and a staging default makes
   the happy path an instance every browser refuses — the "boots and then silently breaks" failure NOTES.txt
   exists to prevent. The rate-limit argument for staging is weaker than the compose comment claims (Decision
   12: failures cost an hour, not a week). Flipping it is one default value and one sentence in three docs.
2. **A CI gate for the chart is out of scope here.** `chart_meta_check.py` runs in no workflow; extending a
   hand-run guard is what this CR does. Wiring `helm` into CI belongs to the chart's OCI-release sub-slice
   (`docs/inbox.md` #26), where a workflow run is the proof anyway. Confirm, or pull it in.

## Inventory — locators, run them

| what | command | classify |
|---|---|---|
| prose that says the chart configures no issuer | `grep -rn -i 'configures no\|no issuer\|nothing in this release obtains\|operator.s issuer\|operator.s certificate issuer' charts/ infra/README.md docs/decisions/0064-kubernetes-helm-deployment-target.md docs/features/admin docs/coverage/features/admin` | every hit is re-worded or narrowed to "the operator's, or the chart's on opt-in" — ADR 0064 already done at refine |
| the same from the other angle: the example annotation | `grep -rn 'cert-manager.io/' charts/ infra/ docs/ backend/src` | chart + README examples switch to `cert-manager.io/issuer: veyq-acme` **or** keep `cluster-issuer` as the bring-your-own example beside it — decide per site; the backend's `VeyqConfig.kt` / `application.properties` / test literals are the passthrough's own examples and stay |
| annotation consumers in the chart | `grep -rn 'ingress.annotations' charts/veyq/templates/` | both go through the one new helper (Decision 7) — if this returns more than two sites at implement, the helper has a third caller |
| rate-limit prose | `grep -rn -i 'rate.limit' infra/ docs/features/admin docs/decisions/0063-database-driven-tenant-ingress.md charts/` | the compose `caserver` comment, `infra/README.md`'s "a week", `.env.example`'s staging line, E2 in the use-case — corrected per Decision 12; the per-IP limiter hits are unrelated |
| the guard's structure | `grep -n 'CI_VALUES\|helm, "template"\|^def ' scripts/chart_meta_check.py` | `render()` takes no values-file argument today; it grows one |
| what the guard already forbids | `sed -n 1,70p scripts/chart_meta_check.py` (the docstring) | the acme render is subject to every existing assertion (i–vi) **plus** the new ones |

## Phases

- [ ] **1 chart** — `charts/veyq/templates/issuer.yaml` (whole file under `{{- if .Values.ingress.tls.acme.enabled }}`;
  `apiVersion: cert-manager.io/v1`, `kind: Issuer`, `metadata.name: veyq-acme`, `veyq.labels`;
  `spec.acme.{server,email,privateKeySecretRef.name: veyq-acme-account-key,solvers[0].http01.ingress.ingressClassName?}`);
  a `veyq.ingressAnnotations` helper merging the issuer annotation into `.Values.ingress.annotations`, used by
  `veyq.ingressMetadata` and by `app-configmap.yaml`'s `range`; the `values.yaml` block + comments (rewrite the
  "configures no issuer" comment to the two-halves statement); the `values.schema.json` `if/then` (Decision 3);
  NOTES.txt gains an acme branch (Decision 11) and keeps the mode-none warning; `charts/veyq/ci/acme-values.yaml`
  = `lint-values.yaml` + the acme block (its header says which guard renders it and why a second file exists).
  · Done when: `helm lint charts/veyq -f charts/veyq/ci/lint-values.yaml` and `… -f charts/veyq/ci/acme-values.yaml`
  both pass; `helm lint … -f ci/acme-values.yaml --set ingress.tls.mode=shared --set ingress.tls.secretName=x`,
  `… --set ingress.tls.acme.email=""` and `… --set 'ingress.annotations.cert-manager\.io/cluster-issuer=x'`
  each **fail naming the key**; `helm template` of both renders is valid YAML.
- [ ] **2 guard** — `scripts/chart_meta_check.py` renders **both** `ci/*.yaml` files (derive the set from the
  directory, do not list it: a third values file enrols itself), runs assertions i–vi over each render
  (determinism twice per file), and adds: (vii) the default render contains **no** `Issuer` and no
  `cert-manager.io/` annotation anywhere; (viii) the acme render contains exactly one `Issuer` named `veyq-acme`,
  in no other kind (`kind: ClusterIssuer` count 0 — fold into a general "no cluster-scoped kind" set with
  `ClusterRole`/`ClusterRoleBinding`); (ix) every `Ingress` in the acme render carries
  `cert-manager.io/issuer: veyq-acme` and `spec.tls[0].secretName == metadata.name`; (x) the acme render's
  ConfigMap properties file contains `veyq.tenant.ingress.kubernetes.tls.mode=per-domain` and
  `veyq.tenant.ingress.kubernetes.annotations."cert-manager.io/issuer"=veyq-acme`; (xi) the three contradictory
  `--set` lints from phase 1 exit non-zero **and** their stderr names the offending key — an exit code alone
  cannot tell "the schema held" from "helm was not found". Update the docstring's limits paragraph (it currently
  says `ingress.tls.mode` other than `none` is rendered by nobody — no longer true).
  · Done when: green on the finished tree; every bite below documented red by its named lever.
- [ ] **3 docs** — `infra/README.md` § Running on Kubernetes: a paragraph for the opt-in (what it templates, the
  preconditions, the lifecycle of Decision 10, staging beside production), the `per-domain` bullet narrowed to
  bring-your-own-issuer, the "a week" sentence corrected; `infra/docker-compose.prod.yml` `caserver` comment and
  `infra/.env.example`'s staging line corrected (Decision 12 — the compose side of the same fact);
  `uc.admin.tenant-reachability.md` step 3 and E2 (the issuer is "the operator's — or the one the chart templates
  on opt-in"; E2 names the `server` value as the Kubernetes staging switch); the coverage yaml's criterion
  comment (Decision 14); `docs/features/admin/_index.md`'s "through the operator's issuer" checked, probably
  unchanged; the one-line comment at `anIngressThisSinkDoesNotOwnSurvivesARegenerate` (Decision 9).
  · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green (≈5 min, run in background) and
  `python3 scripts/validate_coverage.py` green; `./gradlew check` untouched by design — run it once anyway, the
  test-comment edit must not be the reason a build goes red.

## Bite-tests — per test, the method and the lever

| # | assertion (phase 2 numbering) | method | lever |
|---|---|---|---|
| B1 | (viii) exactly one `Issuer` in the acme render | live defect | write the assertion before `issuer.yaml` exists → red on the untouched chart; green after phase 1 |
| B2 | (ix) every acme-render `Ingress` carries the issuer annotation + own-name `secretName` | live defect | same ordering; it also reddens if the helper is wired into `veyq.ingressMetadata` but not into the ConfigMap, which is B4's job to tell apart |
| B3 | (x) the ConfigMap carries `tls.mode=per-domain` + the quoted annotation key | live defect | same ordering |
| B4 | (x) vs (ix) split | injection | remove the helper call from `app-configmap.yaml`'s `range` only → (x) red, (ix) green — proves the two consumers are asserted separately; snapshot-and-restore |
| B5 | (viii) no cluster-scoped kind | injection | `sed 's/kind: Issuer/kind: ClusterIssuer/' issuer.yaml` → red; restore |
| B6 | (vii) default render has no issuer trace | injection | drop the `{{- if …enabled }}` guard around `issuer.yaml` → red on the default render; restore |
| B7 | (xi) schema refuses `mode: shared` + acme | injection | delete the `if/then` from `values.schema.json` → the lint that must fail now passes → red; restore. The assertion checks stderr for the key name, so a missing `helm` (exit 2 path) cannot pass as "the schema held" |
| B8 | (iv) determinism on the acme render | injection | add `{{ now }}` to `issuer.yaml` → red; restore |

## Definition of done

- Phases 1–3 checked; the guard renders every `charts/veyq/ci/*.yaml` and is green on the finished tree; every
  bite above was seen red by its lever and is recorded in the retrospective.
- `for f in scripts/validate_*.py; do python3 "$f"; done` green — **baseline is the command, not a number**:
  `ls scripts/validate_*.py | wc -l` before the first edit, and the same count after (this CR adds no
  `validate_*` guard; `chart_meta_check.py` stays outside the glob, on purpose).
- `python3 scripts/validate_coverage.py` green with the coverage yaml's comment narrowed and no new criterion.
- Both targets re-read against each other (ADR 0064 § Consequences): compose still defaults to production and
  requires the e-mail; the chart now does the same.
- The harvest before deletion: anything this CR *taught* goes to the line it binds (a chart trap → a comment in
  the template; a guard lesson → `changes/README.md`), never to git.

## Machine hints — hold regardless of the CR's final shape

- helm **v4.0.1** at `/opt/homebrew/bin/helm`; `kubectl` present; **no local cluster** (no kind/minikube/k3d):
  cert-manager behaviour cannot be executed here — proofs are rendered manifests + upstream sources, and the
  CR says so wherever it leans on one (Decision 10's "not measured").
- System python is 3.9: a new or extended `scripts/*.py` using `X | None` needs
  `from __future__ import annotations` (`chart_meta_check.py` already carries it — check before assuming).
- The full `validate_*` glob takes ≈5 min: start it in the background at the beginning of phase 3.
- `helm lint` ignores a template `fail` (INFO log, exit 0) and has no `--api-versions` — measured; do not
  build a lint-based bite on either.

## Retrospective

_Filled at implement. Planning and execution misses, root cause, one concrete change; both directions; end with
"next time"._
