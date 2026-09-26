// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/completionjson.ts.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.jsonc.JSONPath
import io.github.thefellow.cedar.jsonc.JsonVisitor
import io.github.thefellow.cedar.jsonc.visit
import io.github.thefellow.cedar.vscode.CompletionItem
import io.github.thefellow.cedar.vscode.CompletionItemKind
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.QuickPickItem
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.SnippetString
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Workspace

private fun isTopLevelJSON(document: TextDocument, position: Position): Boolean {
    var depth = 0
    var inString = false
    var escaped = false

    for (line in 0..position.line) {
        val text = document.lineAt(line).text
        val endChar = if (line == position.line) position.character else text.length

        for (i in 0 until minOf(endChar, text.length)) {
            val char = text[i]

            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\') {
                escaped = true
                continue
            }

            if (char == '"') {
                inString = !inString
                continue
            }

            if (!inString) {
                if (char == '{') {
                    depth++
                } else if (char == '}') {
                    depth--
                }
            }
        }
    }

    return depth == 1 // Inside top-level object
}

private class Extension(val fn: String, val arg: String)

private val EXTENSIONS: Map<String, Extension> = mapOf(
    "ipaddr" to Extension(fn = "ip", arg = "127.0.0.1"),
    "decimal" to Extension(fn = "decimal", arg = "12345.6789"),
    "datetime" to Extension(fn = "datetime", arg = "2024-10-15T11:35:00Z"),
    "duration" to Extension(fn = "duration", arg = "1d2h3m4s5ms"),
)

data class SnippetifyResult(val value: String, val tabstop: Int)

fun snippetify(
    schema: SchemaCacheItem,
    attributes: SchemaCompletionRecord?,
    tabstop: Int,
    stack: List<String>, // recursive loop safety
    indent: Int = 1,
): SnippetifyResult {
    @Suppress("NAME_SHADOWING")
    var tabstop = tabstop
    val completions = schema.completions
    val prefix = "\n" + " ".repeat(indent * 2)
    var s = ""
    // upstream: Object.keys(undefined) throws; treat a missing record as empty
    (attributes ?: emptyMap()).keys.forEach { key ->
        val type = attributes!![key]!!.description
        val children = attributes[key]!!.children
        var value: String
        if (type == "Bool") {
            value = "\${${tabstop++}|false,true|}"
        } else if (type == "String") {
            value = "\"\$${tabstop++}\""
        } else if (type == "Long") {
            value = "\${${tabstop++}:0}"
        } else if (type == "Set" || type.startsWith("Set<")) {
            value = "[\$${tabstop++}]"
        } else if (EXTENSIONS[type] != null) {
            value = "{ \"fn\": \"${EXTENSIONS[type]!!.fn}\", \"arg\": \"\${${tabstop++}:${
                EXTENSIONS[type]!!.arg
            }}\" }"
        } else if (children != null) {
            // upstream passes `tabstop++` and then overwrites tabstop with the result
            snippetify(
                schema,
                children,
                tabstop,
                stack,
                indent + 1,
            ).let { value = it.value; tabstop = it.tabstop }
        } else if (schema.entityTypes.contains(type)) {
            value = "{ \"type\": \"$type\", \"id\": \"\$${tabstop++}\" }"
        } else if (
            completions[type] != null &&
            completions[type]!!.keys.isNotEmpty() &&
            !stack.contains(type)
        ) {
            snippetify(
                schema,
                completions[type],
                tabstop,
                stack + type,
                indent + 1,
            ).let { value = it.value; tabstop = it.tabstop }
        } else {
            value = "" // should not get here
        }
        s += "$prefix\"$key\": $value,"
    }

    return SnippetifyResult(
        value =
            "{" +
                s.jsSubstring(0, s.length - 1) +
                "\n" +
                " ".repeat(maxOf(0, (indent - 1) * 2)) +
                "}",
        tabstop = tabstop,
    )
}

