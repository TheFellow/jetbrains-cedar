# jetbrains-cedar semantic-port stewardship

This directory tracks [`cedar-policy/vscode-cedar`](https://github.com/cedar-policy/vscode-cedar)
as an upstream behavioral contract. The goal is feature parity expressed as an idiomatic
IntelliJ Platform plugin — not a transliteration of VS Code API calls.

The initial port covers the complete reachable history through
`e592133f33e4e9d2dd93ea7ee2e003e7e4e28e66` (vscode-cedar 0.10.6, Cedar SDK 4.13.0, 29 commits).
Every reachable commit up to there is a `baseline` event in `ledger.tsv`. Later commits are
discovered and processed one at a time in topology-safe, oldest-first order.

[`MAPPING.md`](MAPPING.md) maps every upstream file to its counterpart here.

## Ledger model

`ledger.tsv` is an append-only event log. Existing bytes are never edited, sorted, or deleted.

| Column | Meaning |
| --- | --- |
| `event` | Contiguous local event sequence. |
| `sha` | Canonical 40-character upstream Git object ID. |
| `upstream_iso8601` | Upstream committer timestamp, including UTC offset. |
| `disposition` | `baseline`, `pending`, `implemented`, `acknowledged`, or `wedged`. |
| `evidence` | `-`, or the canonical skip/wedge breadcrumb path. |

The only legal transition is `pending` to one terminal disposition:

- `implemented`: a faithful port passed `scripts/semport_validate.sh`, semantic review, and commit gates.
- `acknowledged`: independent review found no port required. A detailed
  `semport/skipped/<full-sha>.md` breadcrumb is mandatory.
- `wedged`: a port attempt could not be made green. A detailed `semport/wedged/<full-sha>.md`
  breadcrumb and recoverable Git stash are mandatory. This is visible parity debt.

The tool rejects closing any commit other than the oldest pending commit.

## Ledger commands

The ignored upstream checkout lives at `inspiration/vscode-cedar`:

```sh
git clone https://github.com/cedar-policy/vscode-cedar.git inspiration/vscode-cedar
git -C inspiration/vscode-cedar fetch origin main
```

```sh
# Append every previously unseen reachable commit as pending.
python3 semport/ledger.py discover --upstream inspiration/vscode-cedar --revision origin/main

# Print the one commit that may be processed next, or CAUGHT_UP.
python3 semport/ledger.py next

# Append a terminal event; only the current `next` SHA is accepted.
python3 semport/ledger.py transition <full-sha> implemented|acknowledged|wedged

# Validate all state transitions and breadcrumb files.
python3 semport/ledger.py verify --repository .
python3 semport/ledger.py status
```

## Running a semport

Two equivalent drivers use the same ledger, scripts and gates:

- **Claude Code**: run `/semport` in this repository (see `.claude/skills/semport/SKILL.md`).
  It processes pending upstream commits one at a time with separate analysis, implementation
  and independent review agents, and stops when caught up. `/semport 1` processes at most one.
- **Attractor / F#kYeah**: [`semport.dot`](semport.dot) encodes the same bounded workflow as a graph:

  ```sh
  attractor --validate semport/semport.dot
  attractor semport/semport.dot
  ```

Both drivers:

1. Require a clean worktree, refresh the ignored upstream clone, append newly discovered commits,
   and select exactly one oldest pending SHA.
2. Analyze that upstream diff and its tests as either `PORT` or `SKIP`.
3. Independently review skips before appending an `acknowledged` event.
4. For ports, produce a concrete plan, implement it, and enforce the path boundary
   (`scripts/semport_scope.sh`).
5. Run the validation contract (`scripts/semport_validate.sh`): `cargo test` for the Cedar SDK
   bridge, `./gradlew test`, `buildPlugin` and `verifyPluginStructure`.
6. Review behavioral faithfulness independently.
7. Append the terminal event only after approval, verify the ledger, and create one local commit
   per upstream commit.
8. On a failed retry, preserve work in a named Git stash, append a `wedged` event with evidence,
   and commit only the breadcrumb and ledger event.

Neither driver pushes. Scratch files go under ignored `.ai/`.

## Typical upstream changes and where they land

| Upstream change | Port |
| --- | --- |
| Upstream version bump (`package.json` `version`) | `pluginVersion` in `gradle.properties` (drop any fourth component) and a `CHANGELOG.md` section mirroring upstream's entry; release by tagging `v<version>`. |
| Cedar SDK bump (`vscode-cedar-wasm/Cargo.toml`) | `cedar-wasm/Cargo.toml` + `cargo update -p cedar-policy --precise <v>` (and the other cedar crates); fix any API drift in `cedar-wasm/src/*.rs`; bump `pluginVersion` if upstream bumped its version. |
| `vscode-cedar-wasm/src/*.rs` logic | Same file under `cedar-wasm/src/` (keep structure; results are JSON-serialized). |
| `syntaxes/*.json` | Copy verbatim to `src/main/resources/syntaxes/`; port changed cases of `src/test/suite/tmgrammar.test.ts` to `TmGrammarTest.kt`. |
| `src/*.ts` logic | The mapped Kotlin file (near line-for-line); IntelliJ wiring in `ide/` if the VS Code registration changed. |
| `package.json` contributions (commands, menus, settings) | `plugin.xml` / `META-INF/cedar-*.xml`, actions, settings. |
| `testdata/` | Mirror into `testdata/`. |
| npm dev-dependency bumps, VS Code packaging, CI | Usually `SKIP` with a breadcrumb explaining the absent JetBrains analog. |

## Integrity checks

```sh
scripts/test_semport.sh                   # unit tests + evidence verification
scripts/test_semport.sh --base origin/main  # also proves ledger.tsv only grew
```
