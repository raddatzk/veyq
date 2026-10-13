<!-- speaks-for: docs/features/platform/use-cases/uc.platform.export-import.md; pair: en/export-and-import.md -->
# Export und Import

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). Du kannst einen Ordner, eine Datei
oder eine Erinnerung als **eine ZIP-Datei** aus veyq herausnehmen — und eine solche Datei wieder hineinladen, in dasselbe
veyq oder in ein anderes.

## Was du tun kannst

- **Exportieren.** Öffne das ⋯-Menü eines Ordners, einer Datei oder einer Erinnerung (oder deren Seite) und wähle
  **Exportieren**. veyq sammelt, was darin ist, sagt dir, wie viel es ist, und lässt dich wählen, wo das ZIP gespeichert wird.
  Ein Ordner bringt alles darunter mit; eine Erinnerung ihre Fotos und Videos im Original, mit Datum und Ort, ihre Kapitel
  und ihr Cover.
- **Importieren.** Wähle in einem Ordner **Neu → Export importieren…** oder zieh die Datei auf den Ordner. veyq erkennt einen
  veyq-Export an seinem Inhalt, nicht an seinem Namen, zeigt dir, was angelegt würde und wohin, und tut es dann. Ist ein Name
  schon vergeben, heißt der neue „Name (2)“.

## Gut zu wissen

- **Das ZIP ist nicht verschlüsselt.** Wer die Datei hat, kann alles darin lesen. Bewahre sie an einem Ort auf, dem du
  vertraust. Der Server sieht einen Export nie: Dein Gerät entschlüsselt und packt ihn, und nur dein Gerät packt einen Import
  aus und verschlüsselt ihn.
- **Orte in Fotos.** Die Option **Orte und Koordinaten einschließen** bestimmt, was veyq dem Export hinzufügt. Die
  Standort-Angaben *in* einer Fotodatei bleiben im Foto — veyq schreibt deine Originale nicht um.
- **Nach dem Import gehört alles dir.** Du bist Eigentümer und Hochladender der importierten Dinge. Freigaben, Mitglieder und
  der Papierkorb reisen nicht mit. Die an einer Erinnerung genannten Personen stehen als einfache Namen in der Datei, werden
  aber nicht wieder gesetzt.
- **Große Exporte.** Das ZIP wird direkt auf die Platte geschrieben, die Größe ist also auf einem Computer mit
  Chromium-Browser (Chrome, Edge) kein Problem; andere Browser laden es über einen eingebauten Weg herunter, und wo beides
  nicht geht, sagt veyq es dir vor dem Start.
- **Bricht ein Import ab** (zum Beispiel weil dein Speicher voll ist), liegt das Angelegte im Papierkorb, und du erfährst,
  wie weit er kam.
- Das wurde mit einer Erinnerung aus zwei Fotos in einem echten Browser ausprobiert. Große Exporte und Fotos mit GPS-Angaben
  noch nicht.
