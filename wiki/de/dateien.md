<!-- speaks-for: docs/features/platform/use-cases/uc.platform.node-cover.md, docs/features/files/use-cases/uc.files.member-files.md, docs/features/memory/use-cases/uc.memory.move-node.md, docs/features/memory/use-cases/uc.memory.delete-node.md, docs/features/memory/use-cases/uc.memory.browse.md; pair: en/files.md -->
# Dateien — und wie du dich zurechtfindest

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). veyq ist eine Plattform
für verschlüsselte, teilbare Dinge und wächst durch **Anwendungen**. **Erinnerungen** ist die Anwendung für Fotos,
Videos und Tagebuch. **Dateien** ist die für alles andere: Dokumente, PDFs, jede Datei, die du aufbewahren willst.
Beide liegen in denselben Ordnern deines Bereichs, Seite an Seite.

## Was du mit einer Datei tun kannst

- **Hochladen** — wähle **Neu → Dateien hochladen** oder lege Dateien auf den Ordner. Das Hochladen läuft im
  Hintergrund; das Status-Menü in der oberen Leiste zeigt, wie weit es ist.
- **Herunterladen** — die Herunterladen-Schaltfläche in der Zeile der Datei gibt dir die Datei genau so zurück, wie
  du sie abgelegt hast, unter dem Namen, den sie in veyq hat. Ein Klick auf die Zeile selbst öffnet die Seite der Datei.
- **Ersetzen** — lege eine neuere Fassung an die Stelle der Datei. Der Name bleibt. veyq **fragt vorher nach**, denn
  es gibt **keinen Versionsverlauf**: Der alte Inhalt lässt sich nicht zurückholen. Dieselbe Frage kommt, wenn du eine
  Datei hochlädst, deren Name in dem Ordner schon vergeben ist.
- **Umbenennen und verschieben** — beides findest du im **⋯**-Menü der Zeile einer Datei. **Verschieben nach …** öffnet eine Liste von
  Orten; wähle einen, und die Datei geht dorthin (mehr dazu unten).
- **Löschen, mit Rückgängig** — eine gelöschte Datei kommt in den **Papierkorb**, und eine Meldung bietet dir sofort
  **Rückgängig** an. Später kannst du sie noch aus dem Papierkorb wiederherstellen, bis sie endgültig entfernt wird;
  der Papierkorb zeigt das Datum. Was im Papierkorb liegt, zählt weiter gegen deinen Speicherplatz, bis er geleert ist.
- **Teilen** — einen ganzen Ordner oder eine einzelne Datei. Siehe [Teilen](teilen.md).

Hat jemand anderes in deinem Bereich dieselbe Datei kurz vor dir ersetzt, sagt dir veyq das, statt seine Fassung
stillschweigend zu überschreiben, und du kannst **Trotzdem ersetzen** wählen.

Ein Name muss im Ordner frei sein: Eine Datei kann nicht denselben Namen tragen wie ein Ordner, eine Erinnerung oder
ein Briefkasten, die schon dort liegen. veyq überspringt so eine Datei und sagt dir, welcher Eintrag den Namen hat.

**Große Dateien** werden Stück für Stück heruntergeladen und auf deinem Gerät wieder zusammengesetzt. Browser
kommen unterschiedlich gut mit sehr großen Dateien zurecht: Kann dein Browser direkt in eine Datei speichern, die du
auswählst, ist das der beste Fall. Kann er das nicht, wird eine sehr große Datei unter Umständen mit einer Meldung
abgelehnt, statt mittendrin zu scheitern.

## Dinge verschieben

**Verschieben nach …** funktioniert gleich bei einem Ordner, einer Erinnerung, einem Kapitel, einer Datei oder einem
Briefkasten: Wähle den neuen Ort aus einer Liste, und alles darin kommt mit und bleibt lesbar. Hat dir jemand einen Ordner freigegeben, in dem du mitwirken darfst, kannst du
Dinge **darin** verschieben — aber nicht hinaus.

## Eine Liste für alles in einem Ordner

