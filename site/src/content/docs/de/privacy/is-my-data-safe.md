---
title: "Sind meine Daten sicher?"
sources:
  - docs/crosscutting/security-model.md
  - docs/features/memory/use-cases/uc.memory.gallery.md
  - docs/features/spaces/use-cases/uc.spaces.verify-identity.md
  - docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md
  - docs/features/spaces/use-cases/uc.spaces.require-verified-identities.md
  - docs/features/account/use-cases/uc.account.recover-access.md
  - docs/features/sharing/use-cases/uc.sharing.share-contributor.md
  - docs/features/sharing/use-cases/uc.sharing.share-uploader.md
  - docs/features/admin/use-cases/uc.admin.privacy-notice.md
  - docs/features/expenses/use-cases/uc.expenses.exchange-rates.md
---

**In einem Satz:** veyq ist **Ende-zu-Ende-verschlüsselt** — deine Fotos, Videos, Tagebuchtexte und
die sensiblen Details drumherum werden auf deinem eigenen Gerät verschlüsselt, *bevor* sie den Server
überhaupt erreichen, und **keine Person, die veyq betreibt, besitzt einen Schlüssel, um sie zu öffnen.** Wir
betreiben das Gebäude; den einzigen Schlüssel hast du.

## Stell dir ein Schließfach vor

Stell dir ein Lagergebäude vor, das wir betreiben. Du mietest ein Fach und hängst dein **eigenes**
Vorhängeschloss dran — und wir haben **keine Kopie davon**. Wir können das Gebäude am Laufen halten,
dich zum richtigen Fach schicken und sehen, *dass* es existiert und wem du einen Schlüssel gegeben
hast. **Was wir nicht können: es öffnen und hineinschauen.** Das ist die ganze Idee; alles Weitere ist
nur die ehrliche Erklärung, wo genau diese Grenze verläuft.

## Was wir wirklich nicht sehen können

All das ist mit **deinen** Schlüsseln gesperrt und für uns — und für alle, die unsere Datenbank
stehlen — nur unverständlicher Datenmüll:

- deine **Fotos und Videos** (und die kleineren Vorschaubilder),
- deine **Tagebuchtexte**,
- deine **Dateien** — Dokumente, PDFs, alles, was du in [Depot](/veyq/de/depot/) ablegst — und ihre **Namen**,
- die **sensiblen Details**: Titel, Bildunterschriften, Beschreibungen, Orte, die abgebildeten
  Personen, Schlagwörter,
- **wo** ein Foto aufgenommen wurde (GPS) und **wann** es entstand,
- der **Dateiname** selbst und alles, was die Kamera ins Bild geschrieben hat: Kamera und Objektiv, wie
  belichtet wurde, wer es gemacht hat, und der Ortsname, der neben den Koordinaten steht.

## Was wir trotzdem sehen — und warum

Wir tun nicht so, als wäre es Zauberei. Um den Dienst zu betreiben — dir deine Inhalte Seite für Seite
zu liefern, zu regeln, wer reindarf — sieht der Server ein paar **wenig sensible, betriebliche** Fakten:

| Wir sehen | Wir sehen nicht |
|---|---|
| dass eine Erinnerung / ein Foto / eine Freigabe **existiert**, und ihre ID | was **darin** ist |
| **wie groß** eine Datei ist (Foto, Dokument, alles) | die Datei selbst — nicht einmal, ob es ein Foto oder ein Video ist; allerdings kann die Art, wie ein Video beim Anschauen nachgeladen wird, das verraten |
| **wer in welcher** Gruppe oder welchem Bereich ist | was sie geschrieben oder hochgeladen haben |
| **wann** etwas hochgeladen oder zuletzt geändert wurde | wann ein Foto **aufgenommen** wurde — nach diesem Datum sortiert dein Gerät die Zeitleiste, und es bleibt verschlossen |

Es ist, als sähe man **beschriftete Kisten im Regal und wer einen Schlüssel hat** — nie den Inhalt.
Genau diese wenigen Fakten machen den Server nutzbar, ohne jemals zu entschlüsseln.

