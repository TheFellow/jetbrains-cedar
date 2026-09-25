// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

/*
 * A minimal, IDE-independent re-creation of the parts of the VS Code extension API used by upstream
 * vscode-cedar. Upstream TypeScript modules are ported against these types nearly line-for-line so that
 * future semports stay mechanical; IntelliJ adapters (see `io.github.thefellow.cedar.ide`) translate to
 * and from platform types.
 */
package io.github.thefellow.cedar.vscode

data class Position(val line: Int, val character: Int) : Comparable<Position> {
    override fun compareTo(other: Position): Int =
        if (line != other.line) line.compareTo(other.line) else character.compareTo(other.character)

    fun isBefore(other: Position) = this < other
    fun isBeforeOrEqual(other: Position) = this <= other
    fun isAfter(other: Position) = this > other
    fun isAfterOrEqual(other: Position) = this >= other
    fun isEqual(other: Position) = this == other
    fun translate(lineDelta: Int = 0, characterDelta: Int = 0) = Position(line + lineDelta, character + characterDelta)
    fun with(line: Int = this.line, character: Int = this.character) = Position(line, character)
}

open class Range(start: Position, end: Position) {
    /** Like VS Code, start is always before or equal to end. */
    val start: Position = if (start <= end) start else end
    val end: Position = if (start <= end) end else start

    constructor(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) :
        this(Position(startLine, startCharacter), Position(endLine, endCharacter))

    val isEmpty get() = start == end
    val isSingleLine get() = start.line == end.line

    fun contains(position: Position) = start <= position && position <= end
    fun contains(range: Range) = contains(range.start) && contains(range.end)
    fun isEqual(other: Range) = start == other.start && end == other.end

    fun intersection(other: Range): Range? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (s > e) null else Range(s, e)
    }

    fun union(other: Range) = Range(minOf(start, other.start), maxOf(end, other.end))
    fun with(start: Position = this.start, end: Position = this.end) = Range(start, end)

    override fun equals(other: Any?) = other is Range && isEqual(other)
    override fun hashCode() = 31 * start.hashCode() + end.hashCode()
    override fun toString() = "Range(${start.line}:${start.character}-${end.line}:${end.character})"
}

class Selection(val anchor: Position, val active: Position) : Range(anchor, active)

/** A file (or virtual) resource identifier. Only the parts upstream uses. */
data class Uri(val scheme: String, val path: String) {
    val fsPath: String get() = path

    fun with(scheme: String = this.scheme, path: String = this.path) = Uri(scheme, path)

    override fun toString() = "$scheme://$path"

    companion object {
        fun file(path: String) = Uri("file", path)
        fun parse(value: String): Uri {
            val i = value.indexOf("://")
            return if (i < 0) file(value) else Uri(value.substring(0, i), value.substring(i + 3))
        }
        fun joinPath(base: Uri, vararg segments: String) =
            base.with(path = (listOf(base.path.trimEnd('/')) + segments.map { it.trim('/') }).joinToString("/"))
    }
}
