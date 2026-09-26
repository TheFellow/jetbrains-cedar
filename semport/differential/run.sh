#!/usr/bin/env bash
# Differential test: run upstream's own src/parser.ts (under node, with stub `vscode` and
# `vscode-cedar-wasm` modules and the real jsonc-parser) and our Kotlin port over testdata/ plus
# semport/differential/fixtures, then compare every entry point's output field by field.
#
# Usage: semport/differential/run.sh [upstream-checkout]   (default: inspiration/vscode-cedar)
set -euo pipefail
cd "$(dirname "$0")/../.."
here=semport/differential
upstream="${1:-inspiration/vscode-cedar}"
work=.ai/differential
rm -rf "$work" && mkdir -p "$work/node_modules/jsonc-parser"

# upstream sources, made importable by node's type stripping
cp "$upstream/src/parser.ts" "$upstream/src/regex.ts" "$work/"
sed -i.bak \
  -e "s|^import { DEFAULT_RANGE } from './diagnostics';|const DEFAULT_RANGE = new vscode.Range(new vscode.Position(0, 0), new vscode.Position(0, 0));|" \
  -e "s|from './regex';|from './regex.ts';|" "$work/parser.ts"
cp "$here/parser.main.ts" "$work/main.ts"
echo '{"type":"module"}' > "$work/package.json"
cp -R "$here/stubs/vscode" "$here/stubs/vscode-cedar-wasm" "$work/node_modules/"

# the jsonc-parser version upstream locks
version=$(python3 -c "import json;print(json.load(open('$upstream/package-lock.json'))['packages']['node_modules/jsonc-parser']['version'])")
curl -sfL "https://registry.npmjs.org/jsonc-parser/-/jsonc-parser-$version.tgz" | tar xz -C "$work"
mv "$work/package/lib" "$work/node_modules/jsonc-parser/lib" && rm -rf "$work/package"
echo '{"type":"commonjs"}' > "$work/node_modules/jsonc-parser/lib/package.json"
cat > "$work/node_modules/jsonc-parser/index.js" <<'JS'
import { createRequire } from 'node:module';
const m = createRequire(import.meta.url)('./lib/umd/main.js');
export const visit = m.visit;
JS
echo '{"name":"jsonc-parser","type":"module","exports":"./index.js"}' > "$work/node_modules/jsonc-parser/package.json"

# Kotlin side (also writes schema translations the TS stub replays)
CEDAR_PARSER_DUMP="$PWD/$work/dump" CEDAR_PARSER_FIXTURES="$PWD/$here/fixtures" \
  mise exec -- ./gradlew --no-configuration-cache -q test --tests '*ParserDumpTest*' --rerun

# TypeScript side
mise exec -- node --experimental-strip-types --no-warnings "$work/main.ts" testdata "$here/fixtures" "$work/dump"

mise exec -- python3 "$here/cmp.py" "$work"
