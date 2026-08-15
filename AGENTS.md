<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **Gahyeon** (21178 symbols, 38708 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/Gahyeon/context` | Codebase overview, check index freshness |
| `gitnexus://repo/Gahyeon/clusters` | All functional areas |
| `gitnexus://repo/Gahyeon/processes` | All execution flows |
| `gitnexus://repo/Gahyeon/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

# Gahyeon AI Quality Loop

For changes involving conversational AI, STT, TTS, voice delivery, weather tools, or their deployment:

- MUST use `$gahyeon-quality-loop` when it is installed; otherwise follow the same repository contracts manually.
- MUST convert every confirmed production failure into an entry in `quality/ai-regressions.json` backed by an executable deterministic test.
- MUST define the incident evidence window, expected invariant, privacy-safe production signal, and rollback trigger.
- MUST run `bash scripts/run_ai_quality_gate.sh` before committing affected code.
- MUST distinguish `no qualifying traffic` from `no failures observed`; absence of traffic is not production validation.
- MUST record the source commit, artifact or image digest, deployment revision, observation window, and qualifying request count when reporting a deployment as validated.
- MUST blind and randomize subjective TTS candidate comparisons and preserve checkpoint, prompt-set, runtime, and synthesis-setting identities.
- MUST create a machine-readable contract with `scripts/create_autonomy_contract.py` for multi-step autonomous work and follow the single next action from `scripts/decide_autonomy_action.py`.
- MUST keep machine-verifiable work autonomous. Ask the user only for a listed human gate, a material scope expansion, new external authority, or a HIGH/CRITICAL GitNexus risk decision.
- MUST use `scripts/evaluate_canary_observation.py` for production promotion decisions. A `rollback` verdict is actionable only when rollback authority was explicitly granted; otherwise stop and request that authority.
- MUST verify generated Piper listening reviews with `scripts/verify_blind_tts_review.py` before asking for subjective selection.
