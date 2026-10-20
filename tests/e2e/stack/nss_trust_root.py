#!/usr/bin/env python3
"""Trust ONE root in Chromium's NSS database, inside the Playwright image, with no `certutil` (ADR 0090 decision 3).

Runs as the browser container's first step (`tests/e2e/stack/docker-compose.e2e.yml`, service `browser`), before
Playwright: it creates `<db>` (Chromium on Linux reads `$HOME/.pki/nssdb`) and imports the run's Pebble root into it
as a trusted TLS issuer (`C,,`, what `certutil -A -t "C,,"` sets). Nothing else is trusted beyond Chromium's own
root store, so a certificate that does not chain to this run's Pebble — Traefik's default one above all — fails the
navigation.

**Why not `certutil`.** It is `libnss3-tools`, which the Playwright image does not carry; installing it would need
the network at run time (the run needs none once its images are pulled) or a derived image of our own. The image does
carry `libnss3` — Chromium links it — and Python, so this calls the five NSS functions `certutil -A` itself calls,
through `ctypes`. Measured 2026-10-16 against `mcr.microsoft.com/playwright:v1.64.1-noble` (arm64): a root trusted this
way made Chromium (headless shell and full) load a page whose leaf it issued; the same page without it failed with
`ERR_CERT_AUTHORITY_INVALID`. A Playwright image that drops libnss3 or Python is red here, loudly, before any test.

Usage: python3 nss_trust_root.py <root.pem> <db-dir>
"""
from __future__ import annotations

import ctypes
import os
import ssl
import sys

NICKNAME = b"veyq-e2e-pebble-root"
TRUST = b"C,,"  # a trusted CA for TLS servers; nothing for email or code signing


def main() -> int:
    pem_path, db_dir = sys.argv[1], sys.argv[2]
    with open(pem_path, encoding="ascii") as f:
        der = ssl.PEM_cert_to_DER_cert(f.read())
    os.makedirs(db_dir, mode=0o700, exist_ok=True)

    nss = ctypes.CDLL("libnss3.so")
    smime = ctypes.CDLL("libsmime3.so")  # where NSS exports CERT_DecodeCertFromPackage
    p = ctypes.c_void_p
    for lib, name, res, args in (
        (nss, "NSS_InitReadWrite", ctypes.c_int, [ctypes.c_char_p]),
        (nss, "PK11_GetInternalKeySlot", p, []),
        (nss, "PK11_NeedUserInit", ctypes.c_int, [p]),
        (nss, "PK11_InitPin", ctypes.c_int, [p, ctypes.c_char_p, ctypes.c_char_p]),
        (smime, "CERT_DecodeCertFromPackage", p, [ctypes.c_char_p, ctypes.c_int]),
        (nss, "PK11_ImportCert", ctypes.c_int, [p, p, ctypes.c_ulong, ctypes.c_char_p, ctypes.c_int]),
        (nss, "CERT_DecodeTrustString", ctypes.c_int, [p, ctypes.c_char_p]),
        (nss, "CERT_GetDefaultCertDB", p, []),
        (nss, "CERT_ChangeCertTrust", ctypes.c_int, [p, p, p]),
        (nss, "CERT_DestroyCertificate", None, [p]),
        (nss, "PK11_FreeSlot", None, [p]),
        (nss, "NSS_Shutdown", ctypes.c_int, []),
        (nss, "PORT_GetError", ctypes.c_int, []),
    ):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = res, args

    def ok(status: int, what: str) -> None:
        if status != 0:  # SECSuccess
            raise SystemExit(f"nss_trust_root: {what} failed, NSS error {nss.PORT_GetError()}")

    ok(nss.NSS_InitReadWrite(f"sql:{db_dir}".encode()), "NSS_InitReadWrite")
    slot = nss.PK11_GetInternalKeySlot()
    if nss.PK11_NeedUserInit(slot):  # a fresh db: an empty password, as `certutil -N --empty-password`
        ok(nss.PK11_InitPin(slot, None, b""), "PK11_InitPin")
    cert = smime.CERT_DecodeCertFromPackage(der, len(der))
    if not cert:
        raise SystemExit(f"nss_trust_root: {pem_path} is not a certificate, NSS error {nss.PORT_GetError()}")
    ok(nss.PK11_ImportCert(slot, cert, 0, NICKNAME, 0), "PK11_ImportCert")
    trust = (ctypes.c_uint * 3)()  # CERTCertTrust: ssl, email, object-signing flags
    ok(nss.CERT_DecodeTrustString(trust, TRUST), "CERT_DecodeTrustString")
    ok(nss.CERT_ChangeCertTrust(nss.CERT_GetDefaultCertDB(), cert, trust), "CERT_ChangeCertTrust")
    nss.CERT_DestroyCertificate(cert)
    nss.PK11_FreeSlot(slot)
    ok(nss.NSS_Shutdown(), "NSS_Shutdown")
    print(f"nss_trust_root: {pem_path} trusted ({TRUST.decode()}) in sql:{db_dir}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
