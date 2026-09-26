# Upstream → port mapping

Upstream: `cedar-policy/vscode-cedar`. Kotlin paths are relative to `src/main/kotlin/io/github/thefellow/cedar/`.
"core" files are near line-for-line ports against the `vscode/` shim; "ide" files wire them into the
IntelliJ Platform.

## Extension sources (`src/*.ts`)

| Upstream | Core port | IntelliJ integration |
| --- | --- | --- |
| `src/extension.ts` (activation, registrations) | — | `META-INF/plugin.xml` + `META-INF/cedar-*.xml`; commands in `ide/actions/` |
| `src/commands.ts` | `core/Commands.kt` | action ids in `META-INF/cedar-actions.xml` |
| `src/about.ts` | — | `ide/actions/CedarActions.kt` (`AboutAction`) |
| `src/parser.ts` | `core/Parser.kt` (+ `core/Js.kt` JS-semantics helpers) | semantic tokens: `ide/navigation/CedarNavigation.kt` |
| `src/regex.ts` | `core/Regex.kt` | — |
| `src/fileutil.ts` | `core/FileUtil.kt` | `saveTextAndFormat`: `ide/actions/SchemaActions.kt`; rename/delete handlers: `ide/validation/CedarValidationListeners.kt`; workspace services: `ide/adapters/IdeWorkspace.kt` |
| `src/diagnostics.ts` | `core/Diagnostics.kt` | `ide/validation/CedarValidationService.kt`, `CedarValidationAnnotator.kt` |
| `src/validate.ts` | `core/Validate.kt` | `ide/validation/*` |
| `src/format.ts` | `core/Format.kt` | `ide/validation/CedarFormatting.kt` (formatting service, code style) |
| `src/quickfix.ts` | `core/QuickFix.kt` | quick fixes in `ide/validation/CedarValidationAnnotator.kt` |
| `src/codelens.ts` | `core/CodeLens.kt` | `ide/validation/CedarCodeVision.kt` |
| `src/completion.ts` | `core/Completion.kt` | `ide/completion/CedarCompletion.kt`, `Snippets.kt` |
| `src/completionjson.ts` | `core/CompletionJson.kt` | `ide/completion/CedarCompletion.kt`, `AddEntity.kt` |
| `src/help.ts` | `core/Help.kt` | — |
| `src/hover.ts` | `core/Hover.kt` | `ide/completion/CedarDocumentation.kt` |
| `src/signaturehelp.ts` | `core/SignatureHelp.kt` | `ide/completion/CedarParameterInfo.kt` |
| `src/definition.ts` | `core/Definition.kt` | `ide/navigation/CedarNavigation.kt` (goto declaration), `CedarProviders.kt` |
| `src/documentsymbols.ts` | `core/DocumentSymbols.kt` | `ide/navigation/CedarStructureView.kt`, folding in `CedarNavigation.kt` |
| `src/policy.ts` | `core/Policy.kt` | `ide/actions/ExportActions.kt` |
| `src/provider.ts` | `core/Provider.kt` | `ide/actions/JsonPreview.kt` |
| `src/generate.ts`, `src/cedarschema.d.ts` | `core/Generate.kt` | `ide/actions/ExportActions.kt` |

The VS Code API itself maps to `vscode/` (types) and `ide/adapters/` (documents, workspace, ranges);
`jsonc-parser`'s `visit` maps to `jsonc/Jsonc.kt`.

## Other upstream files

| Upstream | Port |
| --- | --- |
| `vscode-cedar-wasm/src/*.rs` | `cedar-wasm/src/*.rs` (same modules; results serialized as JSON; `lib.rs` is the JSON C-ABI dispatcher) |
| `vscode-cedar-wasm/Cargo.toml`, `Cargo.lock`, `build.rs` | `cedar-wasm/` (same Cedar crate versions) |
| `vscode-cedar-wasm/pkg` (wasm-bindgen JS glue) | `wasm/CedarWasm.kt` (Chicory runtime), `wasm/Cedar.kt` (typed API) |
| `syntaxes/cedar.tmLanguage.json`, `cedarschema.tmLanguage.json` | `src/main/resources/syntaxes/` verbatim, run by `textmate/`, lexed by `ide/lang/TmLexer.kt` |
| `syntaxes/codeblock.json` (markdown injection) | `ide/lang/CedarCodeFence.kt` (`cedar`/`cedarschema` fences) |
| `language-configuration.json` | `ide/lang/CedarSyntax.kt` (commenter, brace matcher), `CedarEditing.kt` (quote handler) |
| `package.json` `contributes.languages` | `plugin.xml` file types, `ide/lang/Languages.kt` |
| `package.json` `contributes.commands` / `menus` | `META-INF/cedar-actions.xml`, `ide/actions/` |
| `package.json` `contributes.configuration` | `ide/settings/CedarSettings.kt` |
| `package.json` `version` | `gradle.properties` `pluginVersion`, `cedar-wasm/Cargo.toml` |
| `icons/` | `src/main/resources/icons/`, `META-INF/pluginIcon.svg` |
| `testdata/` | `testdata/` |
| `schemas/` (JSON Schemas, not enabled upstream) | not ported |
| `src/test/suite/tmgrammar.test.ts` | `src/test/kotlin/.../textmate/TmGrammarTest.kt` |
| `src/test/suite/validation.test.ts`, `cedar-wasm.test.ts` | `core/ValidationSuiteTest.kt`, `core/CedarWasmSuiteTest.kt` (under `src/test/kotlin/...`) |
| `src/test/suite/completion.test.ts`, `completionjson.test.ts`, `extension.test.ts` | `src/test/kotlin/.../core/CompletionTest.kt` |
| `vscode-cedar-wasm/tests`, Rust unit tests | `cedar-wasm/src/*.rs` `#[cfg(test)]` |
| `.github/`, `Dockerfile`, `eslint.config.mjs`, `tsconfig.json`, `package-lock.json`, `docs/marketplace` | no analog (JetBrains build: `build.gradle.kts`, `.github/workflows/build.yml`) |
