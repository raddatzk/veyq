---
title: "Eine Instanz moderieren"
sources:
  - docs/features/moderation/use-cases/uc.moderation.serve-as-moderator.md
  - docs/features/moderation/enablers/en.moderation.log.md
  - docs/features/moderation/use-cases/uc.moderation.review-a-case.md
  - docs/features/moderation/use-cases/uc.moderation.take-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md
---

Die Person, die einen veyq-Server betreibt, kann dich bitten, ihn zu **moderieren**: dir anzusehen, was gemeldet wird,
in jedem Bereich auf diesem Server, und zu entscheiden, was damit geschieht ([Etwas melden](/veyq/de/help/report-something/)).
Meldungen kommen versiegelt für den Schlüssel an, den du hältst, was sie nennen, bleibt für dich erhalten, und du
bekommst eine Mail, wenn ein neuer Fall eingeht. Zuerst übernimmst du die Aufgabe, damit du diesen Schlüssel hältst;
dann prüfst du Fälle und entscheidest.

## Annehmen

1. Melde dich unter der Adresse eines Bereichs an, zu dem du gehörst, und öffne dort `/moderation`, zum Beispiel
   `https://photos.example.com/moderation`. Die Seite meldet dich selbst an.
2. Wenn du ernannt wurdest, sagt die Seite, was Moderieren bedeutet:
   - du siehst, was gemeldet wurde, und das kann Darstellungen sexuellen Missbrauchs von Kindern einschließen;
   - jedes Mal, wenn du eine Meldung öffnest, hält das **Moderationsprotokoll** das mit deinem Konto fest.
3. Wähle **Annehmen und moderieren**. Wurdest du nicht ernannt, sagt die Seite das und bietet nichts an.

## Deine Adresse bestätigen

Das Annehmen schickt einen Code an die E-Mail-Adresse deines Kontos. Gib ihn unter **Code** ein und wähle **Adresse
bestätigen**. Hinweise auf neue Fälle erreichen dich nur an einer bestätigten Adresse. Kam der Code nicht an oder gilt
er nicht mehr, wähle **Neuen Code senden**. Änderst du die Adresse deines Kontos, bestätigst du die neue genauso.

## Den Moderationsschlüssel öffnen

1. Gib auf `/moderation` dein Passwort und deinen Secret Key ein und wähle **Moderationsschlüssel öffnen**. Die Seite
   fragt jedes Mal nach beidem: Ein vertrauenswürdiges Gerät öffnet die Moderation nicht.
2. Die Seite öffnet nur den Moderationsschlüssel. Auf dieser Seite bleibt nichts anderes von deinem Konto geöffnet;
   was du dir dort ansiehst, erreicht deine eigenen Bereiche also nicht.
3. Bist du neu, hältst du den Schlüssel noch nicht. Er erreicht dich, wenn eine Person, die ihn hält, diese Seite das
   nächste Mal öffnet; die Seite sagt das.
4. Hältst du den Schlüssel und wartet jemand anderes darauf, gibt deine Seite ihn weiter, nachdem sie geprüft hat, dass
   der Schlüssel dieser Person der ist, den du schon kennst. Hat sich ihr Kontoschlüssel geändert, geht er nicht
   weiter, und die Seite sagt das: Vergleiche zuerst ihre Sicherheitsnummer
   ([Eine Person verifizieren](/veyq/de/account/verify-a-person/)).
5. Danach zeigt die Seite **Meldungen**, die Fälle aller Bereiche auf dem Server.

Sagt die Seite, sie habe **abgebrochen**, weil der Schlüssel, den der Server veröffentlicht, nicht der ist, den du
hältst, mach nicht weiter und sag der Person Bescheid, die den Server betreibt.

## Einen Fall prüfen

1. **Meldungen** listet jeden Fall mit seiner Kategorie, der Art des Elements, wann und wie oft es gemeldet wurde, der
   Adresse, unter der es gemeldet wurde, seinem Stand und bis wann es gesichert bleibt. Die dringenden Kategorien stehen
   oben. **Abgeschlossene Fälle zeigen** zeigt die abgeschlossenen.
2. Wähle bei einem Fall **Öffnen**. Du siehst jede Meldung — ihre Begründung, den Kontakt, den die meldende Person
   hinterlassen hat, und den Zusammenhang, auf deinem Gerät mit dem Moderationsschlüssel geöffnet — und die Maßnahmen,
   die Vorschläge, die Sicherung und das Protokoll des Falls.
3. Wähle **Gemeldetes ansehen**. Es öffnet sich unter der Adresse des gemeldeten Bereichs, wo du den
   Moderationsschlüssel erneut öffnest. Dann siehst du das Gemeldete so, wie ein Gast eines Links es sähe, nur zum
   Ansehen, mit den Meldungen daneben: so, wie es bei der Meldung war, auch wenn es seitdem geändert oder gelöscht wurde,
   und auch, wenn es jetzt gesperrt ist. Das Protokoll hält fest, dass du es geöffnet hast.

