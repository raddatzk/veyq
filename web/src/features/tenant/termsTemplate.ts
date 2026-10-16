// A starter clause template for tenant terms (uc.admin.tenant-terms, cr-0114 / ADR 0035) — the pre-E2EE
// 11-clause set (purpose, privacy, rights, children, illegal/CSAM, share-links, guest-uploads, availability,
// review & removal, external services, contact-with-`{contact}`). Seed content the OWNER inserts and then edits
// freely; not UI chrome (so it lives here, not in i18n). DE is neutral-first inclusive language. The `{contact}`
// placeholder is filled by the owner before publishing.

export const TERMS_TEMPLATE: { de: string; en: string } = {
  de: `# Nutzungsbedingungen

## 1. Zweck
Dieser Server dient dem privaten Teilen von Erinnerungen (Fotos, Videos, Notizen) mit Menschen, die du einlädst.

## 2. Datenschutz & Verschlüsselung
Inhalte sind Ende-zu-Ende-verschlüsselt. Die betreibende Person kann private Inhalte nicht lesen; sichtbar sind nur technische Metadaten (Größen, Zeitpunkte, Mitgliedschaften).

## 3. Deine Inhalte
Du behältst alle Rechte an dem, was du hochlädst. Du bestätigst, die nötigen Rechte zum Hochladen und Teilen zu haben.

## 4. Schutz von Kindern
Inhalte, die Minderjährige gefährden oder ausbeuten, sind strikt verboten.

## 5. Verbotene Inhalte
Keine illegalen Inhalte — insbesondere Darstellungen sexuellen Missbrauchs von Kindern (CSAM), Aufrufe zu Gewalt oder Inhalte, die Rechte anderer verletzen.

## 6. Geteilte Links
Öffentlich geteilte Inhalte können von allen mit dem Link geöffnet werden. Teile Links nur mit Menschen, denen du vertraust.

## 7. Uploads durch Gäste
Wenn du einen Upload-Link teilst, verantwortest du, was darüber hochgeladen wird.

## 8. Verfügbarkeit
Der Dienst wird ohne Verfügbarkeitsgarantie bereitgestellt. Sichere dir Wichtiges zusätzlich.

## 9. Prüfung & Entfernung
Gemeldete öffentlich geteilte Inhalte können geprüft und entfernt werden. Private, ungeteilte Inhalte bleiben unlesbar.

## 10. Externe Dienste
Optionale Funktionen (z. B. Kartendienste) können Anfragen an Dritte senden. Sie sind opt-in und in der App gekennzeichnet.

## 11. Kontakt & Löschung
Fragen, Meldungen oder Löschwünsche: {contact}`,
  en: `# Terms of Use

## 1. Purpose
This server is for privately sharing memories (photos, videos, notes) with people you invite.

## 2. Privacy & encryption
Content is end-to-end encrypted. The operator cannot read private content; only technical metadata (sizes, timestamps, memberships) is visible.

## 3. Your content
You keep all rights to what you upload. You confirm you hold the rights needed to upload and share it.

## 4. Protection of children
Content that endangers or exploits minors is strictly forbidden.

## 5. Prohibited content
No illegal content — in particular child sexual abuse material (CSAM), incitement to violence, or content that infringes others' rights.

## 6. Shared links
Publicly shared content can be opened by anyone with the link. Share links only with people you trust.

## 7. Guest uploads
If you share an upload link, you are responsible for what is uploaded through it.

## 8. Availability
The service is provided without any availability guarantee. Keep your own backup of anything important.

## 9. Review & removal
Reported publicly shared content may be reviewed and removed. Private, unshared content stays unreadable.

## 10. External services
Optional features (e.g. map services) may send requests to third parties. They are opt-in and marked in the app.

## 11. Contact & deletion
Questions, reports, or deletion requests: {contact}`,
}
