---
title: "Konto-Schlüssel & Wiederherstellung"
sources:
  - docs/crosscutting/security-model.md
  - docs/features/account/enablers/en.account.key-model.md
  - docs/features/account/use-cases/uc.account.remember-this-device.md
  - docs/features/account/use-cases/uc.account.trust-device.md
  - docs/features/account/use-cases/uc.account.sessions.md
  - docs/features/account/use-cases/uc.account.rotate-keys.md
  - docs/features/account/use-cases/uc.account.recover-access.md
  - docs/features/account/use-cases/uc.account.confirm-email-address.md
  - docs/features/account/use-cases/uc.account.change-email.md
---

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](/veyq/de/privacy/is-my-data-safe/). Weil veyq
Ende-zu-Ende-verschlüsselt ist, **schützen deine Schlüssel — nicht der Server — deine Daten.** So
funktioniert das, ganz ohne Krypto-Vorwissen.

**Ein paar Begriffe, die dir begegnen:**
- **Schlüssel** — ein Geheimnis, das Daten sperrt/entsperrt.
- **Master-Schlüssel** — der eine Schlüssel, der alles andere schützt. Alle deine Erinnerungen hängen
  daran.
- **Secret Key** — ein hochzufälliger Code in deinem **Notfall-Kit**, kombiniert mit deinem Passwort.
  Wird nie an den Server gesendet.
- **Notfall-Kit** — das PDF mit deinem Secret Key. Es ist das, was dich wieder hineinbringt, wenn du dein
  Passwort vergisst.

## Ein Master-Schlüssel, mehrere Wege ihn zu öffnen

Es gibt **einen Master-Schlüssel**, und er ist **separat durch jede eingerichtete Methode** gesperrt:

```
Master-Schlüssel ─┬─ geöffnet durch Passwort + Secret Key   (immer eingerichtet)
                  ├─ geöffnet durch den Secret Key allein   (das Wiederherstellungs-Schloss)
                  └─ geöffnet durch dieses Gerät, wenn du ihm vertraust
```

Jede einzelne öffnet **denselben** Master-Schlüssel, der **alle** deine Inhalte entsperrt. Eine Methode
hinzuzufügen oder zu entfernen fügt nur ein Schloss hinzu oder entfernt es — **deine Erinnerungen werden
nie neu verschlüsselt.**

## Anmelden

- **Das erste Mal auf einem Gerät** — dein **Passwort + der Secret Key** aus deinem Notfall-Kit. Den
  Secret Key tippst du hier einmal, danach auf diesem Gerät nicht wieder.
- **Danach auf demselben Gerät** — reicht dein Passwort; das richtet veyq bei dieser ersten Anmeldung von
  selbst ein. Setzt du stattdessen das Häkchen bei **„Auf diesem Gerät angemeldet bleiben"**, öffnet das
  Gerät direkt deine Erinnerungen, ganz ohne Tippen.
- **Ein fremder Rechner** — nutze ein privates/Inkognito-Fenster und melde dich am Ende ab. Das Fenster
  vergisst seinen Schlüssel, sobald du es schließt; auf dem Server bleibt nur eine gesperrte Kopie, die
  niemand mehr öffnen kann.

Einige stark abgeschottete Browser können gar keinen Schlüssel behalten. Dort braucht Entsperren jedes Mal
deinen Secret Key, und unter Einstellungen → Geräte und Sitzungen steht das auch. Private Fenster erkennt
veyq nicht — ein gewöhnliches behält seinen Schlüssel, bis du es schließt. Und wer deinen Server betreibt,
kann diese Bequemlichkeit für alle abschalten; dann brauchst du deinen Secret Key bei jeder Anmeldung. Ein
Fehler an deinem Konto ist nichts davon.

Normalerweise wird der Secret Key also nur dieses erste Mal gebraucht. Warum überhaupt?

Dein Passwort verlässt dein Gerät nie — auch nicht zum Anmelden. Dein Gerät rechnet daraus ein
Schlüsselpaar aus und zeigt dem Server nur die öffentliche Hälfte; aus ihr lässt sich dein Passwort nicht
zurückrechnen. Aber wer an die Daten des Servers käme, könnte damit in Ruhe **raten**: ein Passwort
ausprobieren, dieselbe Rechnung machen, schauen ob die öffentliche Hälfte passt. Wir machen jeden Versuch
absichtlich langsam — ein Versprechen ist das trotzdem nicht, denn Passwörter sind oft ratbar.

