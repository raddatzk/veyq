# Third-party license texts

The full text of every license a jar in the backend's fast-jar is under, one file per SPDX id, appended to the
generated `THIRD-PARTY-LICENSES.txt` the image ships (`buildSrc`'s `ThirdPartyLicenses`, run after every
`quarkusBuild`). Copied unchanged from SPDX's `license-list-data` at tag `v3.27.0` (`text/<id>.txt`); the MIT and BSD
files are the SPDX templates, so their `<year> <owner>` lines are placeholders — a component's own copyright notice
is in the license or notice file it ships, which the report reproduces above these texts.

A file is added here only together with its entry in `ThirdPartyLicenses.KNOWN`, which is where a new license is
admitted beside AGPL-3.0-only code; the build fails on a license it shipped without a text here.
