#!/usr/bin/env python3
"""Sign a release's MSIX with the project's certificate, and refuse anything but the tested payload under that signer
(ADR 0139 decisions 7 and 12). `release.yml`'s `msix-sign` job runs `sign` on a Windows runner; `promote.py check`
reads the certificate before a promotion writes anything.

    python3 scripts/msix_sign.py certificate                                    # the committed signer, held to msixPublisher
    python3 scripts/msix_sign.py compare --unsigned <msix> --signed <msix> [--certificate <cer>]
    python scripts/msix_sign.py sign --msix <unsigned msix> --out <dir>         # Windows only

**The signer is pinned in the tree, its key is not.** `src/mobile/windows/veyq.cer` (repo_layout.MSIX_CERTIFICATE) is the
public half of the release's certificate, committed by its owner when the certificate is made; the PFX and its password
reach the signing job only as secrets of the GitHub environment `msix-signing` (VEYQ_MSIX_PFX_BASE64,
VEYQ_MSIX_PFX_PASSWORD). **certificate** refuses unless that file is one X.509 certificate whose subject, as Windows
writes it, is `msixPublisher` of `src/mobile/desktop-identity.json` — the MSIX's `Publisher` must equal its signer's
subject or Windows refuses the package — and prints its SHA-1 thumbprint.

**compare** holds a signed package to the staged one it was signed from. An MSIX is a ZIP, and signing adds exactly
`AppxSignature.p7x` and `AppxMetadata/CodeIntegrity.cat` and rewrites `[Content_Types].xml` (measured 2026-11-13 on a
pair `run.py package --sign` made on the Windows VM). So it refuses unless the staged package is unsigned, the signed
one adds those two entries and nothing else, `AppxBlockMap.xml` — which hashes every payload file — is byte-identical,
every other entry the staged package has is byte-identical too, and, with `--certificate`, the package signature's one
signer (its issuer and serial number) is that certificate, which it also carries byte for byte: a PFX holding any other
certificate is refused here, after signtool and before anything is published.

**sign** (Windows): the certificate as above; signtool from the pinned `Microsoft.Windows.SDK.BuildTools` (the version
`Directory.Packages.props` pins, its `.nupkg` fetched from nuget.org and held to `BUILD_TOOLS_SHA256` before it is
opened — a bump of the pin refuses here until the hash is measured again; no Windows SDK of the runner's); the PFX
written from the environment into a directory of its own, used once and deleted; a copy of the package signed without
a timestamp (no timestamp authority in the release's path: a released package installs while the certificate is valid,
ADR 0139 decision 7); then **compare** with the certificate. It writes `<out>/<the package's name>` and `<out>/veyq.cer`
(the certificate's DER), the two files the release attaches.

Its limits: the subject is read by a small DER reader of its own (the attribute types a certificate subject commonly
carries, in Windows' reverse order); the signature itself is Windows' to verify at install — this checks who signed and
what, not the cryptography. `scripts/msix_sign_selftest.py` drives every refusal on synthetic packages.
"""
from __future__ import annotations  # `X | None` — the repo's scripts run on Python 3.9

import argparse
import base64
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402 - where the identity, the certificate and the NuGet pins live

# What signing adds to an MSIX and what it rewrites (measured 2026-11-13, a package and its signed copy from the VM).
SIGNATURE = "AppxSignature.p7x"
CATALOG = "AppxMetadata/CodeIntegrity.cat"
CONTENT_TYPES = "[Content_Types].xml"
BLOCK_MAP = "AppxBlockMap.xml"
# The signtool a release signs with: the NuGet package the Windows build pins (Directory.Packages.props), fetched as
# nuget.org serves it. Measured 2026-11-13: `curl … microsoft.windows.sdk.buildtools.10.0.28000.2957.nupkg | sha256sum`.
# (The lock file's contentHash is not the file's: nuget.org's repository signature is left out of it.)
BUILD_TOOLS_VERSION = "10.0.28000.2957"
BUILD_TOOLS_SHA256 = "b7163fc6f636172726d0985963e4f7f11c41afe1c7fdfe78f8051877360ed696"
BUILD_TOOLS_URL = ("https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.buildtools/{v}/"
                   "microsoft.windows.sdk.buildtools.{v}.nupkg")
PFX_ENV, PASSWORD_ENV = "VEYQ_MSIX_PFX_BASE64", "VEYQ_MSIX_PFX_PASSWORD"
CERTIFICATE_ASSET = "veyq.cer"


class Refused(Exception):
    """A refusal: the message is the whole explanation."""


# ---- a DER reader, enough for a certificate's subject and a signature's signer ------------------------------------

