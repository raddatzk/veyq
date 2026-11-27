package me.raddatz.veyq.mobile.session

/**
 * The starter template of a tenant's terms (uc.admin.tenant-terms; ADR 0035), the web's `termsTemplate.ts` as it is:
 * seed content an owner inserts into one language and then edits, not interface words (so it is no catalogue entry), its
 * `{contact}` the owner's to fill. Ported as text because the core cannot read the web's TypeScript at build time;
 * `TermsTemplateIsTheWebsTest` holds the two equal, so this copy cannot drift.
 */
internal object TermsTemplate {
    /** The template by language, in the web's keys. */
    val byLanguage: Map<String, String> = mapOf(
        "de" to """# Nutzungsbedingungen

## 1. Zweck
Dieser Server dient dem privaten Teilen von Erinnerungen (Fotos, Videos, Notizen) mit Menschen, die du einlädst.

## 2. Datenschutz & Verschlüsselung
Inhalte sind Ende-zu-Ende-verschlüsselt: Der Server speichert sie nur verschlüsselt, und wer ihn betreibt, kann sie dort nicht lesen; sichtbar sind technische Metadaten (Größen, Zeitpunkte, Mitgliedschaften). Wer den Server betreibt, liefert meist auch diese App aus und könnte sie so ändern, dass sie mitliest, was du darin öffnest — dagegen hilft die Verschlüsselung allein nicht. Was der Server über Gäste aufzeichnet und nach einer Meldung aufbewahrt, steht im Abschnitt der Betreiberseite unten.

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
Manche Funktionen senden Anfragen direkt aus deinem Browser an Dritte: Karten- und Ortsnamendienste erst, wenn du sie einschaltest; die Prüfung auf geleakte Passwörter und die Wechselkurse des Tages von selbst. Die Datenschutzerklärung dieses Servers nennt jeden dieser Dienste.

## 11. Kontakt & Löschung
Fragen, Meldungen oder Löschwünsche: {contact}""",
        "en" to """# Terms of Use

## 1. Purpose
This server is for privately sharing memories (photos, videos, notes) with people you invite.

## 2. Privacy & encryption
Content is end-to-end encrypted: the server stores it only encrypted, and whoever runs it cannot read it there; technical metadata (sizes, timestamps, memberships) is visible. Whoever runs the server usually also delivers this app, and could change it to read what you open in it — encryption alone does not prevent that. What the server records about guests and keeps after a report is stated in the operator's section below.

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
Some features send requests straight from your browser to third parties: map and place-name services only once you turn them on; the breached-password check and the day's exchange rates by themselves. This server's privacy notice names each of these services.

## 11. Contact & deletion
Questions, reports, or deletion requests: {contact}""",
    )
}
