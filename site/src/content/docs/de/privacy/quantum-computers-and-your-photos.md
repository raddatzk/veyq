---
title: "Was passiert mit meinen Fotos, wenn die Quantencomputer kommen?"
sources:
  - docs/crosscutting/security-model.md
---

Kurz gesagt:
**deine Erinnerungen werden schon heute mit einem Schutz verschlossen, der auch künftigen
Quantencomputern standhält** — und der Entwurf lässt Raum für stärkere Schlösser später.

## Die Sorge, ehrlich erklärt

Eines Tages könnten leistungsfähige **Quantencomputer** Teile der Verschlüsselung knacken, die heute im
ganzen Internet verwendet wird. Es gibt eine hinterhältige Variante dieser Bedrohung — **„harvest now,
decrypt later"** (heute abgreifen, später entschlüsseln): Geduldige Angreifende kopieren sich *heute*
deine **verschlüsselten** Fotos, legen sie weg und hoffen, sie in etwa fünfzehn Jahren zu knacken. Das
Problem: ein privates Foto von heute ist auch in fünfzehn Jahren **immer noch privat** — der Wert läuft
nicht ab.

## Was veyq dagegen tut

Das Schloss an deinen Fotos und die Schlüssel, die es öffnen, sind **schon heute so gewählt, dass sie
künftigen Quantencomputern widerstehen.** Die Fotos selbst sind mit einem langen Schlüssel einer Art
verschlossen, die Quantencomputer nach heutigem Wissen nicht knacken. Wird ein Schlüssel an jemanden
weitergereicht, mit dem du teilst, kombinieren wir ein klassisches Schloss mit einem neueren, **„post-quanten"-festen** Schloss, und zwar so, dass es sicher bleibt, solange
**eines von beiden** hält. Selbst eine heute abgegriffene Kopie bleibt also gegen die Maschinen von morgen
unlesbar.

veyq vermerkt, welchen Satz Schlösser dein Konto nutzt, und **könnte deshalb später einen stärkeren Satz
einführen** — für Neues, während alles, was du schon hast, lesbar bleibt, wie es ist. Zwei ehrliche Hinweise:
Heute gibt es nur einen Satz, und nichts stellt deine vorhandenen Erinnerungen von selbst auf einen neueren um —
sie behielten die Schlösser, mit denen sie verschlossen wurden.

## Und beim Anmelden?

Das Anmelden nutzt ein **„heutiges" Schloss**, das später einem quantenfesten weichen soll (ein Termin steht
nicht fest) — und das ist in Ordnung. Aus zwei Gründen: eine Anmelde-Prüfung passiert **im Moment** (es gibt nichts Geheimes
zum „später Entschlüsseln"), und selbst wenn jemand sie bräche, öffnete das nur die Tür zu Daten, die
**weiterhin verschlüsselt** sind — nie zu den Fotos selbst. Der Schutz deiner *Inhalte* und der Schutz
deiner *Anmeldung* sind bewusst getrennt.

## Das ehrliche Fazit

Niemand kann Versprechen über die ferne Zukunft geben. Aber veyq ist für **Jahrzehnte** gebaut — mit
quantenfestem Schutz für deine Daten **schon jetzt** und einem Entwurf, der Raum für **stärkere Schlösser
später** lässt. So wird eine heute abgegriffene Kopie morgen nicht zum Problem.

Die Kryptografie dahinter, ausführlich und auf Englisch: [Cryptography](/de/security/cryptography/) und die
[Roadmap](/de/security/roadmap/) für das, was entworfen, aber nicht gebaut ist.
