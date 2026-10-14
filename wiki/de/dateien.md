<!-- speaks-for: docs/features/platform/use-cases/uc.platform.node-cover.md, docs/features/files/use-cases/uc.files.member-files.md, docs/features/files/use-cases/uc.files.view-file.md, docs/features/memory/use-cases/uc.memory.move-node.md, docs/features/memory/use-cases/uc.memory.delete-node.md, docs/features/memory/use-cases/uc.memory.browse.md, docs/features/platform/use-cases/uc.platform.copy-node.md; pair: en/files.md -->
# Dateien — und wie du dich zurechtfindest

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). veyq ist eine Plattform
für verschlüsselte, teilbare Dinge und wächst durch **Anwendungen**. **Erinnerungen** ist die Anwendung für Fotos,
Videos und Tagebuch. **Dateien** ist die für alles andere: Dokumente, PDFs, jede Datei, die du aufbewahren willst.
Beide liegen in denselben Ordnern deines Bereichs, Seite an Seite.

## Was du mit einer Datei tun kannst

- **Hochladen** — wähle **Neu → Dateien hochladen** oder lege Dateien auf den Ordner. Das Hochladen läuft im
  Hintergrund; das Status-Menü in der oberen Leiste zeigt, wie weit es ist, mit einer Liste für jeden Ort, an den du
  hochlädst (ein zweites Hochladen in denselben Ordner kommt zu seiner Liste dazu).
- **Einen Ordner hochladen** — wähle **Neu → Ordner hochladen…** oder lege einen Ordner auf den Ordner: Er kommt als
  Ordner und Dateien an, genau wie auf deinem Computer (leere Ordner darin werden ausgelassen). Ein Name, den manche Computer
  nicht erlauben, wird leicht geändert, und ein schon vergebener Name heißt dann „Name (2)“. Ein Ordner, der ein
  ausgepackter veyq-Download ist, wird dir stattdessen zum Importieren angeboten ([Herunterladen und
  Importieren](herunterladen-und-importieren.md)).
- **Herunterladen** — die Herunterladen-Schaltfläche in der Zeile der Datei (oder oben auf ihrer Seite) gibt dir die
  Datei genau so zurück, wie du sie abgelegt hast, unter dem Namen, den sie in veyq hat. Ein Klick auf die Zeile selbst öffnet die Seite der Datei.
- **Ansehen** — klicke auf die Zeile der Datei, und ihre Seite öffnet sich mit der Datei selbst darauf: ein **Bild**
  wird angezeigt, ein **Text** (reiner Text, Markdown, Code, JSON, CSV …) wird als Text angezeigt (markiere ihn nach Belieben oder nutze
  die Schaltfläche **Kopieren**; mit **Zeilen umbrechen** schaltest du den Zeilenumbruch ein und aus), eine **PDF-Datei**
  wird Seite für Seite angezeigt (mit Seitenzahl und Schaltflächen zum Vergrößern, Verkleinern und Anpassen an die
  Breite), und ein **Video** oder eine **Audiodatei** wird abgespielt, mit einer Schaltfläche für den
  **Vollbildmodus**. Die Datei wird auf deinem Gerät entschlüsselt, während du sie ansiehst, es wird nichts Zusätzliches
  gespeichert, und wer die Datei von dir freigegeben bekommen hat, sieht genau dasselbe. Manches zeigt die Seite nicht
  an: ein Format,
  das dein Browser nicht darstellen kann, und sehr große Bilder oder PDFs (über 64 MB) — und von einem langen Text
  erscheint nur das erste Megabyte (**Kopieren** kopiert dann nur den angezeigten Teil und sagt es dazu). Eine PDF, die
  **mit einem Passwort geschützt** ist, fragt direkt auf der Seite danach: Das Passwort wird nur auf deinem Gerät zum
  Öffnen der PDF verwendet und weder gespeichert noch irgendwohin gesendet; brichst du ab, wird stattdessen der Download
  angeboten. In einer PDF kannst du den **Text** der Seiten auf dem Bildschirm mit Maus oder Tastatur **markieren und
  kopieren**; Links darin tun nichts (sie sind nicht anklickbar, eine PDF kann dich also nie woandershin schicken),
  Anmerkungen und Formularfelder werden nicht als bedienbare Elemente gezeigt, und **eingescannte Seiten** (manche
  Scanner speichern die Seite in einem Bildformat, das veyq nicht zeichnet) können Lücken zeigen — und haben keinen
  Text zum Markieren, außer der Scanner hat ihn mitgespeichert. Wenn etwas nicht angezeigt werden kann, ist die
  Schaltfläche zum Herunterladen gleich daneben.
- **Sehen, wer sie hochgeladen hat** — oben auf der Seite einer Datei sehen Mitglieder des Bereichs, wer die aktuelle
  Fassung hochgeladen hat: Name und Bild eines Mitglieds (ein Klick öffnet sein Profil), „Ehemaliges Mitglied“ für
  jemanden, der den Bereich verlassen hat, oder „einem Gast“, wenn sie über einen Link kam. Nach dem Ersetzen steht dort,
  wer ersetzt hat. Wer nur einen Link von dir hat, sieht das nie.
