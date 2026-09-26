// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/completion.ts.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.CompletionContext
import io.github.thefellow.cedar.vscode.CompletionItem
import io.github.thefellow.cedar.vscode.CompletionItemKind
import io.github.thefellow.cedar.vscode.CompletionTriggerKind
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.SnippetString
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.TextEdit
import io.github.thefellow.cedar.vscode.Workspace

// helper method for set, IPAddr, and Decimal functions
private fun createFunctionItem(
    range: Range,
    label: String,
    snippetString: String? = null,
): CompletionItem {
    // use first line of Hover Help as detail for completion item
    val help = FUNCTION_HELP_DEFINITIONS[label]

    val item = CompletionItem(label, CompletionItemKind.Function)
    if (help != null && help.size > 1) {
        item.labelDetail = help[0].jsSubstring(label.length)
    }
    item.range = range
    if (!snippetString.isNullOrEmpty()) {
        item.insertText = SnippetString(snippetString)
    }

    return item
}

// Set functions
private fun createSetItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    items.add(createFunctionItem(range, "contains", "contains(\$1) \$0"))
    items.add(createFunctionItem(range, "containsAll", "containsAll([\$1]) \$0"))
    items.add(createFunctionItem(range, "containsAny", "containsAny([\$1]) \$0"))
    items.add(createFunctionItem(range, "isEmpty", "isEmpty() \$0"))

    return items
}

// Tag functions
private fun createTagItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    items.add(createFunctionItem(range, "getTag", "getTag(\$1)\$0"))
    items.add(createFunctionItem(range, "hasTag", "hasTag(\$1) \$0"))

    return items
}

// [^\s"]* inside the (" ") avoids a greedy match
private val IP_REGEX = Regex("""\bip\("[^\s"]*"\)\.$""")
private val DECIMAL_REGEX = Regex("""\bdecimal\("[^\s"]*"\)\.$""")
private val DATETIME_REGEX =
    Regex("""\b(datetime\("[^\s"]*"\)|offset\(duration\("[^\s"]*"\)\)|toDate\(\))\.$""")
private val DURATION_REGEX =
    Regex("""\b(duration\("[^\s"]*"\)|durationSince\(datetime\("[^\s"]*"\)\)|toTime\(\))\.$""")

// IPAddr extension functions
private fun createIpFunctionItem(range: Range): CompletionItem {
    return createFunctionItem(range, "ip", "ip(\"\${1:127.0.0.1}\")\$0")
}
private fun createIPAddrItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    items.add(createFunctionItem(range, "isIpv4", "isIpv4() \$0"))
    items.add(createFunctionItem(range, "isIpv6", "isIpv6() \$0"))
    items.add(createFunctionItem(range, "isLoopback", "isLoopback() \$0"))
    items.add(createFunctionItem(range, "isMulticast", "isMulticast() \$0"))
    items.add(createFunctionItem(range, "isInRange", "isInRange(\$1) \$0"))

    return items
}

// Decimal extension functions
private fun createDecimalFunctionItem(range: Range): CompletionItem {
    return createFunctionItem(range, "decimal", "decimal(\"\${1:0.1234}\")\$0")
}
private fun createDecimalItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    items.add(createFunctionItem(range, "lessThan", "lessThan(\$1) \$0"))
    items.add(
        createFunctionItem(range, "lessThanOrEqual", "lessThanOrEqual(\$1) \$0"),
    )
    items.add(createFunctionItem(range, "greaterThan", "greaterThan(\$1) \$0"))
    items.add(
        createFunctionItem(range, "greaterThanOrEqual", "greaterThanOrEqual(\$1) \$0"),
    )

    return items
}

