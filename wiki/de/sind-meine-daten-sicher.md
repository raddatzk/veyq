<!-- speaks-for: docs/crosscutting/security-model.md, docs/features/memory/use-cases/uc.memory.gallery.md, docs/features/spaces/use-cases/uc.spaces.verify-identity.md, docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md, docs/features/account/use-cases/uc.account.recover-access.md; pair: en/is-my-data-safe.md -->
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

Wir tun nicht so, als wäre es Zauberei. Um den Dienst zu betreiben — deine Zeitleiste zu sortieren,
eine Seite anzuzeigen, zu regeln, wer reindarf — sieht der Server ein paar **wenig sensible,
betriebliche** Fakten:

| Wir sehen | Wir sehen nicht |
|---|---|
| dass eine Erinnerung / ein Foto / eine Freigabe **existiert**, und ihre ID | was **darin** ist |
| **wie groß** eine Datei ist und ihren groben Typ (z. B. „ein Video") | die Datei selbst |
| **wer in welcher** Gruppe oder welchem Bereich ist | was sie geschrieben oder hochgeladen haben |
| grobe **Zeitstempel** zum Sortieren deiner Zeitleiste | die Inhalte dahinter |

Es ist, als sähe man **beschriftete Kisten im Regal und wer einen Schlüssel hat** — nie den Inhalt.
Genau diese wenigen Fakten machen den Server nutzbar, ohne jemals zu entschlüsseln.

## Wenn du teilst

Teilen ist der eine Moment, in dem du dich **entscheidest**, die Mauer abzusenken — immer durch deine
ausdrückliche Handlung:

- Teilst du mit einer **Gruppe oder Person**, kann sie diesen Inhalt lesen. Du hast ihn **ihr**
  gegeben — nicht uns; wir können ihn weiterhin nicht lesen.
- Einen **öffentlichen Link** kann **jede Person öffnen, die den Link hat**. Der Schlüssel, der ihn
  entsperrt, steckt **im Link selbst** und erreicht unseren Server nie — eine öffentliche Freigabe ist
  also „lesbar für Link-Inhaber, weiterhin nicht für uns".

Zwei ehrliche Grenzen:
- **Entteilen macht das Heruntergeladene nicht rückgängig.** Ein Entzug stoppt den *künftigen* Zugriff;
  er kann nicht zurückgreifen und löschen, was jemand bereits gespeichert hat.
- **Geteilt ist geteilt.** Hast du anderen einen Schlüssel gegeben, ist die Vertraulichkeit *vor
  ihnen* per Design vorbei — nur die Vertraulichkeit *vor uns* bleibt.

## Illegale Inhalte & Missbrauch

Weil private Inhalte für uns unsichtbar sind, **können** wir deine privaten Erinnerungen nicht scannen
— das ist der Sinn der Sache. Dort, wo Inhalte tatsächlich **verbreitet** werden — bei einer
**öffentlichen Freigabe** — enthält eine Missbrauchsmeldung den Link und damit den Schlüssel, sie kann
also geöffnet und beurteilt werden. Melden und Entfernen funktioniert genau da, wo es soll: beim
Öffentlichen.

Wer darauf reagiert, ist die Person, die deine Instanz betreibt. Eine zentrale Moderationsstelle gibt
es nicht, weil es keinen zentralen Dienst gibt — und eingebaute Moderationswerkzeuge bringt veyq
heute nicht mit. Was der Betrieb sehr wohl in der Hand hat, ist die **Erreichbarkeit**: eine Freigabe
lässt sich offline nehmen. Private Inhalte bleiben trotzdem unlesbar — genau das ist der Entwurf.

## Gebaut, um zu bleiben

Erinnerungen sind für Jahrzehnte, deshalb sind die Schlösser so gewählt, dass sie **selbst künftigen
Quantencomputern standhalten** — eine reale Sorge, denn jemand könnte verschlüsselte Daten heute
kopieren und hoffen, sie später zu knacken. Dieser Schutz ist ein eingebautes Ziel, und das System ist
so entworfen, dass die Schlösser **mit der Zeit erneuert werden können, ohne alles neu zu verschließen,
was du schon hast.**

Mehr: [Was passiert mit meinen Fotos, wenn die Quantencomputer kommen?](quantencomputer-und-deine-fotos.md)

## Ist es wirklich *ihr* Schlüssel — und eine ehrliche Lücke

Wenn du mit einer anderen Person teilst, braucht deine App ihren Schlüssel, den sie von unserem Server holt.
Wer den Server unehrlich betreibt, könnte deiner App theoretisch den *falschen* Schlüssel unterschieben. Um das
zu erkennen, hat jede Person einen **Fingerabdruck** (eine kurze „Sicherheitsnummer"). Du und die
andere Person könnt sie **über einen zweiten Kanal** vergleichen — vorlesen oder einen QR-Code scannen — und
stimmt sie überein, gehört der Schlüssel wirklich dieser Person.

**Der ehrliche Teil:** diese Prüfung ist **manuell** — sie schützt dich nur, wenn du tatsächlich
vergleichst. veyq merkt sich den Schlüssel eines Kontakts **noch nicht** automatisch und warnt dich
nicht, wenn er sich später ändert; das stärkere System („der Server kann über Schlüssel gar nicht
lügen") ist **noch geplant**. Das Werkzeug zum Prüfen gibt es; das automatische Sicherheitsnetz noch
nicht — und das sagen wir lieber, als es vorzutäuschen.

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
