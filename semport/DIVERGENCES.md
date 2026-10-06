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
