---
title: "People in your Memories"
sources:
  - docs/features/memory/use-cases/uc.memory.participants.md
  - docs/features/spaces/use-cases/uc.spaces.set-profile-picture.md
---

In a Memory you can
say **who was there** — the people of a trip, a wedding, a birthday — and open a person to see every Memory they
took part in.

## What you can do

- **Name the people.** Open a Memory and choose **Participants** — the button with two people in the row of buttons at
  the top of the Memory, or an entry of the **⋯** menu at its end. You pick from the members of the space the Memory lives in. They appear in the Memory's header: one person with their picture and name, several as a row of small avatars (up to three; with more, the last one shows "+2", "+3" and so on) — hover over them, or tap them, to see everyone by name. In the picker you can search the members by name. A Memory can name up to 100 people.
- **"I was there" when you create a Memory.** In a shared space (not in your personal one) the dialog for a new Memory
  has **I was there**, ticked unless you untick it. Ticked, you are the Memory's first participant from the start;
  unticked, it starts with nobody. It means "I was there", not "I made this" — so it is your choice each time.
- **Open a person.** Click a name and you land on that person's profile: their name, their picture (or a letter), and below them
  the Memories they took part in — only the ones **you** are allowed to open. A Memory you can't see is simply not
  in the list. Each one shows its cover, name, date and place (when it has them) — as tiles, as rows or on a map: use the
  same **Grid view / List view / Map view** buttons as in a folder, and your choice is remembered. The map is the one you
  know from a folder: it shows the person's Memories that have a place (one without a place is simply not on it), asks
  before it loads any map tiles, and opens a Memory when you click it. (If you have many Memories, the list says when it
  may be incomplete.)
- **Who added a photo.** Open a photo and its details say who added it, as a link to that person's profile. This
  is independent of the participant list: someone who only uploaded, and was never named, still shows up.
- **People who left.** Someone who later leaves the space stays on the Memory, greyed out as "Former member" and
  without a link. Nobody removes them automatically — it is the Memory's record of who was there. You can take them
  off by hand.

- **Give yourself a picture.** In **Settings → Your profile**, or on **your own** profile page (**Edit profile**), you can
  choose a picture and frame it. It then shows instead of your letter wherever you appear in the space: the header of a
  Memory, the list of people, the members list and your profile. Remove it any time and the letter comes back. On
  other people's profiles there is nothing to edit — only they can change their own picture. You can pick a picture
  right when you accept the invitation. If you skipped both name and picture then, a small notice offers to finish them
  after you first unlock; "Not now" hides it for good.

Names follow one rule everywhere: the **real name** if the person gave one, otherwise their **username**.

## Who sees it

- Only **members** of the space. Where you may not change a Memory, there is no button to add people.
- The page you open from a **link** (a share) shows **no participants** and no profile links — whatever role the
  link has. Nobody who receives a link learns who was on a trip from the page.

## Why this is safe

- The list of participants is **sealed together with the Memory**, like its date and place. The server only ever holds
  the unreadable sealed blob, so **the operator cannot see who was on which trip** and cannot work out who is
  often together with whom. That is also why there are no automatic "you were added" notices from the server: the server
  does not know.
- What is stored per person is an anonymous **ID**, never a name. Your device looks the names up in your space's member
  list when it shows them.
- One honest limit: a link is a key to the Memory, and the sealed blob it serves is the same one members use. The page
  shows a link visitor no participants, but someone who is technically skilled and inspects what the link delivers could
  find out **how many** people are listed and their anonymous IDs — **never their names**, which a link visitor
  has no way to look up. If participants ever carried names, this would have to be decided again.
- **Your picture is sealed too.** It is locked on your device with the space's key before it leaves, and the server only
  stores a scrambled block of **always the same size** — so it cannot tell a photo from a drawing, and never sees the
  image, its size or what it shows. Only **members of your space** can open it; a link visitor never gets it and sees
  the letter. The operator can tell only that you have a picture and how often you changed it. A picture belongs to one
  space — another space you belong to shows its own (or the letter). If you are removed from a space, your picture there
  is cleared.
