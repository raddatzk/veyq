<!-- speaks-for: docs/crosscutting/security-model.md; pair: de/quantencomputer-und-deine-fotos.md -->
# What happens to my photos when quantum computers arrive?

A plain-language companion to [Is my data safe?](is-my-data-safe.md). Short version: **your memories are
locked today with protection chosen to survive tomorrow's quantum computers** — and the design leaves room
for stronger locks later.

## The worry, honestly

One day, powerful **quantum computers** may be able to break some of the encryption used across the
internet. There's a sneaky version of this threat called **"harvest now, decrypt later":** a patient
attacker copies your **encrypted** photos *today*, stores them, and hopes to crack them in, say, fifteen
years. The problem is that a private photo from today is *still* private in fifteen years — the value
doesn't expire.

## What veyq does about it

The lock on your photos and the keys that open it are **already chosen to resist future quantum
computers.** The photos themselves are locked with a long key of a kind quantum computers are not expected to
break. When a key is handed to someone you share with, we combine a classic lock with a newer **"post-quantum"** one, in a way that stays safe as long as
**either** of them holds. So even a copy grabbed today stays unreadable against tomorrow's machines.

veyq records which set of locks your account uses, so it **could add a stronger set later** for new things
while what you already have stays readable as it is. Two honest details: today there is only one set, and
nothing moves your existing memories to a newer set by itself — they would keep the locks they were made with.

## What about logging in?

Signing in uses a **"today" lock** that is meant to be swapped for a quantum-resistant one later (no date is
set) — and that's fine. Two reasons: a login check happens **in the moment** (there's nothing secret to "decrypt later"),
and even if someone broke it, it would only open the door to data that is **still scrambled** — never to
the photos themselves. The protection of your *content* and the protection of your *login* are separate
on purpose.

## The honest bottom line

Nobody can make promises about the distant future. But veyq is built for **decades**, with
quantum-resistant protection on your data **now** and a design that leaves room for **stronger locks
later** — so a copy taken today doesn't become a problem tomorrow.
