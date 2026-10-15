---
title: "Adding another device"
sources:
  - docs/features/account/use-cases/uc.account.onboard-device.md
---

A plain-language companion to [Account keys & recovery](/veyq/account-keys-and-recovery/). You can let a new
phone or laptop in without digging out your Emergency Kit: **one device you are already signed in on hands
the other everything it needs.**

## Two ways round — pick the one that fits the device in your hand

Both do exactly the same thing. What differs is which device shows a code and which one scans it, so
whichever device has a camera is the one that scans.

**From the device you are already signed in on** — open **"Add a device"** in Settings.

1. Press **"Show a code"**; it shows a code.
2. Your new device scans it — on its sign-in screen, with the **"Scan QR code"** button beside the secret key
   field.
3. Both screens now show a short number.
4. Check they match, name the new device, and slide to confirm.

**Standing at the new device, with nothing but the app open** — choose **"Sign in with a QR code"** on the
sign-in screen. You need no camera on this one, only on the other.

1. The new device shows a code.
2. You scan it with the device you are already signed in on (**"Add a device"** → **"Scan a code"**).
3. *That* device now shows you a short number.
4. You type that number into the new device, and confirm on the signed-in one.

## The number you type is the step that matters

In the second way round, typing the number feels like a hoop too many. It is the one part you should not
rush, and here is why.

Nothing about the code your new device shows says whose device it is. Your phone
has no way to tell a code shown by the laptop in front of you from one someone sent you in a message. What
it *can* tell is whether whoever answers knows a number **your phone chose and only showed on its own
screen.** Someone who tricked you into scanning their code never sees that screen, so they can never send
the right number back.

That is also why the first way round asks you to *compare* rather than type: there, both screens are in
front of you, so seeing the same number is proof enough.

And the server that passes the messages between your two devices cannot slip in a device of its own either way
round: the code carries a secret that never reaches the server, and both devices check that everything they
receive was made with it.

**Never type in a number someone gives you over the phone or in a message.** Nobody legitimate will ever
ask you for one — not support, not us, not a friend "helping you set things up". If someone is asking, that
is the attack, and typing the number is the moment it succeeds.

## If the number is wrong, that attempt is over

There is exactly one try. Check the number on screen before you confirm — you can correct it as often as
you like until then, but once it is sent, that is the answer.

If it was wrong, your signed-in device says so, nothing is given away, and the code is finished. Start the
whole thing again on the new device; it will show a fresh code. The new device itself is not told anything
— it simply waits, and then the code runs out. So if nothing happens there, don't keep waiting: start
again.

This is deliberate, not a missing feature. A "try again" button on the same code would invite you to push
past the one warning you get.

## Two honest details

**A device added this way gets everything, permanently.** It can read every memory you have. Signing it
out later stops it reaching the server, but it does **not** take the key back — see
[Is my data safe?](/veyq/is-my-data-safe/) for what a sign-out does and does not do. So only ever add a device
you are holding.

If you ever approve a code that turns out not to be your own device, there is one thing that ends it:
[replacing your keys](/veyq/account-keys-and-recovery/#if-someone-got-into-your-account). It shuts that device
out from that moment on — it cannot take back what was already read, and it needs your Emergency Kit.

**A device added this way has no password saved on it.** You never typed one there, and it never learns
one. If it ever forgets its key — you clear the browser, or another device removes its saved key — it will
ask you for your password **and** your Emergency Kit, neither of which was ever typed on it (or you add it
again from a signed-in device). Keep the Emergency Kit somewhere you can reach. And if you don't choose to stay
signed in on it, closing the browser ends its access, and you add it again next time.

## Related

- [Is my data safe?](/veyq/is-my-data-safe/)
- [Account keys & recovery](/veyq/account-keys-and-recovery/)
- [Who gets into your space](/veyq/who-gets-into-your-space/)
