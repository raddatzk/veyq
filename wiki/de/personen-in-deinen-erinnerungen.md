<!-- speaks-for: docs/features/memory/use-cases/uc.memory.participants.md, docs/features/spaces/use-cases/uc.spaces.set-profile-picture.md; pair: en/people-in-your-memories.md -->
# Personen in deinen Erinnerungen

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md) und [Teilen](teilen.md). In einer
Erinnerung kannst du festhalten, **wer dabei war** – die Menschen einer Reise, einer Hochzeit, eines Geburtstags – und
eine Person öffnen, um alle Erinnerungen zu sehen, an denen sie beteiligt war.

## Was du tun kannst

- **Personen nennen.** Öffne eine Erinnerung und wähle unter dem Titel **Beteiligte hinzufügen**. Du wählst aus den
  Mitgliedern des Bereichs, in dem die Erinnerung liegt. Sie erscheinen als Reihe kleiner Avatare in der Kopfzeile der
  Erinnerung (bis zu drei; bei mehr zeigt der letzte „+2“, „+3“ und so weiter) – fahre darüber oder tippe darauf, um alle mit
  Namen zu sehen. In der Auswahl kannst du die Mitglieder nach Namen suchen. Eine Erinnerung kann bis zu 100 Personen nennen.
- **Eine Person öffnen.** Klicke auf einen Namen, und du landest im Profil dieser Person: Name, ihr Bild (oder ein Buchstabe) und
  darunter die Erinnerungen, an denen sie beteiligt war – nur die, die **du** öffnen darfst. Eine Erinnerung, die du nicht
  sehen darfst, fehlt einfach in der Liste. (Hast du sehr viele Erinnerungen, sagt die Liste, wenn sie unvollständig sein
  kann.)
- **Wer ein Foto hinzugefügt hat.** Öffne ein Foto, und in den Details steht, wer es hinzugefügt hat – als Link zum
  Profil. Das ist unabhängig von der Liste der Beteiligten: Auch wer nur hochgeladen hat und nie genannt wurde, wird
  angezeigt.
- **Personen, die gegangen sind.** Wer den Bereich später verlässt, bleibt in der Erinnerung eingetragen, grau als
  „Ehemaliges Mitglied“ und ohne Link. Niemand entfernt sie automatisch – es ist die Aufzeichnung der Erinnerung, wer
  dabei war. Du kannst sie von Hand entfernen.

- **Gib dir selbst ein Bild.** Unter **Einstellungen → Dein Profil** oder auf **deiner eigenen** Profilseite
  (**Profil bearbeiten**) kannst du ein Bild wählen und den Ausschnitt festlegen. Es erscheint dann statt deines
  Buchstabens überall, wo du im Bereich vorkommst: in der Kopfzeile einer Erinnerung, in der Liste der Personen, in der
  Mitgliederliste und in deinem Profil. Du kannst es jederzeit entfernen, dann kehrt der Buchstabe zurück. In den Profilen
  anderer gibt es nichts zu bearbeiten – ihr Bild ändert nur die Person selbst. Bist du einem Bereich als Mitglied
  beigetreten, bietet ein kleiner Hinweis nach dem ersten Entsperren an, Name und Bild zu ergänzen; „Jetzt nicht“ blendet
  ihn dauerhaft aus. (Gehört dir der Bereich, kannst du schon beim Annehmen der Einladung ein Bild wählen.)

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
- **Auch dein Bild ist versiegelt.** Es wird auf deinem Gerät mit dem Schlüssel des Bereichs verschlossen, bevor es dein
  Gerät verlässt; der Server speichert nur einen verwürfelten Block von **immer gleicher Größe** – er kann also ein Foto
  nicht von einer Zeichnung unterscheiden und sieht weder das Bild noch seine Größe noch was darauf zu sehen ist. Öffnen
  können es nur **Mitglieder deines Bereichs**; ein Link-Besucher bekommt es nie und sieht den Buchstaben. Der Betreiber
  erfährt nur, dass du ein Bild hast und wie oft du es geändert hast. Ein Bild gehört zu einem Bereich – ein anderer
  Bereich, in dem du bist, zeigt sein eigenes (oder den Buchstaben). Wirst du aus einem Bereich entfernt, wird dein Bild
  dort gelöscht.
- Noch nicht gebaut: die Anzeige, wer eine **Datei** in Dateien hinzugefügt hat. Diese Seite wurde noch nicht in einem
  echten Browser geprüft.
