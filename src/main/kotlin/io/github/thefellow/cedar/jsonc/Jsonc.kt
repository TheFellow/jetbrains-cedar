/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

/*
 * Kotlin port of jsonc-parser 3.3.1 (https://github.com/microsoft/node-jsonc-parser): the scanner and
 * `visit`, which is the only jsonc-parser API upstream vscode-cedar uses.
 */
package io.github.thefellow.cedar.jsonc

enum class ScanError { None, UnexpectedEndOfComment, UnexpectedEndOfString, UnexpectedEndOfNumber, InvalidUnicode, InvalidEscapeCharacter, InvalidCharacter }

enum class SyntaxKind {
    None, OpenBraceToken, CloseBraceToken, OpenBracketToken, CloseBracketToken, CommaToken, ColonToken, NullKeyword,
    TrueKeyword, FalseKeyword, StringLiteral, NumericLiteral, LineCommentTrivia, BlockCommentTrivia, LineBreakTrivia,
    Trivia, Unknown, EOF,
}

enum class ParseErrorCode {
    None, InvalidSymbol, InvalidNumberFormat, PropertyNameExpected, ValueExpected, ColonExpected, CommaExpected,
    CloseBraceExpected, CloseBracketExpected, EndOfFileExpected, InvalidCommentToken, UnexpectedEndOfComment,
    UnexpectedEndOfString, UnexpectedEndOfNumber, InvalidUnicode, InvalidEscapeCharacter, InvalidCharacter,
}

data class ParseOptions(
    val disallowComments: Boolean = false,
    val allowTrailingComma: Boolean = false,
    val allowEmptyContent: Boolean = false,
) {
    companion object {
        val DEFAULT = ParseOptions(allowTrailingComma = false)
    }
}

/** A JSON path segment is a property name (String) or an array index (Int). */
typealias JSONPath = List<Any>

/** Visitor callbacks. Returning false from a `Begin` callback suppresses callbacks for that subtree. */
interface JsonVisitor {
    fun onObjectBegin(offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath): Boolean = true
    fun onObjectProperty(property: String, offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath) {}
    fun onObjectEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {}
    fun onArrayBegin(offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath): Boolean = true
    fun onArrayEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {}

    /** [value] is a String, Double, Boolean, or null. */
    fun onLiteralValue(value: Any?, offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath) {}
    fun onSeparator(character: String, offset: Int, length: Int, startLine: Int, startCharacter: Int) {}
    fun onComment(offset: Int, length: Int, startLine: Int, startCharacter: Int) {}
    fun onError(error: ParseErrorCode, offset: Int, length: Int, startLine: Int, startCharacter: Int) {}
}

class JSONScanner(private val text: String, private val ignoreTrivia: Boolean = false) {
    private val len = text.length
    private var pos = 0
    private var value = ""
    private var tokenOffset = 0
    private var token = SyntaxKind.Unknown
    private var lineNumber = 0
    private var lineStartOffset = 0
    private var tokenLineStartOffset = 0
    private var prevTokenLineStartOffset = 0
    private var scanError = ScanError.None

    /** JS `charCodeAt`: NaN (here -1) when out of range. */
    private fun code(i: Int): Int = if (i in 0 until len) text[i].code else -1

    private fun scanHexDigits(count: Int, exact: Boolean): Int {
        var digits = 0
        var value = 0
        while (digits < count || !exact) {
            val ch = code(pos)
            value = when (ch) {
                in 48..57 -> value * 16 + ch - 48
                in 65..70 -> value * 16 + ch - 65 + 10
                in 97..102 -> value * 16 + ch - 97 + 10
                else -> break
            }
            pos++
            digits++
        }
        if (digits < count) value = -1
        return value
    }

    fun setPosition(newPosition: Int) {
        pos = newPosition
        value = ""
        tokenOffset = 0
        token = SyntaxKind.Unknown
        scanError = ScanError.None
    }