**Was *deine* Instanz speichert, und wer sie betreibt.** Jede veyq-Instanz betreibt jemand — ein Familienmitglied, ein
Verein, ein Unternehmen —, und jede hat ihre eigene **Datenschutzerklärung**: **Datenschutz**, unten auf jeder Seite,
auch schon vor der Anmeldung. Sie nennt, wer verantwortlich ist, was diese Instanz zu deinem Konto, deiner Anmeldung
und einer Meldung von dir speichert, wie lange, wer sonst etwas erhält (wer sie hostet, ein Mail-Dienst) und was du
verlangen kannst. Den Text schreibt veyq; die Instanz füllt ihre eigenen Angaben ein, also sagt er, was *diese* Instanz
tut. Die Einladungsseite, das Meldeformular und ein Link, über den du etwas hinzufügst, führen dich zu dem Teil, der
dich betrifft.

## Zwei Anfragen, die dein Gerät von selbst verlassen

Zwei Dinge macht veyq, ohne dich vorher zu fragen, weil das, was sie senden, nichts über deine Inhalte verrät. Die
Datenschutzerklärung deiner Instanz nennt beide, und wer deinen Server betreibt, kann sie abschalten.

### Ein neues Passwort prüfen

Wenn du ein Passwort wählst — beim Einrichten eines Servers, beim Beitreten zu einem Bereich, beim Ändern deines
Passworts oder nach einem Zurücksetzen —, gleicht veyq es mit Passwörtern aus bekannten Datenlecks ab. Das
passiert **von selbst**, sobald du das Passwortfeld verlässt, und noch einmal beim Bestätigen, wenn du das
Passwort seitdem geändert hast. Solange du noch tippst, wird nie geprüft.

**Was dein Gerät verlässt:** die ersten **5 Zeichen eines daraus berechneten Hashwerts** — einer Art
Fingerabdruck des Passworts, nie das Passwort selbst — **direkt von deinem Gerät** an den kostenlosen Dienst *Have I Been
Pwned*, nicht über unseren Server. Die Antwort enthält Hunderte Fingerabdrücke, die genauso anfangen, und dein Gerät
sucht den des Passworts selbst darin.

**Was dieser Dienst trotzdem erfährt:** Dein Browser teilt ihm mit, von welcher Seite die Frage kommt. *Have I
Been Pwned* (und das Netzwerkunternehmen davor) erfährt also, dass jemand unter deiner Internetadresse diesen
veyq-Server nutzt und gerade ein Passwort wählt — nicht, wer du bist, und nicht das Passwort.

Taucht das Passwort in einem Datenleck auf, siehst du eine Warnung und entscheidest: ein anderes wählen oder
**„Trotzdem verwenden“** ankreuzen. Kann die Prüfung nicht laufen — du bist offline, oder der Server kommt nicht
ins Internet —, wird nichts blockiert; du erfährst nur, dass das Passwort nicht geprüft werden konnte.

### Die Wechselkurse des Tages

Trägst du eine Ausgabe in einer anderen Währung als der ihrer Kasse ein, lädt dein Gerät **die Wechselkurse des
Tages** — die Euro-Referenzkurse der Europäischen Zentralbank — und trägt den Kurs ein. Das passiert **direkt von
deinem Gerät**, höchstens einmal am Tag, und die Kurse bleiben auf dem Gerät.

**Was dein Gerät verlässt:** das **Datum**, sonst nichts — nicht die Währung, nicht der Betrag, nicht, wer du bist.
Den Kurs für deine Währungen rechnet dein Gerät selbst aus.

**Was dieser Dienst erfährt:** deine Internetadresse, von welchem veyq-Server die Anfrage kommt, und damit, dass dort
heute jemand eine Ausgabe in einer anderen Währung einträgt.

Auch die Person, der dein Bereich gehört, kann das abschalten. Dann, oder wenn die Kurse nicht geladen werden können,
gibst du den Kurs selbst ein; ein gespeicherter Kurs ändert sich nie.

