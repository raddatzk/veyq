---
title: "Depot — and finding your way around"
sources:
  - docs/features/platform/use-cases/uc.platform.node-cover.md
  - docs/features/depot/use-cases/uc.depot.member-files.md
  - docs/features/depot/use-cases/uc.depot.view-file.md
  - docs/features/memory/use-cases/uc.memory.move-node.md
  - docs/features/memory/use-cases/uc.memory.delete-node.md
  - docs/features/memory/use-cases/uc.memory.browse.md
  - docs/features/platform/use-cases/uc.platform.copy-node.md
---

A plain-language companion to [Is my data safe?](/veyq/is-my-data-safe/). veyq is a platform for sealed, shareable
things, and it grows by **applications**. **Memories** is the one for photos, videos and journals. **Depot** is
the one for everything else: documents, PDFs, any file you want to keep. Both live in the same folders of your
space, side by side.

## What you can do with a file

- **Upload** — choose **New → Upload files**, or drop files onto the folder. Uploads run in the background, and the
  status menu in the top bar shows how far they are, one list for each place you upload into (a second upload
  into the same folder joins its list).
- **Upload a folder** — choose **New → Upload folder…**, or drop a folder onto the folder: it arrives as folders and files,
  just as it is on your computer (empty folders inside it are left out). A name some computers do not allow is changed
  slightly, and a name that is already taken becomes "Name (2)". A folder that is an unpacked veyq download is offered as an
  import instead ([Download and import](/veyq/download-and-import/)).
- **Download** — the download button on the file's row (or at the top of its page) gives you the file back exactly as
  you put it in, under the name it has in veyq. Clicking the row itself opens the file's own page.
- **Look at it** — click the file's row and its page opens with the file itself on it: a **picture** is shown, a
  **text** (plain text, Markdown, code, JSON, CSV …) is shown as text (select it as you like, or use the **Copy**
  button; **Wrap lines** switches line wrapping on and off), a **PDF** is shown page by page (with its page
  count and buttons to zoom in and out and to fit it to the width), and a **video** or a **sound file** plays,
  with a button for **full screen**. The file is unlocked on your device while you look, nothing extra is stored,
  and someone you shared the file with sees exactly the same. Some things are not shown on the page: a format your browser cannot display, and very large
  pictures or PDFs (over 64 MB) — and a long text shows only its first megabyte (**Copy** then copies just the part that
  is shown, and says so). A PDF that is **protected by a password** asks for it right on the page: the password is only
  used on your device to open the PDF and is neither stored nor sent anywhere; if you cancel, the download is offered
  instead. In a PDF you can **select and copy the text** of the pages on screen with the mouse or the keyboard; links
  inside it do nothing (they are not clickable, so a PDF can never take you somewhere else), notes and form fields are
  not shown as live elements, and **scanned pages** (some scanners store the page in an image format veyq does not draw)
  may show gaps — and have no text to select unless the scanner added it. When something cannot be shown, the download
  button is right there.
