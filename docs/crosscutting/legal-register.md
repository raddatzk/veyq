# Concern: The legal register

- **ID:** legal-register
- **Type:** **Cross-cutting reference** — the research behind veyq's legal duties: which law, which duty, which
  primary source, and what the project concluded from it. It decides nothing: each conclusion is applied by the ADR
  it names, and every question still open is an [inbox](../inbox.md) row.
- **Status:** **Research, not legal advice.** Each answer needs a qualified review before veyq is offered as a
  service; until then the open questions are treated as
  [ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § Still open says. Where a legal duty and an ADR or the code disagree, the duty wins and the ADR or code is
  corrected (`CLAUDE.md`).
- **Read by:** the Run area's [Moderation](../../site/src/content/docs/run/moderation.md) and
  [Data protection](../../site/src/content/docs/run/data-protection.md) pages. What the server keeps about a person,
  and for how long, is [cc.privacy.retention](cc.privacy.retention.md).

## Who carries the duties

The Server Owner — not veyq's authors, not a tenant owner. An instance hosted for a club or a company makes its
operator a **hosting service** under the DSA (Art. 11–18), a **controller** under the GDPR, a hosting service provider
under the terrorist-content Regulation (EU) 2021/784 ("TCO"), and a provider who identifies itself under § 5 DDG or
§ 18 MStV. A household instance is the subset where some of these may not apply; veyq builds every mechanism for every
instance and leaves that assessment to the operator (ADR 0093 § 14). A tenant owner can delete any clause of their
own terms, so those terms cannot carry a duty that is the operator's
([ADR 0035](../decisions/0035-server-terms-of-service.md) decision 6).

## The register

**L1 — Which law reaches an instance.** The DSA covers a service "normally provided for remuneration": contested for a
non-economic household instance, likely for a club with fees or a paid host. veyq is a hosting service
(Art. 3(g)(iii), recital 29), not an online platform: recital 14 excludes information shared with a finite number of
persons the sender determines, and membership needs a human invitation. Art. 16–18 bind every hosting service with no
exemption for small ones; Art. 15 and Art. 20–28 do not reach a micro or small host. The GDPR's household exemption
(Art. 2(2)(c)) covers only a natural person's purely personal activity: never a club or a company, never someone who
provides the means for others (recital 18), and not where content reaches an unrestricted number of people — so one
link posted publicly can end it (CJEU C-101/01 *Lindqvist*, C-345/17 *Buivids*). § 7 DDG grants the hosting privilege
to unpaid services too. § 5 DDG asks an imprint of a commercial, usually paid service; § 18(1) MStV of every service
not exclusively personal or familial. Whether the DSA reaches an unpaid private service is uncertain; the Bundestag's
research service calls it so in
[*WD 7 - 3000 - 026/26*](https://www.bundestag.de/resource/blob/1182736/WD-7-026-26.pdf) (its conclusion taken from the
research report, not re-read).
*Concluded:* the hosting-not-platform reading holds while a share has no directory, no search and its key in the
fragment; whether a duty reaches an instance is the operator's assessment, and no mode makes it (ADR 0093 Context,
§ 14).
[DSA](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) ·
[CJEU C-25/17](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:62017CJ0025) ·
[CJEU C-101/01](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62001CJ0101) ·
[CJEU C-345/17](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62017CJ0345) ·
[§ 7 DDG](https://www.gesetze-im-internet.de/ddg/__7.html)

**L2 — The terms.** DSA Art. 14 asks for the moderation policy, its procedures and human review in plain language;
TCO Art. 7(1) for the policy on terrorist content; GDPR Art. 13 for the information at collection, and Art. 21(4) for
the right to object stated separately. All fall on the provider, the Server Owner.
*Concluded:* a fixed operator section beside every space's terms, and a fixed instance privacy notice as the one home
of every Art. 13 passage (ADR 0035 decisions 6–8).
[DSA](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) ·
[GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj) ·
[TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784)

**L3 — Hold and log durations.** Neither the DSA nor the GDPR sets one for a host; storage limitation (GDPR
Art. 5(1)(e)) and provable purposes do. The anchors are six months (TCO Art. 6, a duty; DSA Art. 20(1), an analogy),
60 + 30 days (Regulation 2023/1543 Art. 11, applicable since 18 August 2026) and three years from year's end
(§§ 195, 199 BGB; § 31(2) no. 1 OWiG for the fines of § 33 DDG).
*Concluded:* a hold ends by its trigger; a case's records are kept to 31 December of the third year after it closes
(ADR 0093 §§ 4, 11).
[TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) ·
[Regulation 2023/1543](https://eur-lex.europa.eu/eli/reg/2023/1543/oj) ·
[§ 199 BGB](https://www.gesetze-im-internet.de/bgb/__199.html)

**L4 — The guest-write record.** An address is personal data to a host (CJEU C-582/14, *Breyer*), and keeping it for
the functioning of a service can rest on Art. 6(1)(f). No law puts a retention duty on a host. Seven days is the
supervisory practice (the BGH allowed an access provider seven, III ZR 391/13); up to 30 is defensible with a reason.
Backups are not exempt from erasure: deletions have to be tracked and applied on restore (EDPB CEF 2025 report,
§ 4.2.6).
*Concluded:* basis Art. 6(1)(f); N days, 7 by default and at most 30, a value above seven needing a reason in the record
of processing; the backup leaves the rows out, and every erasure is re-applied after a restore (ADR 0093 § 10,
ADR 0092 § 2, [ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8).
[CJEU C-582/14](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62014CJ0582) ·
[EDPB CEF 2025](https://www.edpb.europa.eu/system/files/2026-02/edpb_cef-report_2025_right-to-erasure_en.pdf)

**L5 — Art. 18 reports and child sexual abuse material.** The BKA receives Art. 18 reports as the central office
(§ 13 DDG), through its portal. § 184b(5) StGB exempts possession and making available only where they serve
exclusively the lawful performance of state tasks, of agreements with a state body, or of official or professional
duties.
*Concluded:* for a host bound by Art. 18, a hold that ends at the handover — at the latest 90 days after the report —
serves that duty; never a content file in an export or a mail (ADR 0093 §§ 4, 13).
[§ 13 DDG](https://www.gesetze-im-internet.de/ddg/__13.html) ·
[§ 184b StGB](https://www.gesetze-im-internet.de/stgb/__184b.html) ·
[BKA, Art. 18 reports](https://www.bka.de/DE/DasBKA/OrganisationAufbau/Fachabteilungen/ZentralerInformationsUndFahndungsdienst/Digitale_Eingangsstelle/Guidelines/Verdachtsmeldungen_node.html)

**L6 — Terrorist content.** One hour from receipt of a removal order (TCO Art. 3(3)); twelve hours' notice before an
authority's first order to a host, save in an emergency (Art. 3(2)); no exemption for a small host — size counts only
in the penalty (Art. 18(2)), and Art. 3(7) excuses only objectively justifiable technical or operational reasons. The
host names a contact point (Art. 15), keeps what it blocked for six months (Art. 6) and tells the uploader unless the
authority forbids it for 6 + 6 weeks (Art. 11). In Germany the BKA issues orders and the Bundesnetzagentur supervises
(§ 1 TerrOIBG). The Regulation reaches only dissemination to the public; a `READ` link is a borderline case.
*Concluded:* a `READ` link is treated as reached; an order path on ids, within the hour, without a key and without a
report; an operator who cannot keep the hour turns public links off (ADR 0093 § 12).
[TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) ·
[§ 1 TerrOIBG](https://www.gesetze-im-internet.de/terroibg/__1.html)

**L7 — IP retention in Germany.** No duty today: the old §§ 175 ff. TKG are inapplicable (BVerwG 6 C 6.22, 14 August
2023). The bill BT-Drs. 21/6581 (three months) obliges only providers of internet access (§ 177 TKG-E) and forbids
storing data on the use of digital services on its basis; a host is reached only by requests for data it already has
(§§ 100j–100k StPO-E, the e-Evidence Regulation).
*Concluded:* addresses stay out of logs rather than being kept for a period, and the guest-write record is bounded by
the legitimate interest, not by the access providers' period (ADR 0092 §§ 3, 6; ADR 0093 § 10).
[BT-Drs. 21/6581](https://dserver.bundestag.de/btd/21/065/2106581.pdf) ·
[BVerwG press release 66/2023](https://www.bverwg.de/de/pm/2023/66)

**L8 — Transparency reports.** DSA Art. 15(1) asks every provider of an intermediary service for a public,
machine-readable report at least once a year — orders from authorities (Art. 9, 10), notices (Art. 16) and the action
taken, moderation on its own initiative, automated means — and Art. 15(2) exempts only a micro or small enterprise in
the sense of Recommendation 2003/361/EC (fewer than 50 staff, and turnover or balance sheet of at most EUR 10 million,
linked and partner enterprises counted in) that is not a very large platform. The templates and the period are
Implementing Regulation (EU) 2024/2835: the calendar year, published within two months of its end. TCO Art. 7(2) asks a
host that took action, or was required to, in a calendar year for a public report before 1 March of the next, with
Art. 7(3)'s contents, whatever its size. § 3(2) TerrOIBG has every host under German jurisdiction send the
Bundesnetzagentur, per calendar year and by 1 March, the figures of TCO Art. 21(1)(c). Whether a year with nothing to
count still needs a report is not settled by the text.
*Concluded:* the operator's duty, not built; the Run area's table says who owes what, and the moderation log and case
list hold what a report counts (ADR 0093 Consequences).
[DSA](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) ·
[Implementing Regulation 2024/2835](https://eur-lex.europa.eu/eli/reg_impl/2024/2835/oj) ·
[Recommendation 2003/361/EC](https://eur-lex.europa.eu/eli/reco/2003/361/oj) ·
[TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) ·
[§ 3 TerrOIBG](https://www.gesetze-im-internet.de/terroibg/__3.html)

**L9 — Logs.** A dynamic IP address is personal data (CJEU C-582/14, *Breyer*), and a log line with an account id is
personal data to an operator who holds the database that resolves it (GDPR recital 26). Art. 13(2)(a) and 30(1)(f)
accept "the criteria used to determine that period" where a period is not possible.
*Concluded:* logs carry pseudonymous ids only, on Art. 6(1)(f), bounded by size and a container's recreation; never an
address or an email address (ADR 0092 §§ 3, 5, 6).

**L10 — Erasure.** GDPR Art. 17, with Art. 17(3)(e) keeping what the establishment, exercise or defence of legal claims
needs; Art. 5(1)(e) bounds how long anything is kept, backups included (EDPB CEF 2025, § 4.2.6).
*Concluded:* an account is erased on request, and only what the law keeps stays, under an opaque id and to a date
([ADR 0101](../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md));
a capture lives at most B days, and an erasure is re-applied when an older one is restored (ADR 0056 decision 8).

## Open questions

Each is an inbox row. The ADRs keep their own *Still open* numbering, since code and docs cite it.

| Question | Row | Cited as |
|---|---|---|
| § 184b(5) StGB beyond the 90 days, and for a non-commercial operator | #575 | ADR 0093 Still open 1 |
| A time-bound authority link to a hold snapshot | #576 | ADR 0093 Still open 2 |
| Whether the German IP-retention bill has passed | #577 | ADR 0093 Still open 3 |
| What the recorded source port is worth behind the operator's own NAT | #578 | ADR 0093 Still open 4 |
| § 184b(5) StGB for a club's volunteer moderators | #579 | ADR 0093 Still open 5 |
| Who is the controller of a member's account data | #580 | ADR 0093 Still open 6 |
| Third-party requests on by default: the breached-password check and the exchange rates | #581 | ADR 0093 Still open 7 |
| Whether a non-economic club is an "enterprise" for DSA Art. 15(2) | #582 | ADR 0093 Still open 8 |
| Whether TCO Art. 3(7) excuses an unattended private operator | #583 | ADR 0093 Still open 9 |
| Layered Art. 13 information on a guest page, and Art. 14 for people a report names | #584 | ADR 0093 Still open 10 |
| Reported content inside a backup capture | #585 | ADR 0093 Still open 11 |
| Whether erasing a switched-off application's content needs a basis in the terms | #586 | ADR 0093 Still open 12 |
| Whether the notice of that erasure is adequate | #587 | ADR 0093 Still open 13 |
| Whether switching an application off is a moderation decision | #588 | ADR 0093 Still open 14 |
| A log's period stated as a criterion | #589 | ADR 0092 Still open 1 |
| The admin console's error log names the hop | #590 | ADR 0092 Still open 2 |
| Whether DSA Art. 14(1) reaches a space's own terms before sign-in | — | ADR 0035 decision 6 |
