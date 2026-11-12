---
title: Verify a release
description: Check veyqctl's own signature once, then a release's image signatures and files before anything runs them.
sources:
  - docs/crosscutting/release-and-deployment.md
  - docs/decisions/0057-release-identity-and-deployment-recipe.md
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
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
replaces it with the newest release's `veyqctl`, checked as above against its `veyqctl.bundle`, and then held to the
release it was offered as: a released `veyqctl` names its own release (`veyqctl --version`), so a source cannot pass off
an older signed copy as the newest. It needs no instance, since one `veyqctl` serves every instance on the computer,
and it never replaces a `veyqctl` with an older one.

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
what verifies agree. It rejects every other workflow in the repository — `publish.yml`, which signs every
snapshot, first among them — every other branch, pull-request and tag refs, every other owner, and every repository
whose name only starts with `veyq`: a looser pattern accepts a signature the veyq release never made, and a check that
accepts anything a fork signs checks nothing. The upstream is written out rather than left as a placeholder for the
same reason. If you deploy your own fork's images, verify them against **your** fork's identity, knowingly.
`scripts/validate_release_identity.py` holds every command above, and `veyqctl`'s own pair, to that identity.

**A release, never a snapshot.** A green commit of `main` is published as a snapshot once a week, and any green
commit of `main` or a `release/<major>.x` branch when the maintainer asks for one: its images as
`<major>.<minor>-SNAPSHOT` and `sha-<commit>`, written once and never replaced, signed by `publish.yml`. A release is a snapshot promoted: the **same digests** get `<major>.<minor>`, `<major>` and `latest`, and a
second signature, by `release.yml`. So the image's own version says nothing about which one you have — a snapshot
reports the release it may become — and the name says little more, because a tag can move; **the release identity
above is the answer**: a digest that verifies under it is a release, and one that does not is not. A snapshot has no
GitHub Release, and `veyqctl` never installs one; it is for trying a build on an instance you may throw away, never a
rollback target. Major `0` is pre-release and promises no upgrade path.

## How far this is proven

**Run against v1.0, as written** (2026-11-10, cosign 3.1.3): `cosign verify-blob` accepted `veyqctl` and
`manifest.json` under the identity above, and refused `veyqctl` with one byte appended; `cosign verify` accepted the
app, admin and tools images by the digests the manifest names, and the chart by the digest its `1.0` tag resolves to;
every file the manifest lists matched its sha256 (`shasum -a 256 -c`, macOS's equivalent of `sha256sum -c`); and
`veyqctl verify --release 1.0` verified the manifest and both images and pulled nothing. The other direction:
`0.2478-SNAPSHOT`'s app digest, never promoted, is refused under the identity above (its certificate names
`publish.yml`). A promoted release's digests are its snapshot's bytes, so they also verify under the snapshot
identity — which is why the release identity, not the snapshot's, is the check. Before it publishes, the promotion
verifies `manifest.json` and `veyqctl` against their bundles the way this page does, and refuses to publish a release
that fails it. `veyqctl upgrade`'s refusal of a digest one hex digit off was run against a release produced from the
tree; no upgrade between two published releases has run yet.
