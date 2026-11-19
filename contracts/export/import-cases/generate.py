#!/usr/bin/env python3
"""The import cases both clients answer to (ADR 0152 decision 4): `cases.v1.json` beside this file.

Each case is a download - a store-only ZIP (base64) or the same download unpacked into a folder (a listing of paths and
bytes) - with the verdict a reader of `contracts/export/veyq-subtree.schema.json` owes it: whether an upload offers it as
an import (`screen`), and then either the dry run's totals (`plan`) or the refusal's code and the stage that refuses it
(`refused`, `at`: `open` - the archive and the manifest, before anything is planned; `plan` - a kind's own content check;
`import` - a payload's checksum, met when its hook asks for it, or a plan that holds nothing).

Written with Python's standard library only, independent of both clients (the web's `core/export` and the core's
`export/`), the form of the crypto vectors and the search vectors (ADR 0146). The ZIPs are written field for field as
both clients' writers write them (a data descriptor after each entry, ZIP64 exactly where a field overflows or where a
case asks for it), then a case may damage one field. The kinds are the ones both clients build: `FOLDER`, Depot's `FILE`
(`depot.file`) and `DEPOT_DROPBOX` (`depot.drop-box`); a kind of an application neither has stands for "unknown".

    python3 contracts/export/import-cases/generate.py          # writes cases.v1.json
    python3 contracts/export/import-cases/generate.py --check  # exits 1 unless the file is what this writes, byte for byte

One refusal of the web's own hardening tests is not a case: more nodes than `MAX_NODES` (200 000) needs a manifest of
megabytes; each client keeps that one crafted in its own test.
"""
from __future__ import annotations

import base64
import hashlib
import json
import struct
import sys
import unicodedata
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "cases.v1.json"

SIG_LOCAL = 0x04034B50
SIG_DESCRIPTOR = 0x08074B50
SIG_CENTRAL = 0x02014B50
SIG_EOCD = 0x06054B50
SIG_EOCD64 = 0x06064B50
SIG_LOCATOR64 = 0x07064B50
FLAG_DESCRIPTOR = 0x0008
FLAG_UTF8 = 0x0800
U32 = 0xFFFFFFFF
U16 = 0xFFFF
# 2026-10-14 00:00:00, as DOS time and date
DOS_TIME = 0
DOS_DATE = ((2026 - 1980) << 9) | (10 << 5) | 14


class Zip:
    """A store-only ZIP written as both clients write one; `zip64_at` lowers the ZIP64 threshold (1: every entry)."""

    def __init__(self, zip64_at: int = U32) -> None:
        self.out = bytearray()
        self.entries: list[dict] = []
        self.zip64_at = zip64_at

    def file(self, path: str, data: bytes, name_bytes: bytes | None = None) -> None:
        name = name_bytes if name_bytes is not None else path.encode("utf-8")
        size = len(data)
        zip64 = size >= self.zip64_at
        offset = len(self.out)
        head = struct.pack(
            "<IHHHHHIIIHH", SIG_LOCAL, 45 if zip64 else 20, FLAG_DESCRIPTOR | FLAG_UTF8, 0, DOS_TIME, DOS_DATE, 0,
            U32 if zip64 else 0, U32 if zip64 else 0, len(name), 20 if zip64 else 0,
        ) + name
        if zip64:
            head += struct.pack("<HHQQ", 1, 16, 0, 0)
        self.out += head + data
        crc = zlib.crc32(data) & U32
        self.out += struct.pack("<IIQQ", SIG_DESCRIPTOR, crc, size, size) if zip64 else struct.pack("<IIII", SIG_DESCRIPTOR, crc, size, size)
        self.entries.append({"name": name, "crc": crc, "size": size, "offset": offset, "zip64": zip64, "dir": False})

    def directory(self, path: str) -> None:
        name = (path + "/").encode("utf-8")
        offset = len(self.out)
        self.out += struct.pack("<IHHHHHIIIHH", SIG_LOCAL, 20, FLAG_UTF8, 0, DOS_TIME, DOS_DATE, 0, 0, 0, len(name), 0) + name
        self.entries.append({"name": name, "crc": 0, "size": 0, "offset": offset, "zip64": False, "dir": True})

    def finish(self) -> bytearray:
        cd_start = len(self.out)
        for e in self.entries:
            big_offset = e["offset"] >= self.zip64_at
            fields = (2 if e["zip64"] else 0) + (1 if big_offset else 0)
            extra = b""
            if fields:
                extra = struct.pack("<HH", 1, fields * 8)
                if e["zip64"]:
                    extra += struct.pack("<QQ", e["size"], e["size"])
                if big_offset:
                    extra += struct.pack("<Q", e["offset"])
            version = 45 if fields else 20
            self.out += struct.pack(
                "<IHHHHHHIIIHHHHHII", SIG_CENTRAL, version, version, FLAG_UTF8 if e["dir"] else FLAG_DESCRIPTOR | FLAG_UTF8, 0,
                DOS_TIME, DOS_DATE, e["crc"], U32 if e["zip64"] else e["size"], U32 if e["zip64"] else e["size"],
                len(e["name"]), len(extra), 0, 0, 0, 0x10 if e["dir"] else 0, U32 if big_offset else e["offset"],
            ) + e["name"] + extra
        cd_size = len(self.out) - cd_start
        count = len(self.entries)
        big = count >= U16 or cd_start >= self.zip64_at or cd_size >= self.zip64_at
        if big:
            at = len(self.out)
            self.out += struct.pack("<IQHHIIQQQQ", SIG_EOCD64, 44, 45, 45, 0, 0, count, count, cd_size, cd_start)
            self.out += struct.pack("<IIQI", SIG_LOCATOR64, 0, at, 1)
        self.out += struct.pack(
            "<IHHHHIIH", SIG_EOCD, 0, 0, U16 if big else count, U16 if big else count, U32 if big else cd_size,
            U32 if big else cd_start, 0,
        )
        return self.out


