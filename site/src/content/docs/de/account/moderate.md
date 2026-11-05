---
title: "Eine Instanz moderieren"
sources:
  - docs/features/moderation/use-cases/uc.moderation.serve-as-moderator.md
  - docs/features/moderation/enablers/en.moderation.log.md
  - docs/features/moderation/use-cases/uc.moderation.review-a-case.md
  - docs/features/moderation/use-cases/uc.moderation.take-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.authority-order.md
  - docs/features/moderation/use-cases/uc.moderation.report-to-an-authority.md
---

Die Person, die einen veyq-Server betreibt, kann dich bitten, ihn zu **moderieren**: dir anzusehen, was gemeldet wird,
in jedem Bereich auf diesem Server, und zu entscheiden, was damit geschieht ([Etwas melden](/de/help/report-something/)).
Meldungen kommen versiegelt für den Schlüssel an, den du hältst, was sie nennen, bleibt für dich erhalten, und du
bekommst eine Mail, wenn ein neuer Fall eingeht. Zuerst übernimmst du die Aufgabe, damit du diesen Schlüssel hältst;
dann prüfst du Fälle und entscheidest.

## Annehmen

1. Öffne die Moderationsseite: Geh in veyq zu **Einstellungen → Moderation** und wähle **Moderationsseite öffnen und
   annehmen**. Diesen Abschnitt gibt es erst, wenn du ernannt wurdest. Die Seite öffnet sich in einem eigenen neuen Tab,
   damit Gemeldetes nie eine Seite mit den eigenen Inhalten deines Kontos teilt. Du kannst sie auch selbst öffnen: Sie
   liegt unter `/moderation` auf der Adresse jedes Bereichs, zu dem du gehörst, zum Beispiel
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
   der Schlüssel dieser Person aus dem folgt, den du schon kennst (beim ersten Mal: aus dem, den der Server zeigt).
   Lässt er sich nicht bestätigen, geht er nicht weiter, und die Seite sagt das: Vergleicht eure Sicherheitsnummern
   persönlich ([Ein Schlüssel lässt sich nicht bestätigen](/de/help/key-changed/)).
5. Danach zeigt die Seite **Meldungen**, die Fälle aller Bereiche auf dem Server.

Sagt die Seite, sie habe **abgebrochen**, weil der Schlüssel, den der Server veröffentlicht, nicht der ist, den du
hältst, mach nicht weiter und sag der Person Bescheid, die den Server betreibt.

## Einen Fall prüfen

1. **Meldungen** listet jeden Fall mit seiner Kategorie, der Art des Elements, wann und wie oft es gemeldet wurde, der
   Adresse, unter der es gemeldet wurde, seinem Stand und bis wann es gesichert bleibt. Die dringenden Kategorien stehen
   oben. **Abgeschlossene Fälle zeigen** zeigt die abgeschlossenen.
2. Wähle bei einem Fall **Öffnen**. Du siehst jede Meldung — ihre Begründung, den Kontakt, den die meldende Person
   hinterlassen hat, und den Zusammenhang, auf deinem Gerät mit dem Moderationsschlüssel geöffnet — und die Maßnahmen,
   die Vorschläge, die Sicherung und das Protokoll des Falls. Der Kontakt wird sechs Monate, nachdem die meldende
   Person die Entscheidung erfahren hat, gelöscht.
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
- **Alles von dieser Person sperren** — unter **Alles von einer hochladenden Person** steht jede Person, die die
  gesicherte Kopie als hochladend nennt, mit der Zahl dessen, was sie in diesen Bereich hochgeladen hat. Der Schritt
  sperrt all das als Inhalt, wie **Inhalt sperren** es tut, und bewahrt es für den Fall auf; was die Person später
  hinzufügt, wird nicht gesperrt. Wer davon betroffen und Mitglied des Bereichs ist, und jede Person, der der Bereich
  gehört, erfährt an jedem gesperrten Element, warum.
- **Meldende Person sperren** — weitere Meldungen dieses Mitglieds oder über diesen Link werden abgelehnt. Die schon
  abgegebenen Meldungen bleiben.
