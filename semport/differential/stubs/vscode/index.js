export class Position {
  constructor(line, character) {
    if (line < 0) throw new Error('Illegal argument: line must be non-negative');
    if (character < 0) throw new Error('Illegal argument: character must be non-negative');
    this.line = line; this.character = character;
  }
  isBefore(o) { return this.line < o.line || (this.line === o.line && this.character < o.character); }
  isEqual(o) { return this.line === o.line && this.character === o.character; }
}
export class Range {
  constructor(a, b, c, d) {
    let start, end;
    if (typeof a === 'number') { start = new Position(a, b); end = new Position(c, d); } else { start = a; end = b; }
    if (!start || !end) throw new Error('Invalid arguments');
    if (end.isBefore(start)) { this.start = end; this.end = start; } else { this.start = start; this.end = end; }
  }
  isEqual(o) { return this.start.isEqual(o.start) && this.end.isEqual(o.end); }
}
export class SemanticTokensLegend { constructor(t, m) { this.tokenTypes = t; this.tokenModifiers = m; } }
export class SemanticTokensBuilder {
  constructor(legend) { this.legend = legend; this.data = []; }
  push(range, type, mods) {
    if (range.start.line !== range.end.line) throw new Error('multiline token');
    if (!this.legend.tokenTypes.includes(type)) throw new Error('Illegal argument - TokenType');
    for (const m of mods || []) if (!this.legend.tokenModifiers.includes(m)) throw new Error('Illegal argument - TokenModifier');
    this.data.push([range.start.line, range.start.character, range.end.line, range.end.character, type, (mods || []).join(',')]);
  }
  build() { return { tokens: this.data }; }
}
export const SymbolKind = { Class: 'Class', Struct: 'Struct', Function: 'Function' };
