# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root
- **`docs/adr/`** — read ADRs that touch the area you're about to work in
- **`docs/roadmap.md`** — the execution-level plan (phases, tech decisions, dependency graph); read it alongside `CONTEXT.md`/ADRs when the work touches sequencing or scope
- **`docs/engineering-pitfalls-and-highlights.md`** — numbered pitfall/highlight entries cross-referenced by `#N` from the roadmap; check it when implementing anything the roadmap flags with a number

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo:

```
/
├── CONTEXT.md
├── docs/
│   ├── README.md              ← doc index: what each file is for, when to update it
│   ├── architecture.md
│   ├── roadmap.md
│   ├── engineering-pitfalls-and-highlights.md
│   ├── interview-narrative.md
│   ├── validation-report.md   ← internal-only, not for external display
│   └── adr/
│       ├── 0001-runtime-scope-and-stack.md
│       └── 0002-hand-rolled-loop-as-v1-mainline.md
└── src/
```

This repo is single-context (single Maven module, no monorepo signals) — no `CONTEXT-MAP.md`.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md` (Runtime / Capability Pack / Tool / Skill / Hook / LlmClient / Gateway). Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0002 (hand-rolled loop as V1 mainline) — but worth reopening because…_

## Disclosure rule for anything written in this repo

This repo's code and its externally-visible docs (`architecture.md`, `roadmap.md`, ADRs, `engineering-pitfalls-and-highlights.md`, `interview-narrative.md`) must read as solo original work — never write "ported from X" or name a specific other personal repo as a source of literal code reuse. "I studied a real production-shaped framework's source + design rationale, then independently designed and implemented my own version" is fine and expected; naming the specific other repo/module as where code was moved from is not. `validation-report.md` is the one exception — it's explicitly internal-only and not subject to this rule.
