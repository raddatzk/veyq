<!-- speaks-for: docs/features/memory/use-cases/uc.memory.participants.md; pair: de/personen-in-deinen-erinnerungen.md -->
# People in your Memories

A plain-language companion to [Is my data safe?](is-my-data-safe.md) and [Sharing](sharing.md). In a Memory you can
say **who was there** — the people of a trip, a wedding, a birthday — and open a person to see every Memory they
took part in.

## What you can do

- **Name the people.** Open a Memory and choose **Add participants** under its title. You pick from the members of the
  space the Memory lives in. Their names appear as small chips in the Memory's header. A Memory can name up to 100 people.
- **Open a person.** Click a name and you land on that person's profile: their name, a letter avatar, and below them
  the Memories they took part in — only the ones **you** are allowed to open. A Memory you can't see is simply not
  in the list. (If you have many Memories, the list says when it may be incomplete.)
- **Who added a photo.** Open a photo and its details say who added it, as a link to that person's profile. This
  is independent of the participant list: someone who only uploaded, and was never named, still shows up.
- **People who left.** Someone who later leaves the space stays on the Memory, greyed out as "Former member" and
  without a link. Nobody removes them automatically — it is the Memory's record of who was there. You can take them
  off by hand.

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
- Not built yet: a picture for a person (their avatar is a letter for now), and showing who added a **file** in Files.
