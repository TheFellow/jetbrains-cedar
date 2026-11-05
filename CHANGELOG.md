# Changelog

Versions follow upstream [vscode-cedar](https://github.com/cedar-policy/vscode-cedar/blob/main/CHANGELOG.md).
A fourth version component (e.g. `0.10.6.1`) marks plugin-only releases on top of an upstream version.

## 0.10.6.4 - 2026-11-04

- Cedar schema files (`.cedarschema`) now fold: every multi-line `{...}` and `[...]` block can be collapsed
- Settings take a list of schema files and folders (relative to the project root, or absolute) instead of a single schema file. When they resolve to more than one schema, every policy and entities file is validated against all of them combined, and completion, go to definition and the code lens use every schema
- A schema file may use types declared in another configured schema file. A namespace (or an unqualified declaration) may only be declared once: a second declaration is reported as "already declared" in the file that repeats it
- The existing single schema file setting is migrated to the new list automatically

## 0.10.6.3 - 2026-10-19

- Opening files and navigating to symbols no longer uses deprecated or override-only IntelliJ Platform API

## 0.10.6.2 - 2026-10-05

- Cedar schema annotation values (e.g. `@doc("An organization in Acme")`) are no longer scanned as schema syntax, so text inside them no longer gets type highlighting or produces bogus type references

## 0.10.6.1 - 2026-09-25

- Description states that this is an unofficial port of the VS Code extension
- Plugin icon for dark themes
- Change notes on the Marketplace
- About Cedar Extension no longer uses internal IntelliJ Platform API
- Marketplace publishing workflow and Plugin Verifier checks in CI

## 0.10.6 - 2026-09-25

- Initial release: a semantic port of vscode-cedar 0.10.6 (Cedar SDK 4.13.0)
- Cedar policy and Cedar schema syntax highlighting, including Markdown code blocks
- Validation of Cedar policies, schemas and entities, with quick fixes
- Formatting, completion, signature help, hover, go to declaration, structure view and folding
- Commands: export policies as JSON, JSON preview, translate schemas, export schema diagrams, add entities