- **See who uploaded it** — at the top of a file's page, members of the space see who uploaded its current version:
  a member's name and picture (click it to open their profile), "Former member" for someone who has left the space, or
  the name a guest gave when it came in through a link (in a drop box, the name of that guest's folder) - "a guest" only
  where that name cannot be read. After a replace it names whoever replaced it. People you share a link with never see
  this.
- **Replace** — put a newer version of a file in its place: upload a file with the **same name** into its folder, or
  open the file and use the replace button at the top of its page. The name stays. veyq **asks first**, because there is
  **no version history**: the old content cannot be brought back. A file whose upload never finished is simply filled,
  without the question.
- **Rename and move** — both are in the **⋯** menu of the file's row. **Move to…** lets you open the folder it should go
  to and choose **Move here** (more on moving below).
- **Delete, with an undo** — a deleted file goes to the **trash**, and a message at the time offers **Undo** right
  away. Later you can still restore it from the trash until it is removed for good; the trash shows the date. Space
  in the trash still counts against your storage until the trash is emptied.
- **Share** — a whole folder, or a single file. See [Sharing](/veyq/sharing/).

If someone else in your space replaced the same file a moment before you, veyq tells you instead of silently
overwriting their version, and you can choose **Replace anyway**.

A name has to be free in its folder: a file cannot share a name with a folder, a Memory or a drop box that is
already there. veyq skips such a file and tells you which item has the name.

**Big files** are downloaded piece by piece and put back together on your device. Browsers differ in how well
they cope with very large files: where your browser can save straight to a file you choose, that is the best case.
Where it cannot, a very large file may be refused with a message instead of failing halfway.

## Moving things

**Move to…** works the same on a folder, a Memory, a chapter, a file or a drop box. It opens where the item is now,
with the path above it: click a folder to go into it, or a part of the path to go back up, and choose **Move here**
in the place it should go — or **Cancel**. Only places that can take the item are shown (a chapter, for example, only
goes into a Memory), and **Move here** is greyed out, with the reason, where the item cannot go — for instance where it
already is. Everything inside comes along and stays readable. If somebody shared a folder with you and lets you
contribute, you can move things around **inside** that folder — but not out of it.

## Copying things

**Copy to…** sits right next to **Move to…** on a folder, a file, a Memory or a drop box, and opens the same folder view. Here the
place the item is in now is allowed too: the copy is then called "Name (2)". Before anything is copied, veyq says how
many items and how much data it will copy, what does not come along — shares, favourites, the trash and a folder's
cover; a copied drop box is an ordinary folder that nobody can drop files into — and whether your space still has room
for it. Everything is copied on your device and stored again under new keys, so a big folder takes as long as uploading
it and uses its space twice; the server only sees new things being stored, not that they are a copy. If the copy stops
part-way, what it had made goes to the trash and veyq says how far it got. A copied Memory keeps its photos and videos
with their dates (corrected ones too), places and camera details, its chapters, date, place and cover; who was there and
who added each photo do not come along. Copying is for members of the space only; somebody you shared a folder with
cannot copy.

## Several at once

Choose **Select** next to **New** and the trash, and every entry of the folder gets a tick box. Tick what you want —
or choose **All** — and then **Move to…**, **Copy to…** or **Delete**. The bar says how many you have ticked, and if a tab hides some
of them it says that too: your ticks stay when you switch tabs. If some of the ticked things cannot be moved or
deleted, veyq says how many, and does it with the rest. **Delete** asks once for all of them, and one **Undo** brings
all of them back. **Move to…** opens the same folder view as for one item; anything that cannot go there — something
with the same name already there, for example — is named and stays where it is, and one **Undo** moves back what moved.
**Copy to…** copies everything you ticked in one go and names what it cannot copy.
If somebody shared a folder with you and lets you contribute, you can select and move there too, but not delete.

## One list for everything in a folder

A folder shows **one list** of everything in it: sub-folders, Memories, files and drop boxes together. Folders come
first; everything else follows in alphabetical order, whatever it is (numbers count as numbers, so *Photo 2* comes
before *Photo 10*). Each entry shows what it is — for example *File · 157 B · modified …* or *Memory · June 2024* —
and has its own buttons: the most useful one (for a file, download) sits on the entry, the rest are in its **⋯** menu.
Every entry — folder, Memory, file or drop box — also has a **heart**: it adds the entry to your **favorites** in the
left-hand navigation. Your favorites are yours alone; other members of the space cannot see them, and neither can the
server. Where you may not do something, its button is simply not there (people you share a link with get no heart).

When a folder holds more than one kind of thing, tabs above the list — **All**, **Memories**, **Depot**, **Drop
boxes** — let you show just one kind. Sub-folders stay visible in every tab so you can keep navigating.

## Give a folder a cover

A folder, a drop box or a Memory can have a **cover**: a picture of your choice that replaces its icon in the list, on its page
and in your favorites. Open the **⋯** menu of the entry (or the buttons at the top of its page) and choose **Set cover** (it says **Change cover** once there is one). A window opens: pick an image from your device and drag or
zoom it into the frame you like — the same window for a folder, a drop box and a Memory, and it frames the cover the same way
everywhere it shows. The picture is shrunk on your device and locked with the entry's own key before it leaves it, just
like everything else — the server only ever stores scrambled data. Open **Change cover** again to re-frame it or swap the picture; **Remove cover** in that same window takes it away;
the old picture is deleted from the server's storage (copies that other people's browsers already hold, or a link that was
already handed out, can't be taken back — they expire on their own). It is a picture for the entry, not a file in the folder, and
only people who may change the space can set it. People you share a link with see the covers but can't set them. A file has no
cover.

## Grid and list

In a folder you can switch between **Grid view** and **List view** with the buttons at the top (and **Map view**
where Memories have places). The choice is yours and is remembered; the same switch works for everything in the
folder.

Under each name a small line says what the thing is and what we know about it: a Memory shows its date and place (when it has
them), a file its size and when it was last modified, a drop box how many people have a folder in it. What is not known is
simply not shown. Covers are larger in both views, and the same switch (and the same line) is on a person's profile page.

## Addresses you can bookmark

The address of a folder in your browser reads like its path, for example `…/#holidays/2026/` — **a folder's
address ends with a slash**. A Memory sits under `/memories/`, for example `…/#holidays/memories/rome`. You can
bookmark any of them.

The readable part sits **after the `#`**, and a browser never sends that part to a server. So the names in the
address stay on your device.

## Why this stays private

A file is locked on your own device **before** it is uploaded, exactly like a photo. What we can't see: the file's
**contents** and its **name**. What the server does see is the same as for everything else: that a file exists,
roughly how large it is, when it was uploaded or changed, and who in your space may open it. Details:
[Is my data safe?](/veyq/is-my-data-safe/).
