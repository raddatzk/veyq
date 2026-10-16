---
title: Verify a release
description: Resolve a veyq release to digests and check its cosign signatures before anything runs it.
sources:
  - docs/decisions/0057-release-identity-and-deployment-recipe.md
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
---

A release is two images, `ghcr.io/raddatzk/veyq` and `ghcr.io/raddatzk/veyq-admin`, and a Helm chart,
`ghcr.io/raddatzk/charts/veyq`, and the publish signs all three keylessly with cosign
([ADR 0057](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0057-release-identity-and-deployment-recipe.md) §3,
[ADR 0064](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0064-kubernetes-helm-deployment-target.md)). This is the one procedure for a first
install, an upgrade and a Helm install alike. It ends with the references you deploy, **by digest**: a tag can
move between the moment you verify it and the moment you pull it, and a digest cannot.

```sh
version=<version>        # the release you are about to run, <major>.<minor>, e.g. 1.4 — never a -SNAPSHOT

# 1. resolve each tag to its multi-arch index digest ONCE — everything below uses the digest, never the tag
app_digest="$(docker buildx imagetools inspect "ghcr.io/raddatzk/veyq:${version}" --format '{{.Manifest.Digest}}')"
admin_digest="$(docker buildx imagetools inspect "ghcr.io/raddatzk/veyq-admin:${version}" --format '{{.Manifest.Digest}}')"

# 2. verify both signatures against the one identity that publishes veyq
cosign verify "ghcr.io/raddatzk/veyq@${app_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$'
cosign verify "ghcr.io/raddatzk/veyq-admin@${admin_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$'

# 3. pull both by digest, and extract the recipe from the verified app image
docker pull "ghcr.io/raddatzk/veyq-admin@${admin_digest}"
docker create --name veyq-recipe "ghcr.io/raddatzk/veyq@${app_digest}" >/dev/null
docker cp veyq-recipe:/opt/veyq/deploy/. "./recipe-${version}/"
docker rm veyq-recipe >/dev/null

# 4. the two lines infra/.env pins (under Helm: image.digest and admin.image.digest)
echo "VEYQ_IMAGE=ghcr.io/raddatzk/veyq:${version}@${app_digest}"
echo "VEYQ_ADMIN_IMAGE=ghcr.io/raddatzk/veyq-admin:${version}@${admin_digest}"
```

Under Helm, verify the chart the same way, once, and install it by that digest ([Set up a new instance](/veyq/run/new-instance/#install), Kubernetes):

```sh
chart_digest="$(docker buildx imagetools inspect "ghcr.io/raddatzk/charts/veyq:${version}" --format '{{.Manifest.Digest}}')"
cosign verify "ghcr.io/raddatzk/charts/veyq@${chart_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$'
```

**If either `cosign verify` fails, stop.** Nothing has run yet, and nothing should.

**What the identity accepts, and why it is this narrow.** A signature made by `ci.yml`, veyq's one pipeline, in
`raddatzk/veyq`, on `main` or a `release/<major>.x` branch, under GitHub's own OIDC issuer — which are exactly the
branches the publish runs from, so what signs and what verifies agree. It rejects every other workflow in the
repository, every other branch, pull-request and tag refs, every other owner, and every repository whose name
only starts with `veyq`: a looser pattern accepts a signature the veyq release never made, and a check that
accepts anything a fork signs checks nothing. The upstream is written out rather than left as a placeholder for
the same reason. If you deploy your own fork's images, verify them against **your** fork's identity, knowingly.
`scripts/validate_release_identity.py` holds this command to what the workflow signs.

In the two image lines, Docker pulls by the digest and ignores the tag; the tag is there so a person can still
read which release a line names.

**Pin a release, never a snapshot.** A publish is a release (`<major>.<minor>`, which never moves) or a snapshot
(`<major>.<minor>-SNAPSHOT`, which the next publish of that commit replaces); both are signed by the same identity,
so the signature does not tell them apart and the name does. A snapshot is for trying a build on an instance you
may throw away: it is never a rollback target. `sha-<commit>` names the last publish of a commit, not a release.
Major `0` is pre-release and promises no upgrade path.

**Nothing has been published yet**, so this procedure has never run against a veyq release. The image half has been
run as written against another image that is keyless-signed from a GitHub workflow on `main`, with that image's own
identity. The chart half has been run against a local OCI registry, with a key of its own instead of the keyless
identity: `helm push` put the chart at `<registry>/charts/veyq`, the digest above resolved from its tag, `cosign`
signed and verified the chart by that digest, and Helm installed it by digest and by exact version. The first
publish is where any of it is first run for real.
