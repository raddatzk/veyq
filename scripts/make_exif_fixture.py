#!/usr/bin/env python3
"""Generate the EXIF/XMP/GPS fixture photos the web suite parses with the REAL EXIF reader (`ExifReader`).

Why generated fixtures and not real photos: a third-party photo carries a licence question and someone's
metadata; a generated one is reproducible, auditable byte for byte, and carries exactly the tags the client
claims to read — which is the point. The suite mocks the reader seam (`shared/image/exifTags.ts`) everywhere
else, so it proves our *mapping* and never that the library yields a tag from the bytes. These files close that gap.

**No third-party import, deliberately.** Pillow/piexif would make this shorter and would also make the fixtures
un-regenerable on a machine without them. Everything below is the container specs written out around ONE TIFF
block (IFD0 → Exif IFD + GPS IFD): a JPEG (a baseline body carried as a constant, plus two APP1 segments, Exif then
XMP), a bare TIFF in either byte order, a PNG (`eXIf` + an XMP `iTXt`), a WebP (`VP8X` + `EXIF` + `XMP `), and three
ISO-BMFF files (HEIF, ISO/IEC 23008-12) whose `meta` box carries the TIFF as an `Exif` item and no image item: an
AVIF, a HEIC with an ordinary 40-byte `ftyp`, and a HEIC whose `ftyp` is 52 bytes — the length iOS 18 writes on an
adaptive-HDR photo, which exifr refused (it gave up on any `ftyp` over 50 bytes, inbox #799). The brand lists are
synthetic, not copied from a camera; only their count matters. No image item, so a decoder shows nothing; the readers
need none. Two more JPEGs carry each second-choice source the client falls back to: `-fallbacks` (IFD0 size and date,
lens make, XMP software and rating, the southern and western hemispheres, below sea level) and `-iptc` (the digitised
date, an IPTC object name).

Re-run after changing which tags the client reads:

    python3 scripts/make_exif_fixture.py

Output: src/web/src/features/memories/__fixtures__/exif-sample{,-fallbacks,-iptc}.jpg, exif-sample{,-le}.tif,
exif-sample.{png,webp,avif,heic}, exif-sample-ftyp52.heic
"""
from __future__ import annotations

import struct
import zlib

import repo_layout  # where the code surfaces live (ADR 0106)

FIXTURES = repo_layout.WEB / "src/features/memories/__fixtures__"

# A 1x1 greyscale baseline JPEG (SOI … EOI). Produced once with Pillow
# (`Image.new('L', (1, 1), 128).save(buf, 'JPEG', quality=25)`) and frozen here so this script needs nothing
# installed. Only the pixel data matters to a decoder; every tag below rides in the APP1 segments we splice in.
BASE_JPEG = bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb0043002016181c1814201c1a1c24222026305034302c2c3062464a3a50"
    "74667a787266706e8090b89c8088ae8a6e70a0daa2aebec4ced0ce7c9ae2f2e0c8f0b8cacec6ffc0000b08000100010101"
    "1100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504"
    "040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a16171819"
    "1a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a8384858687"
    "88898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3"
    "e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f002bffd9"
)

# TIFF field types (TIFF 6.0 §2). Only the ones this fixture needs.
BYTE, ASCII, SHORT, LONG, RATIONAL, SRATIONAL = 1, 2, 3, 4, 5, 10
TYPE_SIZE = {BYTE: 1, ASCII: 1, SHORT: 2, LONG: 4, RATIONAL: 8, SRATIONAL: 8}


def _payload(ftype: int, value, bo: str) -> tuple[int, bytes]:
    """(count, raw bytes) for one field's value, in byte order `bo` (">" big-endian, "<" little-endian)."""
    if ftype == ASCII:
        raw = value.encode("ascii") + b"\x00"
        return len(raw), raw
    if ftype == BYTE:
        vals = value if isinstance(value, (list, tuple)) else [value]
        return len(vals), bytes(vals)
    if ftype == SHORT:
        vals = value if isinstance(value, (list, tuple)) else [value]
        return len(vals), b"".join(struct.pack(bo + "H", v) for v in vals)
    if ftype == LONG:
        vals = value if isinstance(value, (list, tuple)) else [value]
        return len(vals), b"".join(struct.pack(bo + "I", v) for v in vals)
    if ftype in (RATIONAL, SRATIONAL):
        pairs = value if isinstance(value[0], (list, tuple)) else [value]
        fmt = bo + ("II" if ftype == RATIONAL else "ii")
        return len(pairs), b"".join(struct.pack(fmt, n, d) for n, d in pairs)
    raise ValueError(f"unsupported type {ftype}")


