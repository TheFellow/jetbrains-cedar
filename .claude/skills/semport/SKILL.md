---
name: semport
description: Sync jetbrains-cedar with upstream cedar-policy/vscode-cedar - discover new upstream commits and semantically port them one at a time (analyze PORT/SKIP, implement in Kotlin/Rust, validate, independent review, ledger event, local commit). Use when asked to semport, sync with upstream, catch up with vscode-cedar, or check for upstream changes.
---

# Semport: follow upstream vscode-cedar

Read `semport/README.md` (ledger model and gates), `semport/MAPPING.md` (upstream file -> port file) and
`AGENTS.md` before starting. The ledger tool is authoritative; never edit `semport/ledger.tsv` by hand.

Arguments: an optional maximum number of upstream commits to process (default: until caught up).

## Loop (one upstream commit per iteration)

1. **Preflight.** `git status --porcelain --untracked-files=all` must be empty (otherwise stop and report).
   `mkdir -p .ai`.
2. **Select.** Run `python3 semport/ledger.py next`. If `CAUGHT_UP`: ensure `inspiration/vscode-cedar` exists
   (clone `https://github.com/cedar-policy/vscode-cedar.git`), `git -C inspiration/vscode-cedar fetch origin main`,
   `python3 semport/ledger.py discover --upstream inspiration/vscode-cedar --revision origin/main`. If the ledger
   changed, `python3 semport/ledger.py verify --repository .` and commit only `semport/ledger.tsv` as
   `semport: discover upstream vscode-cedar commits`. Run `next` again; if still `CAUGHT_UP`, report and stop.
3. **Analyze** (spawn a fresh agent). Give it the full SHA. It inspects
   `git -C inspiration/vscode-cedar show --find-renames <sha>`, the relevant upstream sources/tests and the mapped
   port files, and writes `.ai/semport_decision.md` whose FIRST LINE is exactly `PORT` or `SKIP`, followed by the
   behavioral delta, upstream references, port targets (Kotlin/Rust/resources), VS Code -> IntelliJ mapping, and
   proposed tests. SKIP only when there is provably no observable JetBrains plugin effect; for SKIP it also writes
   `semport/skipped/<full-sha>.md` per `semport/skipped/README.md`.
4. **If SKIP: independent skip review** (a *different* fresh agent). It audits the whole upstream diff against the
   breadcrumb. If approved: `python3 semport/ledger.py transition <sha> acknowledged`, verify, stage only the ledger
   and breadcrumb, commit `semport: acknowledge <12-char-sha> - <subject>`. If it finds a required port: delete the
   breadcrumb and continue with step 5 using its plan.
5. **If PORT: implement** (fresh agent). It implements only this commit's delta, keeping ported modules near
   line-for-line with upstream (same names, comments, TODOs), adds/ports tests, and must not touch ledger or
   breadcrumbs. Then run `scripts/semport_scope.sh` and `scripts/semport_validate.sh` (report in
   `.ai/semport_validation.md`). On failure allow ONE focused repair round, then re-validate.
6. **Review** (a *different* fresh agent): behavioral parity with the upstream commit (messages, ranges, edge cases),
   fidelity of structure, IntelliJ threading rules, cache thread-safety, JS-vs-Java regex differences, tests.
   FIRST LINE `APPROVED` or `REVISE` in `.ai/semport_review.md`. On REVISE allow ONE fix round + validation +
   final review.
7. **Commit.** On approval: `python3 semport/ledger.py transition <sha> implemented`, verify ledger and
   `python3 scripts/semport_guard.py`, stage the port + `semport/ledger.tsv` (never `.ai/` or `inspiration/`),
   commit `semport: implement <12-char-sha> - <subject>` with a body summarizing behavior and tests.
8. **Wedge** when the repair budget is exhausted: `git stash push --include-untracked -m "semport wedge <sha>"`,
   write `semport/wedged/<sha>.md` per its README (include the stash ref and failures), transition `wedged`,
   commit only breadcrumb + ledger as `semport: wedge <12-char-sha> - port needs intervention`.
9. Remove `.ai/semport_*` scratch files and continue with the next commit.

Never push. Never rewrite ledger history. Finish with a short summary: commits processed and their dispositions,
tests run, and anything wedged.