    private fun scanNumber(): String {
        val start = pos
        if (code(pos) == 48) {
            pos++
        } else {
            pos++
            while (pos < len && isDigit(code(pos))) pos++
        }
        if (pos < len && code(pos) == 46) {
            pos++
            if (pos < len && isDigit(code(pos))) {
                pos++
                while (pos < len && isDigit(code(pos))) pos++
            } else {
                scanError = ScanError.UnexpectedEndOfNumber
                return text.substring(start, pos)
            }
        }
        var end = pos
        if (pos < len && (code(pos) == 69 || code(pos) == 101)) {
            pos++
            if (pos < len && code(pos) == 43 || code(pos) == 45) pos++
            if (pos < len && isDigit(code(pos))) {
                pos++
                while (pos < len && isDigit(code(pos))) pos++
                end = pos
            } else {
                scanError = ScanError.UnexpectedEndOfNumber
            }
        }
        return text.substring(start, end)
    }

    private fun scanString(): String {
        val result = StringBuilder()
        var start = pos
        while (true) {
            if (pos >= len) {
                result.append(text, start, pos)
                scanError = ScanError.UnexpectedEndOfString
                break
            }
            val ch = code(pos)
            if (ch == 34) {
                result.append(text, start, pos)
                pos++
                break
            }
            if (ch == 92) {
                result.append(text, start, pos)
                pos++
                if (pos >= len) {
                    scanError = ScanError.UnexpectedEndOfString
                    break
                }
                when (code(pos++)) {
                    34 -> result.append('"')
                    92 -> result.append('\\')
                    47 -> result.append('/')
                    98 -> result.append('\b')
                    102 -> result.append('\u000c')
                    110 -> result.append('\n')
                    114 -> result.append('\r')
                    116 -> result.append('\t')
                    117 -> {
                        val ch3 = scanHexDigits(4, true)
                        if (ch3 >= 0) result.append(ch3.toChar()) else scanError = ScanError.InvalidUnicode
                    }
                    else -> scanError = ScanError.InvalidEscapeCharacter
                }
                start = pos
                continue
            }
            if (ch in 0..0x1f) {
                if (isLineBreak(ch)) {
                    result.append(text, start, pos)
                    scanError = ScanError.UnexpectedEndOfString
                    break
                } else {
                    scanError = ScanError.InvalidCharacter
                    // mark as error but continue with string
                }
            }
            pos++
        }
        return result.toString()
    }

    private fun scanNext(): SyntaxKind {
        value = ""
        scanError = ScanError.None
        tokenOffset = pos
        lineStartOffset = lineNumber
        prevTokenLineStartOffset = tokenLineStartOffset
        if (pos >= len) {
            // at the end
            tokenOffset = len
            token = SyntaxKind.EOF
            return token
        }
        var code = code(pos)
        // trivia: whitespace
        if (isWhiteSpace(code)) {
            val sb = StringBuilder()
            do {
                pos++
                sb.append(code.toChar())
                code = code(pos)
            } while (isWhiteSpace(code))
            value = sb.toString()
            token = SyntaxKind.Trivia
            return token
        }
        // trivia: newlines
        if (isLineBreak(code)) {
            pos++
            value += code.toChar()
            if (code == 13 && code(pos) == 10) {
                pos++
                value += '\n'
            }
            lineNumber++
            tokenLineStartOffset = pos
            token = SyntaxKind.LineBreakTrivia
            return token
        }
        token = when (code) {
            // tokens: []{}:,
            123 -> { pos++; SyntaxKind.OpenBraceToken }
            125 -> { pos++; SyntaxKind.CloseBraceToken }
            91 -> { pos++; SyntaxKind.OpenBracketToken }
            93 -> { pos++; SyntaxKind.CloseBracketToken }
            58 -> { pos++; SyntaxKind.ColonToken }
            44 -> { pos++; SyntaxKind.CommaToken }
            // strings
            34 -> { pos++; value = scanString(); SyntaxKind.StringLiteral }
            // comments
            47 -> scanSlash(code)
            // numbers
            45 -> {
                value += code.toChar()
                pos++
                if (pos == len || !isDigit(code(pos))) {
                    SyntaxKind.Unknown
                } else {
                    // found a minus, followed by a number so we fall through to proceed with scanning numbers
                    value += scanNumber()
                    SyntaxKind.NumericLiteral
                }
            }
            in 48..57 -> { value += scanNumber(); SyntaxKind.NumericLiteral }
            // literals and unknown symbols
            else -> {
                // is a literal? Read the full word.
                while (pos < len && isUnknownContentCharacter(code)) {
                    pos++
                    code = code(pos)
                }
                if (tokenOffset != pos) {
                    value = text.substring(tokenOffset, pos)
                    // keywords: true, false, null
                    when (value) {
                        "true" -> SyntaxKind.TrueKeyword
                        "false" -> SyntaxKind.FalseKeyword
                        "null" -> SyntaxKind.NullKeyword
                        else -> SyntaxKind.Unknown
                    }
                } else {
                    // some
                    value += code.toChar()
                    pos++
                    SyntaxKind.Unknown
                }
            }
        }
        return token
    }

