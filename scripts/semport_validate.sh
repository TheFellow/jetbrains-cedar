#!/usr/bin/env bash
# The repository validation contract: Rust (Cedar SDK bridge) tests, Kotlin/IntelliJ tests,
# plugin packaging and structure verification. Writes a PASS/FAIL report to .ai/semport_validation.md.
set -uo pipefail

cd "$(dirname "$0")/.."
report=".ai/semport_validation.md"
details=".ai/semport_validation.details"
mkdir -p .ai
: >"$details"

status=0
run() {
  printf '\n$ %q' "$1" >>"$details"
  shift
  printf ' %q' "$@" >>"$details"
  printf '\n' >>"$details"
  "$@" >>"$details" 2>&1 || status=1
}

run cargo-test mise exec -- cargo test --manifest-path cedar-wasm/Cargo.toml --release --locked
run gradle-test mise exec -- ./gradlew --no-configuration-cache test
run gradle-build mise exec -- ./gradlew --no-configuration-cache buildPlugin verifyPluginStructure

if [[ "$status" -eq 0 ]]; then
  printf 'PASS\n\n' >"$report"
else
  printf 'FAIL\n\n' >"$report"
fi
cat "$details" >>"$report"
rm -f "$details"
exit "$status"
