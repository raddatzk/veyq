#!/usr/bin/env python3
"""Generate the EXIF/XMP/GPS fixture photo the web suite parses with the REAL `exifr`.

Why a generated fixture and not a real photo: a third-party photo carries a licence question and someone's
metadata; a generated one is reproducible, auditable byte for byte, and carries exactly the tags the client
claims to read — which is the point. The suite mocks `exifr` everywhere else, so it proves our *mapping* and
never that the library yields a tag under the options we pass. This file is what closes that gap.

**No third-party import, deliberately.** Pillow/piexif would make this shorter and would also make the fixture
un-regenerable on a machine without them. Everything below is the JPEG/TIFF spec written out: a baseline JPEG
body carried as a constant, plus two APP1 segments built here (Exif, then XMP).

Re-run after changing which tags the client reads:

    python3 scripts/make_exif_fixture.py

Output: web/src/features/memory/__fixtures__/exif-sample.jpg
"""
from __future__ import annotations

import struct
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "web/src/features/memory/__fixtures__/exif-sample.jpg"

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


def _payload(ftype: int, value) -> tuple[int, bytes]:
    """(count, raw bytes) for one field's value, big-endian."""
    if ftype == ASCII:
        raw = value.encode("ascii") + b"\x00"
        return len(raw), raw
    if ftype == BYTE:
        vals = value if isinstance(value, (list, tuple)) else [value]
        return len(vals), bytes(vals)
    if ftype == SHORT:
        vals = value if isinstance(value, (list, tuple)) else [value]
        return len(vals), b"".join(struct.pack(">H", v) for v in vals)
    if ftype == LONG:
        vals = value if isinstance(value, (list, tuple)) else [value]
        return len(vals), b"".join(struct.pack(">I", v) for v in vals)
    if ftype in (RATIONAL, SRATIONAL):
        pairs = value if isinstance(value[0], (list, tuple)) else [value]
        fmt = ">II" if ftype == RATIONAL else ">ii"
        return len(pairs), b"".join(struct.pack(fmt, n, d) for n, d in pairs)
    raise ValueError(f"unsupported type {ftype}")


def build_ifd(fields: list[tuple[int, int, object]], ifd_offset: int, next_ifd: int = 0) -> bytes:
    """One IFD: count, sorted entries, next-IFD pointer, then the values too large to sit inline.

    `ifd_offset` is this IFD's own offset from the TIFF header start, because every value pointer in TIFF is
    relative to that header and not to the file.
    """
    fields = sorted(fields, key=lambda f: f[0])
    entries_len = 2 + len(fields) * 12 + 4
    out_entries, out_values = b"", b""
    for tag, ftype, value in fields:
        count, raw = _payload(ftype, value)
        size = count * TYPE_SIZE[ftype]
        if size <= 4:
            inline = raw + b"\x00" * (4 - size)
            out_entries += struct.pack(">HHI", tag, ftype, count) + inline
        else:
            pointer = ifd_offset + entries_len + len(out_values)
            out_entries += struct.pack(">HHI", tag, ftype, count) + struct.pack(">I", pointer)
            out_values += raw
            if len(raw) % 2:  # keep the value area 2-byte aligned
                out_values += b"\x00"
    return struct.pack(">H", len(fields)) + out_entries + struct.pack(">I", next_ifd) + out_values


def build_exif_app1() -> bytes:
    """The Exif APP1 payload: "Exif\\0\\0", a big-endian TIFF header, then IFD0 → ExifIFD + GPSIFD."""
    # Laid out in a fixed order so the sub-IFD offsets are known before IFD0 is serialised: IFD0 first, then
    # the Exif IFD, then the GPS IFD. IFD0 carries two placeholder pointers that are filled once the sizes are.
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
        (0x8769, LONG, 0),                   # ExifIFDPointer — patched below
        (0x8825, LONG, 0),                   # GPSInfoIFDPointer — patched below
    ]

    header = b"MM\x00\x2a" + struct.pack(">I", 8)  # big-endian, IFD0 at offset 8
    ifd0_len = len(build_ifd(ifd0_fields, 8))
    exif_offset = 8 + ifd0_len
    exif_ifd = build_ifd(exif_fields, exif_offset)
    gps_offset = exif_offset + len(exif_ifd)
    gps_ifd = build_ifd(gps_fields, gps_offset)

    ifd0_fields = [(t, ty, exif_offset if t == 0x8769 else gps_offset if t == 0x8825 else v)
                   for t, ty, v in ifd0_fields]
    ifd0 = build_ifd(ifd0_fields, 8)
    assert len(ifd0) == ifd0_len, "patching the pointers must not resize IFD0"
    return b"Exif\x00\x00" + header + ifd0 + exif_ifd + gps_ifd


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


def app1(payload: bytes) -> bytes:
    """Wrap a payload in an APP1 marker segment (length covers itself, hence +2)."""
    if len(payload) + 2 > 0xFFFF:
        raise ValueError("APP1 payload does not fit one segment")
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def main() -> None:
    segments = app1(build_exif_app1()) + app1(b"http://ns.adobe.com/xap/1.0/\x00" + XMP_PACKET.encode("utf-8"))
    # APP1 goes directly after SOI, before the JFIF APP0 the base body already carries; decoders and exifr both
    # scan the marker chain, so the order is only a convention — but "Exif first" is what cameras write.
    out = BASE_JPEG[:2] + segments + BASE_JPEG[2:]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(out)
    print(f"wrote {OUT} ({len(out)} bytes)")


if __name__ == "__main__":
    main()