# --- reading back the structure a damage needs ----------------------------------------------------------------------------

def eocd(b: bytes) -> int:
    return len(b) - 22


def central_of(b: bytes, name: str) -> int:
    """The offset of the central record of `name` (a plain archive: no ZIP64 end records)."""
    p = struct.unpack_from("<I", b, eocd(b) + 16)[0]
    while True:
        n_len, x_len, c_len = struct.unpack_from("<HHH", b, p + 28)
        if bytes(b[p + 46:p + 46 + n_len]) == name.encode("utf-8"):
            return p
        p += 46 + n_len + x_len + c_len


def local_of(b: bytes, name: str) -> int:
    return struct.unpack_from("<I", b, central_of(b, name) + 42)[0]


# --- manifests ------------------------------------------------------------------------------------------------------------

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def payload(path: str, data: bytes, media: str = "text/plain", with_sha: bool = True) -> dict:
    p = {"path": path, "size": len(data), "mediaType": media}
    if with_sha:
        p["sha256"] = sha(data)
    return p


def file_node(nid: str, path: str, data: bytes, **over) -> dict:
    n = {"id": nid, "kind": "FILE", "name": path.rsplit("/", 1)[-1], "path": path,
         "content": {"type": "depot.file", "schema": 1, "file": payload(path, data)}}
    n.update(over)
    return n


def folder(nid: str, path: str, children: list | None = None, **over) -> dict:
    n = {"id": nid, "kind": "FOLDER", "name": path.rsplit("/", 1)[-1], "path": path}
    if children:
        n["children"] = children
    n.update(over)
    return n


def manifest(root: dict, applications: list | None = None, **over) -> dict:
    m = {"format": "veyq-subtree", "version": 2, "exportedAt": "2026-10-14T00:00:00.000Z", "appVersion": "1",
         "applications": [{"id": "depot", "contentSchema": 1}] if applications is None else applications, "root": root}
    m.update(over)
    return m


