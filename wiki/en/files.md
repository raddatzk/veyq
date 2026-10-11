<!-- speaks-for: docs/features/files/use-cases/uc.files.member-files.md, docs/features/memory/use-cases/uc.memory.move-node.md, docs/features/memory/use-cases/uc.memory.delete-node.md, docs/features/memory/use-cases/uc.memory.browse.md; pair: de/dateien.md -->
# Files — and finding your way around

A plain-language companion to [Is my data safe?](is-my-data-safe.md). veyq is a platform for sealed, shareable
things, and it grows by **applications**. **Memories** is the one for photos, videos and journals. **Files** is
the one for everything else: documents, PDFs, any file you want to keep. Both live in the same folders of your
space, side by side.

## What you can do with a file

- **Upload** — pick files or drop them onto a folder. Uploads run in the background, and the status menu in the
  top bar shows how far they are.
- **Download** — you get the file back exactly as you put it in, under the name it has in veyq.
- **Replace** — put a newer version of a file in its place. The name stays. veyq **asks first**, because there is
  **no version history**: the old content cannot be brought back. Uploading a file whose name is already taken in
  that folder asks the same question.
- **Rename and move** — rename a file in its row. **Move to…** opens a list of places; pick one and the file goes
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

## Grid and list

In a folder you can switch between **Grid view** and **List view** with the buttons at the top. The choice is
yours and is remembered; the same switch works for everything in the folder.

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
