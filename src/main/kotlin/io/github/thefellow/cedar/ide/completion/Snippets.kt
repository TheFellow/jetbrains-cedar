// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.completion

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.codeInsight.template.impl.Variable
import com.intellij.openapi.project.Project

/**
 * VS Code snippet syntax (the subset upstream uses: `$1`, `${1}`, `${1:placeholder}`, `${1|a,b|}`, `$0`,
 * and `\$`, `\}`, `\\` escapes), converted to IntelliJ live templates.
 */
sealed class SnippetPart {
    data class Text(val text: String) : SnippetPart()

    /** A tab stop; [placeholder] is its default content, [choices] a `${n|a,b|}` choice list. */
    data class TabStop(val index: Int, val placeholder: List<SnippetPart> = emptyList(), val choices: List<String>? = null) :
        SnippetPart()
}

object Snippets {
    fun parse(snippet: String): List<SnippetPart> = Parser(snippet).parse(null)

    /** The text a snippet expands to with every tab stop at its default (placeholder or first choice). */
    fun expand(parts: List<SnippetPart>): String = buildString {
        for (p in parts) when (p) {
            is SnippetPart.Text -> append(p.text)
            is SnippetPart.TabStop -> append(defaultText(p))
        }
    }

    fun expand(snippet: String): String = expand(parse(snippet))

    private fun defaultText(p: SnippetPart.TabStop): String = p.choices?.firstOrNull() ?: expand(p.placeholder)

    /**
     * Builds a live template. Tab stops are visited in numeric order with `$0` (or the end) last, as in
     * VS Code; repeated tab stops are mirrored.
     */
    fun toTemplate(project: Project, snippet: String): Template {
        val parts = parse(snippet)
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.isToReformat = false

        val firstByIndex = LinkedHashMap<Int, SnippetPart.TabStop>()
        fun collect(list: List<SnippetPart>) {
            for (p in list) if (p is SnippetPart.TabStop) {
                val existing = firstByIndex[p.index]
                if (existing == null || (existing.placeholder.isEmpty() && existing.choices == null)) firstByIndex[p.index] = p
            }
        }
        collect(parts)
        // `$0` is the final caret position; `${0:text}` is a final stop whose text is selected
        val finalStop = firstByIndex[0]?.takeIf { it.placeholder.isNotEmpty() || it.choices != null }
        val ordered = firstByIndex.keys.filter { it > 0 }.sorted() + listOfNotNull(finalStop?.index)
        for (index in ordered) {
            val stop = firstByIndex.getValue(index)
            val expression = when (val choices = stop.choices) {
                null -> ConstantNode(expand(stop.placeholder))
                else -> ConstantNode(choices.firstOrNull() ?: "").withLookupStrings(choices)
            }
            // Template.addVariable(name, ...) would also add a segment at the current position
            (template as TemplateImpl).addVariable(Variable(variableName(index), expression, expression, true, false))
        }
        var hasEnd = false
        for (p in parts) when (p) {
            is SnippetPart.Text -> if (p.text.isNotEmpty()) template.addTextSegment(p.text)
            is SnippetPart.TabStop ->
                if (p.index == 0) {
                    if (finalStop != null) template.addVariableSegment(variableName(0))
                    if (!hasEnd) template.addEndVariable()
                    hasEnd = true
                } else {
                    template.addVariableSegment(variableName(p.index))
                }
        }
        return template
    }

    private fun variableName(index: Int) = "TABSTOP_$index"

    private class Parser(private val s: String) {
        private var pos = 0

        /** Parses until [terminator] (unescaped) or the end; the terminator is consumed. */
        fun parse(terminator: Char?): List<SnippetPart> {
            val parts = mutableListOf<SnippetPart>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    parts += SnippetPart.Text(text.toString())
                    text.clear()
                }
            }
            while (pos < s.length) {
                val c = s[pos]
                if (c == '\\' && pos + 1 < s.length && s[pos + 1] in "\$}\\") {
                    text.append(s[pos + 1]); pos += 2; continue
                }
                if (terminator != null && c == terminator) {
                    pos++
                    flush()
                    return parts
                }
                if (c == '$') {
                    val stop = tryTabStop()
                    if (stop != null) {
                        flush()
                        parts += stop
                        continue
                    }
                }
                text.append(c)
                pos++
            }
            flush()
            return parts
        }

        private fun readInt(): Int? {
            val start = pos
            while (pos < s.length && s[pos].isDigit()) pos++
            return if (pos > start) s.substring(start, pos).toInt() else null
        }

        private fun tryTabStop(): SnippetPart.TabStop? {
            val start = pos
            pos++ // $
            if (pos < s.length && s[pos].isDigit()) {
                return SnippetPart.TabStop(readInt()!!)
            }
            if (pos < s.length && s[pos] == '{') {
                pos++
                val index = readInt()
                if (index != null && pos < s.length) {
                    when (s[pos]) {
                        '}' -> { pos++; return SnippetPart.TabStop(index) }
                        ':' -> { pos++; return SnippetPart.TabStop(index, placeholder = parse('}')) }
                        '|' -> {
                            pos++
                            val choices = mutableListOf<String>()
                            val current = StringBuilder()
                            while (pos < s.length) {
                                val c = s[pos]
                                if (c == '\\' && pos + 1 < s.length && s[pos + 1] in ",|\\") {
                                    current.append(s[pos + 1]); pos += 2; continue
                                }
                                if (c == ',') { choices += current.toString(); current.clear(); pos++; continue }
                                if (c == '|' && pos + 1 < s.length && s[pos + 1] == '}') {
                                    choices += current.toString(); pos += 2
                                    return SnippetPart.TabStop(index, choices = choices)
                                }
                                current.append(c); pos++
                            }
                        }
                    }
                }
            }
            pos = start
            return null
        }
    }
}