- **Vorschlagen**, die Anmeldung eines Mitglieds zu sperren oder alles unter der gemeldeten Adresse offline zu nehmen.
  Diese Schritte nimmt nur die Person vor, die den Server betreibt; der Fall zeigt deinen Vorschlag und was aus ihm
  wurde.
- **Sicherung verlängern**, solange etwas gesichert ist: Wähle ein **Neues Ende**, nach dem jetzigen und höchstens
  sechs Monate ab heute, die **Art des Grunds** — Ersuchen oder Anordnung einer Behörde, eine laufende Meldung an eine
  Behörde, ein Widerspruch, ein Anwaltsschreiben oder eine Klage — und gib eine **Begründung** an. Die Begründung ist
  Pflicht und wird versiegelt wie eine Notiz; ihre Art nicht.

Ein Fall, den niemand entscheidet, schließt sich nie von selbst: 30, 75 und 90 Tage nach seiner ersten Meldung bekommt
jede Person aus der Moderation eine Erinnerung per E-Mail, die nur seine Kategorie und sein Alter nennt, und nach 90
Tagen markiert ihn die Liste der Fälle mit **Seit über 90 Tagen unentschieden**.

Eine Sperre geht mit einer **Begründung** an die Betroffenen. Wähle den **Grund**, nenne **Gesetz oder Klausel, auf
die sie sich stützt** — einen kurzen Verweis wie „§ 86a StGB“ oder „Bedingungen des Bereichs, Klausel 4“, so angezeigt,
wie du ihn schreibst — und schreib, **Was du festgestellt hast**, also warum der Inhalt darunter fällt: Beides ist
Pflicht. Was du festgestellt hast, erreicht jede betroffene Person
versiegelt, sodass nur sie es lesen kann — und das Moderationsprotokoll, versiegelt wie eine Notiz —, nie eine E-Mail.
Die Begründung nennt den Server, nie dich, und sagt, dass die Sperre überall gilt, wo der Server erreichbar ist, bis sie
aufgehoben wird. Wer beim Melden eine Adresse hinterlassen hat, erfährt die Entscheidung und die nächsten
Möglichkeiten.

Eine Sperre entscheidet den Fall, und was für ihn gesichert ist, bleibt dann sechs Monate gesichert. **Aufheben** bei
einer Maßnahme — deiner oder der einer anderen moderierenden Person — liefert das Gesperrte wieder unverändert aus. Wird
die letzte Maßnahme in Kraft aufgehoben, wird das Gesicherte freigegeben und der Fall abgeschlossen.

Eine Maßnahme endet von selbst, wenn das Gesperrte endgültig gelöscht wird — ein gelöschter Link, etwas endgültig aus
dem Papierkorb Entferntes, ein gelöschter Bereich: Der Fall zeigt sie dann als aufgehoben, obwohl niemand sie aufgehoben hat. Was für den Fall
gesichert ist, bleibt trotzdem seine sechs Monate gesichert. Eine Sperre auf etwas bereits endgültig Gelöschtes endet
sofort; deine Entscheidung und ihre Begründung bleiben.

## Widersprüche

Wer von einer Sperre betroffen ist, kann einmal widersprechen. Der Fall zeigt den Widerspruch dann unter
**Widersprüche**, für dich geöffnet, und steht wieder in der Liste der Fälle, markiert mit **Widerspruch offen**. Wähle
**Maßnahme bestätigen** oder **Maßnahme aufheben**. Hast du die Maßnahme selbst ergriffen und hält eine andere Person
aus der Moderation den Schlüssel, sagt der Fall, dass eine andere Person entscheiden muss; bist du die einzige Person in
der Moderation, entscheidest du selbst, und die Antwort sagt das.

## Eine Behörde

