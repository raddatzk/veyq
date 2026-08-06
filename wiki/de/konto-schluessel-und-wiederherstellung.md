# Konto-Schlüssel & Wiederherstellung

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). Weil veyq
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
- **Ein Gerät, dem du vertraust** — ab dann reicht dein Passwort. Setzt du stattdessen das Häkchen bei
  **„angemeldet bleiben"**, öffnet das Gerät direkt deine Erinnerungen, ganz ohne Tippen.
- **Ein fremder Rechner** — nutze ein privates/Inkognito-Fenster; es bleibt nichts zurück.

Manche Browser können sich ein Gerät gar nicht merken — private/Inkognito-Fenster und einige stark
abgeschottete Konfigurationen. veyq sagt dir Bescheid, wenn du in so einem bist: Entsperren braucht
dort jedes Mal deinen Secret Key. Das ist eine Grenze des Browsers, kein Fehler an deinem Konto.

Der Secret Key wird nur dieses erste Mal gebraucht. Warum überhaupt?

Dein Passwort verlässt dein Gerät nie — auch nicht zum Anmelden. Dein Gerät rechnet daraus ein
Schlüsselpaar aus und zeigt dem Server nur die öffentliche Hälfte; aus ihr lässt sich dein Passwort nicht
zurückrechnen. Aber wer an die Daten des Servers käme, könnte damit in Ruhe **raten**: ein Passwort
ausprobieren, dieselbe Rechnung machen, schauen ob die öffentliche Hälfte passt. Wir machen jeden Versuch
absichtlich langsam — ein Versprechen ist das trotzdem nicht, denn Passwörter sind oft ratbar.

Genau dafür ist der Secret Key da. Er ist lang und völlig zufällig, Raten ist also aussichtslos, und deine
Erinnerungen brauchen ihn **zusätzlich** zum Passwort. Ein erratenes Passwort allein öffnet nichts.

## Was der Server tatsächlich speichert

Nur die **gesperrten** Kopien deines Master-Schlüssels — nie etwas, das sie öffnet. Das, was eine Kopie
entsperrt, entsteht für einen Moment beim Anmelden auf deinem Gerät und wird dann verworfen. Es wird nie
gespeichert und nie gesendet. Genau das hält den Server blind.

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

Nach dem Zurücksetzen meldet sich jedes Gerät neu an. Du gibst deinen Secret Key dort also noch einmal ein —
auch auf dem, das du gerade in der Hand hast.

**Zwei ehrliche Grenzen.** Sind Passwort **und** Kit weg, bringt nichts deine Erinnerungen zurück: bei uns
liegt kein Hauptschlüssel, und eine Hintertür gibt es nicht. Und wenn auf dem Server, den du nutzt, keine
E-Mail eingerichtet ist, kann der Link nicht verschickt werden — anfordern kannst du ihn trotzdem, und du
bekommst dann klar gesagt, dass es nicht geht, statt auf eine Mail zu warten, die nie kommt. Bitte die Person,
die den Server betreibt, E-Mail einzurichten; mit deinem Konto ist alles in Ordnung, und sobald das erledigt ist,
funktioniert das Zurücksetzen.

**Warum ein vergessenes Passwort deine Erinnerungen nicht verliert:** alles hängt an deinem *Schlüssel*,
nicht am Passwort. Hol den Schlüssel zurück, und alles kommt auf einmal wieder.

**Das Einzige, was wir nicht rückgängig machen können:** verlierst du **jeden** Weg — kein angemeldetes
Gerät und kein Notfall-Kit — sind deine Inhalte weg. Es gibt **keine Hintertür und keine
Schlüsselhinterlegung**: Dasselbe, was *uns* am Lesen hindert, hindert uns am Retten.
