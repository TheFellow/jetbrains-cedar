// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/hover.ts.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.Hover
import io.github.thefellow.cedar.vscode.MarkdownString
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Workspace

private class PrevNextCharacters(val prevChar: String, val nextChar: String)

/**
 * Upstream builds `new vscode.Position(line, start - 1)`, which throws for a word at column 0 and
 * aborts the hover; that case returns null here (callers then provide no hover).
 */
private fun getPrevNextCharacters(document: TextDocument, range: Range): PrevNextCharacters? {
    var prevChar = ""
    var nextChar = ""
    val nextCharPos = Position(
        range.end.line,
        range.end.character + 1,
    )
    // upstream: `if (document.validatePosition(nextCharPos))` is always truthy
    nextChar = document.getText(Range(range.end, nextCharPos))
    if (range.start.character - 1 < 0) return null
    val prevCharPos = Position(
        range.start.line,
        range.start.character - 1,
    )
    prevChar = document.getText(Range(prevCharPos, range.start))

    return PrevNextCharacters(prevChar = prevChar, nextChar = nextChar)
}

private fun createVariableHover(
    workspace: Workspace,
    document: TextDocument,
    position: Position,
    word: String,
): Hover? {
    val mdarray = mutableListOf<MarkdownString>()
    val schemaDoc = getSchemaTextDocument(workspace, document)
    if (schemaDoc != null) {
        val entities = narrowEntityTypes(schemaDoc, word, document, position)
        entities.forEach { entityType ->
            val md = MarkdownString()
            md.appendCodeblock(entityType, "cedar")
            mdarray.add(md)
        }
    }

    if (mdarray.size > 0) {
        return Hover(contents = mdarray)
    }
    return null
}

private fun createPropertyHover(
    workspace: Workspace,
    document: TextDocument,
    position: Position,
    properties: List<String>,
    range: Range?,
): Hover? {
    val mdarray = mutableListOf<MarkdownString>()
    val schemaDoc = getSchemaTextDocument(workspace, document)
    if (schemaDoc != null) {
        val entities = narrowEntityTypes(
            schemaDoc,
            properties.at(0) ?: "undefined",
            document,
            position,
        )
        val completions = parseCedarSchemaDoc(schemaDoc).completions
        val word = properties.at(properties.size - 1)
        entities.forEach { entityType ->
            val (lastType) = traversePropertyChain(
                completions,
                properties,
                entityType,
            )
            if (lastType.isNotEmpty()) {
                val md = MarkdownString()
                md.appendCodeblock("($entityType) ${word ?: "undefined"}: $lastType", "cedar")
                mdarray.add(md)
            }
        }
    }

    if (mdarray.size > 0) {
        return Hover(contents = mdarray, range = range)
    }
    return null
}

class CedarHoverProvider(private val workspace: Workspace) {
    fun provideHover(document: TextDocument, position: Position): Hover? {
        var range = document.getWordRangeAtPosition(position)
        if (range != null) {
            var word = document.getText(range)
            val (prevChar, nextChar) = getPrevNextCharacters(document, range)?.let { it.prevChar to it.nextChar }
                ?: return null
            if (
                prevChar != "." &&
                prevChar != "?" &&
                listOf("principal", "resource", "context", "action").contains(word)
            ) {
                return createVariableHover(workspace, document, position, word)
            }

            if (nextChar == "(") {
                FUNCTION_HELP_DEFINITIONS[word]?.let {
                    return Hover(contents = it)
                }
            }

            var lineIncludingWord = document.getText(
                Range(Position(range.start.line, 0), range.end),
            )
            var attemptPropertyChainMatch = false
            if (prevChar == ".") {
                // principal.propname
                attemptPropertyChainMatch = true
            } else {
                val line = document.lineAt(position).text
                val lineBeforeWord = line.jsSubstring(0, range.start.character)
                if (lineBeforeWord.endsWith(" has ")) {
                    // principal has propname
                    attemptPropertyChainMatch = true
                    // normalize `X has propname` to `X["propname"]`
                    lineIncludingWord =
                        lineBeforeWord.jsSubstring(
                            0,
                            lineBeforeWord.length - 5, // ' has '
                        ) + "[\"$word\"]"
                } else {
                    val prevQuotePos = lineBeforeWord.lastIndexOf('"')
                    val nextQuotePos = line.indexOf('"', range.end.character)
                    if (prevQuotePos != -1 && nextQuotePos != -1) {
                        // update hover highlight range to include quoted string
                        range = Range(
                            range.start.with(character = prevQuotePos + 1),
                            range.end.with(character = nextQuotePos),
                        )
                        word = line.jsSubstring(prevQuotePos + 1, nextQuotePos)
                        if (line.jsSubstring(0, prevQuotePos).endsWith(" has ")) {
                            // principal has "propname"
                            attemptPropertyChainMatch = true
                            // normalize `X has "propname"` to `X["propname"]`
                            lineIncludingWord =
                                lineBeforeWord.jsSubstring(
                                    0,
                                    prevQuotePos - 5, // ' has '
                                ) + "[\"$word\"]"
                        } else {
                            if (
                                line.getOrNull(prevQuotePos - 1) == '[' &&
                                line.getOrNull(nextQuotePos + 1) == ']'
                            ) {
                                // principal["propname"]
                                attemptPropertyChainMatch = true
                                lineIncludingWord = line.jsSubstring(0, nextQuotePos + 2)
                            }
                        }
                    }
                }
            }

            if (attemptPropertyChainMatch) {
                val found = lineIncludingWord.jsMatch(PROPERTY_CHAIN_REGEX)
                if (found != null) {
                    val properties = splitPropertyChain(found.value)
                    return createPropertyHover(
                        workspace,
                        document,
                        position,
                        properties,
                        range,
                    )
                }
            }
        }
        return null
    }
}

class CedarEntitiesJSONHoverProvider {
    fun provideHover(document: TextDocument, position: Position): Hover? {
        val range = document.getWordRangeAtPosition(position)
        if (range != null) {
            val word = document.getText(range)
            val chars = getPrevNextCharacters(document, range) ?: return null
            if (
                chars.prevChar == "\"" &&
                chars.nextChar == "\"" &&
                listOf("ip", "decimal", "datetime", "duration").contains(word)
            ) {
                val line = document.lineAt(position).text
                val lineBeforeWord = line.jsSubstring(0, range.start.character - 1)
                val help = FUNCTION_HELP_DEFINITIONS[word]
                if (
                    lineBeforeWord.trim().endsWith("\"fn\":") &&
                    help != null
                ) {
                    return Hover(contents = help)
                }
            }
        }
        return null
    }
}
