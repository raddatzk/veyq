---
title: "Is my data safe?"
sources:
  - docs/crosscutting/security-model.md
  - docs/features/memory/use-cases/uc.memory.gallery.md
  - docs/features/spaces/use-cases/uc.spaces.verify-identity.md
  - docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md
  - docs/features/spaces/use-cases/uc.spaces.require-verified-identities.md
  - docs/features/account/use-cases/uc.account.recover-access.md
  - docs/features/sharing/use-cases/uc.sharing.share-contributor.md
  - docs/features/sharing/use-cases/uc.sharing.share-uploader.md
  - docs/features/admin/use-cases/uc.admin.privacy-notice.md
  - docs/features/expenses/use-cases/uc.expenses.exchange-rates.md
---

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
- your **files** — documents, PDFs, anything you keep in [Depot](/veyq/depot/) — and their **names**,
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
| **how big** a file is (a photo, a document, anything) | the file itself — not even whether it's a photo or a video, though the way a video streams while someone watches it can give that away |
| **who is in which** group or space | what they wrote or uploaded |
| **when** something was uploaded or last changed | when a photo was **taken** — your device sorts your timeline by that date, and it stays locked |

It's like seeing **labelled boxes on a shelf and who holds a key** — never the contents. Those few
facts are what let the server be useful without ever decrypting.

**What *your* instance keeps, and who runs it.** Every veyq instance is run by someone — a family member, a club, a
company — and each one has its own **privacy notice**: **Privacy**, at the bottom of every page, before you sign in too.
It names who is responsible, what that instance keeps about your account, your sign-in and a report you make, for how
long, who else receives anything (the company hosting it, a mail service), and what you can ask for. veyq writes its
text; the instance fills in its own details, so it says what *that* instance does. The invitation page, the report
form and a link you add something through point you to the part that concerns you.

## Two requests that leave your device by themselves

Two things veyq does without asking you first, because what they send says nothing about your content. Your
instance's privacy notice names both, and whoever runs your server can switch them off.

### Checking a new password

When you choose a password — setting up a server, joining a space, changing your password, or after a reset —
veyq checks it against passwords known from data breaches. It does this **by itself**, as soon as you leave the
password field, and once more when you confirm if you changed the password since. It never checks while you
are still typing.

**What leaves your device:** the first **5 characters of a fingerprint (a hash) worked out from the
password** — never the password itself — sent **straight from your device** to the free service *Have I Been
Pwned*, not through our server. The answer lists hundreds of fingerprints that start the same way, and your
device looks for the password's own in that list by itself.

**What that service does learn:** your browser tells it which site the question comes from. So *Have I Been
Pwned* (and the network company in front of it) learns that someone at your internet address uses this veyq
server and is choosing a password — not who you are, and not the password.

If the password shows up in a breach, you see a warning and decide: pick another one, or tick **"Use it
anyway"**. If the check can't run — you're offline, or the server has no route to the internet — nothing is
blocked; you're simply told the password could not be checked.

### The day's exchange rates

When you record an expense in a currency other than its Pot's, your device fetches **the day's exchange rates** —
the euro reference rates of the European Central Bank — and fills in the rate. It does this **straight from your
device**, at most once a day, and keeps the rates on the device.

**What leaves your device:** the **date**, nothing else — not the currency, not the amount, not who you are. Your
device works out the rate for your currencies itself.

**What that service does learn:** your internet address, which veyq server the request comes from, and so that
someone there is recording an expense in another currency today.

The owner of your space can switch it off too. Then, or if the rates can't be fetched, you type the rate yourself; a
rate you saved never changes.

## When you share

Sharing is the one moment you **choose** to lower the wall — always by your explicit action:

- Share with a **group or person** and they can read that content. You've given it up **to them** —
  not to us; we still can't read it.
- A **public link** — to a folder, a single file or a Memory — can be opened by **anyone who has the link**. The secret that unlocks it lives
  **in the link itself** and never reaches our server — so a public share is "readable by
  link-holders, still not by us." The address without that secret can read nothing — and add nothing: on
  a link that lets guests add photos, their app first has to prove it holds the secret, so someone who
  only has the address (say, from a copy of our database) cannot upload anything through it.

