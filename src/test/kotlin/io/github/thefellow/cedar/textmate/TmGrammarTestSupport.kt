// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.textmate

/** Port of upstream src/test/suite/tmgrammar.ts helpers, over [TmTokenizer]. */
object TmGrammarTestSupport {
    const val CEDAR = "source.cedar"
    const val CEDARSCHEMA = "source.cedarschema"

    data class Token(val text: String, val scopes: List<String>)

    private val grammars = mapOf(
        CEDAR to TmGrammar.load("/syntaxes/cedar.tmLanguage.json"),
        CEDARSCHEMA to TmGrammar.load("/syntaxes/cedarschema.tmLanguage.json"),
    )

    /** Tokenize source text, returning every token whose text is not pure whitespace. */
    fun tokenize(scopeName: String, source: String): List<Token> {
        val tokenizer = TmTokenizer(grammars.getValue(scopeName))
        val tokens = mutableListOf<Token>()
        var state = tokenizer.initialState
        for (line in source.split(Regex("\r?\n"))) {
            val out = mutableListOf<TmToken>()
            state = tokenizer.tokenizeLine(line, 0, line.length, state, out)
            for (t in out) {
                val text = line.substring(t.start, t.end)
                if (text.trim().isEmpty()) continue
                tokens += Token(text, t.scopes)
            }
        }
        return tokens
    }

    /** Scopes of the first token matching `text` (root scope removed); null when there is no such token. */
    fun scopesOf(scopeName: String, source: String, text: String): List<String>? {
        val hit = tokenize(scopeName, source).find { it.text == text || it.text.trim() == text } ?: return null
        return hit.scopes.filter { it != scopeName }
    }

    fun assertScope(scopeName: String, source: String, text: String, prefix: String) {
        val scopes = scopesOf(scopeName, source, text) ?: throw AssertionError("no token \"$text\" in \"$source\"")
        if (scopes.none { it.startsWith(prefix) }) {
            throw AssertionError("expected \"$text\" to carry a scope starting with \"$prefix\", got $scopes")
        }
    }

    fun assertNotScope(scopeName: String, source: String, text: String, prefix: String) {
        val scopes = scopesOf(scopeName, source, text) ?: throw AssertionError("no token \"$text\" in \"$source\"")
        if (scopes.any { it.startsWith(prefix) }) {
            throw AssertionError("expected \"$text\" NOT to carry \"$prefix\", got $scopes")
        }
    }

    fun assertUnscoped(scopeName: String, source: String, text: String) {
        val scopes = scopesOf(scopeName, source, text) ?: throw AssertionError("no token \"$text\" in \"$source\"")
        if (scopes.isNotEmpty()) throw AssertionError("expected \"$text\" to be unscoped, got $scopes")
    }

    /** Wraps an expression in a minimal well formed policy. */
    fun policy(expr: String) = "permit (principal, action, resource)\nwhen { $expr };"
}