// Datetime extension functions
private fun createDatetimeFunctionItem(range: Range): CompletionItem {
    return createFunctionItem(
        range,
        "datetime",
        "datetime(\"\${1:YYYY-MM-DDThh:mm:ssZ}\")\$0",
    )
}
private fun createDatetimeItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    items.add(createFunctionItem(range, "offset", "offset(\$1)\$0"))
    items.add(createFunctionItem(range, "durationSince", "durationSince(\$1)\$0"))
    items.add(createFunctionItem(range, "toDate", "toDate()\$0"))
    items.add(createFunctionItem(range, "toTime", "toTime()\$0"))

    return items
}
private fun createDurationFunctionItem(range: Range): CompletionItem {
    return createFunctionItem(
        range,
        "duration",
        "duration(\"\${1:1d2h3m4s5ms}\")\$0",
    )
}
private fun createDurationItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    items.add(
        createFunctionItem(range, "toMilliseconds", "toMilliseconds() \$0"),
    )
    items.add(createFunctionItem(range, "toSeconds", "toSeconds() \$0"))
    items.add(createFunctionItem(range, "toMinutes", "toMinutes() \$0"))
    items.add(createFunctionItem(range, "toHours", "toHours() \$0"))
    items.add(createFunctionItem(range, "toDays", "toDays() \$0"))

    return items
}

private val ENTITY_REGEX = Regex("""(?:\s|=|\[|\()(?<entity>(?:[_a-zA-Z][_a-zA-Z0-9]*::)+)$""")
private val SCOPE_REGEX =
    Regex("""(?<element>(principal|action|resource))(\s*==\s*|(\s+is\s+([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*)?\s+in\s+\[?)(?<trigger>.?)$""")
private val IS_REGEX =
    Regex("""\b(?<!\.)(?<element>(([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*::"(?<id>([^"]*))"|principal|resource))\s+is\s+(?<trigger>.?)$""")

fun splitPropertyChain(property: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var insideQuotes = false
    var pos = start
    while (pos < property.length) {
        val char = property[pos]
        if (insideQuotes) {
            if (char == '"') {
                // doesn't handled embedded " e.g "5' 10\"" and don't care
                parts.add(property.jsSubstring(start, pos))
                pos += 1
                start = pos + 1
                insideQuotes = false
            }
        } else if (char == '.') {
            if (start != pos) {
                parts.add(property.jsSubstring(start, pos))
            }
            start = pos + 1
        } else if (char == ' ') {
            if (start != pos) {
                parts.add(property.jsSubstring(start, pos))
            }
            pos = pos + " has ".length
            start = pos
        } else if (char == '[') {
            if (start != pos) {
                parts.add(property.jsSubstring(start, pos))
            }
            pos += 2
            insideQuotes = true
            start = pos
        } else if (pos == property.length - 1) {
            parts.add(property.jsSubstring(start))
        }
        pos++
    }

    return parts
}

private fun createEntityItems(
    position: Position,
    schemaDoc: TextDocument,
    element: String,
    trigger: String?,
    typeOnly: Boolean = false,
): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = if (!trigger.isNullOrEmpty()) {
        Range(
            Position(position.line, position.character - trigger.length),
            position,
        )
    } else {
        Range(position, position)
    }

    val definitionRanges = parseCedarSchemaDoc(schemaDoc).definitionRanges

    definitionRanges.forEach { definition ->
        if (element == "action") {
            if (definition.collection == "actions") {
                val item = CompletionItem(
                    definition.etype,
                    CompletionItemKind.Value,
                )
                item.range = range
                items.add(item)
            }
        } else if (definition.collection == "entityTypes") {
            val item = CompletionItem(
                definition.etype,
                CompletionItemKind.Class,
            )
            item.range = range
            if (typeOnly) {
                items.add(item)
            } else {
                val enums = definition.enums
                if (enums != null && enums.isNotEmpty()) {
                    enums.forEach { enumValue ->
                        val enumItem = CompletionItem(
                            "${definition.etype}::\"$enumValue\"",
                            CompletionItemKind.EnumMember,
                        )
                        enumItem.range = range
                        items.add(enumItem)
                    }
                } else {
                    item.insertText = SnippetString(
                        definition.etype + "::\"\$1\"",
                    )
                    items.add(item)
                }
            }
        }
    }

    return items
}

