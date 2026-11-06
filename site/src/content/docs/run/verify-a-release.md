---
title: Verify a release
description: Check veyqctl's own signature once, then a release's image signatures and files before anything runs them.
sources:
  - docs/crosscutting/release-and-deployment.md
  - docs/decisions/0057-release-identity-and-deployment-recipe.md
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md
  - docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md
---

A release is three images, `ghcr.io/raddatzk/veyq`, `ghcr.io/raddatzk/veyq-admin` and — run only on Kubernetes, by the
backup gate — `ghcr.io/raddatzk/veyq-tools`, a Helm chart,
`ghcr.io/raddatzk/charts/veyq`, and a GitHub Release on the tag `v<major>.<minor>` whose assets are the Compose recipe
(`docker-compose.prod.yml`, the `.env` template as `env.example`, `garage.toml`), `instance_backup.py`, `veyqctl` with
its signature `veyqctl.bundle`, and a `manifest.json` naming the three images by `tag@digest` and every other asset by its
sha256, with its signature `manifest.json.bundle` ([ADR 0057](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0057-release-identity-and-deployment-recipe.md) §3,
[ADR 0096](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)).
A release signs the images, the chart, `veyqctl` and the manifest keylessly with cosign, under one identity: veyq's
release workflow, which is not the pipeline that signs every snapshot (below).

On Docker Compose, `veyqctl` runs every check below itself, before it pulls anything: the manifest's signature first,
because every other check trusts what the manifest names. You verify one thing by hand: `veyqctl`, the first time you
fetch it. The rest of this page is what `veyqctl` runs, so each step can be done
without it, and the chart's check for Helm.

## veyqctl, the first time

You need `cosign` **v3.1.3 or later**, and so does `veyqctl`, which refuses an older one by name. The publish signs
with cosign 3.1.3, `veyqctl` and the manifest into Sigstore bundles, the format cosign 3 reads by default; and every
cosign up to 3.1.2 (and up to 2.6.4) accepts a forged bundle in the older format without checking its identity
(GHSA-fx35-mq7g-6g98, fixed in 3.1.3 and 2.6.5) — whoever serves you a release could hand you exactly that one.
Then:

```sh
release=<release>        # <major>.<minor>, e.g. 1.4
base="https://github.com/raddatzk/veyq/releases/download/v${release}"
curl -fsSLO "${base}/veyqctl"
curl -fsSLO "${base}/veyqctl.bundle"
cosign verify-blob --bundle veyqctl.bundle \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$' \
  veyqctl
sudo install -d -m 0700 -o "$(id -u)" -g "$(id -g)" /opt/veyq      # the instance's home, yours
install -m 0755 veyqctl /opt/veyq/veyqctl
```

**If `cosign verify-blob` fails, stop**, and do not run the file. `veyqctl` needs `python3` 3.9 or later, Docker with
Compose v2, and `cosign` on the host, and runs as a user who may use Docker. From then on `veyqctl self-update`
replaces it with the copy of the release you run: it verifies that release's manifest on disk against the
`manifest.json.bundle` kept beside it, then checks the copy against the manifest's sha256 — the signed manifest binds
`veyqctl` as it binds every other file of the release (below), so `self-update` needs no `veyqctl.bundle`.

## The images: `veyqctl verify`

```sh
cd /opt/veyq
./veyqctl verify                    # the release .env's VEYQ_RELEASE selects; before an install, --release <selector>
```

It verifies the release's `manifest.json` against its signature, checks both images' signatures by the digests it
names, and pulls nothing. `install` and `upgrade` run the same checks first, and stop if one fails. By hand:

