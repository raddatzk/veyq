<!-- speaks-for: docs/features/platform/use-cases/uc.platform.export-import.md; pair: en/download-and-import.md -->
# Herunterladen und Importieren

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). Du kannst einen Ordner, eine Datei
oder eine Erinnerung aus veyq herunterladen — eine Datei als sie selbst, alles Größere als **eine ZIP-Datei** — und ein
solches ZIP wieder hineinladen, in dasselbe veyq oder in ein anderes.

## Was du tun kannst

- **Herunterladen.** Öffne das ⋯-Menü eines Ordners oder einer Erinnerung (oder deren Seite) und wähle **Herunterladen**.
  veyq sammelt, was darin ist, sagt dir, wie viel es ist, und lässt dich wählen, wo das ZIP gespeichert wird. **Herunterladen**
  bei einer Datei speichert die Datei selbst.
- **Pack es aus wie jedes ZIP.** Darin findest du deine Ordner als Ordner und deine Dateien und Fotos unter ihren eigenen
  Namen, im Original. Namen, die manche Computer nicht erlauben (etwa mit `:` oder `?`), werden leicht geändert, damit das ZIP
  überall aufgeht; zwei Namen, die sich nur in Groß- und Kleinschreibung unterscheiden, heißen „Name“ und „Name (2)“. Neben
  deinen Dateien liegt ein kleiner versteckter Ordner, `.veyq`, mit einer Liste dessen, was im ZIP ist — Daten, Orte, Kapitel,
  Cover. Diese Liste lässt veyq das ZIP wieder hineinladen.
- **Importieren.** Lade das ZIP in einen Ordner hoch (**Neu → Dateien hochladen** oder zieh es auf den Ordner): veyq erkennt
  einen veyq-Download an seinem Inhalt, nicht an seinem Namen, und fragt dich, ob du ihn **importieren** oder **als Datei
  hochladen** willst. Du kannst auch den **ausgepackten** Ordner — den mit `.veyq` darin — auf den Ordner ziehen und ihn
  importieren. Bevor etwas angelegt wird, siehst du, was angelegt würde und wohin. Ist ein Name schon vergeben, heißt der neue
  „Name (2)“.
- **Aus einem neueren veyq oder mit Dingen, die dieses veyq nicht kennt.** Enthält das ZIP etwas, das dieses veyq nicht wieder
  hineinladen kann — eine Anwendung, die es nicht hat, oder etwas aus einer neueren Version —, zeigt veyq es vor dem Start an.
  Du kannst **den Rest importieren** (das Aufgeführte wird weggelassen und nach dem Import genannt) oder **abbrechen**, was
  nichts verändert.
- **Personen, mit denen du teilst,** können auch herunterladen: Wer einen geteilten Ordner oder eine geteilte Erinnerung
  ansehen darf, kann sie als ZIP herunterladen. Über einen Freigabe-Link importiert niemand.

## Gut zu wissen

- **Das ZIP ist nicht verschlüsselt** — genau wie ein Foto, das du herunterlädst. Wer die Datei hat, kann alles darin lesen;
  bewahre sie also an einem Ort auf, dem du vertraust. Der Server sieht einen Download nie: Dein Gerät entschlüsselt und packt
  ihn, und nur dein Gerät packt einen Import aus und verschlüsselt ihn.
- **Orte in Fotos.** Ein Foto behält im ZIP seine eigenen Standort-Angaben; veyq schreibt deine Originale nicht um.
- **Ein beschädigtes oder verändertes ZIP wird abgelehnt**, mit dem Grund, bevor etwas angelegt wird.
- **Nach dem Import gehört alles dir.** Du bist Eigentümer und Hochladender der importierten Dinge. Freigaben, Mitglieder und
  der Papierkorb reisen nicht mit. Die an einer Erinnerung genannten Personen stehen als einfache Namen in der Liste, werden
  aber nicht wieder gesetzt.
- **Große Downloads.** Das ZIP wird direkt auf die Platte geschrieben, die Größe ist also auf einem Computer mit
  Chromium-Browser (Chrome, Edge) kein Problem; andere Browser laden es über einen eingebauten Weg herunter, und wo beides
  nicht geht, sagt veyq es dir vor dem Start.
- **Ist genug Platz da?** Vor dem Import fragt veyq den Server, ob der Import noch in den Speicher deines Bereichs passt. Wenn nicht, sagt es dir das und startet nicht. Die Antwort ist nur Ja oder Nein — niemand erfährt, wie viel Platz noch frei ist.
- **Bricht ein Import ab** (zum Beispiel weil dein Speicher voll ist), liegt das Angelegte im Papierkorb, und du erfährst,
  wie weit er kam.
- **Noch nicht:** einen gewöhnlichen Ordner (einen ohne `.veyq`) hochladen — veyq sagt das dann. Einen Ordner in einer
  Dateiauswahl zu wählen, geht auch noch nicht; zieh ihn stattdessen hinein.
