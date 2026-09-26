// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.lang

import com.intellij.lang.Language
import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.thefellow.cedar.textmate.TmGrammar
import io.github.thefellow.cedar.textmate.TmToken
import io.github.thefellow.cedar.textmate.TmTokenizer
import java.util.concurrent.ConcurrentHashMap

/** Element type for a TextMate token; [scopes] are outermost-first, as produced by [TmTokenizer]. */
class TmElementType(val scopes: List<String>, language: Language) :
    IElementType("TM:" + scopes.drop(1).joinToString(" ").ifEmpty { "text" }, language) {
    val innermost: String get() = scopes.last()
}

/** Token types for one language: a few fixed ones for PSI/brace matching, the rest per scope list. */
class TmTokenTypes(val language: Language) {
    val comment = IElementType("LINE_COMMENT", language)
    val lParen = IElementType("(", language)
    val rParen = IElementType(")", language)
    val lBrace = IElementType("{", language)
    val rBrace = IElementType("}", language)
    val lBracket = IElementType("[", language)
    val rBracket = IElementType("]", language)
    val stringQuote = IElementType("STRING_QUOTE", language)
    private val byScopes = ConcurrentHashMap<List<String>, TmElementType>()

    fun forToken(scopes: List<String>, text: CharSequence): IElementType {
        val inner = scopes.last()
        if (scopes.any { it.startsWith("comment") }) return comment
        if (text.isNotEmpty() && text.all { it.isWhitespace() } && scopes.size == 1) return TokenType.WHITE_SPACE
        if (text.length == 1 && inner.startsWith("punctuation.section.brackets")) {
            when (text[0]) {
                '(' -> return lParen
                ')' -> return rParen
                '{' -> return lBrace
                '}' -> return rBrace
                '[' -> return lBracket
                ']' -> return rBracket
            }
        }
        return byScopes.computeIfAbsent(scopes) { TmElementType(it, language) }
    }
}

/**
 * IntelliJ lexer over a [TmGrammar]. The whole range is tokenized on [start]; state 0 is reported only at
 * line starts outside any open begin/end rule, so incremental re-lexing restarts at safe points.
 */
class TmLexer(grammar: TmGrammar, private val types: TmTokenTypes) : LexerBase() {
    private val tokenizer = TmTokenizer(grammar)
    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokens: List<TmToken> = emptyList()
    private var restartable: BooleanArray = BooleanArray(0)
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        val out = mutableListOf<TmToken>()
        val restart = mutableListOf<Int>()
        var state = tokenizer.initialState
        var lineStart = startOffset
        while (lineStart <= endOffset) {
            var lineEnd = lineStart
            while (lineEnd < endOffset && buffer[lineEnd] != '\n') lineEnd++
            if (state.isInitial) restart += out.size
            state = tokenizer.tokenizeLine(buffer, lineStart, lineEnd, state, out)
            if (lineEnd < endOffset) out += TmToken(lineEnd, lineEnd + 1, listOf("newline"))
            if (lineEnd >= endOffset) break
            lineStart = lineEnd + 1
        }
        // split unscoped text into whitespace / non-whitespace runs so PSI sees proper whitespace
        val split = mutableListOf<TmToken>()
        val splitRestart = BooleanArray(out.size + 1).also { arr -> restart.forEach { arr[it] = true } }
        val restartFlags = mutableListOf<Boolean>()
        out.forEachIndexed { i, t ->
            val first = split.size
            if (t.scopes.size == 1 && t.scopes[0] != "newline") {
                var s = t.start
                while (s < t.end) {
                    val ws = buffer[s].isWhitespace()
                    var e = s + 1
                    while (e < t.end && buffer[e].isWhitespace() == ws) e++
                    split += TmToken(s, e, t.scopes)
                    s = e
                }
            } else {
                split += t
            }
            for (j in first until split.size) restartFlags += (j == first && splitRestart[i])
        }
        tokens = split
        restartable = restartFlags.toBooleanArray()
        index = 0
    }

    override fun getState() = if (index < restartable.size && restartable[index]) 0 else 1

    override fun getTokenType(): IElementType? {
        if (index >= tokens.size) return null
        val t = tokens[index]
        if (t.scopes.size == 1 && t.scopes[0] == "newline") return TokenType.WHITE_SPACE
        return types.forToken(t.scopes, buffer.subSequence(t.start, t.end))
    }

    override fun getTokenStart() = tokens[index].start
    override fun getTokenEnd() = tokens[index].end
    override fun advance() { index++ }
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = endOffset
}
