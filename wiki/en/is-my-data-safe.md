# Is my data safe?

**In one sentence:** veyq is **end-to-end encrypted** — your photos, videos, journal text and the
sensitive details around them are locked on your own device *before* they ever reach the server, and
**no one running veyq holds a key to open them.** We run the building; you hold the only key.

## Think of a storage unit

Picture a storage building we operate. You rent a unit and put your **own** padlock on it — and we
**don't have a copy of that padlock**. We can keep the building running, point you to the right unit
and see *that* it exists and who you've handed a key to. **What we can't do is open it and look
inside.** That's the whole idea; everything below is just the honest detail of where that line sits.

## What we genuinely can't see

All of this is locked with **your** keys and is gibberish to us — and to anyone who steals our
database:

- your **photos and videos** (and the smaller preview versions),
- your **journal text**,
- the **sensitive details**: titles, captions, descriptions, places, the people in them, tags,
- **where** a photo was taken (GPS) and **when** it was captured,
- the **file's own name**, and everything the camera wrote into the picture: the camera and lens, how it was
  exposed, who made it, and the place name shown beside the coordinate.

## What we *can* still see — and why

We won't pretend it's magic. To run the service — sort your timeline, show a page, enforce who's
allowed in — the server sees a few **low-sensitivity, operational** facts:

| We can see | We cannot see |
|---|---|
| that a memory / photo / share **exists**, and its ID | what's **inside** it |
| **how big** a file is, and its rough type (e.g. "a video") | the file itself |
| **who is in which** group or space | what they wrote or uploaded |
| coarse **timestamps** used to order your timeline | the content behind them |

It's like seeing **labelled boxes on a shelf and who holds a key** — never the contents. Those few
facts are what let the server be useful without ever decrypting.

## When you share

Sharing is the one moment you **choose** to lower the wall — always by your explicit action:

- Share with a **group or person** and they can read that content. You've given it up **to them** —
  not to us; we still can't read it.
- A **public link** can be opened by **anyone who has the link**. The secret that unlocks it lives
  **in the link itself** and never reaches our server — so a public share is "readable by
  link-holders, still not by us."

Two honest limits:
- **Un-sharing doesn't un-download.** Revoking access stops *future* access; it can't reach back and
  erase what someone already saved.
- **Shared means shared.** Once you hand a key to others, privacy *from them* is gone by design — only
  privacy *from us* still holds.

## Illegal content & abuse

Because private content is invisible to us, we **can't** scan your private memories — that's the
point. Where content is actually **distributed** — a **public share** — an abuse report includes the
link, and therefore the key, so it can be opened and judged. Notice-and-takedown works exactly where
it should: on the public stuff.

Who acts on it is whoever runs your instance. There is no central moderation team, because there is
no central service — and veyq ships no built-in moderation tooling today. What the operator does have
is control over **reachability**: they can take a share offline. They still cannot read anything
private, which is the whole design.

## Built to last

Memories are for decades, so the locks are chosen to **resist even future quantum computers** — a real
concern, because someone could copy encrypted data today and hope to crack it later. That protection is
a built-in goal, and the system is designed so the locks can be **upgraded over time without
re-locking everything you already have.**

More: [What happens to my photos when quantum computers arrive?](quantum-computers-and-your-photos.md)

## Making sure it's really *their* key — and an honest gap

When you share with a friend, your app needs your friend's key, which it gets from our server. A
dishonest operator could, in theory, hand your app the *wrong* key. To catch that, every person has a
**fingerprint** (a short "safety number"). You and your friend can compare it **out-of-band** — read
it aloud or scan a QR code — and a match proves the key is genuinely theirs.

**The honest part:** this check is **manual** — it only protects you if you actually compare. veyq does
**not yet** automatically remember a contact's key and warn you if it later changes, and the stronger
"the server can't lie about keys at all" system is **still planned**. The tool to verify exists; the
automatic safety net doesn't yet — and we'd rather tell you than imply it does.

## If something goes wrong

- **Forgot your password?** You won't lose your memories — they're tied to your *key*, not your
  password. Your Emergency Kit is what gets you back in; a device you are still signed in on keeps
  working meanwhile. Resetting takes two things at once: a link we mail you, and the secret key from
  your kit. Neither works alone — see [Account keys & recovery](account-keys-and-recovery.md).
- **Lost everything** — no signed-in device and no Emergency Kit? Then it's **gone.** There is
  **no backdoor and no escrow**: the same thing that stops *us* from reading your data stops us from
  rescuing it. That's the deal, stated up front.

> **Self-hosting note:** if *you* run your own veyq server, *you* are the operator — so the "can the
> operator be trusted" question doesn't apply to your own instance.
