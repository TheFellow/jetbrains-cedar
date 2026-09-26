// generate.ts differential driver (run via semport/differential/run.sh)
import * as fs from 'node:fs';
import * as path from 'node:path';
import { generateDiagram, SchemaExportType } from './generate.ts';

const [testdata, fixtures, out] = process.argv.slice(2);
const walk = (d: string): string[] => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]).sort();
const isSchemaJson = (f: string) => path.basename(f) === 'cedarschema.json' || f.endsWith('.cedarschema.json');

for (const root of [testdata, fixtures]) for (const f of walk(root).filter(isSchemaJson)) {
  const rel = path.basename(root) + '/' + path.relative(root, f);
  let json: any;
  try { json = JSON.parse(fs.readFileSync(f, 'utf8')); } catch { continue; }
  if (json === null || typeof json !== 'object' || Array.isArray(json)) continue;
  for (const type of [SchemaExportType.PlantUML, SchemaExportType.Mermaid]) {
    let text: string;
    try { text = generateDiagram('diagram', json, type); } catch { text = 'THREW'; }
    const o = path.join(out, 'generate-ts', `${rel}.${type}`);
    fs.mkdirSync(path.dirname(o), { recursive: true });
    fs.writeFileSync(o, text);
  }
}