Eine Meldung, die für einen Schlüssel versiegelt ist, den du nicht hältst, sagt, dass sie sich nicht öffnen lässt.
Öffnet sich keine Meldung eines Falls mit den Schlüsseln, die du hältst, bleibt auch das Gemeldete verschlossen.

## Entscheiden

Unter **Entscheiden** schreibst du, wenn du willst, eine **Notiz** — sie gehört zu deinem nächsten Schritt und ist so
versiegelt, dass nur die Moderation sie lesen kann — und wählst einen Schritt. Jeder sagt, was er erreicht, bevor du
ihn bestätigst.

- **Fall zurückweisen** — die Meldung war unbegründet. Der Fall wird abgeschlossen, und was für ihn gesichert war, wird
  sofort freigegeben.
- **Link sperren** — alle Gäste dieses Links sehen dann einen Link, den es nicht gibt. Die Person, die ihn erstellt hat,
  kann ihn weder reaktivieren noch erneuern, solange die Sperre besteht.
- **Inhalt sperren** — niemand kann ihn mehr öffnen, auch die Mitglieder nicht. Alle sehen an seiner Stelle **Von der
  Moderation gesperrt**. Gelöscht wird nichts.
- **Hochladenden Gast sperren** — der Gast kann nichts mehr hinzufügen, und der Link, über den er kam, wird mit ihm
  gesperrt.
- **Meldende Person sperren** — weitere Meldungen dieses Mitglieds oder über diesen Link werden abgelehnt. Die schon
  abgegebenen Meldungen bleiben.
- **Vorschlagen**, die Anmeldung eines Mitglieds zu sperren oder alles unter der gemeldeten Adresse offline zu nehmen.
  Diese Schritte nimmt nur die Person vor, die den Server betreibt; der Fall zeigt deinen Vorschlag und was aus ihm
  wurde.
- **Sicherung verlängern**, solange etwas gesichert ist: Wähle ein **Neues Ende**, nach dem jetzigen und höchstens
  sechs Monate ab heute, und gib eine **Begründung** an, etwa die Anfrage einer Behörde oder eine Klage. Die Begründung
  ist Pflicht und wird versiegelt wie eine Notiz.

Eine Sperre geht mit einer **Begründung** an die Betroffenen. Wähle den **Grund** und schreib, **Was du festgestellt
hast**: Auf den meisten Servern ist das Pflicht, auf einem privaten freiwillig. Es erreicht jede betroffene Person
versiegelt, sodass nur sie es lesen kann — und das Moderationsprotokoll, versiegelt wie eine Notiz —, nie eine E-Mail.
Die Begründung nennt den Server, nie dich. Wer beim Melden eine Adresse hinterlassen hat, erfährt die Entscheidung.

Eine Sperre entscheidet den Fall, und was für ihn gesichert ist, bleibt dann sechs Monate gesichert. **Aufheben** bei
einer Maßnahme — deiner oder der einer anderen moderierenden Person — liefert das Gesperrte wieder unverändert aus. Wird
die letzte Maßnahme in Kraft aufgehoben, wird das Gesicherte freigegeben und der Fall abgeschlossen.

## Widersprüche

Wer von einer Sperre betroffen ist, kann einmal widersprechen. Der Fall zeigt den Widerspruch dann unter
**Widersprüche**, für dich geöffnet, und steht wieder in der Liste der Fälle, markiert mit **Widerspruch offen**. Wähle
**Maßnahme bestätigen** oder **Maßnahme aufheben**. Hast du die Maßnahme selbst ergriffen und hält eine andere Person
aus der Moderation den Schlüssel, sagt der Fall, dass eine andere Person entscheiden muss; bist du die einzige Person in
der Moderation, entscheidest du selbst, und die Antwort sagt das.

## Was festgehalten wird, und wie lange

Das Moderationsprotokoll hält fest, wann du ernannt wurdest und angenommen hast, jedes Weitergeben des Schlüssels und
wann du niederlegst: wer, wann und für wen, nie einen Schlüssel und nie Inhalte. Es wird bis zum 31. Dezember des
dritten Jahres nach dem Ende deiner Moderation aufbewahrt und dann gelöscht. In einem Fall hält es jedes Öffnen zur
Prüfung fest und jeden Schritt und jedes Aufheben, jeweils wer und wann; deine Notizen und Begründungen nur versiegelt. Das
Protokoll eines Falls wird bis zum 31. Dezember des dritten Jahres nach seinem Abschluss aufbewahrt.

## Niederlegen

Wähle **Moderation niederlegen**. Deine Rolle und deine Kopie des Schlüssels enden sofort. Solange nur du den
Schlüssel hältst, kannst du nicht niederlegen; zuerst muss eine andere moderierende Person ihn halten. Wer den
Schlüssel noch nicht hält, bittet die Person, die den Server betreibt, um die Entfernung.
