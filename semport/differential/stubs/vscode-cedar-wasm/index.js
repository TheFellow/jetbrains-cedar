export function translateSchemaToJSON(text) {
  const t = globalThis.__translations.get(text);
  if (!t) throw new Error('no translation for text');
  return { success: t.success, schema: t.schema ?? undefined, free() {} };
}