## Wenn du teilst

Teilen ist der eine Moment, in dem du dich **entscheidest**, die Mauer abzusenken — immer durch deine
ausdrückliche Handlung:

- Teilst du mit einer **Gruppe oder Person**, kann sie diesen Inhalt lesen. Du hast ihn **ihr**
  gegeben — nicht uns; wir können ihn weiterhin nicht lesen.
- Einen **öffentlichen Link** — auf einen Ordner, eine einzelne Datei oder eine Erinnerung — kann **jede Person öffnen, die den Link hat**. Der Schlüssel, der ihn
  entsperrt, steckt **im Link selbst** und erreicht unseren Server nie — eine öffentliche Freigabe ist
  also „lesbar für alle mit dem Link, weiterhin nicht für uns". Die Adresse ohne diesen Schlüssel kann
  nichts lesen — und nichts hinzufügen: Bei einem Link, über den Gäste Fotos beisteuern dürfen, muss ihre
  App erst beweisen, dass sie den Schlüssel hat. Wer nur die Adresse kennt (etwa aus einer Kopie unserer
  Datenbank), kann darüber nichts hochladen.

Wie die verschiedenen Links funktionieren — nur ansehen, ansehen und mitwirken, Briefkasten — steht unter
[Teilen](/veyq/de/sharing/); wie du sie abschaltest und erneuerst, unter
[Links ändern, abschalten, erneuern und löschen](/veyq/de/sharing/manage-links/).

Drei ehrliche Grenzen:
- **Entteilen macht das Heruntergeladene nicht rückgängig.** Ein Entzug stoppt den *künftigen* Zugriff;
  er kann nicht zurückgreifen und löschen, was jemand bereits gespeichert hat.
- **Links, die entstanden sind, bevor jemand gegangen ist, funktionieren weiter, bis du sie erneuerst
  oder abschaltest.** Alle in einer Gruppe oder einem geteilten Bereich können jeden seiner öffentlichen
  Links kopieren — wer geht, hat also vielleicht noch einen. Erneuern gibt dem Link eine neue Adresse und
  löscht die alte; gib dann die neue weiter. Schaltest du einen Link ab oder erneuerst du ihn, oder ist
  sein Ablaufdatum vorbei, bekommt, was darunter liegt, ein neues Schloss, damit der alte Link auch
  künftig nichts mehr öffnet. Entfernst du jemanden, kannst du im selben Schritt — oder jederzeit — alle
  Links des Bereichs abschalten. Einen abgeschalteten Link kannst du endgültig löschen; die Fotos, die
  Gäste darüber hinzugefügt haben, bleiben in der Erinnerung. Wird eine Erinnerung endgültig gelöscht,
  verschwinden auch ihre Links.
- **Geteilt ist geteilt.** Hast du anderen einen Schlüssel gegeben, ist die Vertraulichkeit *vor
  ihnen* per Design vorbei — nur die Vertraulichkeit *vor uns* bleibt.

## Illegale Inhalte & Missbrauch

Weil private Inhalte für uns unsichtbar sind, **können** wir deine privaten Erinnerungen nicht scannen
— das ist der Sinn der Sache. Was jemand **meldet**, ist etwas anderes: Wer etwas sieht — ein Mitglied des
Bereichs oder ein Gast eines Links —, kann **Melden** wählen, und sein Gerät übergibt der Moderation des Servers die
Schlüssel zu genau dem Gemeldeten, so versiegelt, dass nur diese Moderation sie öffnen kann. Sonst öffnet sich damit
nichts vom Bereich, außer der Moderation erfährt niemand von der Meldung, und gesperrt wird dadurch nichts: Ein Mensch
entscheidet. Was gemeldet wurde, bleibt für die Moderation bis zu 90 Tage erhalten, auch wenn es inzwischen gelöscht
wird. [Etwas melden](/veyq/de/help/report-something/)

