# Contributing to veyq

Thank you for considering it. For anything larger than a fix, open an issue first, so we can agree on the shape
before you spend time on it. A security problem is never an issue or a pull request: see [SECURITY.md](SECURITY.md).

## Licence: what you contribute stays AGPL

veyq is [AGPL-3.0-only](LICENSE), and so is everything contributed to it — under that licence and nothing wider.
There is no contributor licence agreement to sign, and nobody, the maintainer included, receives a right to
relicense your work. Why, and what that rules out, is [ADR 0095](docs/decisions/0095-contributions-come-in-under-the-agpl-with-a-dco-sign-off.md).

The veyq name and brand assets are not part of the licence ([NOTICE](NOTICE)): a fork runs under its own name.

## Sign off every commit

Each commit carries a sign-off: your statement, under the
[Developer Certificate of Origin 1.1](https://developercertificate.org/), that you wrote the change or otherwise
have the right to contribute it under the project's licence. It is one line at the end of the commit message,
naming the commit's author:

```
Signed-off-by: Jane Doe <jane@example.org>
```

`git commit -s` writes it for you. To add it to commits you already made on your branch:

```bash
git rebase --signoff main
```

A pull request whose commits lack it is not merged until they have it.

## Before you open the pull request

Each surface proves itself with its own check, named in its `CLAUDE.md`: `./gradlew check` in
[`backend/`](backend/CLAUDE.md), `npm run check` in [`web/`](web/CLAUDE.md) and [`admin-web/`](admin-web/CLAUDE.md).
A change to `docs/` or `contracts/` also runs the trust chain — every `scripts/validate_*.py` — which CI runs on
each pull request. The API contracts in `contracts/` are the source of truth: change the spec, never a generated
type. Conventions are in the [developer styleguide](docs/crosscutting/developer-styleguide.md).