    private fun scanSlash(code: Int): SyntaxKind {
        val start = pos - 1
        // Single-line comment
        if (code(pos + 1) == 47) {
            pos += 2
            while (pos < len) {
                if (isLineBreak(code(pos))) break
                pos++
            }
            value = text.substring(start, pos)
            return SyntaxKind.LineCommentTrivia
        }
        // Multi-line comment
        if (code(pos + 1) == 42) {
            pos += 2
            val safeLength = len - 1 // For lookahead.
            var commentClosed = false
            while (pos < safeLength) {
                val ch = code(pos)
                if (ch == 42 && code(pos + 1) == 47) {
                    pos += 2
                    commentClosed = true
                    break
                }
                pos++
                if (isLineBreak(ch)) {
                    if (ch == 13 && code(pos) == 10) pos++
                    lineNumber++
                    tokenLineStartOffset = pos
                }
            }
            if (!commentClosed) {
                pos++
                scanError = ScanError.UnexpectedEndOfComment
            }
            value = text.substring(start.coerceAtLeast(0), pos.coerceAtMost(len))
            return SyntaxKind.BlockCommentTrivia
        }
        // just a single slash
        value += code.toChar()
        pos++
        return SyntaxKind.Unknown
    }

    private fun isUnknownContentCharacter(code: Int): Boolean {
        if (isWhiteSpace(code) || isLineBreak(code)) return false
        return when (code) {
            125, 93, 123, 91, 34, 58, 44, 47 -> false
            else -> true
        }
    }

    private fun scanNextNonTrivia(): SyntaxKind {
        var result: SyntaxKind
        do {
            result = scanNext()
        } while (result.ordinal >= SyntaxKind.LineCommentTrivia.ordinal && result.ordinal <= SyntaxKind.Trivia.ordinal)
        return result
    }

    fun getPosition() = pos
    fun scan(): SyntaxKind = if (ignoreTrivia) scanNextNonTrivia() else scanNext()
    fun getToken() = token
    fun getTokenValue() = value
    fun getTokenOffset() = tokenOffset
    fun getTokenLength() = pos - tokenOffset
    fun getTokenStartLine() = lineStartOffset
    fun getTokenStartCharacter() = tokenOffset - prevTokenLineStartOffset
    fun getTokenError() = scanError

    private companion object {
        fun isWhiteSpace(ch: Int) = ch == 32 || ch == 9
        fun isLineBreak(ch: Int) = ch == 10 || ch == 13
        fun isDigit(ch: Int) = ch in 48..57
    }
}

fun createScanner(text: String, ignoreTrivia: Boolean = false) = JSONScanner(text, ignoreTrivia)

/** Parses the given text and invokes the visitor functions for each object, array and literal reached. */
fun visit(text: String, visitor: JsonVisitor, options: ParseOptions = ParseOptions.DEFAULT): Boolean =
    JsonVisit(text, visitor, options).run()

private class JsonVisit(text: String, private val visitor: JsonVisitor, options: ParseOptions) {
    private val scanner = createScanner(text, false)

    // Important: Only pass copies of this to visitor functions to prevent accidental modification, and
    // to not affect visitor functions which stored a reference to a previous JSONPath
    private val jsonPath = mutableListOf<Any>()

    // Depth of onXXXBegin() callbacks suppressed. onXXXEnd() decrements this if it isn't 0 already.
    // Callbacks are only called when this value is 0.
    private var suppressedCallbacks = 0
    private val disallowComments = options.disallowComments
    private val allowTrailingComma = options.allowTrailingComma
    private val allowEmptyContent = options.allowEmptyContent
    private val pathSupplier: () -> JSONPath = { jsonPath.toList() }

    private inline fun ifNotSuppressed(action: () -> Unit) {
        if (suppressedCallbacks == 0) action()
    }