class CedarEntitiesJSONCompletionItemProvider(private val workspace: Workspace) {
    fun provideCompletionItems(document: TextDocument, position: Position): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        if (isTopLevelJSON(document, position)) {
            val schemaDoc = getSchemaTextDocument(workspace, document)
            if (schemaDoc != null) {
                if (validateSchemaDoc(workspace, schemaDoc, createDiagnosticCollection())) {
                    val schema = parseCedarSchemaDoc(schemaDoc)
                    val entityTypes = schema.entityTypes

                    entityTypes.forEach { entityType ->
                        val completions = schema.completions
                        val (attrs, tabstop) = snippetify(
                            schema,
                            completions[entityType],
                            2,
                            listOf(entityType),
                            2,
                        )

                        val tmpEntity = """
  "uid": { "type": "$entityType", "id": "${'$'}1" },
  "attrs": $attrs,
  "parents": [${'$'}$tabstop]
"""
                        val item = CompletionItem(entityType, CompletionItemKind.Struct)
                        item.labelDescription = "\"uid\": { \"type\": \"$entityType\", ..."
                        item.range = Range(position, position)

                        item.insertText = SnippetString(tmpEntity)
                        items.add(item)
                    }
                }
            }
        }
        return items
    }
}

/**
 * Upstream `addEntitiesJSON(textEditor, diagnosticCollection)`. The editor interactions are passed in:
 * [showQuickPick] is `await vscode.window.showQuickPick(items, { title })` in continuation-passing style
 * (call `onPicked` with the choice, or null when cancelled) and [insertSnippet] is
 * `textEditor.insertSnippet(snippet, position)`.
 */
fun addEntitiesJSON(
    workspace: Workspace,
    document: TextDocument,
    cursorPosition: Position,
    diagnosticCollection: DiagnosticCollection,
    showQuickPick: (items: List<QuickPickItem>, title: String, onPicked: (QuickPickItem?) -> Unit) -> Unit,
    insertSnippet: (snippet: SnippetString, position: Position) -> Unit,
): Boolean {
    var position: Position? = null
    val currentLine = cursorPosition.line
    var entities = 0
    var depth = 0
    visit(
        document.getText(),
        object : JsonVisitor {
            override fun onObjectBegin(offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath): Boolean {
                if (depth == 0) {
                    entities++
                }
                depth++
                return true
            }

            override fun onObjectEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {
                depth--
                if (depth == 0 && position == null && startLine > currentLine) {
                    position = Position(startLine, startCharacter + 1)
                }
            }
        },
    )

    var entityType = ""
    var attrs = ""
    var tabstop = 2

    // the rest of upstream's function, after the (awaited) quick pick
    fun finish() {
        val insertAt = position ?: Position(0, 1)

        val tmpEntity = """{
  "uid": { "type": "$entityType", "id": "${'$'}1" },
  "attrs": $attrs,
  "parents": [${'$'}$tabstop]
}"""

        val snippet = (if (entities > 0) ",\n" else "\n") + tmpEntity
        val entity = SnippetString(snippet)
        insertSnippet(entity, insertAt)
    }

    val schemaDoc = getSchemaTextDocument(workspace, document)
    if (schemaDoc != null) {
        if (validateSchemaDoc(workspace, schemaDoc, diagnosticCollection)) {
            val schema = parseCedarSchemaDoc(schemaDoc)
            val entityTypes = schema.entityTypes
            val items = entityTypes.map { etype -> QuickPickItem(label = etype) }
            if (items.isNotEmpty()) {
                showQuickPick(items, "Add Cedar entity") { result ->
                    if (result != null) {
                        entityType = result.label

                        val completions = schema.completions
                        snippetify(
                            schema,
                            completions[entityType],
                            tabstop,
                            listOf(entityType),
                            2,
                        ).let { attrs = it.value; tabstop = it.tabstop }
                    }
                    finish()
                }
                return true
            }
        }
    }

    finish()

    return true
}
