<!-- speaks-for: docs/features/platform/use-cases/uc.platform.export-import.md; pair: de/export-und-import.md -->
# Export and import

A plain-language companion to [Is my data safe?](is-my-data-safe.md). You can take a folder, a file or a Memory out of veyq
as **one ZIP file** — and bring such a file back in, into the same veyq or another one.

## What you can do

- **Export.** Open the ⋯ menu of a folder, file or Memory (or the page of one) and choose **Export**. veyq gathers what is
  inside, tells you how much it is, and lets you pick where to save the ZIP. A folder brings everything under it; a Memory
  brings its photos and videos in their original form, with their dates and places, its chapters and its cover.
- **Import.** In a folder choose **New → Import an export…**, or drop the file onto the folder. veyq recognises a veyq export
  by what is inside it, not by its name, shows you what would be created and where, and then does it. If a name is already
  taken, the new one becomes "Name (2)".

## Good to know

- **The ZIP is not encrypted.** Everything in it is readable by whoever holds the file. Keep it somewhere you trust. The
  server never sees an export: your device decrypts and packs it, and only your device unpacks and encrypts an import.
- **Places in photos.** The option **Include places and coordinates** controls what veyq adds to the export. The location
  tags *inside* a photo file stay in the photo — veyq does not rewrite your originals.
- **After an import everything is yours.** You are the owner and uploader of the imported items. Shares, members and the trash
  do not travel with an export. The people named on a Memory come along as plain names in the file, but are not set again.
- **Big exports.** The ZIP is written straight to disk, so size is no problem on a computer with a Chromium-based browser
  (Chrome, Edge); other browsers download it through a built-in route, and where neither works veyq tells you before it starts.
- **Enough room?** Before importing, veyq asks the server whether the import still fits your space's storage. If it does not, it tells you and does not start. The answer is only yes or no — nobody learns how much space is left.
- **If an import stops** (for example because your storage is full), what it created is moved to the trash and you are told
  how far it got.
- This has been tried with a Memory of two photos in a real browser. Large exports and photos with GPS tags have not been tried yet.
