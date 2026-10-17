---
title: "Eine Instanz moderieren"
sources:
  - docs/features/moderation/use-cases/uc.moderation.serve-as-moderator.md
  - docs/features/moderation/enablers/en.moderation.log.md
---

Die Person, die einen veyq-Server betreibt, kann dich bitten, ihn zu **moderieren**: dir anzusehen, was gemeldet wird,
in jedem Bereich auf diesem Server, und zu entscheiden, was damit geschieht. Das Melden selbst ist noch nicht gebaut.
Heute kannst du die Aufgabe übernehmen, damit du den Schlüssel hältst, für den Meldungen versiegelt werden.

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
5. Danach zeigt die Seite **Meldungen**. Solange das Melden nicht gebaut ist, steht dort, dass nichts gemeldet wurde.

Sagt die Seite, sie habe **abgebrochen**, weil der Schlüssel, den der Server veröffentlicht, nicht der ist, den du
hältst, mach nicht weiter und sag der Person Bescheid, die den Server betreibt.

## Was festgehalten wird, und wie lange

Das Moderationsprotokoll hält fest, wann du ernannt wurdest und angenommen hast, jedes Weitergeben des Schlüssels und
wann du niederlegst: wer, wann und für wen, nie einen Schlüssel und nie Inhalte. Es wird bis zum 31. Dezember des
dritten Jahres nach dem Ende deiner Moderation aufbewahrt und dann gelöscht.

## Niederlegen

Wähle **Moderation niederlegen**. Deine Rolle und deine Kopie des Schlüssels enden sofort. Solange nur du den
Schlüssel hältst, kannst du nicht niederlegen; zuerst muss eine andere moderierende Person ihn halten. Wer den
Schlüssel noch nicht hält, bittet die Person, die den Server betreibt, um die Entfernung.