How the different links work — view only, view and add, drop box — is in [Sharing](/veyq/sharing/); switching
them off and renewing them in [Change, switch off, renew and delete links](/veyq/sharing/manage-links/).

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
point. What someone **reports** is different: anyone who sees something — a member of the space or a guest of a
link — can choose **Report**, and their device hands the moderators of the server the keys to exactly what was
reported, sealed so that only those moderators can open them. Nothing else of the space opens with them, nobody but
the moderators learns of the report, and nothing is blocked by it: a person decides. What was reported is kept for
them for up to 90 days even if it is deleted in the meantime. [Report something](/veyq/help/report-something/)

Who acts on it is the people who run your server and the moderators they appoint. There is no central moderation
team, because there is no central service. The moderators can block what was reported — a link, an item or a guest
who uploaded through a link — and everyone then sees **Blocked by the operator** in its place, without a reason; a
block deletes nothing and can be lifted ([Report something](/veyq/help/report-something/#blocked-by-the-operator)).
Blocking a member's sign-in or taking everything on an address offline is up to the person who runs the server. They
still cannot read anything private, which is the whole design.

## Built to last

Memories are for decades, so the locks are chosen to **resist even future quantum computers** — a real
concern, because someone could copy encrypted data today and hope to crack it later. The design leaves
room to add stronger locks later for new things you save, while what you already have stays readable as it
is. That also means your existing memories **don't move to new locks by themselves** — today there is only
one set of locks, so nothing has needed to.

More: [What happens to my photos when quantum computers arrive?](/veyq/privacy/quantum-computers-and-your-photos/)

## Making sure it's really *their* key — and an honest gap

When you share with a friend, your app needs your friend's key, which it gets from our server. A
dishonest operator could, in theory, hand your app the *wrong* key.

**Your app remembers the key it first saw.** When your friend
[secures their account](/veyq/account/replace-your-keys/), their new key is signed by their previous one, and your
app follows that signature from the key it remembered: it goes on sharing with them, and their profile says **"New
identity, signed by their previous key"**. If the server hands over a key that does *not* follow from the one your
app remembered, that person is marked **"Can't confirm this key"**, and your app gives that key nothing. There is no
button to accept it: a real change is always signed, so a key that isn't can be someone posing as them. The two of
you then compare your **safety numbers** — every key has a short one — in person. Meanwhile someone else in the space
whose app does confirm their key passes them the space's key, and nobody's posting waits for it.

**Checking before anything changes.** Remembering only helps from the first key your app saw: if the server
handed you a wrong key from the very start, nothing would look wrong. So you can check it yourself. Your own
profile shows *your* safety number and a code; open a friend's profile, scan the code on their screen or read the
digits to each other, and if it matches they are marked **"Verified"** — on their profile and in every member list
(step by step: [Verify a person](/veyq/account/verify-a-person/)).
When a verified friend secures their account, the mark doesn't carry over to the new key; compare again to restore it.

**The honest part:** the comparing is up to you. Nothing requires
you to verify anyone before you share with them; a setting that would does not exist yet. The stronger system,
where the server can't lie about keys at all, is **planned, not built** — and we'd rather tell you than imply
otherwise.

## If something goes wrong

- **Forgot your password?** You won't lose your memories — they're tied to your *key*, not your
  password. Your Emergency Kit is what gets you back in; a device you are still signed in on keeps
  working meanwhile. Resetting takes two things at once: a link we mail you, and the secret key from
  your kit. Neither works alone — see [Forgot your password](/veyq/account/forgot-password/).
- **Lost everything** — no signed-in device and no Emergency Kit? Then it's **gone.** There is
  **no backdoor and no escrow**: the same thing that stops *us* from reading your data stops us from
  rescuing it. That's the deal, stated up front. More situations, and what to do: [Help](/veyq/help/).

## The page itself comes from the server — an honest limit

Everything above is done by the veyq page in your browser, and that page, including the one that opens a shared
link, comes from the server each time you load it. So your things stay locked from someone who runs the server
honestly, and from anyone who only gets hold of its stored data. But someone running the server who is set on reading
along could change that page. On someone else's server, veyq in a browser protects you from an honest operator, not
from a hostile one, and we would rather say so than claim more:
[Known limitations](/veyq/security/known-limitations/#the-operator-served-web-client-is-passive-operator-only).

> **Self-hosting note:** if *you* run your own veyq server, *you* are the operator — so the "can the
> operator be trusted" question doesn't apply to your own instance.

## For the technically curious

The Security area explains all of this in depth, in English: [Start here](/veyq/security/start-here/),
[What the server sees](/veyq/security/what-the-server-sees/), [Threat model](/veyq/security/threat-model/) and
[Known limitations](/veyq/security/known-limitations/).
