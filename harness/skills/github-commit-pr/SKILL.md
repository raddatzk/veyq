---
name: github-commit-pr
description: 'Commit messages for git and github pull requests. Use when doing git commit or issuing a PR.'
---

### Commits

- **CM-01** Make sure, you know which AEBT Ticket is currently worked on, if you don't know, ask the user. Don't interfere or skip.
- **CM-02** Use **Conventional Commits** format — append `(GitHub Copilot generated)` to the description.
  Use full words (`feature`, `fix`, `docs`, `chore`) — not abbreviations (`feat`).
  The scope is the JIRA ticket number when one exists. **Look it up** from the Traceability
  table in `agent/plan/roadmap.md` before committing — do not guess:
  ```
  type(AEBT-NNNN): short description (GitHub Copilot generated)
  ```
- **CM-03** **Choosing the type:**
    - `docs` — all changed files are human-readable documentation (README, .adoc, .md outside
      `agent/record/` and `agent/plan/`)
    - `feature` — new functionality: production code, tests, agent files, templates, new tooling
    - `chore` — backlog/log/findings bookkeeping only; build config; cleanup
    - `fix` — correcting a bug or wrong behaviour in existing code or rules
      Examples:
    - `feature(AEBT-1069): add approval group endpoint (GitHub Copilot generated)`
    - `docs(AEBT-1129): rewrite README, add PROJECT_CONTEXT and onboarding stubs (GitHub Copilot generated)`
    - `chore(backlog): complete BL-031 verification (GitHub Copilot generated)` *(no ticket: use descriptive scope)*
- **CM-04** **One commit per completed BL item.** When a single prompt completes multiple items, make one
  commit per item with a focused message. Never batch unrelated BL items into one commit.
- **CM-05** **Do not commit autonomously.** Always wait for the user's signal before running `git add` /
  `git commit`, unless the user has explicitly granted blanket permission for the session.

### Pull Requests and Merging (component projects)

- **MG-01** **Squash-merge strategy:** all commits on a feature branch are squashed into one commit on
  `main`. The squash commit message = the conventional commit for the BL item.
- **MG-02** **PR creation on explicit request only.** When the user asks to open a PR, use GitHub CLI:
  `gh pr create --title "type(AEBT-NNNN): …" --body "…"`.
  The agent prepares and opens the PR; the human performs the merge on GitHub.
- **MG-03** `gh` must be installed (`brew install gh`) and authenticated (`gh auth login`) — see
  `documentation/onboarding/getting-started.adoc`.
