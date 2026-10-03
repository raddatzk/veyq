<!-- speaks-for: docs/crosscutting/security-model.md, docs/features/memory/use-cases/uc.memory.gallery.md, docs/features/spaces/use-cases/uc.spaces.verify-identity.md, docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md, docs/features/account/use-cases/uc.account.recover-access.md, docs/features/sharing/use-cases/uc.sharing.share-contributor.md, docs/features/sharing/use-cases/uc.sharing.share-uploader.md; pair: en/is-my-data-safe.md -->
# Sind meine Daten sicher?

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
| **wie groß** eine Datei ist | die Datei selbst — nicht einmal, ob es ein Foto oder ein Video ist; allerdings kann die Art, wie ein Video beim Anschauen nachgeladen wird, das verraten |
| **wer in welcher** Gruppe oder welchem Bereich ist | was sie geschrieben oder hochgeladen haben |
| **wann** etwas hochgeladen oder zuletzt geändert wurde | wann ein Foto **aufgenommen** wurde — nach diesem Datum sortiert dein Gerät die Zeitleiste, und es bleibt verschlossen |

Es ist, als sähe man **beschriftete Kisten im Regal und wer einen Schlüssel hat** — nie den Inhalt.
Genau diese wenigen Fakten machen den Server nutzbar, ohne jemals zu entschlüsseln.

## Wenn du teilst

Teilen ist der eine Moment, in dem du dich **entscheidest**, die Mauer abzusenken — immer durch deine
ausdrückliche Handlung:

- Teilst du mit einer **Gruppe oder Person**, kann sie diesen Inhalt lesen. Du hast ihn **ihr**
  gegeben — nicht uns; wir können ihn weiterhin nicht lesen.
- Einen **öffentlichen Link** kann **jede Person öffnen, die den Link hat**. Der Schlüssel, der ihn
  entsperrt, steckt **im Link selbst** und erreicht unseren Server nie — eine öffentliche Freigabe ist
  also „lesbar für alle mit dem Link, weiterhin nicht für uns". Die Adresse ohne diesen Schlüssel kann
  nichts lesen — und nichts hinzufügen: Bei einem Link, über den Gäste Fotos beisteuern dürfen, muss ihre
  App erst beweisen, dass sie den Schlüssel hat. Wer nur die Adresse kennt (etwa aus einer Kopie unserer
  Datenbank), kann darüber nichts hochladen.

Drei ehrliche Grenzen:
- **Entteilen macht das Heruntergeladene nicht rückgängig.** Ein Entzug stoppt den *künftigen* Zugriff;
  er kann nicht zurückgreifen und löschen, was jemand bereits gespeichert hat.
- **Links, die entstanden sind, bevor jemand gegangen ist, funktionieren weiter, bis du sie erneuerst.**
  Alle in einer Gruppe oder einem geteilten Bereich können jeden seiner öffentlichen Links kopieren — wer
  geht, hat also vielleicht noch einen. Erneuern gibt dem Link eine neue Adresse und löscht die alte;
  gib dann die neue weiter. Entfernst du jemanden, kannst du im selben Schritt — oder jederzeit — alle
  Links des Bereichs abschalten. Einen abgeschalteten Link kannst du endgültig löschen; die Fotos, die
  Gäste darüber hinzugefügt haben, bleiben in der Erinnerung. Wird eine Erinnerung endgültig gelöscht,
  verschwinden auch ihre Links.
- **Geteilt ist geteilt.** Hast du anderen einen Schlüssel gegeben, ist die Vertraulichkeit *vor
  ihnen* per Design vorbei — nur die Vertraulichkeit *vor uns* bleibt.

## Illegale Inhalte & Missbrauch

Weil private Inhalte für uns unsichtbar sind, **können** wir deine privaten Erinnerungen nicht scannen
— das ist der Sinn der Sache. Bei einer **öffentlichen Freigabe** ist das anders: Wer sie meldet, gibt
den Link weiter und damit auch den Schlüssel — sie lässt sich also öffnen und beurteilen. Einen
Melde-Knopf gibt es in veyq nicht; melden heißt, der Person zu schreiben, die deine Instanz betreibt.

