// A starter clause template for tenant terms (uc.admin.tenant-terms, cr-0114 / ADR 0035) — the pre-E2EE
// 11-clause set (purpose, privacy, rights, children, illegal/CSAM, share-links, guest-uploads, availability,
// review & removal, external services, contact-with-`{contact}`). Seed content the OWNER inserts and then edits
// freely; not UI chrome (so it lives here, not in i18n). DE is neutral-first inclusive language. The `{contact}`
// placeholder is filled by the owner before publishing.
//
// What the HOST owes (the notice path, what is kept, the measures, the statement and objection, the authorities, the
// guest-write record's information) is not the space's to state: it is the fixed operator section veyq serves after
// every space's terms (ADR 0035 decision 6), which a space owner cannot delete. So clause 9 points there instead of
// regulating review itself, and clauses 2 and 7 say nothing about what the server keeps beyond pointing there.

export const TERMS_TEMPLATE: { de: string; en: string } = {
  de: `# Nutzungsbedingungen

## 1. Zweck
Dieser Server dient dem privaten Teilen von Erinnerungen (Fotos, Videos, Notizen) mit Menschen, die du einlädst.

## 2. Datenschutz & Verschlüsselung
Inhalte sind Ende-zu-Ende-verschlüsselt. Wer den Server betreibt, kann private Inhalte nicht lesen; sichtbar sind nur technische Metadaten (Größen, Zeitpunkte, Mitgliedschaften). Was der Server über Gäste aufzeichnet und nach einer Meldung aufbewahrt, steht im Abschnitt der Betreiberseite unten.

## 3. Deine Inhalte
Du behältst alle Rechte an dem, was du hochlädst. Du bestätigst, die nötigen Rechte zum Hochladen und Teilen zu haben.

## 4. Schutz von Kindern
Inhalte, die Minderjährige gefährden oder ausbeuten, sind strikt verboten.

## 5. Verbotene Inhalte
Keine illegalen Inhalte — insbesondere Darstellungen sexuellen Missbrauchs von Kindern (CSAM), Aufrufe zu Gewalt oder Inhalte, die Rechte anderer verletzen.

## 6. Geteilte Links
Öffentlich geteilte Inhalte können von allen mit dem Link geöffnet werden. Teile Links nur mit Menschen, denen du vertraust.

## 7. Uploads durch Gäste
Wenn du einen Upload-Link teilst, verantwortest du, was darüber hochgeladen wird. Was der Server über das Hinzufügen durch Gäste aufzeichnet, steht im Abschnitt der Betreiberseite unten.

## 8. Verfügbarkeit
Der Dienst wird ohne Verfügbarkeitsgarantie bereitgestellt. Sichere dir Wichtiges zusätzlich.

## 9. Meldungen & Maßnahmen
Wie Inhalte gemeldet werden, wer sie prüft, welche Maßnahmen es gibt und wie du widersprechen kannst, regelt der Abschnitt der Betreiberseite unten. Er gilt für jeden Bereich auf diesem Server.

## 10. Externe Dienste
Optionale Funktionen (z. B. Kartendienste) können Anfragen an Dritte senden. Sie sind opt-in und in der App gekennzeichnet.

## 11. Kontakt & Löschung
Fragen, Meldungen oder Löschwünsche: {contact}`,
  en: `# Terms of Use

## 1. Purpose
This server is for privately sharing memories (photos, videos, notes) with people you invite.

## 2. Privacy & encryption
Content is end-to-end encrypted. Whoever runs the server cannot read private content; only technical metadata (sizes, timestamps, memberships) is visible. What the server records about guests and keeps after a report is stated in the operator's section below.

## 3. Your content
You keep all rights to what you upload. You confirm you hold the rights needed to upload and share it.

## 4. Protection of children
Content that endangers or exploits minors is strictly forbidden.

## 5. Prohibited content
No illegal content — in particular child sexual abuse material (CSAM), incitement to violence, or content that infringes others' rights.

## 6. Shared links
Publicly shared content can be opened by anyone with the link. Share links only with people you trust.

## 7. Guest uploads
If you share an upload link, you are responsible for what is uploaded through it. What the server records when guests add something is stated in the operator's section below.

## 8. Availability
The service is provided without any availability guarantee. Keep your own backup of anything important.

## 9. Reports & measures
How content is reported, who reviews it, which measures there are and how to object is set out in the operator's section below. It applies to every space on this server.

## 10. External services
Optional features (e.g. map services) may send requests to third parties. They are opt-in and marked in the app.

## 11. Contact & deletion
Questions, reports, or deletion requests: {contact}`,
}
