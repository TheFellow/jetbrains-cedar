# Deliberate divergences from upstream

Places where this port intentionally behaves differently from `cedar-policy/vscode-cedar`, usually to fix an
upstream bug before upstream does. Each entry is a small, self-contained patch on top of a faithful port, marked
in the code with a `DIVERGENCE (semport/DIVERGENCES.md#<anchor>)` comment.

When a semport touches code near a divergence, check whether upstream fixed the same problem:

- **Upstream fixed it**: port upstream's fix faithfully, delete our patch and its comment, keep (or adapt) the
  regression test, and remove the entry here. The patch should then be a no-op: the test passes either way.
- **Upstream changed the code but didn't fix it**: port the change and keep the patch.

`semport/differential/run.sh` compares upstream TS with this port, so its fixtures must avoid the inputs a
divergence changes (or the comparison will report the divergence as a diff).

## schema-annotation-values

**Upstream:** `src/parser.ts`, `parseCedarSchemaCedarDoc` (line scan for ` in `, `[...]`, attribute `:` and `//`),
as of `e592133`.
**Port:** `core/Parser.kt`, `maskAnnotationValues` / `ANNOTATION_VALUE_REGEX`.
**Test:** `ParserTest.schemaCedarAnnotationValuesAreNotScanned`.

Upstream scans each line of a `.cedarschema` file with string matching that doesn't skip annotation values, so
`@doc("An organization in Acme")` produces a `type` semantic token over `Acme` (it renders in the type color
inside the green string) and records `Acme::Acme` as a referenced type. `[...]` inside a value is treated as a
type/action list, `name: Type` inside a value within a declaration as an attribute type, and `//` inside a value
as the start of a comment.

The port replaces the contents of each `@name("...")` value with the same number of spaces before scanning the
line, so offsets are unchanged and nothing inside an annotation value is tokenized or referenced.

## schema-folding

**Upstream:** `src/extension.ts`, `registerFoldingRangeProvider` (registered for `{ language: 'cedar' }` only),
as of `e592133`.
**Port:** `ide/navigation/CedarNavigation.kt`, `CedarSchemaFoldingBuilder`; registered in
`META-INF/cedar-navigation.xml`.
**Test:** `NavigationPlatformTest.testSchemaFolding`, `NavigationPlatformTest.testSchemaFoldingNestedAndIgnoresStringsAndComments`.

Upstream registers no folding range provider for `cedarschema`, so VS Code falls back to its built-in
indentation-based folding. IntelliJ has no such fallback: without a `lang.foldingBuilder` a schema doesn't fold at
all. The port adds a builder that folds every multi-line `{...}` and `[...]` block, using the lexer's bracket tokens
so brackets inside comments and strings (e.g. `@doc("...")` values) are ignored. This approximates the VS Code
experience rather than reproducing its indentation folding exactly.

If upstream adds a `cedarschema` folding range provider, port it into `core/` (like `CedarFoldingRangeProvider`),
point the `cedarschema` builder at it, and remove this entry and `CedarSchemaFoldingBuilder`. Adapt the tests to the
ranges upstream produces.

## multiple-schema-files

**Upstream:** `src/fileutil.ts`, `getSchemaUri` / `getSchemaTextDocument` (one `cedar.schemaFile`, or one
auto-detected schema), as of `e592133`.
**Port:**
- `core/CombinedSchema.kt` (`CombinedSchemaDocument`, `combineSchemaDocuments`, `schemaPartResult`,
  `validateCombinedSchemaDoc`).
- Hooks marked `DIVERGENCE (semport/DIVERGENCES.md#multiple-schema-files)` in `core/FileUtil.kt`
  (`getSchemaUris`, `getSchemaTextDocument`), `core/Validate.kt` (`associateSchemaWithDoc`,
  `validateSchemaDoc`), `core/Definition.kt` (`findSchemaDefinition`), `core/CodeLens.kt`,
  `vscode/Workspace.kt` (`schemaFiles`), `wasm/Cedar.kt`.
- `cedar-wasm/src/schema_fragment.rs` and its `lib.rs` dispatch entries.
- IDE: `ide/settings/CedarSettings.kt` (list editor; `schemaFiles` state, migrating `schemaFile`),
  `ide/adapters/IdeWorkspace.kt`, `ide/actions/CedarActions.kt` (Open Cedar Schema opens all),
  `ide/validation/CedarCodeVision.kt`.

**Test:** `CombinedSchemaTest`, `ValidationIdeTest.testSettingsMigrateTheSingleSchemaFile`,
`ValidationIdeTest.testPolicyValidatedAgainstSeveralSchemas`.

Upstream validates every policy against at most one schema file. The port's setting is a list of schema files and
folders, each absolute or relative to the project root; a folder means the schema files directly in it, found
with upstream's auto-detect rules. One relative file entry behaves exactly like upstream's `cedar.schemaFile`,
including auto-detect taking precedence. More than one schema, a folder, or an absolute path: the configured
schemas are combined and used for every document, and auto-detect is skipped.

The schemas are treated as fragments of one schema: a file may use types declared in another. Cedar validation
resolves every type, so the files can't be validated one by one. Instead:

- Each Cedar file is translated to JSON as a fragment (types unresolved, new wasm export
  `translateSchemaFragmentToJSON`). The files are combined into an in-memory JSON schema
  (`CombinedSchemaDocument`, `languageId` `json`, version derived from the parts'). Policy and entities
  validation, completion and hover use it unchanged.
- As within one Cedar schema, a namespace may only be declared once. A file that declares a namespace an earlier
  file already declares gets ``namespace `App` is already declared in users.cedarschema`` on its declaration, and
  adds nothing to the combination. Unqualified declarations (no namespace) may come from several files, but a
  repeated one gets the same kind of error (``entity type `Thing` is already declared in a.cedarschema``).
- Each file gets syntax diagnostics as a fragment (`validateSchemaFragmentCedar` / `validateSchemaFragmentJSON`).
  Without "already declared" errors, errors in the combination as a whole are reported on every file, prefixed
  `Combined with the other configured schemas: `. For a type no file declares, upstream's undeclared-type
  handling also marks each use (with its quick fix).
- Go to definition and the "Validated using ..." code vision use the real files. Editing any of them
  re-validates the policies that use the combination.

If upstream adds support for several schemas, port it, remove this divergence and adapt the tests. If upstream
changes `getSchemaUri` / `getSchemaTextDocument` / `validateSchemaDoc`, keep the hooks above.