def build_ifd(fields: list[tuple[int, int, object]], ifd_offset: int, bo: str, next_ifd: int = 0) -> bytes:
    """One IFD: count, sorted entries, next-IFD pointer, then the values too large to sit inline.

    `ifd_offset` is this IFD's own offset from the TIFF header start, because every value pointer in TIFF is
    relative to that header and not to the file.
    """
    fields = sorted(fields, key=lambda f: f[0])
    entries_len = 2 + len(fields) * 12 + 4
    out_entries, out_values = b"", b""
    for tag, ftype, value in fields:
        count, raw = _payload(ftype, value, bo)
        size = count * TYPE_SIZE[ftype]
        if size <= 4:
            inline = raw + b"\x00" * (4 - size)
            out_entries += struct.pack(bo + "HHI", tag, ftype, count) + inline
        else:
            pointer = ifd_offset + entries_len + len(out_values)
            out_entries += struct.pack(bo + "HHI", tag, ftype, count) + struct.pack(bo + "I", pointer)
            out_values += raw
            if len(raw) % 2:  # keep the value area 2-byte aligned
                out_values += b"\x00"
    return struct.pack(bo + "H", len(fields)) + out_entries + struct.pack(bo + "I", next_ifd) + out_values


def build_tiff(ifd0_fields: list, exif_fields: list, gps_fields: list, bo: str = ">") -> bytes:
    """A TIFF block: a TIFF header (big-endian `MM` unless `bo` is "<", `II`), then IFD0 → ExifIFD + GPSIFD (each sub-IFD only when it has fields)."""
    # Laid out in a fixed order so the sub-IFD offsets are known before IFD0 is serialised: IFD0 first, then
    # the Exif IFD, then the GPS IFD. IFD0 carries the pointers as placeholders that are filled once the sizes are.
    ifd0_fields = list(ifd0_fields)
    if exif_fields:
        ifd0_fields.append((0x8769, LONG, 0))  # ExifIFDPointer — patched below
    if gps_fields:
        ifd0_fields.append((0x8825, LONG, 0))  # GPSInfoIFDPointer — patched below

    header = (b"MM\x00\x2a" if bo == ">" else b"II\x2a\x00") + struct.pack(bo + "I", 8)  # IFD0 at offset 8
    ifd0_len = len(build_ifd(ifd0_fields, 8, bo))
    exif_offset = 8 + ifd0_len
    exif_ifd = build_ifd(exif_fields, exif_offset, bo) if exif_fields else b""
    gps_offset = exif_offset + len(exif_ifd)
    gps_ifd = build_ifd(gps_fields, gps_offset, bo) if gps_fields else b""

    ifd0_fields = [(t, ty, exif_offset if t == 0x8769 else gps_offset if t == 0x8825 else v)
                   for t, ty, v in ifd0_fields]
    ifd0 = build_ifd(ifd0_fields, 8, bo)
    assert len(ifd0) == ifd0_len, "patching the pointers must not resize IFD0"
    return header + ifd0 + exif_ifd + gps_ifd