def _tlv(data: bytes, i: int) -> tuple[int, int, int]:
    """(tag, start of the content, end of the content) of the element at `i`."""
    if i + 2 > len(data):
        raise Refused("a DER element runs past the end of its data")
    tag, length = data[i], data[i + 1]
    i += 2
    if length & 0x80:
        n = length & 0x7F
        if n == 0 or n > 4 or i + n > len(data):
            raise Refused("a DER length this reader does not take")
        length = int.from_bytes(data[i:i + n], "big")
        i += n
    if i + length > len(data):
        raise Refused("a DER element runs past the end of its data")
    return tag, i, i + length


def _children(data: bytes, start: int, end: int) -> list[tuple[int, int, int, int]]:
    """(tag, element start, content start, end) of each element in data[start:end]."""
    out, i = [], start
    while i < end:
        tag, s, e = _tlv(data, i)
        out.append((tag, i, s, e))
        i = e
    return out


SEQUENCE, SET = 0x30, 0x31
# The attribute types a subject commonly carries, as Windows names them in a certificate's Subject.
NAMES = {"2.5.4.3": "CN", "2.5.4.6": "C", "2.5.4.7": "L", "2.5.4.8": "S", "2.5.4.10": "O", "2.5.4.11": "OU",
         "1.2.840.113549.1.9.1": "E", "2.5.4.5": "SERIALNUMBER"}