def jbytes(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def archive(m: dict | bytes, files: dict[str, bytes], dirs: list[str] = (), at: str = ".veyq/manifest.json", zip64_at: int = U32) -> bytearray:
    z = Zip(zip64_at)
    for d in dirs:
        z.directory(d)
    for path, data in files.items():
        z.file(path, data)
    z.file(at, m if isinstance(m, bytes) else jbytes(m))
    return z.finish()


A = b"the bytes of a.txt"
B = b"the bytes of b.txt"
C = "Café.txt"


def sound_tree() -> tuple[dict, dict[str, bytes], list[str]]:
    """A folder holding a file, a sub-folder with a file whose name is not ASCII, and a drop box with a guest's folder."""
    root = folder("n1", "Home", [
        file_node("n2", "Home/a.txt", A),
        folder("n3", "Home/Day 1", [file_node("n4", f"Home/Day 1/{C}", B)]),
        {"id": "n5", "kind": "DEPOT_DROPBOX", "name": "Inbox", "path": "Home/Inbox",
         "content": {"type": "depot.drop-box", "schema": 1}, "children": [folder("n6", "Home/Inbox/Anna")]},
    ])
    files = {"Home/a.txt": A, f"Home/Day 1/{C}": B}
    return root, files, ["Home", "Home/Day 1", "Home/Inbox", "Home/Inbox/Anna"]


SOUND_PLAN = {"nodes": 6, "bytes": len(A) + len(B), "skipped": []}


def b64(data: bytes) -> str:
    return base64.b64encode(bytes(data)).decode("ascii")


def zip_case(cid: str, about: str, data: bytes, screen: bool, expect: dict) -> dict:
    return {"id": cid, "about": about, "zip": b64(data), "screen": screen, "expect": expect}


def dir_case(cid: str, about: str, files: dict[str, bytes], screen: bool, expect: dict) -> dict:
    listing = [{"path": p, "bytes": b64(d)} for p, d in files.items()]
    return {"id": cid, "about": about, "directory": listing, "screen": screen, "expect": expect}


def plan(nodes: int, bytes_: int, skipped: list | None = None) -> dict:
    return {"plan": {"nodes": nodes, "bytes": bytes_, "skipped": skipped or []}}


def refused(code: str, at: str = "open") -> dict:
    return {"refused": code, "at": at}


def unpacked(data: bytes, nfd: bool = False) -> dict[str, bytes]:
    """A ZIP of these cases unpacked as `unzip` would: every file at its path; `nfd` decomposes every name."""
    out: dict[str, bytes] = {}
    p = struct.unpack_from("<I", data, eocd(data) + 16)[0]
    count = struct.unpack_from("<H", data, eocd(data) + 10)[0]
    for _ in range(count):
        size = struct.unpack_from("<I", data, p + 24)[0]
        n_len, x_len, c_len = struct.unpack_from("<HHH", data, p + 28)
        name = bytes(data[p + 46:p + 46 + n_len]).decode("utf-8")
        offset = struct.unpack_from("<I", data, p + 42)[0]
        ln, lx = struct.unpack_from("<HH", data, offset + 26)
        start = offset + 30 + ln + lx
        if not name.endswith("/"):
            out[unicodedata.normalize("NFD", name) if nfd else name] = bytes(data[start:start + size])
        p += 46 + n_len + x_len + c_len
    return out


def cases() -> list[dict]:
    out: list[dict] = []
    root, files, dirs = sound_tree()
    sound = archive(manifest(root), files, dirs)

    # --- sound downloads ---------------------------------------------------------------------------------------------
    out.append(zip_case("sound", "a folder, its files, a sub-folder and a drop box: everything is planned", sound, True, {"plan": SOUND_PLAN}))
    out.append(zip_case("sound_zip64", "the same, every entry and the directory in ZIP64 fields", archive(manifest(root), files, dirs, zip64_at=1), True, {"plan": SOUND_PLAN}))
    out.append(dir_case("unpacked", "the same download unpacked into a folder", unpacked(sound), True, {"plan": SOUND_PLAN}))
    out.append(dir_case("unpacked_nfd", "unpacked by a file system that decomposes every name", unpacked(sound, nfd=True), True, {"plan": SOUND_PLAN}))
    v1 = {"format": "veyq-subtree", "version": 1, "exportedAt": "2026-10-13T09:30:00Z", "appVersion": "0.9",
          "options": {"includeLocation": False},
          "root": {"id": "n1", "kind": "FOLDER", "name": "Old", "children": [
              {"id": "n2", "kind": "FILE", "name": "a.txt", "content": {"type": "depot.file", "schema": 1, "file": payload("data/n2/a.txt", A)}}]}}
    out.append(zip_case("version_1", "an archive written before the tree layout (manifest.json, data/<id>/)",
                        archive(v1, {"data/n2/a.txt": A}, at="manifest.json"), True, plan(2, len(A))))
    out.append(dir_case("version_1_unpacked", "version 1 was never unpacked by veyq: a folder holding only manifest.json is no download",
                        {"manifest.json": jbytes(v1), "data/n2/a.txt": A}, False, refused("not_an_export")))
    left = manifest(folder("n1", "Home", [
        file_node("n2", "Home/keep.txt", A),
        {"id": "n3", "kind": "LEDGER_SHEET", "name": "Budget", "path": "Home/Budget", "content": {"type": "ledgers.sheet", "schema": 1},
         "children": [folder("n4", "Home/Budget/May")]},
        file_node("n5", "Home/newer.txt", B, content={"type": "depot.file", "schema": 2, "file": payload("Home/newer.txt", B)}),
        {"id": "n6", "kind": "LEDGER_SHEET", "name": "Taxes", "path": "Home/Taxes", "content": {"type": "ledgers.sheet", "schema": 1}},
        {"id": "n7", "kind": "FILE", "name": "Mixed", "path": "Home/Mixed", "content": {"type": "ledgers.sheet", "schema": 1}},
    ]), [{"id": "depot", "contentSchema": 2}, {"id": "ledgers", "contentSchema": 1}])
    out.append(zip_case("left_out", "an application this build lacks, content newer than it reads, and content of another application than its kind's: listed and left out",
                        archive(left, {"Home/keep.txt": A, "Home/newer.txt": B}), True, plan(2, len(A), [
                            {"name": "Budget", "application": "ledgers", "reason": "unknown_application", "nodes": 2},
                            {"name": "newer.txt", "application": "depot", "reason": "newer_content", "nodes": 1},
                            {"name": "Taxes", "application": "ledgers", "reason": "unknown_application", "nodes": 1},
                            {"name": "Mixed", "application": "ledgers", "reason": "unknown_application", "nodes": 1},
                        ])))
    out.append(zip_case("schema_from_applications", "a content without its own schema takes the application's: newer, left out",
                        archive(manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A, content={"type": "depot.file", "file": payload("Home/a.txt", A)})]),
                                         [{"id": "depot", "contentSchema": 2}]), {"Home/a.txt": A}), True,
                        plan(1, 0, [{"name": "a.txt", "application": "depot", "reason": "newer_content", "nodes": 1}])))
    nothing = manifest({"id": "n1", "kind": "LEDGER_SHEET", "name": "Budget", "path": "Budget", "content": {"type": "ledgers.sheet", "schema": 1}},
                       [{"id": "ledgers", "contentSchema": 1}])
    out.append(zip_case("nothing_importable", "nothing in it is importable: planned as nothing, refused before a write",
                        archive(nothing, {}), True, refused("nothing_to_import", "import")))

    # --- not a download --------------------------------------------------------------------------------------------------
    out.append(zip_case("not_a_zip", "a file that is no ZIP", b"PK\x03\x04 but nothing else of an archive, and long enough", False, refused("not_an_export")))
    out.append(zip_case("too_small", "fewer bytes than an end record", b"PK\x03\x04", False, refused("not_an_export")))
    z = Zip()
    z.file("holiday.jpg", b"not really a photo")
    out.append(zip_case("ordinary_zip", "an ordinary ZIP, no manifest", z.finish(), False, refused("not_an_export")))
    out.append(dir_case("ordinary_folder", "a folder without the manifest", {"a.txt": A}, False, refused("not_an_export")))
    out.append(zip_case("other_format", "a manifest of another format", archive(manifest(folder("n1", "Home"), format="something-else"), {}), True, refused("not_an_export")))
    out.append(zip_case("no_end_record", "the end record overwritten", (lambda b: (b.__setitem__(slice(eocd(b), eocd(b) + 4), b"XXXX"), b)[1])(archive(manifest(folder("n1", "Home")), {})),
                        False, refused("not_an_export")))

    # --- the manifest ----------------------------------------------------------------------------------------------------
    out.append(zip_case("newer_version", "a manifest version this build does not read", archive(manifest(folder("n1", "Home"), version=3), {}), True, refused("newer_version")))
    out.append(zip_case("not_json", "a manifest that is not JSON", archive(b"{not json", {}), True, refused("invalid_manifest")))
    out.append(zip_case("not_utf8", "a manifest that is not UTF-8", archive(b'{"format":"veyq-subtree\xff"}', {}), True, refused("invalid_manifest")))
    out.append(zip_case("wrong_type", "a field of the wrong type", archive(manifest(folder("n1", "Home", name=42)), {}), True, refused("invalid_manifest")))
    out.append(zip_case("bad_id", "a node id outside its alphabet", archive(manifest(folder("n/1", "Home")), {}), True, refused("invalid_manifest")))
    out.append(zip_case("id_twice", "a node id used twice", archive(manifest(folder("n1", "Home", [folder("n1", "Home/x")])), {}), True, refused("invalid_manifest")))
    out.append(zip_case("bad_content_type", "a content type that is not <application>.<name>",
                        archive(manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A, content={"type": "Not A Type", "schema": 1, "file": payload("Home/a.txt", A)})])), {"Home/a.txt": A}),
                        True, refused("invalid_manifest")))
    out.append(zip_case("application_not_listed", "an application the manifest does not list",
                        archive(manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A)]), []), {"Home/a.txt": A}), True, refused("invalid_manifest")))
    out.append(zip_case("version_2_at_version_1_place", "a version 2 manifest at the version 1 place",
                        archive(manifest(folder("n1", "Home")), {}, at="manifest.json"), True, refused("invalid_manifest")))
    out.append(zip_case("name_too_long", "a name over its contract length (1024)", archive(manifest(folder("n1", "Home", name="x" * 1025)), {}), True, refused("too_large")))
    out.append(zip_case("name_at_limit", "a name at its contract length", archive(manifest(folder("n1", "Home", name="x" * 1024)), {}), True, plan(1, 0)))
    deep = folder("d", "Home")
    n, path = deep, "Home"
    for i in range(101):
        path += "/d"
        child = {"id": f"d{i}", "kind": "FOLDER", "name": "d", "path": path}
        n["children"] = [child]
        n = child
    out.append(zip_case("too_deep", "a tree deeper than 100", archive(manifest(deep), {}), True, refused("too_large")))
    out.append(zip_case("file_without_payload", "Depot's own content check: a file without its payload",
                        archive(manifest(folder("n1", "Home", [{"id": "n2", "kind": "FILE", "name": "a.txt", "path": "Home/a.txt", "content": {"type": "depot.file", "schema": 1}}])), {}),
                        True, refused("invalid_manifest", "plan")))
    out.append(zip_case("drop_box_with_more", "Depot's own content check: a drop box that carries more than its type",
                        archive(manifest(folder("n1", "Home", [{"id": "n2", "kind": "DEPOT_DROPBOX", "name": "Inbox", "path": "Home/Inbox",
                                                                 "content": {"type": "depot.drop-box", "schema": 1, "dropKey": "x"}}])), {}),
                        True, refused("invalid_manifest", "plan")))

    # --- paths -----------------------------------------------------------------------------------------------------------
    up = manifest(folder("n1", "Home", [{"id": "n2", "kind": "FILE", "name": "x", "path": "Home/x",
                                         "content": {"type": "depot.file", "schema": 1, "file": {"path": "Home/x/..", "size": 1, "mediaType": "text/plain"}}}]))
    out.append(zip_case("upward_segment", "a payload path with an upward segment", archive(up, {}), True, refused("unsafe_path")))
    out.append(zip_case("into_veyq", "a node reaching into .veyq/", archive(manifest(folder("n1", ".veyq")), {}), True, refused("unsafe_path")))
    out.append(zip_case("payload_of_another", "a payload that is not its node's",
                        archive(manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A, content={"type": "depot.file", "schema": 1, "file": payload("Home/b.txt", A)})])), {"Home/b.txt": A}),
                        True, refused("unsafe_path")))
    out.append(zip_case("two_segments_below", "a node path that is not one segment below its parent",
                        archive(manifest(folder("n1", "Home", [folder("n2", "Home/x/y")])), {}), True, refused("unsafe_path")))
    out.append(zip_case("reserved_name", "a name a file system refuses (CON)", archive(manifest(folder("n1", "Home", [file_node("n2", "Home/CON", A)])), {"Home/CON": A}),
                        True, refused("unsafe_path")))
    out.append(zip_case("absolute_root", "a root path that is absolute", archive(manifest(folder("n1", "/Home")), {}), True, refused("unsafe_path")))

    # --- payloads --------------------------------------------------------------------------------------------------------
    one = manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A)]))
    out.append(zip_case("payload_missing", "a payload the archive lacks", archive(one, {}), True, refused("payload_missing")))
    out.append(zip_case("size_mismatch", "a payload that is not its declared size", archive(one, {"Home/a.txt": A + b"!"}), True, refused("size_mismatch")))
    out.append(dir_case("unpacked_payload_missing", "an unpacked download lacking a payload", {".veyq/manifest.json": jbytes(one)}, True, refused("payload_missing")))
    bad_sha = manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A, content={"type": "depot.file", "schema": 1, "file": {**payload("Home/a.txt", A), "sha256": sha(B)}})]))
    out.append(zip_case("sha256_mismatch", "bytes that do not match their SHA-256", archive(bad_sha, {"Home/a.txt": A}), True, refused("checksum", "import")))
    out.append(dir_case("unpacked_sha256_mismatch", "the same, unpacked: the SHA-256 alone tells", {".veyq/manifest.json": jbytes(bad_sha), "Home/a.txt": A}, True, refused("checksum", "import")))
    no_sha = manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A, content={"type": "depot.file", "schema": 1, "file": payload("Home/a.txt", A, with_sha=False)})]))
    flipped = archive(no_sha, {"Home/a.txt": A})
    at = bytes(flipped).index(A)
    flipped[at] ^= 0x01
    out.append(zip_case("crc_mismatch", "one flipped bit of a payload whose manifest names no SHA-256: only the CRC-32 tells", flipped, True, refused("checksum", "import")))
    out.append(zip_case("no_sha256", "a payload without a SHA-256 is sound", archive(no_sha, {"Home/a.txt": A}), True, plan(2, len(A))))

    # --- the ZIP's structure ---------------------------------------------------------------------------------------------
    two = manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", A), file_node("n3", "Home/b.txt", B)]))
    two_files = {"Home/a.txt": A, "Home/b.txt": B}

    def damaged(cid: str, about: str, damage, base=None) -> None:
        b = archive(two, two_files) if base is None else base
        damage(b)
        out.append(zip_case(cid, about, b, False, refused("damaged_archive")))

    damaged("offset_outside", "an entry's offset outside the file",
            lambda b: struct.pack_into("<I", b, central_of(b, "Home/b.txt") + 42, len(b) + 100))
    damaged("declared_size", "a declared size that is not the actual one",
            lambda b: struct.pack_into("<II", b, central_of(b, "Home/a.txt") + 20, len(A) + 1, len(A) + 1))
    damaged("compressed", "a compressed entry", lambda b: struct.pack_into("<H", b, central_of(b, "Home/a.txt") + 10, 8))
    damaged("compressed_local", "a local header that names a method", lambda b: struct.pack_into("<H", b, local_of(b, "Home/a.txt") + 8, 8))
    damaged("encrypted", "an encrypted entry", lambda b: struct.pack_into("<H", b, central_of(b, "Home/a.txt") + 8, FLAG_DESCRIPTOR | FLAG_UTF8 | 1))
    damaged("multi_disk", "a multi-disk archive", lambda b: struct.pack_into("<H", b, eocd(b) + 4, 1))
    damaged("entry_on_other_disk", "an entry on another disk", lambda b: struct.pack_into("<H", b, central_of(b, "Home/a.txt") + 34, 2))
    damaged("local_name_differs", "a local header whose name is not the directory's",
            lambda b: b.__setitem__(local_of(b, "Home/a.txt") + 30, ord("X")))
    damaged("bad_local_signature", "a local header without its signature", lambda b: struct.pack_into("<I", b, local_of(b, "Home/a.txt"), 0))
    damaged("descriptor_crc", "a data descriptor whose CRC is not the directory's",
            lambda b: struct.pack_into("<I", b, central_of(b, "Home/a.txt") + 16, (zlib.crc32(A) ^ 1) & U32))
    damaged("directory_outside", "a central directory that runs past its end record",
            lambda b: struct.pack_into("<I", b, eocd(b) + 12, struct.unpack_from("<I", b, eocd(b) + 12)[0] + 1000))
    damaged("more_entries", "more entries than the directory holds", lambda b: struct.pack_into("<HH", b, eocd(b) + 8, 400, 400))

    # a name listed twice: two entries of one name
    z = Zip()
    z.file("Home/a.txt", A)
    z.file("Home/a.txt", A)
    z.file(".veyq/manifest.json", jbytes(two))
    out.append(zip_case("duplicate_name", "a name listed twice", z.finish(), False, refused("damaged_archive")))
    # a name that is not UTF-8
    z = Zip()
    z.file("Home/a.txt", A)
    z.file("Home/b\xff.txt", B, name_bytes=b"Home/b\xff.txt")
    z.file(".veyq/manifest.json", jbytes(one))
    out.append(zip_case("name_not_utf8", "an entry name that is not UTF-8", z.finish(), False, refused("damaged_archive")))

    # two entries that overlap: c.txt's directory line pointed at a whole b.txt entry stored as the bytes of a.txt
    inner_zip = Zip()
    inner_zip.file("Home/b.txt", B)
    inner_full = inner_zip.finish()
    inner_end = struct.unpack_from("<I", inner_full, eocd(inner_full) + 16)[0]
    inner = bytes(inner_full[:inner_end])
    over = manifest(folder("n1", "Home", [file_node("n2", "Home/a.txt", inner), file_node("n3", "Home/b.txt", B)]))
    z = Zip()
    z.file("Home/a.txt", inner)
    z.file("Home/c.txt", b"zzz")
    z.file(".veyq/manifest.json", jbytes(over))
    b = z.finish()
    c = central_of(b, "Home/c.txt")
    b[c + 46:c + 46 + len("Home/b.txt")] = b"Home/b.txt"
    struct.pack_into("<I", b, c + 16, zlib.crc32(B) & U32)
    struct.pack_into("<I", b, c + 20, len(B))
    struct.pack_into("<I", b, c + 24, len(B))
    struct.pack_into("<I", b, c + 42, 30 + len("Home/a.txt"))
    out.append(zip_case("overlap", "two entries that overlap", b, False, refused("damaged_archive")))

    # a ZIP64 directory offset far past the end
    b = archive(one, {"Home/a.txt": A}, zip64_at=1)
    record_at = struct.unpack_from("<Q", b, len(b) - 22 - 20 + 8)[0]
    assert struct.unpack_from("<I", b, record_at)[0] == SIG_EOCD64
    struct.pack_into("<Q", b, record_at + 48, len(b) * 4)
    out.append(zip_case("zip64_out_of_range", "a ZIP64 field out of range", b, False, refused("damaged_archive")))
    b = archive(one, {"Home/a.txt": A}, zip64_at=1)
    struct.pack_into("<I", b, len(b) - 22 - 20, 0)
    out.append(zip_case("zip64_locator_missing", "a ZIP64 end without its locator", b, False, refused("damaged_archive")))
    return out


def render() -> bytes:
    doc = {
        "about": "Import cases both clients answer to (ADR 0152 decision 4); written by generate.py beside this file, never by hand.",
        "version": 1,
        "cases": cases(),
    }
    return (json.dumps(doc, ensure_ascii=False, indent=1) + "\n").encode("utf-8")


def main(argv: list[str]) -> int:
    data = render()
    if "--check" in argv:
        if not OUT.exists() or OUT.read_bytes() != data:
            print(f"{OUT.name} is not what generate.py writes: run it without --check", file=sys.stderr)
            return 1
        print(f"{OUT.name}: {len(json.loads(data)['cases'])} cases, reproduced byte for byte")
        return 0
    OUT.write_bytes(data)
    print(f"wrote {OUT.name}: {len(json.loads(data)['cases'])} cases, {len(data)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
