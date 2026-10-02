<!-- speaks-for: docs/crosscutting/security-model.md, docs/features/memory/use-cases/uc.memory.gallery.md, docs/features/spaces/use-cases/uc.spaces.verify-identity.md, docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md, docs/features/account/use-cases/uc.account.recover-access.md; pair: de/sind-meine-daten-sicher.md -->
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

We won't pretend it's magic. To run the service — hand you your things page by page, enforce who's
allowed in — the server sees a few **low-sensitivity, operational** facts:

| We can see | We cannot see |
|---|---|
| that a memory / photo / share **exists**, and its ID | what's **inside** it |
| **how big** a file is | the file itself — not even whether it's a photo or a video, though the way a video streams while someone watches it can give that away |
| **who is in which** group or space | what they wrote or uploaded |
| **when** something was uploaded or last changed | when a photo was **taken** — your device sorts your timeline by that date, and it stays locked |

It's like seeing **labelled boxes on a shelf and who holds a key** — never the contents. Those few
facts are what let the server be useful without ever decrypting.

## When you share

Sharing is the one moment you **choose** to lower the wall — always by your explicit action:

- Share with a **group or person** and they can read that content. You've given it up **to them** —
  not to us; we still can't read it.
- A **public link** can be opened by **anyone who has the link**. The secret that unlocks it lives
  **in the link itself** and never reaches our server — so a public share is "readable by
  link-holders, still not by us."

Three honest limits:
- **Un-sharing doesn't un-download.** Revoking access stops *future* access; it can't reach back and
  erase what someone already saved.
- **Links made before someone left keep working until you renew them.** Everyone in a group or a
  shared space can copy every public link of it, so someone who leaves may still have one. Renewing a
  link gives it a new address and deletes the old one — hand out the new one. When you remove someone
  you can also switch off every link of the space in the same step, or at any time. A link you switched
  off can be deleted for good; the photos guests added through it stay in the Memory. Deleting a Memory
  for good deletes its links too.
- **Shared means shared.** Once you hand a key to others, privacy *from them* is gone by design — only
  privacy *from us* still holds.

## Illegal content & abuse

Because private content is invisible to us, we **can't** scan your private memories — that's the
point. A **public share** is different: whoever reports one hands over the link, and with it the key, so
it can be opened and judged. There is no report button in veyq — reporting means writing to whoever runs
your instance.

Who acts on it is that person. There is no central moderation team, because there is no central service —
and veyq ships no moderation tooling today. The operator **cannot switch off a single share**: only the
person who created it can. What the operator can do is take a **whole space** on their server offline — for
its members and for every share in it. They still cannot read anything private, which is the whole design.

## Built to last

Memories are for decades, so the locks are chosen to **resist even future quantum computers** — a real
concern, because someone could copy encrypted data today and hope to crack it later. The design leaves
room to add stronger locks later for new things you save, while what you already have stays readable as it
is. That also means your existing memories **don't move to new locks by themselves** — today there is only
one set of locks, so nothing has needed to.

More: [What happens to my photos when quantum computers arrive?](quantum-computers-and-your-photos.md)

## Making sure it's really *their* key — and an honest gap

When you share with a friend, your app needs your friend's key, which it gets from our server. A
dishonest operator could, in theory, hand your app the *wrong* key.

**Your app remembers the key it first saw.** If the server later hands over a different one, that person is
marked **"Key changed"**, and your app gives them no keys until you have looked at it. **"Review key"** shows
the number you trusted next to the one shown now — every key has a short **safety number** — so the two of
you can compare them over a call, and then you accept the new key or refuse it. A change is often harmless
(your friend replaced their keys), but it can also be someone posing as them.

**The honest part:** the comparing is up to you. Accepting is a single tap, and the app cannot tell whether
you really compared. There is no way yet to check someone's number *before* anything has changed — no QR
code to scan, no "verified" mark. And remembering only helps from the first key your app saw: if the server
handed you a wrong key from the very start, nothing looks wrong. The stronger system, where the server can't
lie about keys at all, is **planned, not built** — and we'd rather tell you than imply otherwise.

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