def _oid(raw: bytes) -> str:
    first, rest, parts, value = raw[0], raw[1:], [], 0
    parts += [str(min(first // 40, 2)), str(first - 40 * min(first // 40, 2))]
    for b in rest:
        value = (value << 7) | (b & 0x7F)
        if not b & 0x80:
            parts.append(str(value))
            value = 0
    return ".".join(parts)


def _string(tag: int, raw: bytes) -> str:
    if tag == 0x1E:  # BMPString
        return raw.decode("utf-16-be")
    if tag in (0x0C, 0x13, 0x16, 0x14):  # UTF8String, PrintableString, IA5String, T61String
        return raw.decode("utf-8" if tag == 0x0C else "latin-1")
    raise Refused(f"a subject attribute of a string type this reader does not take (tag 0x{tag:02x})")


def der_of(raw: bytes) -> bytes:
    """A certificate file's DER: the bytes themselves, or a PEM's one `CERTIFICATE` block decoded."""
    if b"-----BEGIN" in raw:
        blocks = re.findall(rb"-----BEGIN CERTIFICATE-----\s+(.*?)-----END CERTIFICATE-----", raw, re.S)
        if len(blocks) != 1:
            raise Refused(f"the PEM holds {len(blocks)} certificates, not one")
        return base64.b64decode(b"".join(blocks[0].split()))
    return raw


def _tbs(der: bytes) -> list[tuple[int, int, int, int]]:
    tag, s, e = _tlv(der, 0)
    if tag != SEQUENCE or e != len(der):
        raise Refused("not one DER certificate (an X.509 SEQUENCE and nothing after it)")
    parts = _children(der, s, e)
    if len(parts) != 3 or parts[0][0] != SEQUENCE:
        raise Refused("not an X.509 certificate: no tbsCertificate, signature algorithm and signature")
    fields = _children(der, parts[0][2], parts[0][3])
    if fields and fields[0][0] == 0xA0:  # [0] version
        fields = fields[1:]
    if len(fields) < 6:
        raise Refused("not an X.509 certificate: its tbsCertificate is short of a subject")
    return fields  # serial, signature, issuer, validity, subject, subjectPublicKeyInfo, …


def subject(der: bytes) -> str:
    """The certificate's subject as Windows writes it (`X509Certificate2.Subject`, a package's `Publisher`): the
    relative names in reverse order, `, ` between them."""
    _, _, s, e = _tbs(der)[4]
    names = []
    for rdn_tag, _, rs, re_ in _children(der, s, e):
        if rdn_tag != SET:
            raise Refused("the subject is not a sequence of sets")
        for atv_tag, _, as_, ae in _children(der, rs, re_):
            items = _children(der, as_, ae)
            if atv_tag != SEQUENCE or len(items) != 2 or items[0][0] != 0x06:
                raise Refused("a subject attribute is not a type and a value")
            oid = _oid(der[items[0][2]:items[0][3]])
            name = NAMES.get(oid, f"OID.{oid}")
            names.append(f"{name}={_string(items[1][0], der[items[1][2]:items[1][3]])}")
    return ", ".join(reversed(names))


def signer_id(der: bytes) -> tuple[bytes, bytes]:
    """The certificate's issuer and serial number (their DER), the pair a PKCS #7 signer names it by."""
    fields = _tbs(der)
    serial, issuer = fields[0], fields[2]
    return der[issuer[1]:issuer[3]], der[serial[1]:serial[3]]


def thumbprint(der: bytes) -> str:
    return hashlib.sha1(der).hexdigest().upper()


def signers(p7x: bytes) -> list[tuple[bytes, bytes]]:
    """The issuer and serial (DER) of each signer of an MSIX's `AppxSignature.p7x` (`PKCX`, then a CMS SignedData)."""
    if not p7x.startswith(b"PKCX"):
        raise Refused(f"{SIGNATURE} does not start with `PKCX`")
    data = p7x[4:]
    tag, s, e = _tlv(data, 0)
    content = _children(data, s, e)
    if tag != SEQUENCE or len(content) != 2 or content[1][0] != 0xA0:
        raise Refused(f"{SIGNATURE} holds no CMS ContentInfo")
    inner = _children(data, content[1][2], content[1][3])
    if len(inner) != 1 or inner[0][0] != SEQUENCE:
        raise Refused(f"{SIGNATURE} holds no SignedData")
    signed_data = _children(data, inner[0][2], inner[0][3])
    if not signed_data or signed_data[-1][0] != SET:
        raise Refused(f"{SIGNATURE}'s SignedData ends in no set of signers")
    out = []
    for info_tag, _, is_, ie in _children(data, signed_data[-1][2], signed_data[-1][3]):
        fields = _children(data, is_, ie)
        if info_tag != SEQUENCE or len(fields) < 2 or fields[1][0] != SEQUENCE:
            raise Refused(f"a signer of {SIGNATURE} is named by no issuer and serial number")
        sid = _children(data, fields[1][2], fields[1][3])
        if len(sid) != 2:
            raise Refused(f"a signer of {SIGNATURE} is named by no issuer and serial number")
        out.append((data[sid[0][1]:sid[0][3]], data[sid[1][1]:sid[1][3]]))
    return out


# ---- the certificate, the comparison ------------------------------------------------------------------------------

def publisher() -> str:
    return json.loads(repo_layout.DESKTOP_IDENTITY.read_text())["msixPublisher"]


def certificate(path: pathlib.Path | None = None, want: str | None = None) -> bytes:
    """The committed signer's DER, refused unless it is one certificate of the MSIX's publisher."""
    path = path or repo_layout.MSIX_CERTIFICATE
    want = want if want is not None else publisher()
    if not path.is_file():
        raise Refused(f"{path.relative_to(repo_layout.ROOT) if path.is_relative_to(repo_layout.ROOT) else path} does "
                      f"not exist: the release's MSIX signer is not set up — its owner makes the `{want}` certificate, "
                      f"commits its public half there and stores the PFX in the `msix-signing` environment "
                      f"(infra/README.md § Cutting a release)")
    der = der_of(path.read_bytes())
    got = subject(der)
    if got != want:
        raise Refused(f"{path.name}'s subject is `{got}`, not `{want}` (desktop-identity.json's msixPublisher) — "
                      f"Windows refuses a package whose Publisher is not its signer's subject")
    return der


def compare(unsigned: pathlib.Path, signed: pathlib.Path, cert_der: bytes | None) -> str:
    """Refused unless `signed` is `unsigned` with a signature added (and, given `cert_der`, that certificate's); returns
    the block map's sha256."""
    with zipfile.ZipFile(unsigned) as u, zipfile.ZipFile(signed) as s:
        before = {i.filename for i in u.infolist()}
        after = {i.filename for i in s.infolist()}
        if BLOCK_MAP not in before:
            raise Refused(f"{unsigned.name} carries no {BLOCK_MAP}: it is no MSIX")
        if SIGNATURE in before:
            raise Refused(f"{unsigned.name} is signed already — the release signs the staged, unsigned package")
        added, lost = sorted(after - before), sorted(before - after)
        if lost or set(added) != {SIGNATURE, CATALOG}:
            raise Refused(f"signing added {added} and removed {lost}, where it adds exactly {SIGNATURE} and {CATALOG}")
        if u.read(BLOCK_MAP) != s.read(BLOCK_MAP):
            raise Refused(f"signing changed the block map: {signed.name}'s {BLOCK_MAP} is not the staged one's, so "
                          f"its payload is not the one Checks built and tested")
        changed = sorted(n for n in before - {CONTENT_TYPES} if u.read(n) != s.read(n))
        if changed:
            raise Refused(f"signing changed {', '.join(changed[:5])} — a payload file is not the staged one")
        if cert_der is not None:
            p7x = s.read(SIGNATURE)
            found = signers(p7x)
            if found != [signer_id(cert_der)] or cert_der not in p7x:
                raise Refused(f"{signed.name} is not signed by the committed certificate (thumbprint "
                              f"{thumbprint(cert_der)}) alone: the PFX the signing job received holds another one")
        return hashlib.sha256(u.read(BLOCK_MAP)).hexdigest()


# ---- sign, on Windows -----------------------------------------------------------------------------------------------

def pinned_build_tools() -> str:
    props = repo_layout.WINDOWS_PACKAGES_PROPS.read_text()
    m = re.search(r'Include="Microsoft\.Windows\.SDK\.BuildTools" Version="\[([0-9.]+)\]"', props)
    if not m:
        raise Refused(f"{repo_layout.WINDOWS_PACKAGES_PROPS.name} pins no Microsoft.Windows.SDK.BuildTools")
    if m.group(1) != BUILD_TOOLS_VERSION:
        raise Refused(f"the Windows build pins Microsoft.Windows.SDK.BuildTools {m.group(1)}, this signs with "
                      f"{BUILD_TOOLS_VERSION}: measure the new .nupkg's sha256 into BUILD_TOOLS_SHA256 with the bump")
    return m.group(1)


def signtool(work: pathlib.Path) -> pathlib.Path:
    """signtool.exe (x64) of the pinned build tools, from a .nupkg held to its sha256 before it is opened."""
    version = pinned_build_tools()
    with urllib.request.urlopen(BUILD_TOOLS_URL.format(v=version), timeout=300) as response:
        raw = response.read()
    got = hashlib.sha256(raw).hexdigest()
    if got != BUILD_TOOLS_SHA256:
        raise Refused(f"the build tools' .nupkg hashes to {got}, not the pinned {BUILD_TOOLS_SHA256}")
    with zipfile.ZipFile(io.BytesIO(raw)) as nupkg:
        wanted = [n for n in nupkg.namelist() if re.fullmatch(r"bin/[0-9.]+/x64/[^/]+", n)]
        if not any(n.endswith("/signtool.exe") for n in wanted):
            raise Refused("the build tools carry no x64 signtool.exe")
        for name in wanted:
            target = work / "tools" / pathlib.PurePosixPath(name).name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(nupkg.read(name))
    return work / "tools" / "signtool.exe"


def sign(msix: pathlib.Path, out: pathlib.Path) -> None:
    if os.name != "nt":
        raise Refused("`sign` runs signtool, a Windows program: it runs on the release's Windows runner")
    cert_der = certificate()
    pfx_b64, password = os.environ.get(PFX_ENV), os.environ.get(PASSWORD_ENV)
    if not pfx_b64 or password is None:
        raise Refused(f"no signer: {PFX_ENV} and {PASSWORD_ENV} are the `msix-signing` environment's secrets, and this "
                      f"step reads them from its own env only")
    out.mkdir(parents=True, exist_ok=True)
    signed = out / msix.name
    if signed.exists():
        raise Refused(f"{signed} exists already")
    work = pathlib.Path(tempfile.mkdtemp(prefix="msix-sign-"))
    try:
        tool = signtool(work)
        pfx = work / "secret" / "signer.pfx"
        pfx.parent.mkdir()
        shutil.copyfile(msix, signed)
        try:
            pfx.write_bytes(base64.b64decode(pfx_b64, validate=True))
            done = subprocess.run([str(tool), "sign", "/q", "/fd", "SHA256", "/f", str(pfx), "/p", password, str(signed)],
                                  capture_output=True, text=True)
        finally:
            shutil.rmtree(pfx.parent, ignore_errors=True)
        if done.returncode != 0:
            # signtool's own words, never its command line: the password is on it
            raise Refused(f"signtool exited {done.returncode}: {(done.stderr or done.stdout).strip()[-400:]}")
        block_map = compare(msix, signed, cert_der)
    except BaseException:
        signed.unlink(missing_ok=True)
        raise
    finally:
        shutil.rmtree(work, ignore_errors=True)
    (out / CERTIFICATE_ASSET).write_bytes(cert_der)
    print(f"msix_sign: {signed.name} signed by {subject(cert_der)} (thumbprint {thumbprint(cert_der)}); "
          f"{BLOCK_MAP} byte-identical (sha256 {block_map}), every payload file unchanged")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("certificate")
    c.add_argument("--certificate", type=pathlib.Path, default=None)
    k = sub.add_parser("compare")
    k.add_argument("--unsigned", type=pathlib.Path, required=True)
    k.add_argument("--signed", type=pathlib.Path, required=True)
    k.add_argument("--certificate", type=pathlib.Path, default=None)
    s = sub.add_parser("sign")
    s.add_argument("--msix", type=pathlib.Path, required=True)
    s.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "certificate":
            der = certificate(args.certificate)
            print(f"msix_sign: {subject(der)}, thumbprint {thumbprint(der)}")
        elif args.command == "compare":
            der = None if args.certificate is None else der_of(args.certificate.read_bytes())
            block_map = compare(args.unsigned, args.signed, der)
            print(f"msix_sign: {args.signed.name} is {args.unsigned.name} signed"
                  f"{'' if der is None else f' by {subject(der)} ({thumbprint(der)})'}; {BLOCK_MAP} sha256 {block_map}")
        else:
            sign(args.msix, args.out)
    except Refused as e:
        print(f"msix_sign: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