def sample_tiff(bo: str = ">") -> bytes:
    """The main sample: every tag the client reads, each from its first-choice source."""
    exif_fields = [
        (0x829A, RATIONAL, (1, 500)),        # ExposureTime — 1/500 s
        (0x829D, RATIONAL, (18, 10)),        # FNumber — f/1.8
        (0x8827, SHORT, 200),                # ISO
        (0x9003, ASCII, "2021:07:15 18:04:05"),  # DateTimeOriginal
        (0x9204, SRATIONAL, (-7, 10)),       # ExposureBiasValue — -0.7 EV, the SIGNED type is the point
        (0x9209, SHORT, 9),                  # Flash — bitfield: 0x1 fired | 0x8 compulsory
        (0x920A, RATIONAL, (50, 1)),         # FocalLength — 50 mm
        (0xA002, LONG, 4032),                # ExifImageWidth
        (0xA003, LONG, 3024),                # ExifImageHeight
        (0xA405, SHORT, 75),                 # FocalLengthIn35mmFormat
        (0xA434, ASCII, "RF 50mm F1.8 STM"), # LensModel
    ]
    gps_fields = [
        (0x0000, BYTE, [2, 3, 0, 0]),        # GPSVersionID
        (0x0001, ASCII, "N"),                # GPSLatitudeRef
        (0x0002, RATIONAL, [(48, 1), (8, 1), (13, 1)]),   # GPSLatitude — 48°8'13"
        (0x0003, ASCII, "E"),                # GPSLongitudeRef
        (0x0004, RATIONAL, [(11, 1), (34, 1), (30, 1)]),  # GPSLongitude — 11°34'30"
        (0x0005, BYTE, 0),                   # GPSAltitudeRef — 0 = above sea level
        (0x0006, RATIONAL, (5194, 10)),      # GPSAltitude — 519.4 m
        (0x0010, ASCII, "T"),                # GPSImgDirectionRef — true north
        (0x0011, RATIONAL, (2153, 10)),      # GPSImgDirection — 215.3°
        (0x001F, RATIONAL, (48, 10)),        # GPSHPositioningError — 4.8 m
    ]
    ifd0_fields = [
        (0x010E, ASCII, "Marienplatz at golden hour"),  # ImageDescription
        (0x010F, ASCII, "Canon"),            # Make
        (0x0110, ASCII, "EOS R"),            # Model
        (0x0112, SHORT, 6),                  # Orientation — 6 = rotate 90° CW
        (0x0131, ASCII, "veyq fixture 1.0"), # Software
        (0x013B, ASCII, "A. Photographer"),  # Artist
        (0x4746, SHORT, 4),                  # Rating — 0..5
        (0x8298, ASCII, "(c) 2021 A. Photographer"),  # Copyright
    ]
    return build_tiff(ifd0_fields, exif_fields, gps_fields, bo)


def fallbacks_tiff() -> bytes:
    """The second-choice sources, each where its first choice is missing — and the hemispheres the main one lacks."""
    ifd0_fields = [
        (0x0100, LONG, 640),                 # ImageWidth — the size, with no Exif PixelXDimension
        (0x0101, LONG, 480),                 # ImageLength (exifr: ImageHeight)
        (0x010F, ASCII, "Nikon"),            # Make
        (0x0132, ASCII, "2020:01:02 03:04:05"),  # DateTime (exifr: ModifyDate) — the third date choice
    ]
    exif_fields = [
        (0xA433, ASCII, "Nikkor"),           # LensMake — no LensModel
    ]
    gps_fields = [
        (0x0001, ASCII, "S"),                # GPSLatitudeRef — south: negative
        (0x0002, RATIONAL, [(33, 1), (52, 1), (4, 1)]),   # GPSLatitude — 33°52'4"
        (0x0003, ASCII, "W"),                # GPSLongitudeRef — west: negative
        (0x0004, RATIONAL, [(70, 1), (40, 1), (12, 1)]),  # GPSLongitude — 70°40'12"
        (0x0005, BYTE, 1),                   # GPSAltitudeRef — 1 = below sea level
        (0x0006, RATIONAL, (125, 10)),       # GPSAltitude — 12.5 m
    ]
    return build_tiff(ifd0_fields, exif_fields, gps_fields)


def iptc_tiff() -> bytes:
    """Only the second date choice — the rest of what this file says sits in IPTC."""
    return build_tiff([], [(0x9004, ASCII, "2019:03:04 05:06:07")], [])  # DateTimeDigitized (exifr: CreateDate)


EXIF_HEADER = b"Exif\x00\x00"  # what precedes the TIFF block in a JPEG APP1 and in a HEIF Exif item


XMP_PACKET = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:xmp="http://ns.adobe.com/xap/1.0/">
   <xmp:Rating>4</xmp:Rating>
   <xmp:CreatorTool>veyq fixture 1.0</xmp:CreatorTool>
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Marienplatz</rdf:li></rdf:Alt></dc:title>
   <dc:description><rdf:Alt><rdf:li xml:lang="x-default">Marienplatz at golden hour</rdf:li></rdf:Alt></dc:description>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

