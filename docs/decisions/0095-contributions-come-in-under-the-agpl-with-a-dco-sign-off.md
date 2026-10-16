# ADR 0095 — Contributions come in under the AGPL with a DCO sign-off, never a CLA

- Status: Accepted; built — [`CONTRIBUTING.md`](../../CONTRIBUTING.md) asks for the sign-off and `NOTICE` keeps the name out of the grant; a CI check stays deferred (decision 3)
- Date: 2026-10-16

## Context

veyq is licensed AGPL-3.0-only (`LICENSE`, `NOTICE`), and today every line of it has one author. That author is
the only copyright holder, so the AGPL binds everyone but them: they may relicense, sell a licence that is not
the AGPL, or keep a hosted feature closed. Running veyq commercially as a service needs none of that — the AGPL
allows it, and its one demand on a service (§13: offer the running version's source to its users) costs nothing
for a project that publishes its source anyway.

The first contribution from someone else changes the holder set. Accepted under the AGPL alone, that code binds
the maintainer too: every option above then needs that contributor's consent, or a rewrite of what they wrote.
Because the licence is `-only`, so does a move to a later AGPL version. Whether to keep those options is decided
before the first contribution, not after it, because a contribution already merged cannot be re-papered without
finding its author.

Three ways to keep them were weighed against keeping none:

- **A contributor licence agreement (CLA)** granting the maintainer the right to relicense, proprietary included.
  Under German law an author cannot transfer the copyright itself (§ 29 UrhG), only grant rights of use (§ 31), so
  the assignment-style CLAs common in US projects do not work as written; it needs a drafted grant and a bot or a
  register to collect signatures. And it is the mechanism by which open projects have gone closed, which is why
  contributors read a CLA as a warning.
- **The FSFE Fiduciary Licence Agreement (FLA 2.0)**, written for German and EU law: a wide grant to the
  maintainer, bound by a promise to release the work only under free licences. It keeps the relicensing option
  among free licences and drops the proprietary one.
- **No outside contributions**, issues only. Keeps every option with no paperwork, and turns contributors away.

What tips it is veyq's own claim. Its confidentiality holds against a malicious operator exactly where the user
serves their own client ([ADR 0026](0026-client-trust-tiers.md)), so self-hostability is the security guarantee,
not only a sovereignty one (root `CLAUDE.md`). A maintainer who holds the right to take the code closed is a
maintainer who could end that guarantee for the next release. The options a CLA keeps — a paid non-AGPL licence,
a closed add-on — are ones the project's own invariant already rules out.

## Decision

1. **Inbound equals outbound.** A contribution is licensed to the project under AGPL-3.0-only, the licence it is
   distributed under, and under nothing wider. There is no CLA and no FLA.
2. **Every commit of a contribution carries a Developer Certificate of Origin 1.1 sign-off** —
   `Signed-off-by: Name <email>` naming its author, as `git commit -s` writes it. The sign-off is the contributor's
   statement that they may contribute the change under the project's licence; it grants nothing beyond it.
   [`CONTRIBUTING.md`](../../CONTRIBUTING.md) says how.
3. **The maintainer checks the sign-off when reviewing**, and there is no CI check for it. Every contribution is
   merged by hand after review, so a missing line is seen where it would be caught; a check earns its place once
   contributions arrive faster than that review (`docs/ai/README.md` rule 3). It then belongs in `scripts/` like
   every other gate, not with an external DCO service, which would be the project's first build dependency on a
   third party's hosted bot.
4. **The name is not part of the grant.** `NOTICE` already keeps the veyq name, logos and brand assets out of the
   AGPL (allowed by its § 7(e)); a fork or a competing service runs the code under its own name. What makes that
   enforceable is a registered mark, which is the owner's to file and not a matter of this repository.

## Consequences

- From the first contribution on, veyq stays AGPL-3.0-only for good, and so does every version that contains that
  contribution. Relicensing — to anything, a later AGPL included — needs every contributor's consent or the removal
  of their code. That is the point, and it is the cost.
- A business built on veyq is a service, support or hosting — never a licence sold around the AGPL. Anyone else
  may host veyq commercially on the same terms; what sets the original apart is its name.
- Contributors sign off with one flag and sign nothing else. Commits by the maintainer, the copyright holder of
  what they write, need no sign-off.
- If the project ever wants dual licensing after all, this ADR is superseded before outside code lands, not
  after — once contributions are in, the choice is already gone.
