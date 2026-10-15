---
title: "Download and import"
sources:
  - docs/features/platform/use-cases/uc.platform.export-import.md
---

A plain-language companion to [Is my data safe?](/veyq/is-my-data-safe/). You can download a folder, a file, a Memory or a drop
box from veyq — a file as itself, anything bigger as **one ZIP file** — and bring such a ZIP back in, into the same veyq or
another one.

## What you can do

- **Download.** Open the ⋯ menu of a folder, a Memory or a drop box (or the page of one) and choose **Download**. veyq
  gathers what is inside, tells you how much it is, and lets you pick where to save the ZIP. A file's **Download** saves the
  file itself. A drop box comes with a folder for each person who sent something, named as the drop box shows them (two
  people called Anna: "Anna" and "Anna (2)").
- **Unpack it like any ZIP.** Inside you find your folders as folders and your files and photos under their own names, in
  their original form. Names that some computers do not allow (a `:` or a `?`, for example) are changed slightly so the ZIP
  opens everywhere; two names that differ only in capital letters become "Name" and "Name (2)". Beside your files sits a small
  hidden folder, `.veyq`, with a list of what is in the ZIP — dates, places, chapters, covers. That list is what lets veyq
  bring the ZIP back in.
- **Import.** Upload the ZIP into a folder (**New → Upload files**, or drop it onto the folder): veyq recognises a veyq
  download by what is inside it, not by its name, and asks you whether to **Import** it or **Upload it as a file**. You can
  also bring in the **unpacked** folder — the one holding `.veyq` — with **New → Upload folder…** or by dropping it, and import
  that. Before anything is created you see what would be created and where. If a name is already taken, the new one becomes
  "Name (2)". A drop box comes back as an ordinary folder with a folder for each person in it: its links and the people who
  sent something cannot come back with it.
- **Any other folder** you choose with **New → Upload folder…** (or drop) is simply uploaded, as folders and files.
- **From a newer veyq, or with things this veyq does not know.** If the ZIP holds something this veyq cannot bring back in —
  an application it does not have, or something saved by a newer version — veyq lists it before it starts. You can
  **Import the rest** (what is listed is left out, and named once the import is done) or **Cancel**, which changes nothing.
- **People you share with** can download too: whoever may view a shared folder or Memory can download it - and a drop box in
  it - as a ZIP. Nobody imports through a share link: someone who may add to a shared folder can upload a folder, and it
  arrives as folders and files, even an unpacked download. Someone who only sends files into a drop box downloads nothing.

## Good to know

- **The ZIP is not encrypted** — exactly like a photo you download. Everything in it is readable by whoever holds the file,
  so keep it somewhere you trust. The server never sees a download: your device decrypts and packs it, and only your device
  unpacks and encrypts an import.
- **Places in photos.** A photo keeps its own location tags in the ZIP; veyq does not rewrite your originals.
- **A damaged or changed ZIP is refused**, with the reason, before anything is created.
- **After an import everything is yours.** You are the owner and uploader of the imported items. Shares, members and the trash
  do not travel with a download. The people named on a Memory come along as plain names in the list, but are not set again.
- **Big downloads.** The ZIP is written straight to disk, so size is no problem on a computer with a Chromium-based browser
  (Chrome, Edge); other browsers download it through a built-in route, and where neither works veyq tells you before it starts.
- **Enough room?** Before importing, and before uploading a folder, veyq asks the server whether it still fits your space's storage. If it does not, it tells you and does not start. The answer is only yes or no — nobody learns how much space is left.
- **If an import stops** (for example because your storage is full), what it created is moved to the trash and you are told
  how far it got. A folder upload is an ordinary upload instead: a file that fails is shown as failed, the rest arrives.
