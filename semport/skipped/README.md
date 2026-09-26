# Deliberate skip breadcrumbs

An `acknowledged` ledger event must point to
`semport/skipped/<full-sha>.md`. The file records:

- the full SHA, subject, author, and upstream date;
- every upstream path changed;
- why the change has no JetBrains plugin effect; and
- the evidence inspected, including related jetbrains-cedar behavior or tests.

"TypeScript-specific" is not sufficient by itself. The explanation must show why no
observable plugin behavior (highlighting, diagnostics, completion, commands, Cedar SDK results) or
packaging, performance or security property needs to change. "VS Code-specific" is likewise not sufficient: explain why the JetBrains analog is absent or unaffected.
