import * as fs from 'node:fs';
import * as path from 'node:path';
import * as P from './parser.ts';

const [testdata, fixtures, out] = process.argv.slice(2);  // run via semport/differential/run.sh
const walk = (d: string): string[] => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]).sort();
const r = (x: any) => x ? [x.start.line, x.start.character, x.end.line, x.end.character] : null;
const refs = (l: any[]) => l.map((x) => [x.name, r(x.range)]);
const completions = (c: any): any => Object.fromEntries(Object.entries(c).filter(([, v]) => v !== undefined).map(([k, rec]: any) => [k, Object.fromEntries(Object.entries(rec).map(([a, d]: any) => [a, { description: d.description, children: d.children ? completions({ x: d.children }).x : null }]))]));
const lang = (f: string) => f.endsWith('.cedar') ? 'cedar' : (path.basename(f) === 'cedarschema' || f.endsWith('.cedarschema')) ? 'cedarschema' : 'json';
(globalThis as any).__translations = new Map();

function split(text: string) { return text.split(/\r\n|\r|\n/); }
function doc(text: string, uriPath: string, languageId: string) {
  const lines = split(text);
  return { uri: { toString: () => 'file://' + uriPath }, languageId, version: 1, lineCount: lines.length, lineAt: (i: number) => ({ text: lines[i] }), getText: () => text };
}
const guard = (f: () => any) => { try { return f(); } catch (e: any) { return { THREW: String(e.message) }; } };

for (const root of [testdata, fixtures]) for (const f of walk(root)) {
  const rel = path.basename(root) + '/' + path.relative(root, f);
  const text = fs.readFileSync(f, 'utf8');
  const l = lang(f);
  const result: any = {};
  const texts: string[] = [];
  result.policies = guard(() => { const p = P.parseCedarPoliciesDoc(doc(text, `/p/${rel}`, l) as any, (_r: any, t: string) => texts.push(t));
    return { policies: p.policies.map((x: any) => ({ id: x.id, range: r(x.range), effectRange: r(x.effectRange) })), texts, tokens: p.tokens.tokens, referencedTypes: refs(p.referencedTypes), actionIds: refs(p.actionIds), annotations: Array.from(p.annotations) }; });
  if (l === 'json') {
    result.json = guard(() => { const j = P.parseCedarJsonPolicyDoc(doc(text, `/j/${rel}`, l) as any); return { tokens: j.tokens.tokens, referencedTypes: refs(j.referencedTypes), actionIds: refs(j.actionIds) }; });
    result.entities = guard(() => { const e = P.parseCedarEntitiesDoc(doc(text, `/e/${rel}`, l) as any); return { entities: e.entities.map((x: any) => ({ uid: x.uid, range: r(x.range), uidKeyRange: r(x.uidKeyRange), uidTypeRange: r(x.uidTypeRange), attrsKeyRange: r(x.attrsKeyRange), attrsRange: r(x.attrsRange), attrsNameRanges: Object.fromEntries(Object.entries(x.attrsNameRanges).map(([k, v]) => [k, r(v)])), parentsKeyRange: r(x.parentsKeyRange), parentsRange: r(x.parentsRange), tagsKeyRange: r(x.tagsKeyRange), tagsRange: r(x.tagsRange), tagsNameRanges: Object.fromEntries(Object.entries(x.tagsNameRanges).map(([k, v]) => [k, r(v)])) })), tokens: e.tokens.tokens, referencedTypes: refs(e.referencedTypes) }; });
    result.links = guard(() => { const t = P.parseCedarTemplateLinksDoc(doc(text, `/t/${rel}`, l) as any); return { links: t.links.map((x: any) => [x.id, r(x.range), r(x.linkIdRange)]), tokens: t.tokens.tokens, referencedTypes: refs(t.referencedTypes) }; });
    result.auth = guard(() => { const a = P.parseCedarAuthDoc(doc(text, `/a/${rel}`, l) as any); return { tokens: a.tokens.tokens, referencedTypes: refs(a.referencedTypes), actionIds: refs(a.actionIds) }; });
  }
  if (l !== 'cedar') {
    if (l === 'cedarschema') (globalThis as any).__translations.set(text, JSON.parse(fs.readFileSync(path.join(out, 'translate', rel + '.json'), 'utf8')));
    result.schema = guard(() => { const s = P.parseCedarSchemaDoc(doc(text, `/s/${rel}`, l) as any); return { definitionRanges: s.definitionRanges.map((x: any) => ({ collection: x.collection, etype: x.etype, enums: x.enums ?? null, range: r(x.range), etypeRange: r(x.etypeRange), symbol: x.symbol })), tokens: s.tokens.tokens, referencedTypes: refs(s.referencedTypes), entityTypes: s.entityTypes, actionIds: refs(s.actionIds), completions: completions(s.completions), tags: s.tags }; });
  }
  const o = path.join(out, 'ts', rel + '.json');
  fs.mkdirSync(path.dirname(o), { recursive: true });
  fs.writeFileSync(o, JSON.stringify(result, null, 2));
}