# The fallbacks' XMP: no title or description, a rating only here, the software only here — and `dc:creator` and
# `dc:rights`, which no reader maps (exifr never did: its keys are `creator`/`rights`, the code once asked for
# `Creator`/`Rights`), so the fixture pins that they stay out.
XMP_FALLBACKS = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:xmp="http://ns.adobe.com/xap/1.0/">
   <xmp:Rating>3</xmp:Rating>
   <xmp:CreatorTool>darktable 4.8</xmp:CreatorTool>
   <dc:creator><rdf:Seq><rdf:li>B. Editor</rdf:li></rdf:Seq></dc:creator>
   <dc:rights><rdf:Alt><rdf:li xml:lang="x-default">(c) B. Editor</rdf:li></rdf:Alt></dc:rights>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""


def iptc_app13(datasets: list[tuple[int, str]]) -> bytes:
    """An APP13 payload: "Photoshop 3.0", one 8BIM resource 0x0404 holding IPTC-IIM record-2 datasets (IIM 4.2)."""
    iim = b"".join(b"\x1c\x02" + bytes([ds]) + struct.pack(">H", len(v.encode("utf-8"))) + v.encode("utf-8")
                   for ds, v in datasets)
    resource = b"8BIM" + struct.pack(">H", 0x0404) + b"\x00\x00" + struct.pack(">I", len(iim)) + iim
    if len(iim) % 2:
        resource += b"\x00"
    return b"Photoshop 3.0\x00" + resource


def app13(payload: bytes) -> bytes:
    return b"\xff\xed" + struct.pack(">H", len(payload) + 2) + payload


def app1(payload: bytes) -> bytes:
    """Wrap a payload in an APP1 marker segment (length covers itself, hence +2)."""
    if len(payload) + 2 > 0xFFFF:
        raise ValueError("APP1 payload does not fit one segment")
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def build_jpeg(tiff: bytes, xmp: str | None = XMP_PACKET, extra: bytes = b"") -> bytes:
    segments = app1(EXIF_HEADER + tiff)
    if xmp is not None:
        segments += app1(b"http://ns.adobe.com/xap/1.0/\x00" + xmp.encode("utf-8"))
    segments += extra
    # APP1 goes directly after SOI, before the JFIF APP0 the base body already carries; decoders and EXIF readers
    # scan the marker chain, so the order is only a convention — but "Exif first" is what cameras write.
    return BASE_JPEG[:2] + segments + BASE_JPEG[2:]


def png_chunk(kind: bytes, data: bytes) -> bytes:
    """One PNG chunk: length, type, data, CRC-32 over type + data (PNG spec §5.3)."""
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def build_png(tiff: bytes) -> bytes:
    """A 1x1 greyscale PNG carrying the TIFF block as `eXIf` (PNG 1.5 extension) and the XMP as an `iTXt`."""
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)  # 1x1, 8-bit, greyscale, deflate, no filter set, no interlace
    # iTXt: keyword, NUL, compression flag + method (0 = uncompressed), empty language tag + NUL, empty translation + NUL
    itxt = b"XML:com.adobe.xmp\x00" + b"\x00\x00" + b"\x00" + b"\x00" + XMP_PACKET.encode("utf-8")
    idat = zlib.compress(b"\x00\x80")  # one scanline: filter byte 0, one grey pixel
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr) + png_chunk(b"eXIf", tiff) + png_chunk(b"iTXt", itxt)
            + png_chunk(b"IDAT", idat) + png_chunk(b"IEND", b""))


# The VP8L bitstream of a 1x1 lossless WebP — the image every WebP feature probe decodes
# (`UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==`): signature 0x2f, then 14-bit width-1 and height-1 = 0.
VP8L_1X1 = bytes.fromhex("2f00000010071011118888fe07")


def riff_chunk(kind: bytes, data: bytes) -> bytes:
    """One RIFF chunk: FourCC, little-endian size, data, padded to an even length."""
    return kind + struct.pack("<I", len(data)) + data + (b"\x00" if len(data) % 2 else b"")


def build_webp(tiff: bytes) -> bytes:
    """An extended-format WebP (`VP8X` flags EXIF + XMP), its image, then the `EXIF` (raw TIFF) and `XMP ` chunks."""
    flags = 0x08 | 0x04  # EXIF present | XMP present
    vp8x = bytes([flags, 0, 0, 0]) + (0).to_bytes(3, "little") + (0).to_bytes(3, "little")  # canvas 1x1 (minus one)
    body = (b"WEBP" + riff_chunk(b"VP8X", vp8x) + riff_chunk(b"VP8L", VP8L_1X1) + riff_chunk(b"EXIF", tiff)
            + riff_chunk(b"XMP ", XMP_PACKET.encode("utf-8")))
    return b"RIFF" + struct.pack("<I", len(body)) + body