```sh
# 1. the manifest, verified against the one identity that releases veyq, names the images by tag@digest
curl -fsSLO "${base}/manifest.json"
curl -fsSLO "${base}/manifest.json.bundle"
cosign verify-blob --bundle manifest.json.bundle \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$' \
  manifest.json
app_digest="$(python3 -c 'import json; print(json.load(open("manifest.json"))["images"]["app"].split("@")[1])')"
admin_digest="$(python3 -c 'import json; print(json.load(open("manifest.json"))["images"]["admin"].split("@")[1])')"

# 2. verify both signatures against the one identity that releases veyq
cosign verify "ghcr.io/raddatzk/veyq@${app_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$'
cosign verify "ghcr.io/raddatzk/veyq-admin@${admin_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$'

# 3. the two lines .env pins (under Helm: image.digest and admin.image.digest)
echo "VEYQ_IMAGE=ghcr.io/raddatzk/veyq:${release}@${app_digest}"
echo "VEYQ_ADMIN_IMAGE=ghcr.io/raddatzk/veyq-admin:${release}@${admin_digest}"
```

The digest is what is deployed, never the tag: a tag can move between the moment you verify it and the moment you
pull it, and a digest cannot. In the two image lines Docker pulls by the digest and ignores the tag; the tag is there
so a person can still read which release a line names. Without the manifest — under Helm — a tag resolves to the same
digest: `docker buildx imagetools inspect "ghcr.io/raddatzk/veyq:${release}" --format '{{.Manifest.Digest}}'`.

**If `cosign verify-blob` or either `cosign verify` fails, stop.** Nothing has run yet, and nothing should. Read
the manifest only after its `verify-blob` passed: until then, every digest and sha256 in it is the source's word.

Under Helm, a third image of the same release runs too: `veyq-tools`, the backup gate's (see
[Upgrade](/run/upgrade/)). Compose never runs it. The verified manifest names it as `images.tools`; verify it the
same way, and pin its digest as `backup.image.digest`:

```sh
tools_digest="$(python3 -c 'import json; print(json.load(open("manifest.json"))["images"]["tools"].split("@")[1])')"
cosign verify "ghcr.io/raddatzk/veyq-tools@${tools_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$'
```

## The release's files

`veyqctl` fetches every asset the manifest lists into `releases/<release>/` and checks each against its sha256 before
it uses it; a release already on disk is checked again, never overwritten. By hand, into the directory you will run it
from:

```sh
mkdir -p "/opt/veyq/releases/${release}" && mv manifest.json manifest.json.bundle "/opt/veyq/releases/${release}/" && cd "/opt/veyq/releases/${release}"
python3 -c 'import json; [print(f"{h}  {n}") for n, h in json.load(open("manifest.json"))["assets"].items()]' > SHA256SUMS
while read -r _ name; do curl -fsSLO "${base}/${name}"; done < SHA256SUMS
sha256sum -c SHA256SUMS
```