Ein Ordner zeigt **eine Liste** von allem, was darin liegt: Unterordner, Erinnerungen, Dateien und Briefkästen
gemeinsam. Zuerst kommen die Ordner, danach alles andere in alphabetischer Reihenfolge, egal was es ist (Zahlen zählen
als Zahlen, *Foto 2* steht also vor *Foto 10*). Jeder Eintrag sagt, was er ist — zum Beispiel *Datei · 157 B · geändert …*
oder *Erinnerung · Juni 2024* — und hat eigene Schaltflächen: die wichtigste (bei einer Datei das Herunterladen) sitzt direkt am Eintrag, die übrigen im
**⋯**-Menü. Jeder Eintrag — Ordner, Erinnerung, Datei oder Briefkasten — hat außerdem ein **Herz**: Es nimmt den Eintrag in
deine **Favoriten** in der Navigation links auf. Deine Favoriten gehören nur dir; weder andere Mitglieder des Raums noch
der Server können sie sehen. Was du nicht darfst, hat schlicht keine Schaltfläche (wer nur einen Link bekommt, hat kein
Herz).

Liegt in einem Ordner mehr als eine Art von Dingen, zeigen dir Tabs über der Liste — **Alle**, **Erinnerungen**,
**Dateien**, **Briefkästen** — nur eine Art. Unterordner bleiben in jedem Tab sichtbar, damit du weiter navigieren kannst.

## Einem Ordner ein Cover geben

Ein Ordner, ein Briefkasten oder eine Erinnerung kann ein **Cover** haben: ein Bild deiner Wahl, das in der Liste, auf der
Seite des Eintrags und in deinen Favoriten sein Symbol ersetzt. Öffne das **⋯**-Menü des Eintrags (oder die Schaltflächen oben
auf seiner Seite) und wähle **Cover festlegen** (hat der Eintrag schon eins, heißt es **Cover ändern**). Es öffnet sich ein Fenster: Such ein Bild von
deinem Gerät aus und zieh oder zoome es in den Ausschnitt, den du möchtest — dasselbe Fenster für Ordner, Briefkasten und
Erinnerung, und der Ausschnitt sieht überall gleich aus. Das Bild wird auf deinem Gerät verkleinert und
mit dem eigenen Schlüssel des Eintrags verschlüsselt, bevor es das Gerät verlässt — wie alles andere auch; der Server speichert
nur verschlüsselte Daten. Über **Cover ändern** richtest du den Ausschnitt neu ein oder tauschst das Bild; **Cover entfernen** im selben Fenster nimmt es weg; das alte Bild wird aus dem Speicher
des Servers gelöscht (Kopien, die andere Browser schon haben, oder ein bereits verteilter Link lassen sich nicht zurückholen —
sie laufen von selbst ab). Es ist ein Bild für den Eintrag, keine Datei im Ordner, und nur wer den Raum ändern darf, kann es
festlegen. Wer nur einen Link bekommt, sieht die Cover, kann sie aber nicht ändern. Eine Datei hat kein Cover.

## Raster und Liste

In einem Ordner wechselst du mit den Schaltflächen oben zwischen **Rasteransicht** und **Listenansicht** (und
**Kartenansicht**, wo Erinnerungen Orte haben). Die Wahl gehört dir und wird gemerkt; derselbe Schalter gilt für alles
im Ordner.

## Adressen zum Merken

Die Adresse eines Ordners im Browser liest sich wie sein Pfad, zum Beispiel `…/#urlaub/2026/` — **die Adresse eines
Ordners endet mit einem Schrägstrich**. Eine Erinnerung liegt unter `/memories/`, zum Beispiel
`…/#urlaub/memories/rom`. Du kannst jede davon als Lesezeichen speichern.

Der lesbare Teil steht **hinter dem `#`**, und diesen Teil schickt ein Browser nie an einen Server. Die Namen in der
Adresse bleiben also auf deinem Gerät.

## Warum das privat bleibt

Eine Datei wird auf deinem eigenen Gerät verschlüsselt, **bevor** sie hochgeladen wird — genau wie ein Foto. Was wir
nicht sehen können: den **Inhalt** der Datei und ihren **Namen**. Was der Server sieht, ist dasselbe wie bei allem
anderen: dass es eine Datei gibt, ungefähr wie groß sie ist, wann sie hochgeladen oder geändert wurde und wer in
deinem Bereich sie öffnen darf. Die Einzelheiten: [Sind meine Daten sicher?](sind-meine-daten-sicher.md).
