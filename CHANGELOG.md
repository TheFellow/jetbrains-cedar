# Changelog

Versions follow upstream [vscode-cedar](https://github.com/cedar-policy/vscode-cedar/blob/main/CHANGELOG.md).
A fourth version component (e.g. `0.10.6.1`) marks plugin-only releases on top of an upstream version.

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
