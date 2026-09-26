# AGENTS.md — jetbrains-cedar

A JetBrains (IntelliJ Platform) plugin that is a **semantic port** of
[cedar-policy/vscode-cedar](https://github.com/cedar-policy/vscode-cedar). Upstream is the behavioral contract;
`semport/` tracks which upstream commits have been ported (see `semport/README.md`).

## Layout

| Path | What |
| --- | --- |
| `cedar-wasm/` | Rust crate: upstream `vscode-cedar-wasm/src/*.rs`, same files, but exposed through one JSON C-ABI export (`cedar_call`) instead of wasm-bindgen. Built for `wasm32-wasip1` by the Gradle `cargoBuild` task. |
| `src/main/kotlin/.../cedar/wasm` | Runs the wasm module in the JVM with [Chicory](https://chicory.dev) (pure Java, no native libs). `Cedar` mirrors upstream's `vscode-cedar-wasm` TypeScript API. |
| `src/main/kotlin/.../cedar/vscode` | A tiny re-creation of the VS Code API types upstream uses (Position, Range, TextDocument, Diagnostic, CompletionItem, ...). Lets upstream modules port near line-for-line. |
| `src/main/kotlin/.../cedar/core` | Ports of upstream `src/*.ts`, one Kotlin file per TS file (see `semport/MAPPING.md`). IDE-independent, unit-testable. |
| `src/main/kotlin/.../cedar/jsonc`, `textmate` | Ports of `jsonc-parser`'s `visit`, and a TextMate grammar interpreter that runs upstream's `syntaxes/*.tmLanguage.json` verbatim. |
| `src/main/kotlin/.../cedar/ide` | IntelliJ integration: languages/lexer/highlighting (`lang`), adapters (`adapters`), settings, validation/annotator/formatter (`validation`), structure view/folding/goto/semantic highlighting (`navigation`), completion/parameter info/documentation (`completion`), commands (`actions`). |
| `src/main/resources/META-INF/` | `plugin.xml` includes one fragment per feature area (`cedar-*.xml`). |
| `testdata/` | Mirror of upstream `testdata/`. |
| `semport/` | Ledger, pipeline (`semport.dot`), differential harness (`differential/run.sh`). |

## Rules for ports

- Keep ported `core/` files structurally close to upstream: same function and type names, same comments/TODOs,
  same order. Functions that reach for `vscode.workspace`/`window` take a leading `workspace: Workspace`.
- Replicate JavaScript semantics where they matter (`core/Js.kt`: `indexOf` with a start, falsy `0`/`""`,
  `substring` clamping, regex `match`/`matchAll`); prefer Java regex constructs equivalent to the JS ones.
- Caches in `core/` are keyed by `uri.toString()` + document version like upstream and must be thread-safe
  (IntelliJ calls from background threads).
- IntelliJ threading: document/PSI reads in read actions, writes in write commands on the EDT, Cedar SDK calls
  off the EDT where possible (`CedarWasm` calls are serialized).
- `syntaxes/*.json` are copied verbatim from upstream; don't edit them here.
- Don't edit `semport/ledger.tsv` by hand; use `semport/ledger.py`.

## Build and test

Tools are pinned in `mise.toml` (Java 25, Gradle, Rust + `wasm32-wasip1`, Python, Node).

```sh
mise install
./gradlew test                  # Kotlin unit + IntelliJ platform tests (builds the wasm first)
./gradlew buildPlugin           # build/distributions/jetbrains-cedar-<version>.zip
./gradlew runIde                # sandboxed IDE (GoLand by default) with the plugin
scripts/semport_validate.sh     # the full validation contract used by semports
semport/differential/run.sh     # upstream TS vs Kotlin output comparison (parser.ts, generate.ts)
```
