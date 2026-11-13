#!/usr/bin/env python3
"""Offline self-test of `scripts/msix_sign.py` (ADR 0133 decisions 7 and 12): the committed signer's subject and the
signed package's comparison, on certificates and packages written here — a DER encoder of its own, no OpenSSL, no
Windows. One package pair in which signing holds, and one forgery per refusal, each of which must name what it saw.
`checks.yml`'s `guards` job runs it.

    python3 scripts/msix_sign_selftest.py

What it cannot see: signtool, and a real signature (a pair `run.py package --sign` made on the Windows VM is what
`compare` was measured on, 2026-11-13).
"""
from __future__ import annotations

import pathlib
import sys
import tempfile
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import msix_sign as ms  # noqa: E402

failures: list[str] = []


def der(tag: int, content: bytes) -> bytes:
    n = len(content)
    length = bytes([n]) if n < 0x80 else bytes([0x80 | ((n.bit_length() + 7) // 8)]) + n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([tag]) + length + content


def oid(dotted: str) -> bytes:
    parts = [int(p) for p in dotted.split(".")]
    body = bytes([40 * parts[0] + parts[1]])
    for p in parts[2:]:
        chunk = [p & 0x7F]
        p >>= 7
        while p:
            chunk.insert(0, 0x80 | (p & 0x7F))
            p >>= 7
        body += bytes(chunk)
    return der(0x06, body)


def name(*pairs: tuple[str, str], string_tag: int = 0x0C) -> bytes:
    rdns = b"".join(der(0x31, der(0x30, oid(o) + der(string_tag, v.encode("utf-16-be" if string_tag == 0x1E else "utf-8"))))
                    for o, v in pairs)
    return der(0x30, rdns)


CN, O = "2.5.4.3", "2.5.4.10"


def cert(subject_name: bytes, serial: int = 7, issuer_name: bytes | None = None) -> bytes:
    alg = der(0x30, oid("1.2.840.113549.1.1.11") + b"\x05\x00")
    tbs = der(0x30, der(0xA0, der(0x02, b"\x02")) + der(0x02, serial.to_bytes(2, "big")) + alg
              + (issuer_name or subject_name) + der(0x30, b"") + subject_name + der(0x30, b""))
    return der(0x30, tbs + alg + der(0x03, b"\x00sig"))


def p7x(signer_cert: bytes, embedded: list[bytes], also: bytes | None = None) -> bytes:
    def info(c: bytes) -> bytes:
        issuer, serial = ms.signer_id(c)
        return der(0x30, der(0x02, b"\x01") + der(0x30, issuer + serial) + der(0x30, b"") + der(0x04, b"sig"))
    signed_data = der(0x30, der(0x02, b"\x01") + der(0x31, b"") + der(0x30, oid("1.3.6.1.4.1.311.2.1.4"))
                      + der(0xA0, b"".join(embedded)) + der(0x31, info(signer_cert) + (info(also) if also else b"")))
    return b"PKCX" + der(0x30, oid("1.2.840.113549.1.7.2") + der(0xA0, signed_data))


def check(label: str, condition: bool, detail: str = "") -> None:
    if not condition:
        failures.append(f"{label}{': ' + detail if detail else ''}")


def refusal(fn, *args) -> str:
    try:
        fn(*args)
    except ms.Refused as e:
        return str(e)
    return ""


# ---- the subject, as Windows writes it --------------------------------------------------------------------------
veyq = cert(name((CN, "veyq")))
check("a one-name subject", ms.subject(veyq) == "CN=veyq", ms.subject(veyq))
check("RDNs in Windows' reverse order", ms.subject(cert(name((O, "veyq"), (CN, "veyq")))) == "CN=veyq, O=veyq")
check("a BMPString value", ms.subject(cert(name((CN, "veyq"), string_tag=0x1E))) == "CN=veyq")
check("an unknown attribute type by its OID", ms.subject(cert(name(("1.2.3.4", "x")))) == "OID.1.2.3.4=x")
check("not a certificate", "not one DER certificate" in refusal(ms.subject, der(0x31, b"")))
check("bytes after the certificate", "not one DER certificate" in refusal(ms.subject, veyq + b"\x00"))
pem = b"subject=/CN=veyq\n-----BEGIN CERTIFICATE-----\n" + __import__("base64").encodebytes(veyq) + b"-----END CERTIFICATE-----\n"
check("a PEM with a header line reads as its DER", ms.der_of(pem) == veyq)
check("two PEM certificates are refused", "not one" in refusal(ms.der_of, pem + pem))

with tempfile.TemporaryDirectory(prefix="msix_sign_selftest_") as tmp:
    root = pathlib.Path(tmp)
    (root / "veyq.cer").write_bytes(veyq)
    check("the publisher's certificate holds", ms.certificate(root / "veyq.cer", "CN=veyq") == veyq)
    (root / "other.cer").write_bytes(cert(name((CN, "someone"))))
    check("another subject is refused", "not `CN=veyq`" in refusal(ms.certificate, root / "other.cer", "CN=veyq"))
    check("no committed certificate is refused, naming the owner step",
          "signer is not set up" in refusal(ms.certificate, root / "absent.cer", "CN=veyq"))

    # ---- a package and its signed copy ---------------------------------------------------------------------------
    PAYLOAD = {"AppxManifest.xml": b"<Package/>", "Veyq.exe": b"MZ" + b"\x00" * 64, "AppxBlockMap.xml": b"<BlockMap/>",
               "[Content_Types].xml": b"<Types/>"}

    def package(path: pathlib.Path, files: dict[str, bytes]) -> pathlib.Path:
        with zipfile.ZipFile(path, "w") as z:
            for n, b in files.items():
                z.writestr(n, b)
        return path

    unsigned = package(root / "veyq-1.2-x64.msix", PAYLOAD)
    good = {**PAYLOAD, "[Content_Types].xml": b"<Types><p7x/></Types>", ms.CATALOG: b"cat",
            ms.SIGNATURE: p7x(veyq, [veyq])}

    def signed(label: str, files: dict[str, bytes]) -> pathlib.Path:
        return package(root / f"{label}.msix", files)

    try:
        ms.compare(unsigned, signed("good", good), veyq)
    except ms.Refused as e:
        failures.append(f"a package signed by the committed certificate is refused: {e}")
    check("the comparison without a certificate holds too", not refusal(ms.compare, unsigned, root / "good.msix", None))
    other = cert(name((CN, "veyq")), serial=8)  # the same subject, another certificate: another key
    for label, files, says in (
        ("block map changed", {**good, "AppxBlockMap.xml": b"<BlockMap x='1'/>"}, "signing changed the block map"),
        ("payload changed", {**good, "Veyq.exe": b"MZ!"}, "signing changed Veyq.exe"),
        ("a file added", {**good, "extra.dll": b"x"}, "signing added"),
        ("a file removed", {k: v for k, v in good.items() if k != "AppxManifest.xml"}, "removed ['AppxManifest.xml']"),
        ("no catalog", {k: v for k, v in good.items() if k != ms.CATALOG}, "where it adds exactly"),
        ("signed by another certificate", {**good, ms.SIGNATURE: p7x(other, [other])}, "not signed by the committed"),
        ("a second signer beside it", {**good, ms.SIGNATURE: p7x(veyq, [veyq, other], also=other)},
         "not signed by the committed"),
        ("the certificate named but not carried", {**good, ms.SIGNATURE: p7x(veyq, [other])}, "not signed by the committed"),
        ("no PKCX signature", {**good, ms.SIGNATURE: b"junk"}, "does not start with `PKCX`"),
    ):
        said = refusal(ms.compare, unsigned, signed(label.replace(" ", "-"), files), veyq)
        if says is None:
            check(f"{label}: holds", not said, said)
        else:
            check(f"{label}: refused by saying `{says}`", says in said, said or "accepted")
    check("a staged package that is signed already is refused",
          "signed already" in refusal(ms.compare, root / "good.msix", root / "good.msix", veyq))
    check("a staged file that is no MSIX is refused",
          "no MSIX" in refusal(ms.compare, package(root / "zip.msix", {"a": b"b"}), root / "good.msix", veyq))

# ---- the pinned signtool is the Windows build's --------------------------------------------------------------------
check("the signtool pin is the version Directory.Packages.props pins", not refusal(ms.pinned_build_tools),
      refusal(ms.pinned_build_tools))

if failures:
    print("MSIX SIGN SELFTEST FAILED:")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("msix sign selftest OK: the committed signer is held to the publisher as Windows writes it, and a signed copy "
      "holds only with its block map and payload unchanged, the two signing files added and the committed certificate "
      "its one signer — every forgery refused by name")