Genau dafür ist der Secret Key da. Er ist lang und völlig zufällig, Raten ist also aussichtslos, und deine
Erinnerungen brauchen ihn **zusätzlich** zum Passwort. Ein erratenes Passwort allein öffnet nichts.

## Ein Passwort wählen

Wenn du ein neues Passwort wählst, gleicht veyq es mit Passwörtern aus bekannten Datenlecks ab — von selbst,
sobald du das Feld verlässt, nie während du tippst. Nur die ersten 5 Zeichen eines daraus berechneten
Hashwerts gehen dabei direkt von deinem Gerät an den kostenlosen Dienst *Have I Been Pwned*; dein Browser teilt
diesem Dienst außerdem mit, welchen veyq-Server du nutzt. Taucht das Passwort in einem Datenleck auf, kannst du es
trotzdem verwenden — kreuze **„Trotzdem verwenden“** an —, und wenn die Prüfung nicht laufen kann, wird nichts
blockiert. Die Einzelheiten: [Sind meine Daten sicher?](/veyq/de/privacy/is-my-data-safe/#die-eine-prüfung-die-dein-gerät-von-selbst-verlässt)

## Wenn ein Gerät weg ist

Du siehst jede Stelle, an der dein Konto gerade angemeldet ist, und kannst jede davon von dort aus abmelden —
praktisch, wenn ein Laptop verschwindet oder du dich auf einem fremden Rechner angemeldet und das Abmelden
vergessen hast. **„Überall sonst abmelden"** beendet alle anderen Sitzungen auf einmal und lässt dich dort
angemeldet, wo du gerade bist. Deinen Secret Key brauchst du dafür nicht.

Das gilt auch auf einem **geliehenen oder nagelneuen Gerät** — und genau so eines hast du meistens in der Hand,
wenn ein Laptop verschwunden ist. Wähle auf dessen Anmeldeseite **„Ich habe meinen Secret Key nicht dabei"**:
Du meldest dich an, deine Erinnerungen bleiben gesperrt, und angeboten werden zwei Dinge: **„Überall abmelden,
auch hier"** — oder, falls das Kit doch noch auftaucht, hier entsperren, ohne auf diesem Gerät etwas zu
speichern. Dieses Gerät behält danach nichts: keinen gespeicherten Schlüssel, und angemeldet bleibst du nur,
bis der Browser geschlossen wird. Die Liste deiner Geräte braucht entsperrte Erinnerungen; von dort aus meldest
du also alle Geräte ab, nicht ein bestimmtes.

**Abmelden ist nicht die ganze Geschichte, und diesen Teil liest du am besten zweimal.** Ein Gerät abzumelden
hindert es daran, den Server zu erreichen; es hindert es *nicht* daran, deine Erinnerungen zu öffnen, denn ein
Gerät, an dem du angemeldet warst, hält ein eigenes Schloss auf deinem Master-Schlüssel (das Bild oben auf
dieser Seite). Ist ein Gerät wirklich weg, nimm stattdessen **„Abmelden + Schlüssel entfernen"** in seiner
Zeile (fehlt der Knopf dort, entferne seinen Schlüssel unter **„Geräte mit gespeichertem Schlüssel"**). Wer das
Gerät dann hat, braucht dein Passwort *und* deinen Secret Key — heb dir das also für wirklich verlorene Geräte
auf, denn beim nächsten Mal fragt dich dein eigenes Gerät nach dem Notfall-Kit.

Zwei ehrliche Hinweise. Die Gerätenamen erfährt der Server nie: Sie liegen verschlüsselt, und lesen kann sie nur
deine entsperrte App. Und nichts davon greift auf das verlorene Gerät selbst durch — entzogen wird ihm, was der
Server für es aufbewahrt hat.

## Wenn jemand in dein Konto gekommen ist

Manchmal reicht Abmelden nicht — weil es nie die ganze Geschichte war. Angenommen, du hast einen
Anmelde-Code bestätigt, der gar nicht dein neues Handy war. Dieses Gerät hat die Schlüssel zu deinen
Erinnerungen, und kein Abmelden nimmt sie zurück.

**Deine Schlüssel zu ersetzen beendet das.** Du findest es unter Einstellungen → Persönlich → Sicherheit.
Deine Räume bekommen neue Schlösser, das alte Gerät ist ab diesem Moment von allem ausgesperrt, und alle
anderen Geräte müssen sich neu anmelden.

Lies diese vier Punkte, bevor du anfängst. Sie sind das ganze Bild, und keiner davon ist Kleingedrucktes.

**Es stoppt, was danach kommt — es holt nichts zurück.** Was diese Person schon gesehen hat, hat sie
weiterhin. Es gibt keine Variante davon, die in ihr Gerät greift und Gelesenes löscht. Was es tut, ist einen
Strich ziehen: ab hier nichts Neues mehr.

**Jedes Gerät verliert seinen gespeicherten Schlüssel, auch dieses.** Alle anderen Geräte werden abgemeldet,
und jedes braucht danach dein Passwort **und** dein Notfall-Kit, um wieder hineinzukommen — genau den
gespeicherten Schlüssel nimmt das hier weg. Das Gerät, an dem du es machst, bleibt angemeldet, fragt aber bei
der nächsten Anmeldung dort ebenfalls nach beidem. Mach das also dort, wo du an dein Kit kommst, nicht
zwischen Tür und Angel.

**Du brauchst dein Notfall-Kit, und genau das ist der Punkt.** Es ist das Einzige, was die andere Person
nicht hat. Ohne diese Bedingung könnte *sie* die Schlüssel zuerst ersetzen und *dich* dauerhaft aussperren —
womit diese Funktion schlechter wäre als gar keine. Die Prüfung ist also keine Hürde, sie ist der ganze
Schutz.

**In geteilten Räumen muss dich jemand anderes wieder hereinlassen.** Deine neuen Schlüssel sehen für alle,
die dich kennen, aus wie eine fremde Person, die behauptet, du zu sein — und genau das soll die App ja
merken. Jemand anderes im jeweiligen Raum bestätigt, dass du wirklich du bist, am besten indem ihr die
Sicherheitsnummer am Telefon vergleicht. Erst dann bist du wieder drin. Bis dahin bist du draußen, und daran
ändert nichts, was du allein tust. (Nachfragen kann die App nur bei einem Schlüssel, den sie schon kennt: Ein
Mitglied, dessen App deinen alten Schlüssel nie hatte, lässt dich ohne Nachfrage wieder herein.)

Eine Sache lohnt sich, *bevor* du das je brauchst: **mach in einem Raum, den nur du besitzt, jemanden zur
zweiten Besitzerin oder zum zweiten Besitzer.** Sonst ist dort niemand, der dich wieder hereinlassen kann.

Dein Notfall-Kit funktioniert danach weiter — dein Secret Key ändert sich nicht, nur das, was er öffnet.

## Die Adresse ändern, mit der du dich anmeldest

Unter *Einstellungen → Sicherheit*, neben der Adresse, an die ein Link zum Zurücksetzen ginge, wählst du
**„Anmeldeadresse ändern"**. Du gibst die neue Adresse, dein Passwort und deinen Secret Key ein, und wir schicken
einen Link an die **neue** Adresse. Öffne ihn auf einem Gerät, auf dem du in diesem Konto angemeldet bist — erst
dann wird geändert. Bis dahin meldest du dich weiter mit der alten Adresse an, und öffnest du den Link nie, ändert
sich nichts.

Deine Erinnerungen, deine Schlüssel und der Secret Key in deinem Notfall-Kit bleiben genau, wie sie sind. Was sich
ändert: Du meldest dich mit der neuen Adresse an, deine anderen Geräte werden abgemeldet, und deine **alte** Adresse
bekommt einen kurzen Hinweis darauf — ohne die neue Adresse. Einladungen, die an deine alte Adresse gingen, kannst du
mit diesem Konto nicht mehr annehmen.

Warum Passwort **und** Secret Key? An deine Adresse geht ein Link zum Zurücksetzen des Passworts. Wer nur ein
angemeldetes Gerät hat — oder nur dein Passwort —, darf das nicht in ein eigenes Postfach umleiten können.

Erstelle danach ein **neues Notfall-Kit**: Das alte funktioniert weiter, zeigt aber deine alte Adresse. Die Seite,
die die Änderung abschließt, bietet es dir gleich an, sobald du Passwort und Secret Key eingibst — sie prüft beides
zuerst, damit nie ein vertippter Schlüssel in einem Kit landet.

Kann die neue Adresse nicht verwendet werden — etwa weil sich schon ein anderes Konto damit anmeldet —, erfährst du
das erst, wenn du den Link öffnest. Das ist Absicht: Sonst könnte jede Person mit diesem Formular ausprobieren, zu
welchen Adressen es hier ein Konto gibt.

## Was der Server tatsächlich speichert

Nur die **gesperrten** Kopien deines Master-Schlüssels — nie etwas, das sie öffnet. Was eine Kopie mit deinem
Passwort entsperrt, entsteht für einen Moment beim Anmelden auf deinem Gerät und wird dann verworfen.
Aufbewahrt werden nur die Schlüssel, die ein Gerät für seine eigene Kopie hält, und die bleiben auf diesem
Gerät. Gesendet wird keiner davon. Genau das hält den Server blind.

## Wenn du dein Passwort vergisst

Dein Notfall-Kit ist das, was dich zurückbringt. Einen separaten „Wiederherstellungscode" gibt es nicht —
**der Secret Key in deinem Kit ist der Wiederherstellungsschlüssel.** Das ist Absicht: ein zweiter Code, der
genau dasselbe öffnet wie der erste, wäre nur eine weitere Sache, die man verlieren kann.

Zum Zurücksetzen müssen **zwei Dinge zusammenkommen**, und das mit Absicht:

1. **Ein Link, den wir dir mailen** — er beweist, dass du das Postfach lesen kannst, unter dem dein Konto
   registriert ist.
2. **Der Secret Key aus deinem Kit** — er beweist, dass das Konto dir gehört.

Keines von beiden reicht allein. Wer in dein Postfach einbricht, bekommt eine gesperrte Kopie deines
Schlüssels und keine Möglichkeit, sie zu öffnen. Wer dein Kit findet, bekommt keinen Link. Sind beide da,
wählst du ein neues Passwort, und alles Gespeicherte ist weiterhin da — deine Erinnerungen werden nie neu
verschlüsselt, und **dein Kit funktioniert weiter**, weil dein Secret Key sich nicht ändert.

**Sorg dafür, dass dich der Link erreicht.** Unter *Einstellungen → Sicherheit* siehst du, an welche
E-Mail-Adresse ein Link zum Zurücksetzen ginge und ob sie bestätigt ist. Bist du über eine E-Mail-Einladung
beigetreten, ist sie das schon. Hast du deinen Bereich über einen Link eingerichtet, den dir jemand gegeben hat,
schick dir einmal eine Bestätigung und öffne sie, während du angemeldet bist — dann weißt du, dass der Link
ankommt, bevor du ihn brauchst. Gesperrt wird nichts, wenn du es nicht tust.

Nach dem Zurücksetzen meldet sich jedes Gerät neu an. Du gibst deinen Secret Key dort also noch einmal ein —
auch auf dem, das du gerade in der Hand hast.

**Zwei ehrliche Grenzen.** Sind Passwort **und** Kit weg, bringt nichts deine Erinnerungen zurück: bei uns
liegt kein Hauptschlüssel, und eine Hintertür gibt es nicht. Und wenn für deinen Bereich keine E-Mail
eingerichtet ist, kann der Link nicht verschickt werden — anfordern kannst du ihn trotzdem, und du bekommst
dann klar gesagt, dass es nicht geht, statt auf eine Mail zu warten, die nie kommt. E-Mail wird pro Bereich
eingerichtet, von einer Person, der der Bereich gehört, in dessen Einstellungen — nicht von der Person, die
den Server betreibt. Sprich also jemanden an, dem dein Bereich gehört; mit deinem Konto ist alles in Ordnung,
und sobald das erledigt ist, funktioniert das Zurücksetzen.

**Warum ein vergessenes Passwort deine Erinnerungen nicht verliert:** alles hängt an deinem *Schlüssel*,
nicht am Passwort. Hol den Schlüssel zurück, und alles kommt auf einmal wieder.

**Das Einzige, was wir nicht rückgängig machen können:** verlierst du **jeden** Weg — kein angemeldetes
Gerät und kein Notfall-Kit — sind deine Inhalte weg. Es gibt **keine Hintertür und keine
Schlüsselhinterlegung**: Dasselbe, was *uns* am Lesen hindert, hindert uns am Retten.
