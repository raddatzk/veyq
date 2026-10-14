<!-- speaks-for: docs/features/platform/use-cases/uc.platform.export-import.md; pair: en/download-and-import.md -->
# Herunterladen und Importieren

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). Du kannst einen Ordner, eine Datei,
eine Erinnerung oder einen Briefkasten aus veyq herunterladen — eine Datei als sie selbst, alles Größere als **eine
ZIP-Datei** — und ein solches ZIP wieder hineinladen, in dasselbe veyq oder in ein anderes.

## Was du tun kannst

- **Herunterladen.** Öffne das ⋯-Menü eines Ordners, einer Erinnerung oder eines Briefkastens (oder deren Seite) und wähle
  **Herunterladen**. veyq sammelt, was darin ist, sagt dir, wie viel es ist, und lässt dich wählen, wo das ZIP gespeichert
  wird. **Herunterladen** bei einer Datei speichert die Datei selbst. Ein Briefkasten kommt mit einem Ordner für jede Person,
  die etwas eingeworfen hat, so benannt, wie der Briefkasten sie zeigt (zwei Personen namens Anna: „Anna“ und „Anna (2)“).
- **Pack es aus wie jedes ZIP.** Darin findest du deine Ordner als Ordner und deine Dateien und Fotos unter ihren eigenen
  Namen, im Original. Namen, die manche Computer nicht erlauben (etwa mit `:` oder `?`), werden leicht geändert, damit das ZIP
  überall aufgeht; zwei Namen, die sich nur in Groß- und Kleinschreibung unterscheiden, heißen „Name“ und „Name (2)“. Neben
  deinen Dateien liegt ein kleiner versteckter Ordner, `.veyq`, mit einer Liste dessen, was im ZIP ist — Daten, Orte, Kapitel,
  Cover. Diese Liste lässt veyq das ZIP wieder hineinladen.
- **Importieren.** Lade das ZIP in einen Ordner hoch (**Neu → Dateien hochladen** oder zieh es auf den Ordner): veyq erkennt
  einen veyq-Download an seinem Inhalt, nicht an seinem Namen, und fragt dich, ob du ihn **importieren** oder **als Datei
  hochladen** willst. Du kannst auch den **ausgepackten** Ordner — den mit `.veyq` darin — mit **Neu → Ordner hochladen…**
  oder per Hineinziehen hineinladen und ihn importieren. Bevor etwas angelegt wird, siehst du, was angelegt würde und wohin.
  Ist ein Name schon vergeben, heißt der neue „Name (2)“. Ein Briefkasten kommt als gewöhnlicher Ordner zurück, mit einem
  Ordner für jede Person darin: Seine Links und wer etwas eingeworfen hat, kommen nicht mit zurück.
- **Jeden anderen Ordner**, den du mit **Neu → Ordner hochladen…** wählst (oder hineinziehst), lädt veyq einfach hoch, als
  Ordner und Dateien.
- **Aus einem neueren veyq oder mit Dingen, die dieses veyq nicht kennt.** Enthält das ZIP etwas, das dieses veyq nicht wieder
  hineinladen kann — eine Anwendung, die es nicht hat, oder etwas aus einer neueren Version —, zeigt veyq es vor dem Start an.
  Du kannst **den Rest importieren** (das Aufgeführte wird weggelassen und nach dem Import genannt) oder **abbrechen**, was
  nichts verändert.
- **Personen, mit denen du teilst,** können auch herunterladen: Wer einen geteilten Ordner oder eine geteilte Erinnerung
  ansehen darf, kann sie — und einen Briefkasten darin — als ZIP herunterladen. Über einen Freigabe-Link importiert niemand:
  Wer zu einem geteilten Ordner etwas hinzufügen darf, kann einen Ordner hochladen, und er kommt als Ordner und Dateien an,
  auch ein ausgepackter Download. Wer nur Dateien in einen Briefkasten einwirft, lädt nichts herunter.

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
- **Ist genug Platz da?** Vor dem Import und vor dem Hochladen eines Ordners fragt veyq den Server, ob das noch in den Speicher deines Bereichs passt. Wenn nicht, sagt es dir das und startet nicht. Die Antwort ist nur Ja oder Nein — niemand erfährt, wie viel Platz noch frei ist.
- **Bricht ein Import ab** (zum Beispiel weil dein Speicher voll ist), liegt das Angelegte im Papierkorb, und du erfährst,
  wie weit er kam. Ein hochgeladener Ordner ist dagegen ein gewöhnliches Hochladen: Eine Datei, die scheitert, wird als
  gescheitert gezeigt, der Rest kommt an.
