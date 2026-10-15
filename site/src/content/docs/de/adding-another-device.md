---
title: "Ein weiteres Gerät hinzufügen"
sources:
  - docs/features/account/use-cases/uc.account.onboard-device.md
---

Eine verständliche Ergänzung zu [Konto-Schlüssel & Wiederherstellung](/veyq/de/account-keys-and-recovery/).
Du kannst ein neues Handy oder einen neuen Laptop hereinlassen, ohne dein Notfall-Kit herauszusuchen: **ein
Gerät, an dem du schon angemeldet bist, gibt dem anderen alles Nötige.**

## Zwei Wege — nimm den, der zum Gerät in deiner Hand passt

Beide tun genau dasselbe. Der Unterschied ist nur, welches Gerät einen Code zeigt und welches ihn scannt —
so scannt immer das Gerät, das eine Kamera hat.

**Am Gerät, an dem du schon angemeldet bist** — öffne in den Einstellungen **„Gerät hinzufügen"**.

1. Tippe auf **„Code anzeigen"**; es zeigt einen Code.
2. Dein neues Gerät scannt ihn — auf seiner Anmeldeseite, mit dem Knopf **„QR-Code scannen"** neben dem Feld
   für den Secret Key.
3. Danach zeigen beide Bildschirme eine kurze Zahl.
4. Prüfe, dass sie übereinstimmt, gib dem neuen Gerät einen Namen und schiebe zur Bestätigung.

**Am neuen Gerät, an dem du nur die App offen hast** — wähle dort auf der Anmeldeseite **„Über QR-Code
anmelden"**. Dieses Gerät braucht keine Kamera, nur das andere.

1. Das neue Gerät zeigt einen Code.
2. Du scannst ihn mit dem Gerät, an dem du schon angemeldet bist (**„Gerät hinzufügen"** → **„Code scannen"**).
3. *Dieses* Gerät nennt dir dann eine kurze Zahl.
4. Du tippst die Zahl am neuen Gerät ein und bestätigst am angemeldeten.

## Die Zahl, die du eintippst, ist der entscheidende Schritt

Beim zweiten Weg wirkt das Eintippen wie eine Hürde zu viel. Es ist genau der Teil, den du nicht überspringen
solltest — und das ist der Grund.

Nichts an dem Code, den dein neues Gerät zeigt, sagt, wessen Gerät dahintersteckt.
Dein Handy kann einen Code, den der Laptop vor dir anzeigt, nicht von einem unterscheiden, den dir jemand in
einer Nachricht geschickt hat. Was es *erkennen* kann: ob die Gegenseite eine Zahl kennt, **die dein Handy
selbst gewürfelt und nur auf seinem eigenen Bildschirm gezeigt hat.** Wer dich zu einem fremden Code
verleitet hat, sieht diesen Bildschirm nie und kann die richtige Zahl deshalb nie zurückschicken.

Deshalb wirst du beim ersten Weg nur zum *Vergleichen* aufgefordert und nicht zum Tippen: dort hast du beide
Bildschirme vor dir, und dieselbe Zahl zu sehen ist Beweis genug.

Und der Server, der die Nachrichten zwischen deinen beiden Geräten weiterreicht, kann auf keinem der beiden
Wege ein eigenes Gerät einschleusen: Der Code trägt ein Geheimnis, das den Server nie erreicht, und beide
Geräte prüfen, dass alles, was bei ihnen ankommt, damit gemacht wurde.

**Tippe nie eine Zahl ein, die dir jemand am Telefon oder in einer Nachricht durchgibt.** Seriös fragt
danach niemand — kein Support, wir nicht, und auch nicht jemand, der dir „beim Einrichten hilft". Wenn
jemand danach fragt, *ist* das der Angriff, und das Eintippen ist der Moment, in dem er gelingt.

## Stimmt die Zahl nicht, ist dieser Versuch vorbei

Du hast genau einen Versuch. Prüfe die Zahl, bevor du bestätigst — bis dahin kannst du sie beliebig oft
korrigieren, aber einmal abgeschickt ist sie die Antwort.

War sie falsch, sagt dir das angemeldete Gerät genau das, es wird nichts weitergegeben, und der Code ist
verbraucht. Fang am neuen Gerät einfach neu an; es zeigt dann einen frischen Code. Dem neuen Gerät selbst
wird nichts mitgeteilt — es wartet nur, und irgendwann läuft der Code ab. Wenn dort also nichts passiert:
nicht weiter warten, sondern neu anfangen.

Das ist Absicht und keine fehlende Funktion. Ein „Nochmal versuchen" auf demselben Code würde dich einladen,
über die einzige Warnung hinwegzugehen, die du bekommst.

## Zwei ehrliche Hinweise

**Ein so hinzugefügtes Gerät bekommt alles, dauerhaft.** Es kann jede deiner Erinnerungen lesen. Späteres
Abmelden hindert es daran, den Server zu erreichen, nimmt ihm den Schlüssel aber **nicht** zurück — was ein
Abmelden bewirkt und was nicht, steht in [Sind meine Daten sicher?](/veyq/de/is-my-data-safe/). Füge also
nur Geräte hinzu, die du in der Hand hältst.

Solltest du doch einmal einen Code bestätigen, der nicht dein eigenes Gerät war, gibt es genau eine Sache,
die das beendet: [deine Schlüssel ersetzen](/veyq/de/account-keys-and-recovery/#wenn-jemand-in-dein-konto-gekommen-ist).
Damit ist dieses Gerät ab diesem Moment ausgesperrt — zurückholen kann es nichts, was schon gelesen wurde,
und du brauchst dafür dein Notfall-Kit.

**Ein so hinzugefügtes Gerät hat kein Passwort gespeichert.** Du hast dort nie eines eingetippt, und es
lernt auch keines. Sollte es seinen Schlüssel je verlieren — du löschst die Browserdaten, oder ein anderes
Gerät entfernt seinen gespeicherten Schlüssel — fragt es dich nach deinem Passwort **und** deinem Notfall-Kit,
und beides wurde dort nie eingegeben (oder du fügst es von einem angemeldeten Gerät aus erneut hinzu). Bewahre
das Notfall-Kit also erreichbar auf. Und bleibst du darauf nicht angemeldet, endet sein Zugang mit dem
Schließen des Browsers, und du fügst es beim nächsten Mal erneut hinzu.

## Verwandt

- [Sind meine Daten sicher?](/veyq/de/is-my-data-safe/)
- [Konto-Schlüssel & Wiederherstellung](/veyq/de/account-keys-and-recovery/)
- [Wer in deinen Bereich kommt](/veyq/de/who-gets-into-your-space/)