**Eine Anordnung einer Behörde.** Über der Liste der Fälle öffnet **Anordnung einer Behörde eintragen** ein Formular:
die **Art der Anordnung**, der **Link oder Link-Kennung aus der Anordnung** – füge ihn so ein, wie die Anordnung ihn
nennt; nur seine Kennung verlässt dein Gerät –, das **Aktenzeichen der Behörde**, so versiegelt, dass nur die Moderation
es lesen kann, und der **Eingang bei der Kontaktstelle**. Eine Entfernungsanordnung sperrt den Link und alles, wozu er
Zugang gibt, nach deiner Bestätigung sofort für alle; du siehst dann, wann, auf die Sekunde, und den Text für das
Rückmeldeformular der Behörde zum **Kopieren**. Gibt es hier keinen solchen Link, wird nichts gesperrt, und du bekommst
einen Entwurf, der das sagt. Jede Anordnung bleibt bei ihrem Fall und wird mit ihm gelöscht (unten);
**Eingetragene Anordnungen** listet sie auf.

**Die Betroffenen später informieren.** Nur eine Behörde kann verlangen, die Betroffenen noch nicht zu informieren. Dann
setze unter **Entscheiden** das Häkchen bei **Eine Behörde hat verlangt, die Betroffenen noch nicht zu informieren**,
wähle **Informieren am** – höchstens sechs Wochen im Voraus – und trag das Verlangen der Behörde ein. Die Begründung
wartet bis zu diesem Tag, und die Liste der Fälle erinnert dich daran. **Aufschub verlängern** nimmt die Verlängerung
der Behörde einmal an.

**An eine Behörde melden.** In einem Fall zeigt **Angaben für die Meldung zusammenstellen**, was der Server darüber
weiß – den Fall, seine Kategorie, jede Zeit in UTC, die Maßnahmen, wer es hochgeladen hat und bei einem Gast die
festgehaltene Adresse und Zeit – als Text zum **Kopieren** in das Formular der Behörde. Lade das Gemeldete nie herunter,
leite es nie weiter und mache kein Bild davon. **Festhalten, dass an eine Behörde gemeldet wurde** hält das mit dem
Aktenzeichen der Behörde im Protokoll des Falls fest – kopiere die Angaben also vorher: Bei Darstellungen sexuellen
Missbrauchs von Kindern beendet das auch, was vom Fall aufbewahrt wird.

**Darstellungen sexuellen Missbrauchs von Kindern.** Bevor sich ein solcher Fall öffnet, sagt eine ganze Seite, was du
nie tun sollst und was zuerst zu tun ist. **Zurück zur Liste** lässt ihn geschlossen; **Verstanden – Fall öffnen** öffnet
ihn, mit **Inhalt sperren** als erstem Schritt. Solche Inhalte werden nur aufbewahrt, bis du festhältst, dass an eine
Behörde gemeldet wurde, und nie länger als 90 Tage nach der Meldung – das Sperren verlängert das nicht. Verlängere nur,
solange deine Meldung an die Behörde noch läuft oder weil die Behörde darum bittet: Nur diese beiden Arten von Grund
werden angeboten, und der Server lehnt jede andere ab.

## Was festgehalten wird, und wie lange

Das Moderationsprotokoll hält fest, wann du ernannt wurdest und angenommen hast, jedes Weitergeben des Schlüssels und
wann du niederlegst: wer, wann und für wen, nie einen Schlüssel und nie Inhalte. Es wird bis zum 31. Dezember des
dritten Jahres nach dem Ende deiner Moderation aufbewahrt und dann gelöscht. In einem Fall hält es jedes Öffnen zur
Prüfung fest und jeden Schritt und jedes Aufheben, jeweils wer und wann; deine Notizen und Begründungen nur versiegelt. Ein
Fall – sein Protokoll, seine Meldungen und alles, was darin entschieden wurde – wird bis zum 31. Dezember des dritten
Jahres nach seinem Abschluss aufbewahrt und dann gelöscht, aber nie, solange eine seiner Sperren noch gilt. Die Adresse
einer meldenden Person wird sechs Monate, nachdem sie die Entscheidung erfahren hat, gelöscht.

## Niederlegen

Wähle **Moderation niederlegen**. Deine Rolle und deine Kopie des Schlüssels enden sofort. Solange nur du den
Schlüssel hältst, kannst du nicht niederlegen; zuerst muss eine andere moderierende Person ihn halten. Wer den
Schlüssel noch nicht hält, bittet die Person, die den Server betreibt, um die Entfernung.