private fun createAttributeItems(
    position: Position,
    entityType: String,
    attributes: SchemaCompletionRecord?,
): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    if (attributes != null) {
        attributes.keys.forEach { key ->
            val item = CompletionItem(key, CompletionItemKind.Field)
            item.labelDetail = ": ${attributes[key]!!.description}"
            item.labelDescription = entityType
            item.range = range
            val match = key.jsMatch(IDENT_REGEX)
            if (match == null) {
                // properties not matching IDENT need a different notation
                item.insertText = SnippetString("[\"$key\"]")
                // and remove the preceding . that triggered the completion
                item.additionalTextEdits = listOf(
                    TextEdit.delete(
                        Range(
                            Position(position.line, position.character - 1),
                            position,
                        ),
                    ),
                )
            }
            items.add(item)
        }
    }

    return items
}

private fun createEntityTypesAttributeItems(
    position: Position,
    schemaDoc: TextDocument,
    entityTypes: List<String>,
): List<CompletionItem> {
    var items: List<CompletionItem> = emptyList()
    val completions = parseCedarSchemaDoc(schemaDoc).completions
    val tags = parseCedarSchemaDoc(schemaDoc).tags
    var addTagItems = false
    entityTypes.forEach { entityType ->
        val attributes = completions[entityType]
        items = items + createAttributeItems(position, entityType, attributes)
        if (tags.contains(entityType)) {
            addTagItems = true
        }
    }
    if (addTagItems) {
        items = items + createTagItems(position)
    }

    return items
}

private fun createVariableItem(range: Range, label: String): CompletionItem {
    val item = CompletionItem(label, CompletionItemKind.Variable)
    item.range = range
    return item
}

private fun createInvokeItems(position: Position): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val range = Range(position, position)

    listOf("principal", "action", "resource", "context").forEach { element ->
        items.add(createVariableItem(range, element))
    }

    items.add(createIpFunctionItem(range))
    items.add(createDecimalFunctionItem(range))
    items.add(createDatetimeFunctionItem(range))
    items.add(createDurationFunctionItem(range))

    return items
}

private fun provideCedarPeriodTriggerItems(
    workspace: Workspace,
    position: Position,
    linePrefix: String,
    document: TextDocument,
): List<CompletionItem>? {
    if (linePrefix.endsWith(").")) {
        if (linePrefix.jsMatch(IP_REGEX) != null) {
            return createIPAddrItems(position)
        } else if (linePrefix.jsMatch(DECIMAL_REGEX) != null) {
            return createDecimalItems(position)
        } else if (linePrefix.jsMatch(DATETIME_REGEX) != null) {
            return createDatetimeItems(position)
        } else if (linePrefix.jsMatch(DURATION_REGEX) != null) {
            return createDurationItems(position)
        }
    }

    val found = linePrefix.jsMatch(PROPERTY_CHAIN_REGEX)
    if (found != null) {
        val properties = splitPropertyChain(found.value)
        val schemaDoc = getSchemaTextDocument(workspace, document)
        if (schemaDoc != null) {
            val entities = narrowEntityTypes(
                schemaDoc,
                properties.at(0) ?: "undefined",
                document,
                position,
            )

            if (properties.size == 1) {
                return createEntityTypesAttributeItems(position, schemaDoc, entities)
            } else {
                var items: List<CompletionItem> = emptyList()
                val completions = parseCedarSchemaDoc(schemaDoc).completions
                val lastTypes = mutableSetOf<String>()
                entities.forEach { entityType ->
                    val (lastType, completion) = traversePropertyChain(
                        completions,
                        properties,
                        entityType,
                    )

                    if (lastType.isNotEmpty()) {
                        if (lastType == "Record" && completion != null) {
                            items = items + createAttributeItems(position, entityType, completion)
                        } else if (!lastTypes.contains(lastType)) {
                            lastTypes.add(lastType)
                            if (lastType.startsWith("Set<")) {
                                items = items + createSetItems(position)
                            } else if (lastType == "ipaddr") {
                                items = items + createIPAddrItems(position)
                            } else if (lastType == "decimal") {
                                items = items + createDecimalItems(position)
                            } else if (lastType == "datetime") {
                                items = items + createDatetimeItems(position)
                            } else if (lastType == "duration") {
                                items = items + createDurationItems(position)
                            } else if (!PRIMITIVE_TYPES.contains(lastType)) {
                                items = items + createEntityTypesAttributeItems(
                                    position,
                                    schemaDoc,
                                    listOf(lastType),
                                )
                            }
                        }
                    }
                }
                return items
            }
        }
    }

    if (linePrefix.endsWith("].")) {
        return createSetItems(position)
    }

    return null
}