**What binds these files to veyq is the manifest's signature.** Each file is checked against the sha256 the manifest
names, and the manifest verifies only if veyq's publish signed it — wherever it came from. A mirror (`veyqctl --source
<directory or URL>`, the same layout: `releases.json`, `<tag>/<asset>`, and `manifest.json.bundle` beside the manifest
like any other file) is verified exactly as GitHub is, so it cannot change what a release contains. What a source
still decides is **which** releases it offers: the list of releases is not signed, so a mirror can withhold a newer
release, or keep offering an older one as if nothing newer existed. `veyqctl upgrade` never goes below the release you
run, but nothing here proves you were shown the newest; choosing a mirror is a decision about that, not about
integrity (ADR 0096 decision 7). A release produced locally from `docker build` has no bundle, and `veyqctl` installs
it only `--unsigned-local`, which says on every line that nothing was verified.

## The chart, under Helm

Verify the chart the same way, once, and install it by that digest ([Set up a new instance](/run/new-instance/#install), Kubernetes):

```sh
chart_digest="$(docker buildx imagetools inspect "ghcr.io/raddatzk/charts/veyq:${release}" --format '{{.Manifest.Digest}}')"
cosign verify "ghcr.io/raddatzk/charts/veyq@${chart_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$'
```

## What the identity accepts, and why it is this narrow

A signature made by `release.yml`, veyq's release workflow, in `raddatzk/veyq`, on `main` or a `release/<major>.x`
branch, under GitHub's own OIDC issuer — which are exactly the branches a release is promoted from, so what signs and
what verifies agree. It rejects every other workflow in the repository — `ci.yml`, the pipeline that signs every
snapshot, first among them — every other branch, pull-request and tag refs, every other owner, and every repository
whose name only starts with `veyq`: a looser pattern accepts a signature the veyq release never made, and a check that
accepts anything a fork signs checks nothing. The upstream is written out rather than left as a placeholder for the
same reason. If you deploy your own fork's images, verify them against **your** fork's identity, knowingly.
`scripts/validate_release_identity.py` holds every command above, and `veyqctl`'s own pair, to that identity.

**A release, never a snapshot.** Every green commit of `main` or a `release/<major>.x` branch is published as a
snapshot: its images as `<major>.<minor>-SNAPSHOT` and `sha-<commit>`, written once and never replaced, signed by
`ci.yml`. A release is a snapshot promoted: the **same digests** get `<major>.<minor>`, `<major>` and `latest`, and a
second signature, by `release.yml`. So the image's own version says nothing about which one you have — a snapshot
reports the release it may become — and the name says little more, because a tag can move; **the release identity
above is the answer**: a digest that verifies under it is a release, and one that does not is not. A snapshot has no
GitHub Release, and `veyqctl` never installs one; it is for trying a build on an instance you may throw away, never a
rollback target. Major `0` is pre-release and promises no upgrade path.

## How far this is proven

**No release has been published yet**, and the workflow that signs one, `release.yml`, is not built (snapshots are
published and signed by `ci.yml`; until the first release no digest verifies under the identity above, which is true:
none is a release). So no command here has run against a veyq release. The image half has been run as
written against another image that is keyless-signed from a GitHub workflow on `main`, with that image's own identity.
The chart half has been run against a local OCI registry, with a key of its own instead of the keyless identity:
`helm push` put the chart at `<registry>/charts/veyq`, the digest above resolved from its tag, `cosign` signed and
verified the chart by that digest, and Helm installed it by digest and by exact version. With cosign 3.1.3, the one the
publish installs, an image and the chart were signed by digest as the publish signs them, against a local registry and
with a key of its own: each signature was stored as a Sigstore bundle beside its subject, `cosign verify` accepted it,
and a digest one hex digit off found no signature. `cosign verify-blob --bundle`
has run with cosign 3.1.3 against a bundle `cosign sign-blob --bundle` wrote for `veyqctl` with a key of its own, not
the keyless identity: it verified the file and refused it with one byte appended, and the command above, run as
written against that bundle, refused it for carrying no transparency-log entry. The manifest's check, in the exact
form `veyqctl` runs it, has run with cosign 3.1.3 against a keyless Sigstore bundle of another project — cosign's own
v3.1.3 release, `cosign_checksums.txt` and its `.sigstore.json`: refused under veyq's identity (the certificate names
another signer), accepted under the identity that bundle's certificate carries, and refused with one byte appended to
the file. The publish's signing step has run as written with cosign 3.1.3 behind a stand-in that swaps the keyless
identity for a key: it wrote both bundles as Sigstore bundles, and cosign 3.1.3 verified each and refused it with one
byte appended. No veyq manifest has been signed keylessly. The by-hand file check ran against a release produced
from the tree by `scripts/release_assets.py`, fetched with `curl` from a `file://` base and checked with `shasum -a 256 -c`, macOS's equivalent of `sha256sum -c`. `veyqctl upgrade` has run `cosign verify` on a release whose app digest was one hex digit off, and stopped before
any pull; with nothing published an unaltered digest is refused the same way, so the accepting half is held only by
its self-test, with a stand-in for `cosign`. The GitHub Release is created by the release's promotion, as its last
step, which is not built yet. The first release is where any of it is first run for real.