    private fun onObjectBegin() = begin { visitor.onObjectBegin(scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter(), pathSupplier) }
    private fun onArrayBegin() = begin { visitor.onArrayBegin(scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter(), pathSupplier) }
    private fun onObjectEnd() = end { visitor.onObjectEnd(scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter()) }
    private fun onArrayEnd() = end { visitor.onArrayEnd(scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter()) }
    private fun onObjectProperty(property: String) = ifNotSuppressed {
        visitor.onObjectProperty(property, scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter(), pathSupplier)
    }
    private fun onLiteralValue(value: Any?) = ifNotSuppressed {
        visitor.onLiteralValue(value, scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter(), pathSupplier)
    }
    private fun onSeparator(character: String) = ifNotSuppressed {
        visitor.onSeparator(character, scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter())
    }
    private fun onComment() = ifNotSuppressed {
        visitor.onComment(scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter())
    }
    private fun onError(error: ParseErrorCode) = ifNotSuppressed {
        visitor.onError(error, scanner.getTokenOffset(), scanner.getTokenLength(), scanner.getTokenStartLine(), scanner.getTokenStartCharacter())
    }

    private inline fun begin(callback: () -> Boolean) {
        if (suppressedCallbacks > 0) {
            suppressedCallbacks++
        } else if (!callback()) {
            suppressedCallbacks = 1
        }
    }

    private inline fun end(callback: () -> Unit) {
        if (suppressedCallbacks > 0) suppressedCallbacks--
        if (suppressedCallbacks == 0) callback()
    }

    private fun scanNext(): SyntaxKind {
        while (true) {
            val token = scanner.scan()
            when (scanner.getTokenError()) {
                ScanError.InvalidUnicode -> handleError(ParseErrorCode.InvalidUnicode)
                ScanError.InvalidEscapeCharacter -> handleError(ParseErrorCode.InvalidEscapeCharacter)
                ScanError.UnexpectedEndOfNumber -> handleError(ParseErrorCode.UnexpectedEndOfNumber)
                ScanError.UnexpectedEndOfComment -> if (!disallowComments) handleError(ParseErrorCode.UnexpectedEndOfComment)
                ScanError.UnexpectedEndOfString -> handleError(ParseErrorCode.UnexpectedEndOfString)
                ScanError.InvalidCharacter -> handleError(ParseErrorCode.InvalidCharacter)
                ScanError.None -> {}
            }
            when (token) {
                SyntaxKind.LineCommentTrivia, SyntaxKind.BlockCommentTrivia ->
                    if (disallowComments) handleError(ParseErrorCode.InvalidCommentToken) else onComment()
                SyntaxKind.Unknown -> handleError(ParseErrorCode.InvalidSymbol)
                SyntaxKind.Trivia, SyntaxKind.LineBreakTrivia -> {}
                else -> return token
            }
        }
    }

    private fun handleError(error: ParseErrorCode, skipUntilAfter: List<SyntaxKind> = emptyList(), skipUntil: List<SyntaxKind> = emptyList()) {
        onError(error)
        if (skipUntilAfter.size + skipUntil.size > 0) {
            var token = scanner.getToken()
            while (token != SyntaxKind.EOF) {
                if (token in skipUntilAfter) {
                    scanNext()
                    break
                } else if (token in skipUntil) {
                    break
                }
                token = scanNext()
            }
        }
    }

    private fun parseString(isValue: Boolean): Boolean {
        val value = scanner.getTokenValue()
        if (isValue) {
            onLiteralValue(value)
        } else {
            onObjectProperty(value)
            // add property name afterwards
            jsonPath.add(value)
        }
        scanNext()
        return true
    }

    private fun parseLiteral(): Boolean {
        when (scanner.getToken()) {
            SyntaxKind.NumericLiteral -> {
                val tokenValue = scanner.getTokenValue()
                var value = jsNumber(tokenValue)
                if (value.isNaN()) {
                    handleError(ParseErrorCode.InvalidNumberFormat)
                    value = 0.0
                }
                onLiteralValue(value)
            }
            SyntaxKind.NullKeyword -> onLiteralValue(null)
            SyntaxKind.TrueKeyword -> onLiteralValue(true)
            SyntaxKind.FalseKeyword -> onLiteralValue(false)
            else -> return false
        }
        scanNext()
        return true
    }

