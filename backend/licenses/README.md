# Third-party license texts

The full text of every license a jar in the backend's fast-jar is under, one file per SPDX id, appended to the
generated `THIRD-PARTY-LICENSES.txt` the image ships (`buildSrc`'s `ThirdPartyLicenses`, run after every
`quarkusBuild`). Copied unchanged from SPDX's `license-list-data` at tag `v3.27.0` (`text/<id>.txt`); the MIT and BSD
files are the SPDX templates, so their `<year> <owner>` lines are placeholders.

A file is added here only together with its entry in `ThirdPartyLicenses.KNOWN`, which is where a new license is
admitted beside AGPL-3.0-only code; the build fails on a license it shipped without a text here.

## `notices/` — the copyright lines a template lacks

A jar under MIT or BSD that carries no license file of its own gets its project's file here, as
`<group>.<module>.txt`, copied unchanged from the project's repository at the tag of the version the build ships. The
build fails on such a jar without one, and on a file no shipped jar needs (the dependency left, or now carries its
own), so a version bump that changes a holder line is the moment to re-copy it from the new tag.

| File | Copied from |
|---|---|
| `com.sun.istack.istack-commons-runtime.txt` | `github.com/eclipse-ee4j/jaxb-istack-commons`, tag `4.1.2`, `LICENSE.md` |
| `org.antlr.antlr4-runtime.txt` | `github.com/antlr/antlr4`, tag `4.13.2`, `LICENSE.txt` |
| `org.crac.crac.txt` | `github.com/CRaC/org.crac`, tag `1.5.0`, `LICENSE` |
| `org.ow2.asm.asm.txt` | `gitlab.ow2.org/asm/asm`, tag `ASM_9_9_1`, `LICENSE.txt` |
