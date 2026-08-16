# Account keys & recovery

A plain-language companion to [Is my data safe?](is-my-data-safe.md). Because veyq is end-to-end
encrypted, **your keys — not the server — guard your data.** Here's how that works, no crypto
background needed.

**A few words you'll see:**
- **Key** — a secret that locks/unlocks data.
- **Master key** — the one key that protects everything else. All your memories hang off it.
- **Secret key** — a high-entropy code in your **Emergency Kit**, combined with your password. Never
  sent to the server.
- **Emergency Kit** — the PDF holding your secret key. It is what gets you back in if you forget your
  password.

## One master key, several ways to open it

There is **one master key**, and it's locked **separately by each method you set up**:

```
master key ─┬─ opened by your password + secret key   (always set up)
            ├─ opened by your secret key alone         (the recovery lock)
            └─ opened by this device, once you trust it
```

Any one of them opens the **same** master key, which unlocks **all** your content. Adding or removing a
method just adds or removes one lock — **your memories are never re-encrypted.**

## Logging in

- **First time on a device** — your **password + the secret key** from your Emergency Kit. You type the
  secret key once here, and then not again on this device.
- **A device you trust** — from then on, just your password. Tick **"remember me"** instead and the device
  opens straight to your memories with nothing to type at all.
- **Someone else's computer** — use a private/incognito window; nothing is kept.

Some browsers can't remember a device at all — private/incognito windows, and a few locked-down
configurations. veyq tells you when you're in one: unlocking there needs your secret key every time. That
is the browser's limit, not a fault in your account.

The secret key is only needed that first time. Why have it at all?

Your password never leaves your device — not even to sign in. Your device turns it into a pair of keys and
shows the server only the public half, which cannot be turned back into your password. But someone who got
hold of the server's data could sit there and **guess**: try a password, do the same computation, see whether
the public half matches. We make each guess deliberately slow, and that still is not a promise — people pick
guessable passwords.

That is what the secret key is for. It is long and completely random, so guessing it is hopeless, and your
memories need it *as well as* your password. A guessed password on its own opens nothing.

## If a device is lost

You can see every place your account is currently signed in, and sign any of them out from there — useful when
a laptop goes missing, or you signed in on someone else's machine and forgot to sign out. **"Sign out
everywhere else"** ends every other session in one go and keeps you signed in where you are. You don't need
your secret key for any of this.

That holds on a **borrowed or brand-new device** too, which is the one you're most likely to be holding when a
laptop goes missing. On the sign-in screen there, choose **"I don't have my secret key with me"**: you sign in,
your memories stay locked, and the one thing offered is to sign every device out, this one included. That
device keeps nothing afterwards — no saved key, and you stay signed in only until the browser closes.

**Signing out is not the whole story, and this is the part worth reading twice.** Signing a device out stops it
reaching the server; it does *not* stop it opening your memories, because a device you trusted holds its own
lock on your master key (the picture at the top of this page). If a device is really gone, use **"sign out and
remove this device's saved key"** instead. Then whoever has it needs your password *and* your secret key —
so keep that for devices you have actually lost, because it is your own device that will ask you for the
Emergency Kit next time.

Two honest details. The server never learns your device names: those are stored scrambled and are readable only
after you unlock, so on a locked screen you'll see dates instead of names. And none of this reaches into the
lost device itself — what it takes away is what the server was keeping for it.

## If someone got into your account

Sometimes signing a device out is not enough — because it never was the whole story. Say you approved a
sign-in code that wasn't really your own new phone. That device holds the keys to your memories, and no
amount of signing it out takes them back.

**Replacing your keys is what ends that.** You'll find it under Settings → Personal → Security. Your spaces
get new locks, the old device is shut out of everything from that moment on, and every other device has to
sign in again.

Read these four before you start. They are the whole picture, and none of them is small print.

**It stops what comes next — it can't take back what's gone.** Anything that person already saw, they still
have. There is no version of this that reaches into their device and deletes what they read. What it does is
draw a line: from here on, nothing new.

**Every other device signs out and loses its saved key.** Each one needs your password again, and a device
that only ever had a saved key — one you added by scanning a code — will need your Emergency Kit. Do this
when you are somewhere you can get to your kit, not on the way out the door.

**You need your Emergency Kit, and that is the point.** It's the one thing the other person doesn't have.
Without that requirement, *they* could replace the keys first and lock *you* out permanently — which would
make this feature worse than not having it at all. So the check isn't friction; it's the whole defence.

**Shared spaces need someone else to let you back in.** Your new keys look, to everyone who knows you, like
a stranger claiming to be you — which is exactly what the app is supposed to notice. Someone else in each
shared space confirms it's really you, best by comparing the security number over a call, and only then are
you back in. Until they do, you're outside that space, and nothing you do alone changes it.

One thing worth setting up *before* you ever need this: **in a space only you own, make someone else an owner
too.** Otherwise there is nobody there who can let you back in.

Your Emergency Kit keeps working afterwards — your secret key doesn't change, only what it opens.

## What the server actually stores

Only the **locked** copies of your master key — never anything that opens them. The thing that unlocks a
copy is built on your device for a moment during sign-in, then thrown away. It is never stored and never
sent. That's what keeps the server blind.

## If you forget your password

Your Emergency Kit is what gets you back. There is no separate "recovery code" — **the secret key in your kit
is the recovery key.** That's deliberate: a second code that opens exactly what the first one opens would just
be one more thing to lose.

Resetting takes **two things at once**, on purpose:

1. **A link we mail you**, which proves you can read the mailbox your account is registered under.
2. **The secret key from your kit**, which proves the account is yours.

Neither is enough alone. Someone who breaks into your mailbox receives a locked copy of your key and no way to
open it. Someone who finds your kit gets no link. Once both are in place you pick a new password, and
everything you saved is still there — your memories are never re-encrypted, and **your kit keeps working**,
because your secret key doesn't change.

After a reset, every device signs in fresh. You'll enter your secret key once more on each one — including the
one you're holding.

**Two honest limits.** If your password **and** your kit are both gone, nothing can bring your memories back:
there's no master key on our side and no back door. And if the server you're on has no email set up, the reset
link can't be sent — you can still ask for one, and it will tell you plainly that it can't, rather than leave you
waiting for a mail that was never coming. Ask whoever runs the server to set email up; nothing about your account
is broken, and the reset works the moment they have.

**Why a forgotten password doesn't lose your memories:** everything is tied to your *key*, not your
password. Get the key back and everything returns at once.

**The one thing we can't undo:** lose **every** route — no signed-in device and no Emergency Kit — and your
content is gone. There is **no backdoor and no escrow**: the same thing that stops *us* reading your data
stops us rescuing it.