    private fun parseProperty(): Boolean {
        if (scanner.getToken() != SyntaxKind.StringLiteral) {
            handleError(ParseErrorCode.PropertyNameExpected, emptyList(), listOf(SyntaxKind.CloseBraceToken, SyntaxKind.CommaToken))
            return false
        }
        parseString(false)
        if (scanner.getToken() == SyntaxKind.ColonToken) {
            onSeparator(":")
            scanNext() // consume colon
            if (!parseValue()) {
                handleError(ParseErrorCode.ValueExpected, emptyList(), listOf(SyntaxKind.CloseBraceToken, SyntaxKind.CommaToken))
            }
        } else {
            handleError(ParseErrorCode.ColonExpected, emptyList(), listOf(SyntaxKind.CloseBraceToken, SyntaxKind.CommaToken))
        }
        jsonPath.removeAt(jsonPath.size - 1) // remove processed property name
        return true
    }

    private fun parseObject(): Boolean {
        onObjectBegin()
        scanNext() // consume open brace
        var needsComma = false
        while (scanner.getToken() != SyntaxKind.CloseBraceToken && scanner.getToken() != SyntaxKind.EOF) {
            if (scanner.getToken() == SyntaxKind.CommaToken) {
                if (!needsComma) handleError(ParseErrorCode.ValueExpected)
                onSeparator(",")
                scanNext() // consume comma
                if (scanner.getToken() == SyntaxKind.CloseBraceToken && allowTrailingComma) break
            } else if (needsComma) {
                handleError(ParseErrorCode.CommaExpected)
            }
            if (!parseProperty()) {
                handleError(ParseErrorCode.ValueExpected, emptyList(), listOf(SyntaxKind.CloseBraceToken, SyntaxKind.CommaToken))
            }
            needsComma = true
        }
        onObjectEnd()
        if (scanner.getToken() != SyntaxKind.CloseBraceToken) {
            handleError(ParseErrorCode.CloseBraceExpected, listOf(SyntaxKind.CloseBraceToken), emptyList())
        } else {
            scanNext() // consume close brace
        }
        return true
    }

    private fun parseArray(): Boolean {
        onArrayBegin()
        scanNext() // consume open bracket
        var isFirstElement = true
        var needsComma = false
        while (scanner.getToken() != SyntaxKind.CloseBracketToken && scanner.getToken() != SyntaxKind.EOF) {
            if (scanner.getToken() == SyntaxKind.CommaToken) {
                if (!needsComma) handleError(ParseErrorCode.ValueExpected)
                onSeparator(",")
                scanNext() // consume comma
                if (scanner.getToken() == SyntaxKind.CloseBracketToken && allowTrailingComma) break
            } else if (needsComma) {
                handleError(ParseErrorCode.CommaExpected)
            }
            if (isFirstElement) {
                jsonPath.add(0)
                isFirstElement = false
            } else {
                jsonPath[jsonPath.size - 1] = (jsonPath[jsonPath.size - 1] as Int) + 1
            }
            if (!parseValue()) {
                handleError(ParseErrorCode.ValueExpected, emptyList(), listOf(SyntaxKind.CloseBracketToken, SyntaxKind.CommaToken))
            }
            needsComma = true
        }
        onArrayEnd()
        if (!isFirstElement) {
            jsonPath.removeAt(jsonPath.size - 1) // remove array index
        }
        if (scanner.getToken() != SyntaxKind.CloseBracketToken) {
            handleError(ParseErrorCode.CloseBracketExpected, listOf(SyntaxKind.CloseBracketToken), emptyList())
        } else {
            scanNext() // consume close bracket
        }
        return true
    }

    private fun parseValue(): Boolean = when (scanner.getToken()) {
        SyntaxKind.OpenBracketToken -> parseArray()
        SyntaxKind.OpenBraceToken -> parseObject()
        SyntaxKind.StringLiteral -> parseString(true)
        else -> parseLiteral()
    }

    fun run(): Boolean {
        scanNext()
        if (scanner.getToken() == SyntaxKind.EOF) {
            if (allowEmptyContent) return true
            handleError(ParseErrorCode.ValueExpected)
            return false
        }
        if (!parseValue()) {
            handleError(ParseErrorCode.ValueExpected)
            return false
        }
        if (scanner.getToken() != SyntaxKind.EOF) {
            handleError(ParseErrorCode.EndOfFileExpected)
        }
        return true
    }

    /** JS `Number(string)` for the token shapes the scanner produces. */
    private fun jsNumber(s: String): Double = s.toDoubleOrNull() ?: Double.NaN
}