private fun provideCedarTriggerCharacterCompletionItems(
    workspace: Workspace,
    document: TextDocument,
    position: Position,
    context: CompletionContext,
): List<CompletionItem>? {
    val linePrefix = document
        .lineAt(position)
        .text.jsSubstring(0, position.character)

    if (context.triggerCharacter == ".") {
        return provideCedarPeriodTriggerItems(workspace, position, linePrefix, document)
    } else if (context.triggerCharacter == ":") {
        if (linePrefix.endsWith("::")) {
            val found = linePrefix.jsMatch(ENTITY_REGEX)
            if (found != null) {
                val entity = found.group("entity")
                val schemaDoc = getSchemaTextDocument(workspace, document)
                if (schemaDoc != null) {
                    return createEntityItems(position, schemaDoc, "", entity)
                }
            }
        }
    } else if (context.triggerCharacter == "@") {
        if (linePrefix == "// @") {
            val item = CompletionItem(
                "@formatter:off",
                CompletionItemKind.Property,
            )
            item.insertText = SnippetString("formatter:off\$0")
            item.range = Range(position, position)
            return listOf(item)
        } else if (linePrefix.trim() == "@") {
            val annotations = parseCedarPoliciesDoc(document).annotations
            val items = mutableListOf<CompletionItem>()
            annotations.forEach { annotation ->
                val item = CompletionItem(
                    "@$annotation",
                    CompletionItemKind.Property,
                )
                item.insertText = SnippetString(annotation + "(\"\$1\")\$0")
                item.range = Range(position, position)
                items.add(item)
            }
            return items
        }
    } else if (context.triggerCharacter == "?") {
        // ?principal and ?resource
        val found = linePrefix
            .jsSubstring(0, linePrefix.length - 1)
            .jsMatch(SCOPE_REGEX)
        if (found != null) {
            val element = found.group("element")!!
            val item = CompletionItem(
                "?$element",
                CompletionItemKind.Variable,
            )
            item.insertText = SnippetString(element)
            item.range = Range(position, position)
            return listOf(item)
        }
    }

    return null
}

private fun createSnippetItem(
    label: String,
    description: String,
    insertText: SnippetString,
    range: Range,
): CompletionItem {
    val item = CompletionItem(label, CompletionItemKind.Snippet)
    item.labelDescription = description
    item.insertText = insertText
    item.range = range

    return item
}

private fun createPermitSnippetItems(range: Range): List<CompletionItem> {
    val item1 = createSnippetItem(
        "permit",
        "permit when",
        SnippetString(
            "permit (principal, action, resource)\n" + "when { \${0:Expr} };",
        ),
        range,
    )

    val item2 = createSnippetItem(
        "permit",
        "permit",
        SnippetString(
            "permit (\n" +
                "    principal == \${1:Path}::\"\${2:id}\",\n" +
                "    action == Action::\"\${3:id}\",\n" +
                "    resource == \${4:Path}::\"\${5:id}\"\n" +
                ")\$0;",
        ),
        range,
    )

    return listOf(item1, item2)
}

private fun createForbidSnippetItems(range: Range): List<CompletionItem> {
    val item1 = createSnippetItem(
        "forbid",
        "forbid when",
        SnippetString(
            "forbid (principal, action, resource)\n" + "when { \${0:Expr} };",
        ),
        range,
    )

    val item2 = createSnippetItem(
        "forbid",
        "forbid unless",
        SnippetString(
            "forbid (principal, action, resource)\n" + "unless { \${0:Expr} };",
        ),
        range,
    )

    return listOf(item1, item2)
}