def box(kind: bytes, payload: bytes) -> bytes:
    """One ISO-BMFF box (ISO/IEC 14496-12 §4.2): 32-bit size, FourCC, payload."""
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full_box(kind: bytes, version: int, payload: bytes) -> bytes:
    """A FullBox: the box header, then version (8 bits) and flags (24 bits, all zero here)."""
    return box(kind, struct.pack(">I", version << 24) + payload)


def build_heif(major: bytes, compatible: list[bytes], tiff: bytes) -> bytes:
    """ftyp, then a `meta` whose one item is an `Exif` item located (by `iloc`) in the trailing `mdat`.

    The item's data is HEIF's Exif block (ISO/IEC 23008-12 Annex A.2.1): a 32-bit offset from its end to the TIFF
    header, here 6 — past the "Exif\\0\\0" Apple writes there too.
    """
    ftyp = box(b"ftyp", major + struct.pack(">I", 0) + b"".join(compatible))
    exif_item = struct.pack(">I", len(EXIF_HEADER)) + EXIF_HEADER + tiff
    hdlr = full_box(b"hdlr", 0, struct.pack(">I", 0) + b"pict" + b"\x00" * 12 + b"\x00")
    infe = full_box(b"infe", 2, struct.pack(">HH", 1, 0) + b"Exif" + b"\x00")  # item 1, unprotected, type Exif, no name
    iinf = full_box(b"iinf", 0, struct.pack(">H", 1) + infe)

    def iloc(extent_offset: int) -> bytes:
        # version 0: offset_size 4 | length_size 4, base_offset_size 0 | reserved 0, one item with one extent
        return full_box(b"iloc", 0, bytes([0x44, 0x00]) + struct.pack(">HHHHII", 1, 1, 0, 1, extent_offset, len(exif_item)))

    def meta(extent_offset: int) -> bytes:
        return full_box(b"meta", 0, hdlr + iinf + iloc(extent_offset))

    # The extent offset is absolute in the file and the `iloc` that holds it has a fixed size, so measure first.
    mdat_payload_offset = len(ftyp) + len(meta(0)) + 8
    return ftyp + meta(mdat_payload_offset) + box(b"mdat", exif_item)


# The compatible brands: an ordinary iPhone-like set (6 brands → a 40-byte ftyp, which exifr read), and the same set
# grown by three (→ 52 bytes, which exifr refused). Both name `heic`, so exifr's brand check is not what decides.
HEIC_BRANDS = [b"mif1", b"MiHE", b"MiPr", b"miaf", b"MiHB", b"heic"]
HEIC_BRANDS_52 = HEIC_BRANDS + [b"tmap", b"MiHA", b"MiHM"]


def main() -> None:
    tiff = sample_tiff()
    iptc = iptc_app13([(5, "Hofgarten"), (120, "Hofgarten in spring")])  # ObjectName, Caption-Abstract
    outputs = {
        "exif-sample.jpg": build_jpeg(tiff),
        "exif-sample-fallbacks.jpg": build_jpeg(fallbacks_tiff(), XMP_FALLBACKS),
        "exif-sample-iptc.jpg": build_jpeg(iptc_tiff(), None, app13(iptc)),
        "exif-sample.tif": tiff,
        "exif-sample-le.tif": sample_tiff("<"),  # little-endian (`II`), as most cameras write it; Apple writes `MM`
        "exif-sample.png": build_png(tiff),
        "exif-sample.webp": build_webp(tiff),
        "exif-sample.avif": build_heif(b"avif", [b"avif", b"mif1", b"miaf"], tiff),
        "exif-sample.heic": build_heif(b"heic", HEIC_BRANDS, tiff),
        "exif-sample-ftyp52.heic": build_heif(b"heic", HEIC_BRANDS_52, tiff),
    }
    assert struct.unpack(">I", outputs["exif-sample-ftyp52.heic"][:4])[0] == 52, "the iOS 18 case needs a 52-byte ftyp"
    assert struct.unpack(">I", outputs["exif-sample.heic"][:4])[0] == 40
    FIXTURES.mkdir(parents=True, exist_ok=True)
    for name, data in outputs.items():
        (FIXTURES / name).write_bytes(data)
        print(f"wrote {FIXTURES / name} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