Reagieren kann dann nur diese Person. Eine zentrale Moderationsstelle gibt es nicht, weil es keinen
zentralen Dienst gibt — und Moderationswerkzeuge bringt veyq heute nicht mit. **Eine einzelne Freigabe
abschalten kann der Betrieb nicht**; das kann nur, wer sie erstellt hat. Was der Betrieb kann: einen
**ganzen Bereich** auf seinem Server offline nehmen — für dessen Mitglieder und mit jeder Freigabe darin.
Private Inhalte bleiben trotzdem unlesbar — genau das ist der Entwurf.

## Gebaut, um zu bleiben

Erinnerungen sind für Jahrzehnte, deshalb sind die Schlösser so gewählt, dass sie **selbst künftigen
Quantencomputern standhalten** — eine reale Sorge, denn jemand könnte verschlüsselte Daten heute
kopieren und hoffen, sie später zu knacken. Der Entwurf lässt Raum, später stärkere Schlösser für Neues
einzuführen, während alles, was du schon hast, lesbar bleibt, wie es ist. Das heißt aber auch: Deine
vorhandenen Erinnerungen **wechseln nicht von selbst auf neue Schlösser** — heute gibt es nur einen Satz
Schlösser, nötig war das also noch nie.

Mehr: [Was passiert mit meinen Fotos, wenn die Quantencomputer kommen?](quantencomputer-und-deine-fotos.md)

## Ist es wirklich *ihr* Schlüssel — und eine ehrliche Lücke

Wenn du mit einer anderen Person teilst, braucht deine App ihren Schlüssel, den sie von unserem Server holt.
Wer den Server unehrlich betreibt, könnte deiner App theoretisch den *falschen* Schlüssel unterschieben.

**Deine App merkt sich den Schlüssel, den sie zuerst gesehen hat.** Liefert der Server später einen anderen,
wird die Person mit **„Schlüssel geändert"** markiert, und deine App gibt ihr keine Schlüssel mehr, bis du
dir das angesehen hast. **„Schlüssel prüfen"** zeigt die Nummer, der du vertraut hast, neben der jetzt
gezeigten — jeder Schlüssel hat eine kurze **Sicherheitsnummer** —, damit ihr beide sie am Telefon
vergleichen könnt. Danach nimmst du den neuen Schlüssel an oder lehnst ihn ab. Oft ist so eine Änderung
harmlos (die Person hat ihre Schlüssel ersetzt), sie kann aber auch heißen, dass sich jemand als sie ausgibt.

**Der ehrliche Teil:** Ob ihr wirklich vergleicht, liegt bei dir. Annehmen ist ein einziger Klick, und die
App kann nicht erkennen, ob ihr verglichen habt. Eine Nummer *vorab* prüfen, bevor sich etwas geändert hat,
geht noch nicht — kein QR-Code zum Scannen, keine Markierung „verifiziert". Und das Merken schützt erst ab
dem ersten Schlüssel, den deine App gesehen hat: Hat der Server dir von Anfang an einen falschen geliefert,
sieht nichts verdächtig aus. Das stärkere System, bei dem der Server über Schlüssel gar nicht lügen kann,
ist **geplant, aber nicht gebaut** — und das sagen wir lieber, als es vorzutäuschen.

## Wenn etwas schiefgeht

- **Passwort vergessen?** Du verlierst deine Erinnerungen nicht — sie hängen an deinem *Schlüssel*,
  nicht am Passwort. Dein Notfall-Kit ist das, was dich wieder hineinbringt; ein Gerät, auf dem du noch
  angemeldet bist, funktioniert derweil weiter. Zum Zurücksetzen müssen zwei Dinge zusammenkommen: ein
  Link, den wir dir mailen, und der Secret Key aus deinem Kit. Keines von beiden reicht allein — mehr
  dazu in [Konto-Schlüssel & Wiederherstellung](konto-schluessel-und-wiederherstellung.md).
- **Alles verloren** — kein angemeldetes Gerät und kein Notfall-Kit? Dann ist es **weg.**
  Es gibt **keine Hintertür und keine Schlüsselhinterlegung**: Dasselbe, was *uns* am Lesen deiner
  Daten hindert, hindert uns auch daran, sie zu retten. Das sagen wir von vornherein.

> **Hinweis fürs Self-Hosting:** Betreibst *du* deinen eigenen veyq-Server, liegt der Betrieb bei *dir* —
> dann stellt sich die Frage, ob man der betreibenden Person vertrauen kann, für deine eigene Instanz gar nicht.