- **Ersetzen** — lege eine neuere Fassung an die Stelle der Datei: Lade eine Datei mit **demselben Namen** in ihren
  Ordner hoch, oder öffne die Datei und nutze die Schaltfläche zum Ersetzen oben auf ihrer Seite. Der Name bleibt. veyq
  **fragt vorher nach**, denn es gibt **keinen Versionsverlauf**: Der alte Inhalt lässt sich nicht zurückholen. Eine
  Datei, deren Hochladen nie fertig wurde, wird einfach gefüllt, ohne Frage.
- **Umbenennen und verschieben** — beides findest du im **⋯**-Menü der Zeile einer Datei. Mit **Verschieben nach …**
  öffnest du den Ordner, in den die Datei soll, und wählst **Hierher verschieben** (mehr dazu unten).
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
Briefkasten. Es öffnet sich dort, wo der Eintrag gerade liegt, mit dem Pfad darüber: Klicke auf einen Ordner, um
hineinzugehen, oder auf einen Teil des Pfads, um zurückzugehen, und wähle an dem Ort, an den er soll, **Hierher
verschieben** — oder **Abbrechen**. Gezeigt werden nur Orte, die den Eintrag aufnehmen können (ein Kapitel zum Beispiel
nur in eine Erinnerung), und **Hierher verschieben** ist ausgegraut, mit dem Grund, wo er nicht hinkann — etwa dort, wo
er schon liegt. Alles darin kommt mit und bleibt lesbar. Hat dir jemand einen Ordner freigegeben, in dem du mitwirken
darfst, kannst du Dinge **darin** verschieben — aber nicht hinaus.

## Dinge kopieren

**Kopieren nach …** steht gleich neben **Verschieben nach …** bei einem Ordner, einer Datei, einer Erinnerung oder einem Briefkasten und
öffnet dieselbe Ordneransicht. Hier ist auch der Ort erlaubt, an dem der Eintrag gerade liegt: Die Kopie heißt dann
„Name (2)“. Bevor etwas kopiert wird, sagt veyq, wie viele Einträge und wie viele Daten es kopiert, was nicht mitkommt —
Freigaben, Favoriten, der Papierkorb und das Titelbild eines Ordners; ein kopierter Briefkasten ist ein gewöhnlicher
Ordner, in den niemand mehr etwas einwerfen kann — und ob in deinem Bereich noch Platz dafür ist. Alles wird auf deinem
Gerät kopiert und unter neuen Schlüsseln neu gespeichert: Ein großer Ordner braucht so lange wie sein Hochladen und
belegt seinen Platz doppelt; der Server sieht nur, dass Neues gespeichert wird, nicht, dass es eine Kopie ist. Hört das
Kopieren mittendrin auf, kommt, was schon angelegt war, in den Papierkorb, und veyq sagt, wie weit es gekommen ist.
Eine kopierte Erinnerung behält ihre Fotos und Videos mit Aufnahmezeit (auch korrigierter), Ort und Kameradaten, ihre
Kapitel, ihr Datum, ihren Ort und ihr Titelbild; wer dabei war und wer welches Foto hinzugefügt hat, kommt nicht mit.
Kopieren können nur Mitglieder des Bereichs; wem du einen Ordner freigegeben hast, kann nicht kopieren.

## Mehreres auf einmal

Wähle **Auswählen** neben **Neu** und dem Papierkorb, und jeder Eintrag des Ordners bekommt ein Kästchen zum Anhaken.
Hake an, was du möchtest — oder wähle **Alle** — und dann **Verschieben nach …**, **Kopieren nach …** oder **Löschen**. Die Leiste zeigt, wie
viele du angehakt hast, und wenn ein Tab einige davon ausblendet, sagt sie auch das: Deine Haken bleiben, wenn du den
Tab wechselst. Lässt sich ein Teil des Angehakten nicht verschieben oder löschen, sagt veyq, wie viele, und erledigt
den Rest. **Löschen** fragt einmal für alle, und ein **Rückgängig** holt alle zurück. **Verschieben nach …** öffnet
dieselbe Ordneransicht wie für einen Eintrag; was dort nicht hinkann — weil dort zum Beispiel schon etwas mit
demselben Namen liegt —, wird genannt und bleibt, wo es ist, und ein **Rückgängig** verschiebt zurück, was verschoben
wurde. **Kopieren nach …** kopiert alles Angehakte in einem Zug und nennt, was es nicht kopieren kann. Hat dir jemand einen Ordner freigegeben, in dem du mitwirken darfst, kannst du dort auch auswählen und
verschieben, aber nicht löschen.

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

Unter jedem Namen steht eine kleine Zeile, was es ist und was wir darüber wissen: Eine Erinnerung zeigt ihr Datum und ihren
Ort (wenn sie welche hat), eine Datei ihre Größe und wann sie zuletzt geändert wurde, ein Briefkasten, wie viele Personen
einen Ordner darin haben. Was nicht bekannt ist, wird einfach nicht gezeigt. Die Cover sind in beiden Ansichten größer, und
derselbe Schalter (und dieselbe Zeile) steht auch auf der Profilseite einer Person.

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
