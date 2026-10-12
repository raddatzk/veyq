<!-- speaks-for: docs/features/platform/use-cases/uc.platform.node-cover.md, docs/features/files/use-cases/uc.files.member-files.md, docs/features/memory/use-cases/uc.memory.move-node.md, docs/features/memory/use-cases/uc.memory.delete-node.md, docs/features/memory/use-cases/uc.memory.browse.md; pair: de/dateien.md -->
# Files — and finding your way around

A plain-language companion to [Is my data safe?](is-my-data-safe.md). veyq is a platform for sealed, shareable
things, and it grows by **applications**. **Memories** is the one for photos, videos and journals. **Files** is
the one for everything else: documents, PDFs, any file you want to keep. Both live in the same folders of your
space, side by side.

## What you can do with a file

- **Upload** — choose **New → Upload files**, or drop files onto the folder. Uploads run in the background, and the
  status menu in the top bar shows how far they are.
- **Download** — the download button on the file's row gives you the file back exactly as you put it in, under the
  name it has in veyq. Clicking the row itself opens the file's own page.
- **Replace** — put a newer version of a file in its place. The name stays. veyq **asks first**, because there is
  **no version history**: the old content cannot be brought back. Uploading a file whose name is already taken in
  that folder asks the same question.
- **Rename and move** — both are in the **⋯** menu of the file's row. **Move to…** opens a list of places; pick one and the file goes
  there (more on moving below).
- **Delete, with an undo** — a deleted file goes to the **trash**, and a message at the time offers **Undo** right
  away. Later you can still restore it from the trash until it is removed for good; the trash shows the date. Space
  in the trash still counts against your storage until the trash is emptied.
- **Share** — a whole folder, or a single file. See [Sharing](sharing.md).

If someone else in your space replaced the same file a moment before you, veyq tells you instead of silently
overwriting their version, and you can choose **Replace anyway**.

A name has to be free in its folder: a file cannot share a name with a folder, a Memory or a drop box that is
already there. veyq skips such a file and tells you which item has the name.

**Big files** are downloaded piece by piece and put back together on your device. Browsers differ in how well
they cope with very large files: where your browser can save straight to a file you choose, that is the best case.
Where it cannot, a very large file may be refused with a message instead of failing halfway.

## Moving things

**Move to…** works the same on a folder, a Memory, a chapter, a file or a drop box: choose the new place from a
list, and everything inside comes along and stays readable. If somebody shared a
folder with you and lets you contribute, you can move things around **inside** that folder — but not out of it.

## One list for everything in a folder

A folder shows **one list** of everything in it: sub-folders, Memories, files and drop boxes together. Folders come
first; everything else follows in alphabetical order, whatever it is (numbers count as numbers, so *Photo 2* comes
before *Photo 10*). Each entry shows what it is — for example *File · 157 B · modified …* or *Memory · June 2024* —
and has its own buttons: the most useful one (for a file, download) sits on the entry, the rest are in its **⋯** menu.
Every entry — folder, Memory, file or drop box — also has a **heart**: it adds the entry to your **favorites** in the
left-hand navigation. Your favorites are yours alone; other members of the space cannot see them, and neither can the
server. Where you may not do something, its button is simply not there (people you share a link with get no heart).

When a folder holds more than one kind of thing, tabs above the list — **All**, **Memories**, **Files**, **Drop
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
[Is my data safe?](is-my-data-safe.md).
