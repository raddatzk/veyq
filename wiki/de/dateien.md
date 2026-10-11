<!-- speaks-for: docs/features/files/use-cases/uc.files.member-files.md, docs/features/memory/use-cases/uc.memory.move-node.md, docs/features/memory/use-cases/uc.memory.delete-node.md, docs/features/memory/use-cases/uc.memory.browse.md; pair: en/files.md -->
# Dateien — und wie du dich zurechtfindest

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). veyq ist eine Plattform
für verschlüsselte, teilbare Dinge und wächst durch **Anwendungen**. **Erinnerungen** ist die Anwendung für Fotos,
Videos und Tagebuch. **Dateien** ist die für alles andere: Dokumente, PDFs, jede Datei, die du aufbewahren willst.
Beide liegen in denselben Ordnern deines Bereichs, Seite an Seite.

## Was du mit einer Datei tun kannst

- **Hochladen** — wähle Dateien aus oder lege sie auf einen Ordner. Das Hochladen läuft im Hintergrund; das
  Status-Menü in der oberen Leiste zeigt, wie weit es ist.
- **Herunterladen** — du bekommst die Datei genau so zurück, wie du sie abgelegt hast, unter dem Namen, den sie in
  veyq hat.
- **Ersetzen** — lege eine neuere Fassung an die Stelle der Datei. Der Name bleibt. veyq **fragt vorher nach**, denn
  es gibt **keinen Versionsverlauf**: Der alte Inhalt lässt sich nicht zurückholen. Dieselbe Frage kommt, wenn du eine
  Datei hochlädst, deren Name in dem Ordner schon vergeben ist.
- **Umbenennen und verschieben** — benenne eine Datei in ihrer Zeile um. **Verschieben nach …** öffnet eine Liste von
  Orten; wähle einen, und die Datei geht dorthin (mehr dazu unten).
- **Löschen, mit Rückgängig** — eine gelöschte Datei kommt in den **Papierkorb**, und eine Meldung bietet dir sofort
  **Rückgängig** an. Später kannst du sie noch aus dem Papierkorb wiederherstellen, bis sie endgültig entfernt wird;
  der Papierkorb zeigt das Datum. Was im Papierkorb liegt, zählt weiter gegen deinen Speicherplatz, bis er geleert ist.
- **Teilen** — einen ganzen Ordner oder eine einzelne Datei. Siehe [Teilen](teilen.md).

Hat jemand anderes in deinem Bereich dieselbe Datei kurz vor dir ersetzt, sagt dir veyq das, statt seine Fassung
stillschweigend zu überschreiben, und du kannst **Trotzdem ersetzen** wählen.

Ein Name muss im Ordner frei sein: Eine Datei kann nicht denselben Namen tragen wie ein Ordner, eine Erinnerung oder
ein Briefkasten, die schon dort liegen. veyq überspringt so eine Datei und sagt dir, welcher Eintrag den Namen hat.

**Große Dateien** werden Stück für Stück heruntergeladen und auf deinem Gerät wieder zusammengesetzt. Kann dein
Browser direkt in eine Datei speichern, die du auswählst, funktionieren auch sehr große Dateien. Kann er das nicht,
wird eine Datei von mehr als etwa einem halben Gigabyte mit einer Meldung abgelehnt, statt mittendrin zu scheitern.

## Dinge verschieben

**Verschieben nach …** funktioniert gleich bei einem Ordner, einer Erinnerung, einem Kapitel, einer Datei oder einem
Briefkasten: Wähle den neuen Ort aus einer Liste, und alles darin kommt mit und bleibt lesbar. Ein Verschieben lässt
sich sofort rückgängig machen. Hat dir jemand einen Ordner freigegeben, in dem du mitwirken darfst, kannst du Dinge
**darin** verschieben — aber nicht hinaus.

## Raster und Liste

In einem Ordner wechselst du mit den Schaltflächen oben zwischen **Rasteransicht** und **Listenansicht**. Die Wahl
gehört dir und wird gemerkt; derselbe Schalter gilt für alles im Ordner.

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
