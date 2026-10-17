---
title: "Moderate an instance"
sources:
  - docs/features/moderation/use-cases/uc.moderation.serve-as-moderator.md
  - docs/features/moderation/enablers/en.moderation.log.md
---

The person who runs a veyq server can ask you to **moderate** it: to look at what people report, in every space on
that server, and to decide what happens with it. Reporting itself is not built yet. What you can do today is take up
the role, so that you hold the key reports will be sealed to.

## Accept

1. Sign in on the address of a space you belong to, and open `/moderation` there, for example
   `https://photos.example.com/moderation`. The page signs you in itself.
2. If you were appointed, the page says what moderating means:
   - you will see what was reported, and that can include child sexual abuse material;
   - every time you open a report, the **moderation log** records it with your account.
3. Choose **Accept and moderate**. If you were not appointed, the page says so and offers nothing.

## Confirm your address

Accepting sends a code to your account's email address. Enter it under **Code** and choose **Confirm address**.
Notices about new cases reach you only at a confirmed address. If the code did not arrive or no longer works, choose
**Send a new code**. When you change your account's address, you confirm the new one the same way.

## Open the moderation key

1. On `/moderation`, enter your password and your Secret Key, and choose **Open the moderation key**. The page asks
   for both every time: a trusted device does not open moderation.
2. The page opens only the moderation key. Nothing else of your account stays open on this page, so what you will
   look at there cannot reach your own spaces.
3. If you are new, you do not hold the key yet. It reaches you when a moderator who holds it next opens this page; the
   page says so.
4. If you hold the key and someone else is waiting for it, your page passes it on, after checking that person's key
   is the one you have seen before. If their account key changed, it is not passed on, and the page says so: compare
   their safety number first ([Verify a person](/veyq/account/verify-a-person/)).
5. Then the page shows **Reports**. Until reporting is built, it says nothing has been reported.

If the page says it **stopped** because the key the server publishes is not the one you hold, do not go on, and tell
the person who runs the server.

## What is logged, and for how long

The moderation log records when you were appointed and accepted, each time you pass the key on, and when you step
down: who, when and for whom, never a key and never content. It is kept until 31 December of the third year after you
stop moderating, then deleted.

## Step down

Choose **Step down as moderator**. Your role and your copy of the key end at once. You cannot step down while you are
the only moderator who holds the key; another moderator must hold it first. Someone who does not hold the key yet
asks the person who runs the server to remove them.
