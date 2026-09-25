// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.vscode

class TextLine(val lineNumber: Int, val text: String, val range: Range, val rangeIncludingLineBreak: Range) {
    val firstNonWhitespaceCharacterIndex: Int get() = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
    val isEmptyOrWhitespace: Boolean get() = text.isBlank()
}

/** Port target for `vscode.TextDocument`. Offsets are UTF-16 code units, as in VS Code. */
interface TextDocument {
    val uri: Uri
    val fileName: String get() = uri.fsPath
    val languageId: String

    /** Increases on every change, like VS Code's document version. */
    val version: Long
    val lineCount: Int

    fun getText(range: Range? = null): String
    fun lineAt(line: Int): TextLine
    fun lineAt(position: Position): TextLine = lineAt(position.line)
    fun offsetAt(position: Position): Int
    fun positionAt(offset: Int): Position

    fun validatePosition(position: Position): Position {
        if (lineCount == 0) return Position(0, 0)
        if (position.line < 0) return Position(0, 0)
        if (position.line >= lineCount) return lineAt(lineCount - 1).range.end
        val text = lineAt(position.line).text
        return Position(position.line, position.character.coerceIn(0, text.length))
    }

    fun validateRange(range: Range): Range = Range(validatePosition(range.start), validatePosition(range.end))

    /** Default word definition matches VS Code's for most languages closely enough for upstream's uses. */
    fun getWordRangeAtPosition(position: Position, regex: Regex? = null): Range? {
        val text = lineAt(position.line).text
        val re = regex ?: DEFAULT_WORD_REGEX
        for (m in re.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start <= position.character && position.character <= end && end > start) {
                return Range(position.line, start, position.line, end)
            }
            if (start > position.character) break
        }
        return null
    }

    companion object {
        val DEFAULT_WORD_REGEX =
            Regex("""(-?\d*\.\d\w*)|([^`~!@#$%^&*()\-=+\[{\]}\\|;:'",.<>/?\s]+)""")
    }
}

/** A [TextDocument] over an immutable string snapshot. */
open class StringTextDocument(
    private val text: String,
    override val uri: Uri,
    override val languageId: String,
    override val version: Long = 1,
) : TextDocument {
    private val lineStarts: IntArray = buildList {
        add(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                add(i + 2); i += 2; continue
            }
            if (c == '\n' || c == '\r') add(i + 1)
            i++
        }
    }.toIntArray()

    override val lineCount: Int get() = lineStarts.size

    private fun lineEnd(line: Int): Int {
        if (line + 1 >= lineStarts.size) return text.length
        var end = lineStarts[line + 1]
        if (end > 0 && text[end - 1] == '\n') end--
        if (end > lineStarts[line] && end > 0 && text[end - 1] == '\r') end--
        return end
    }

    override fun getText(range: Range?): String {
        if (range == null) return text
        val r = validateRange(range)
        return text.substring(offsetAt(r.start), offsetAt(r.end))
    }

    override fun lineAt(line: Int): TextLine {
        require(line in 0 until lineCount) { "Illegal value for `line`: $line" }
        val start = lineStarts[line]
        val end = lineEnd(line)
        val nextStart = if (line + 1 < lineStarts.size) lineStarts[line + 1] else text.length
        val range = Range(line, 0, line, end - start)
        val withBreak = if (line + 1 < lineCount) Range(line, 0, line + 1, 0) else range
        return TextLine(line, text.substring(start, end), range, if (nextStart > end) withBreak else range)
    }

    override fun offsetAt(position: Position): Int {
        val p = validatePosition(position)
        return lineStarts[p.line] + p.character
    }

    override fun positionAt(offset: Int): Position {
        val o = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= o) lo = mid else hi = mid - 1
        }
        return Position(lo, minOf(o - lineStarts[lo], lineEnd(lo) - lineStarts[lo]))
    }
}