Reagieren können dann die Leute, die deinen Server betreiben, und die Moderation, die sie bestimmen. Eine zentrale
Moderationsstelle gibt es nicht, weil es keinen zentralen Dienst gibt. Die Moderation kann sperren, was gemeldet
wurde — einen Link, ein Element oder einen Gast, der über einen Link hochgeladen hat —, und alle sehen an seiner Stelle
dann **Von der Moderation gesperrt**, ohne Begründung; eine Sperre löscht nichts und lässt sich aufheben
([Etwas melden](/veyq/de/help/report-something/#von-der-moderation-gesperrt)). Die Anmeldung eines Mitglieds zu
sperren oder alles unter einer Adresse offline zu nehmen, ist Sache der Person, die den Server betreibt. Private
Inhalte bleiben trotzdem unlesbar — genau das ist der Entwurf.

## Gebaut, um zu bleiben

Erinnerungen sind für Jahrzehnte, deshalb sind die Schlösser so gewählt, dass sie **selbst künftigen
Quantencomputern standhalten** — eine reale Sorge, denn jemand könnte verschlüsselte Daten heute
kopieren und hoffen, sie später zu knacken. Der Entwurf lässt Raum, später stärkere Schlösser für Neues
einzuführen, während alles, was du schon hast, lesbar bleibt, wie es ist. Das heißt aber auch: Deine
vorhandenen Erinnerungen **wechseln nicht von selbst auf neue Schlösser** — heute gibt es nur einen Satz
Schlösser, nötig war das also noch nie.

Mehr: [Was passiert mit meinen Fotos, wenn die Quantencomputer kommen?](/veyq/de/privacy/quantum-computers-and-your-photos/)

## Ist es wirklich *ihr* Schlüssel — und eine ehrliche Lücke

Wenn du mit einer anderen Person teilst, braucht deine App ihren Schlüssel, den sie von unserem Server holt.
Wer den Server unehrlich betreibt, könnte deiner App theoretisch den *falschen* Schlüssel unterschieben.

**Deine App merkt sich den Schlüssel, den sie zuerst gesehen hat.** Sichert die Person
[ihr Konto ab](/veyq/de/account/replace-your-keys/), ist ihr neuer Schlüssel mit ihrem vorherigen unterschrieben, und
deine App folgt dieser Unterschrift von dem Schlüssel aus, den sie sich gemerkt hat: Sie teilt weiter mit ihr, und in
ihrem Profil steht **„Neue Identität, mit ihrem vorherigen Schlüssel unterschrieben"**. Liefert der Server einen
Schlüssel, der *nicht* aus dem gemerkten folgt, wird die Person mit **„Schlüssel nicht bestätigt"** markiert, und
deine App gibt diesem Schlüssel nichts. Eine Schaltfläche zum Annehmen gibt es nicht: Ein echter Wechsel ist immer
unterschrieben, ein Schlüssel ohne Unterschrift kann also heißen, dass sich jemand als die Person ausgibt. Ihr
vergleicht dann eure **Sicherheitsnummern** — jeder Schlüssel hat eine kurze — persönlich. In der Zeit gibt ihr ein
anderes Mitglied des Bereichs, dessen App den Schlüssel bestätigen kann, den Schlüssel des Bereichs, und niemand
wartet damit beim Posten.

**In einem Bereich bürgt die Einladung für jedes Mitglied.** Jede Einladung unterschreibt eine Person, die den Bereich
besitzt, und wer sie annimmt, hinterlässt einen über diese Einladung unterschriebenen Nachweis des Schlüssels, mit dem
die Person beigetreten ist. Deine App prüft den Schlüssel jedes Mitglieds gegen diesen Nachweis. Schiebt der Server
einen anderen Schlüssel unter — selbst einen, den deine App noch nie gesehen hat —, steht dort **„Schlüssel nicht
bestätigt"**, und ebenso bei einem Mitglied, das der Server ganz ohne Einladung hinzugefügt hat.

**Selbst prüfen.** Wer einen Bereich besitzt, hat keine Einladung hinter sich, und wer als besitzend gilt, sagt der
Server; außerhalb eines gemeinsamen Bereichs hat deine App nur den Schlüssel, den sie zuerst gesehen hat. Deshalb
kannst du jede Person selbst prüfen. Dein eigenes Profil zeigt *deine* Sicherheitsnummer, einen kürzeren Kettencode
und einen Code zum Scannen; öffne das Profil der anderen Person, scanne den Code auf ihrem Bildschirm oder lest euch
die Ziffern vor, und stimmt beides überein, wird die Person als **„Verifiziert"** markiert — im Profil, wo steht, wer
sie verifiziert hat, und in jeder Mitgliederliste, für alle in diesem Bereich (Schritt für Schritt:
[Eine Person verifizieren](/veyq/de/account/verify-a-person/)). Sichert eine verifizierte Person ihr Konto ab, gilt
das für den neuen Schlüssel nicht weiter; vergleicht erneut, um es wiederzubekommen.

**Der ehrliche Teil:** Ob ihr wirklich vergleicht, liegt bei dir. Nichts verlangt, dass du jemanden verifizierst, bevor du mit dieser
Person teilst; eine Einstellung, die das verlangt, gibt es noch nicht. Unser Server kann eine Verifizierung weder lesen
noch fälschen, sieht aber, wer wen verifiziert hat. Das stärkere System, bei dem der Server über Schlüssel gar nicht
lügen kann, ist **geplant, aber nicht gebaut** — und das sagen wir lieber, als es vorzutäuschen.

## Wenn etwas schiefgeht

- **Passwort vergessen?** Du verlierst deine Erinnerungen nicht — sie hängen an deinem *Schlüssel*,
  nicht am Passwort. Dein Notfall-Kit ist das, was dich wieder hineinbringt; ein Gerät, auf dem du noch
  angemeldet bist, funktioniert derweil weiter. Zum Zurücksetzen müssen zwei Dinge zusammenkommen: ein
  Link, den wir dir mailen, und der Secret Key aus deinem Kit. Keines von beiden reicht allein — mehr
  dazu in [Passwort vergessen](/veyq/de/account/forgot-password/).
- **Alles verloren** — kein angemeldetes Gerät und kein Notfall-Kit? Dann ist es **weg.**
  Es gibt **keine Hintertür und keine Schlüsselhinterlegung**: Dasselbe, was *uns* am Lesen deiner
  Daten hindert, hindert uns auch daran, sie zu retten. Das sagen wir von vornherein. Weitere Situationen und was
  zu tun ist: [Hilfe](/veyq/de/help/).

## Die Seite selbst kommt vom Server — eine ehrliche Grenze

All das erledigt die veyq-Seite in deinem Browser, und diese Seite, auch die, die einen geteilten Link öffnet, kommt
bei jedem Laden vom Server. Deine Sachen bleiben also verschlossen vor einer Person, die den Server ehrlich betreibt,
und vor allen, die nur an seine gespeicherten Daten kommen. Wer den Server betreibt und es darauf anlegt mitzulesen,
könnte diese Seite aber verändern. Auf dem Server einer anderen Person schützt dich veyq im Browser also vor ehrlichem
Betrieb, nicht vor böswilligem, und das sagen wir lieber, als mehr zu versprechen:
[Bekannte Grenzen](/veyq/security/known-limitations/#the-operator-served-web-client-is-passive-operator-only) (auf
Englisch)

> **Hinweis fürs Self-Hosting:** Betreibst *du* deinen eigenen veyq-Server, liegt der Betrieb bei *dir* —
> dann stellt sich die Frage, ob man der betreibenden Person vertrauen kann, für deine eigene Instanz gar nicht.

## Für technisch Interessierte

Der Bereich Sicherheit erklärt das alles ausführlich, auf Englisch: [Start here](/veyq/security/start-here/),
[What the server sees](/veyq/security/what-the-server-sees/), [Threat model](/veyq/security/threat-model/) und
[Known limitations](/veyq/security/known-limitations/).
