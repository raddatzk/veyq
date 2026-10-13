<!-- speaks-for: docs/features/memory/use-cases/uc.memory.participants.md; pair: en/people-in-your-memories.md -->
# Personen in deinen Erinnerungen

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md) und [Teilen](teilen.md). In einer
Erinnerung kannst du festhalten, **wer dabei war** – die Menschen einer Reise, einer Hochzeit, eines Geburtstags – und
eine Person öffnen, um alle Erinnerungen zu sehen, an denen sie beteiligt war.

## Was du tun kannst

- **Personen nennen.** Öffne eine Erinnerung und wähle unter dem Titel **Beteiligte hinzufügen**. Du wählst aus den
  Mitgliedern des Bereichs, in dem die Erinnerung liegt. Ihre Namen erscheinen als kleine Chips in der Kopfzeile der
  Erinnerung. Eine Erinnerung kann bis zu 100 Personen nennen.
- **Eine Person öffnen.** Klicke auf einen Namen, und du landest im Profil dieser Person: Name, ein Buchstaben-Avatar und
  darunter die Erinnerungen, an denen sie beteiligt war – nur die, die **du** öffnen darfst. Eine Erinnerung, die du nicht
  sehen darfst, fehlt einfach in der Liste. (Hast du sehr viele Erinnerungen, sagt die Liste, wenn sie unvollständig sein
  kann.)
- **Wer ein Foto hinzugefügt hat.** Öffne ein Foto, und in den Details steht, wer es hinzugefügt hat – als Link zum
  Profil. Das ist unabhängig von der Liste der Beteiligten: Auch wer nur hochgeladen hat und nie genannt wurde, wird
  angezeigt.
- **Personen, die gegangen sind.** Wer den Bereich später verlässt, bleibt in der Erinnerung eingetragen, grau als
  „Ehemaliges Mitglied“ und ohne Link. Niemand entfernt sie automatisch – es ist die Aufzeichnung der Erinnerung, wer
  dabei war. Du kannst sie von Hand entfernen.

Für Namen gilt überall dieselbe Regel: der **echte Name**, wenn die Person ihn angegeben hat, sonst ihr **Benutzername**.

## Wer das sieht

- Nur **Mitglieder** des Bereichs. Wo du eine Erinnerung nicht ändern darfst, gibt es auch keine Schaltfläche zum
  Hinzufügen von Personen.
- Die Seite, die du über einen **Link** (eine Freigabe) öffnest, zeigt **keine Beteiligten** und keine Profil-Links –
  egal, welche Rolle der Link hat. Wer einen Link bekommt, erfährt über die Seite nicht, wer auf einer Reise dabei war.

## Warum das sicher ist

- Die Liste der Beteiligten ist **zusammen mit der Erinnerung versiegelt**, wie ihr Datum und ihr Ort. Der Server hält
  nur den unlesbaren, versiegelten Block – **der Betreiber kann nicht sehen, wer bei welcher Reise dabei war**, und
  auch nicht, wer oft mit wem zusammen ist. Deshalb gibt es auch keine automatischen „Du wurdest hinzugefügt“-Hinweise
  vom Server: Der Server weiß es nicht.
- Gespeichert wird pro Person eine anonyme **ID**, nie ein Name. Dein Gerät schlägt die Namen in der Mitgliederliste
  deines Bereichs nach, wenn es sie anzeigt.
- Eine ehrliche Grenze: Ein Link ist ein Schlüssel zur Erinnerung, und der versiegelte Block, den er liefert, ist derselbe,
  den Mitglieder benutzen. Die Seite zeigt Link-Besuchern keine Beteiligten, aber wer technisch versiert ist und
  untersucht, was der Link ausliefert, könnte herausfinden, **wie viele** Personen eingetragen sind und ihre anonymen
  IDs – **nie ihre Namen**, die ein Link-Besucher nicht nachschlagen kann. Würden Beteiligte je mit Namen gespeichert,
  müsste das neu entschieden werden.
- Noch nicht gebaut: ein Bild für eine Person (ihr Avatar ist vorerst ein Buchstabe) und die Anzeige, wer eine **Datei**
  in Dateien hinzugefügt hat.
