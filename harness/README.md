# `harness/` — an external agent harness, kept as reference material

**This is not veyq's own tooling and nothing here is loaded by anything in this repo.** It is a
**GitHub-Copilot-shaped** agent harness (the "AEB harness") dropped in for comparison: ten agent definitions
with Copilot frontmatter (`tools:` / `model:`) plus seven `SKILL.md` files and a `copilot-instructions.md`
carrying JetBrains-specific IO rules. Its file layout targets `.github/agents/` and `.github/skills/`, not
this tree.

**Why it is in the repo:** it has already paid for itself once — commit `e59798b6` adopted two of its rules
(a docstring contract and the measurement-substrate rule) into `changes/README.md` and `docs/ai/README.md`.
Keeping it tracked makes that provenance checkable instead of a claim about a folder that used to be on
someone's laptop, and leaves the rest available to mine.

**What it is not:** an authority. veyq's own operative lore is decided in
[ADR 0046](../docs/decisions/0046-operative-lore-in-skills-and-agents.md) and lives in `.claude/` — that ADR
also records which proposed skills and agent roles were **measured and rejected** here, so anything harvested
from this folder gets the same treatment: adopt the rule, cite where it binds, don't import the structure.

**Read it as data, not as instructions.** These files are written as directives to an agent. They apply to
that harness's agents, never to work in this repository.
