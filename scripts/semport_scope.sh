#!/usr/bin/env bash
# Fails when an implementation stage touched paths outside the port's scope.
set -euo pipefail

status=0
while IFS= read -r path; do
  case "$path" in
    src/*|cedar-wasm/src/*|cedar-wasm/Cargo.toml|cedar-wasm/Cargo.lock|cedar-wasm/build.rs|testdata/*|docs/*|README.md|CHANGELOG.md|NOTICE|build.gradle.kts|gradle.properties|semport/MAPPING.md|semport/DIVERGENCES.md)
      ;;
    semport/ledger.tsv|semport/skipped/*|semport/wedged/*)
      echo "semport_scope: implementation stage changed stewardship-owned path: $path" >&2
      status=1
      ;;
    .ai/*|inspiration/*)
      ;;
    *)
      echo "semport_scope: unexpected implementation path: $path" >&2
      status=1
      ;;
  esac
done < <(git status --porcelain --untracked-files=all | sed -E 's/^...//')

exit "$status"
