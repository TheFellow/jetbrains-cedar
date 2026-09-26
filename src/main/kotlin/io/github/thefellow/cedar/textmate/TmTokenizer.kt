// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.textmate

/** A token: [start, end) within the tokenized text, with scopes outermost-first (grammar scope first). */
data class TmToken(val start: Int, val end: Int, val scopes: List<String>)

/**
 * Line-by-line tokenizer over a [TmGrammar], following TextMate's rule selection: the earliest match
 * wins, ties go to the end pattern of the enclosing begin/end rule and then to pattern order.
 */
class TmTokenizer(private val grammar: TmGrammar) {

    private class Frame(val rule: TmGrammar.BeginEndRule, val end: Regex, val scopes: List<String>)

    /** State carried between lines: the stack of open begin/end rules. */
    class State internal constructor(internal val frames: List<Any>) {
        val isInitial get() = frames.isEmpty()
        override fun equals(other: Any?) = other is State && frames == other.frames
        override fun hashCode() = frames.hashCode()
    }

    val initialState = State(emptyList())

    fun tokenize(text: String): List<TmToken> {
        val tokens = mutableListOf<TmToken>()
        var state = initialState
        var lineStart = 0
        while (lineStart <= text.length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = text.length
            state = tokenizeLine(text, lineStart, lineEnd, state, tokens)
            if (lineEnd < text.length) tokens += TmToken(lineEnd, lineEnd + 1, listOf(grammar.scopeName))
            lineStart = lineEnd + 1
        }
        return tokens
    }

    /** Tokenizes `text[lineStart, lineEnd)` (no line terminator) appending to [out]; returns the next state. */
    fun tokenizeLine(text: CharSequence, lineStart: Int, lineEnd: Int, state: State, out: MutableList<TmToken>): State {
        val line = text.subSequence(lineStart, lineEnd).toString()
        @Suppress("UNCHECKED_CAST")
        val stack = ArrayDeque(state.frames as List<Frame>)
        val base = listOf(grammar.scopeName)
        var pos = 0

        fun emit(start: Int, end: Int, scopes: List<String>) {
            if (end <= start) return
            out += TmToken(lineStart + start, lineStart + end, scopes)
        }

        fun emitCaptures(m: MatchResult, scopes: List<String>, captures: Map<Int, String>) {
            val s = m.range.first
            val e = m.range.last + 1
            if (captures.isEmpty()) { emit(s, e, scopes); return }
            // innermost capture per char: higher group numbers are nested deeper
            val groups = captures.keys.sorted().mapNotNull { g ->
                m.groups[g]?.let { Triple(g, it.range.first, it.range.last + 1) }
            }
            var segStart = s
            var segScopes: List<String>? = null
            for (i in s until e) {
                val covering = groups.filter { (_, gs, ge) -> i in gs until ge }
                var sc = scopes
                if (captures[0] != null) sc = sc + captures.getValue(0)
                for ((g, _, _) in covering) if (g != 0) sc = sc + captures.getValue(g)
                if (sc != segScopes) {
                    if (segScopes != null) emit(segStart, i, segScopes)
                    segStart = i
                    segScopes = sc
                }
            }
            if (segScopes != null) emit(segStart, e, segScopes)
        }

        var guard = 0
        while (pos <= line.length && guard++ < 10_000) {
            val frame = stack.lastOrNull()
            val scopes = frame?.scopes ?: base
            val rules = frame?.rule?.patterns?.invoke() ?: grammar.patterns

            var bestRule: TmGrammar.Rule? = null
            var bestMatch: MatchResult? = null
            var endMatch: MatchResult? = null
            if (frame != null) {
                endMatch = frame.end.find(line, pos)
            }
            for (rule in rules) {
                val re = when (rule) {
                    is TmGrammar.MatchRule -> rule.match
                    is TmGrammar.BeginEndRule -> rule.begin
                }
                val m = re.find(line, pos) ?: continue
                if (bestMatch == null || m.range.first < bestMatch.range.first) {
                    bestMatch = m
                    bestRule = rule
                    if (m.range.first == pos) break
                }
            }

            if (endMatch != null && (bestMatch == null || endMatch.range.first <= bestMatch.range.first)) {
                emit(pos, endMatch.range.first, frame!!.contentScopes())
                val outer = frame.scopes
                emitCaptures(endMatch, outer, frame.rule.endCaptures)
                stack.removeLast()
                val newPos = endMatch.range.last + 1
                pos = newPos
                if (endMatch.value.isEmpty() && newPos >= line.length) break
                continue
            }

            if (bestMatch == null || bestRule == null) {
                emit(pos, line.length, frame?.contentScopes() ?: base)
                break
            }

            emit(pos, bestMatch.range.first, frame?.contentScopes() ?: base)
            val matchEnd = bestMatch.range.last + 1
            when (val rule: TmGrammar.Rule = bestRule) {
                is TmGrammar.MatchRule -> {
                    val sc = (frame?.contentScopes() ?: base).let { if (rule.name != null) it + rule.name else it }
                    emitCaptures(bestMatch, sc, rule.captures)
                    pos = if (matchEnd == bestMatch.range.first) {
                        // zero-length match: consume one character to make progress
                        if (pos < line.length) emit(pos, pos + 1, frame?.contentScopes() ?: base)
                        pos + 1
                    } else matchEnd
                }
                is TmGrammar.BeginEndRule -> {
                    val sc = (frame?.contentScopes() ?: base).let { if (rule.name != null) it + rule.name else it }
                    emitCaptures(bestMatch, sc, rule.beginCaptures)
                    stack.addLast(Frame(rule, rule.endRegex(bestMatch), sc))
                    pos = matchEnd
                }
            }
            if (pos > line.length) break
        }
        return State(stack.toList())
    }

    private fun Frame.contentScopes() = if (rule.contentName != null) scopes + rule.contentName else scopes
}