private fun createWhenSnippetItems(range: Range): List<CompletionItem> {
    val item1 = createSnippetItem(
        "when",
        "when condition",
        SnippetString("when { \${0:Expr} }"),
        range,
    )

    return listOf(item1)
}

private fun createUnlessSnippetItems(range: Range): List<CompletionItem> {
    val item1 = createSnippetItem(
        "unless",
        "unless condition",
        SnippetString("unless { \${0:Expr} }"),
        range,
    )

    return listOf(item1)
}

private val SKIP_I_REGEX = Regex("""\b(principal|action|resource)\s+i$""")

private fun provideCedarInvokeCompletionItems(
    workspace: Workspace,
    document: TextDocument,
    position: Position,
): List<CompletionItem>? {
    val lineText = document.lineAt(position).text
    val linePrefix = lineText.jsSubstring(0, position.character)
    val lineSuffix = lineText.jsSubstring(position.character)
    // upstream: `new vscode.Position(line, character - 1)` throws at column 0, so no items are offered
    if (position.character - 1 < 0) return null
    val range = Range(
        Position(position.line, position.character - 1),
        position,
    )

    // some completion items are only suggests at beginning of line
    if (linePrefix.length == 1) {
        return when (linePrefix) {
            "p" -> createPermitSnippetItems(range)
            "w" -> createWhenSnippetItems(range)
            "u" -> createUnlessSnippetItems(range)
            "f" -> createForbidSnippetItems(range)
            else -> null
        }
    }

    var typeOnly = false
    var found = linePrefix.jsMatch(IS_REGEX)
    if (found != null) {
        typeOnly = true
    } else {
        found = linePrefix.jsMatch(SCOPE_REGEX)
    }
    if (found != null) {
        val element = found.group("element")!!
        val trigger = found.group("trigger")
        val schemaDoc = getSchemaTextDocument(workspace, document)
        if (schemaDoc != null) {
            typeOnly = typeOnly || lineSuffix.startsWith("::\"")
            return createEntityItems(position, schemaDoc, element, trigger, typeOnly)
        }
    }

    val lastChar = linePrefix.jsSubstring(linePrefix.length - 1)
    if (lastChar == " ") {
        // hotkey triggered completion
        if (linePrefix.endsWith(" has ")) {
            return provideCedarPeriodTriggerItems(
                workspace,
                position,
                linePrefix.jsSubstring(0, linePrefix.length - 5),
                document,
            )
        }

        return createInvokeItems(position)
    }
    val penultimateChar = linePrefix.jsSubstring(
        linePrefix.length - 2,
        linePrefix.length - 1,
    )
    if (listOf(" ", "(", "{", "[").contains(penultimateChar)) {
        when (lastChar) {
            "p" -> return listOf(createVariableItem(range, "principal"))

            "a" -> return listOf(createVariableItem(range, "action"))

            "r" -> return listOf(createVariableItem(range, "resource"))

            "c" -> return listOf(createVariableItem(range, "context"))

            "i" -> {
                if (linePrefix.jsMatch(SKIP_I_REGEX) == null) {
                    return listOf(createIpFunctionItem(range))
                }
            }

            "d" -> return listOf(
                createDecimalFunctionItem(range),
                createDatetimeFunctionItem(range),
                createDurationFunctionItem(range),
            )

            else -> {}
        }
    }

    return null
}

class CedarCompletionItemProvider(private val workspace: Workspace) {
    fun provideCompletionItems(
        document: TextDocument,
        position: Position,
        context: CompletionContext,
    ): List<CompletionItem>? {
        if (context.triggerKind == CompletionTriggerKind.TriggerCharacter) {
            return provideCedarTriggerCharacterCompletionItems(
                workspace,
                document,
                position,
                context,
            )
        } else if (context.triggerKind == CompletionTriggerKind.Invoke) {
            return provideCedarInvokeCompletionItems(
                workspace,
                document,
                position,
            )
        }

        return null
    }
}

// Cedar schema
class CedarSchemaCompletionItemProvider {
    fun provideCompletionItems(
        document: TextDocument,
        position: Position,
        context: CompletionContext,
    ): List<CompletionItem>? {
        if (context.triggerKind == CompletionTriggerKind.TriggerCharacter) {
            return provideTriggerCharacterCompletionItems(
                document,
                position,
                context,
            )
        } else if (context.triggerKind == CompletionTriggerKind.Invoke) {
            return provideInvokeCompletionItems(
                document,
                position,
                context,
            )
        }

        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun provideTriggerCharacterCompletionItems(
        document: TextDocument,
        position: Position,
        context: CompletionContext,
    ): List<CompletionItem>? {
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun provideInvokeCompletionItems(
        document: TextDocument,
        position: Position,
        context: CompletionContext,
    ): List<CompletionItem>? {
        val lineText = document.lineAt(position).text
        val linePrefix = lineText.jsSubstring(0, position.character)
        // upstream: `new vscode.Position(line, character - 1)` throws at column 0, so no items are offered
        if (position.character - 1 < 0) return null
        val range = Range(
            Position(position.line, position.character - 1),
            position,
        )

        // some completion items are only suggests at beginning of line
        if (position.line == document.lineCount - 1 && linePrefix.length == 1) {
            when (linePrefix) {
                "n" -> return createNamespaceSnippetItems(range)
                else -> {}
            }
        }

        if (linePrefix.trim().length == 1) {
            val definitionRanges = parseCedarSchemaDoc(document).definitionRanges
            for (definitionRange in definitionRanges) {
                if (definitionRange.range.contains(position)) {
                    return null
                }
            }
            when (linePrefix.trim()) {
                "a" -> return createActionSnippetItems(
                    range,
                    linePrefix.jsSubstring(0, linePrefix.length - 1),
                )
                "e" -> return createEntitySnippetItems(
                    range,
                    linePrefix.jsSubstring(0, linePrefix.length - 1),
                )
                "t" -> return createTypeSnippetItems(
                    range,
                    linePrefix.jsSubstring(0, linePrefix.length - 1),
                )
                else -> {}
            }
        }

        return null
    }
}

private fun createNamespaceSnippetItems(range: Range): List<CompletionItem> {
    val item1 = createSnippetItem(
        "namespace",
        "namespace",
        SnippetString("namespace \${1:NS} {\n" + "  \$0" + "\n}"),
        range,
    )
    return listOf(item1)
}

private fun createTypeSnippetItems(range: Range, prefix: String = "  "): List<CompletionItem> {
    val item1 = createSnippetItem(
        "type",
        "type",
        SnippetString(
            listOf("type \${1:T} = {", "  \$0", "};").joinToString(prefix + "\n"),
        ),
        range,
    )
    return listOf(item1)
}

private fun createEntitySnippetItems(range: Range, prefix: String = "  "): List<CompletionItem> {
    val item1 = createSnippetItem(
        "entity",
        "entity",
        SnippetString(
            listOf("entity \${1:E} {", "  \$0", "};").joinToString(prefix + "\n"),
        ),
        range,
    )
    val item2 = createSnippetItem(
        "entity",
        "entity in",
        SnippetString(
            listOf("entity \${1:E} in [\${2:E}] {", "  \$0", "};").joinToString(prefix + "\n"),
        ),
        range,
    )
    return listOf(item1, item2)
}

private fun createActionSnippetItems(range: Range, prefix: String = "  "): List<CompletionItem> {
    val item1 = createSnippetItem(
        "action",
        "action",
        SnippetString(
            listOf(
                "action \"\${1:a}\" appliesTo {",
                "  principal: [\${3:P}],",
                "  resource: [\${4:R}],",
                "  context: {\$0}",
                "};",
            ).joinToString(prefix + "\n"),
        ),
        range,
    )
    val item2 = createSnippetItem(
        "action",
        "action in",
        SnippetString(
            listOf(
                "action \"\${1:a}\" in [\${2:a}] appliesTo {",
                "  principal: [\${3:P}],",
                "  resource: [\${4:R}],",
                "  context: {\$0}",
                "};",
            ).joinToString(prefix + "\n"),
        ),
        range,
    )
    return listOf(item1, item2)
}
