// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/parser.ts.
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.jsonc.JSONPath
import io.github.thefellow.cedar.jsonc.JsonVisitor
import io.github.thefellow.cedar.jsonc.visit
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.SemanticTokens
import io.github.thefellow.cedar.vscode.SemanticTokensBuilder
import io.github.thefellow.cedar.vscode.SemanticTokensLegend
import io.github.thefellow.cedar.vscode.SymbolKind
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.wasm.Cedar
import java.util.concurrent.ConcurrentHashMap

/*
 * see https://code.visualstudio.com/api/language-extensions/semantic-highlight-guide
 * see https://github.com/microsoft/node-jsonc-parser
 */

private val tokenTypes = listOf(
    "namespace",
    "type",
    "struct",
    "property",
    "macro",
    "function",
    "variable",
    "operator",
    "keyword",
    "enumMember",
    "decorator",
    "string",
)
private val tokenModifiers = listOf("declaration", "deprecated", "readonly")
val semanticTokensLegend = SemanticTokensLegend(tokenTypes, tokenModifiers)

/** Port target for `vscode.DocumentSemanticTokensProvider`. */
fun interface DocumentSemanticTokensProvider {
    fun provideDocumentSemanticTokens(document: TextDocument): SemanticTokens
}

private fun makeRange(startLine: Int, startCharacter: Int, length: Int, offset: Int = 0): Range {
    return Range(
        Position(startLine, startCharacter + offset),
        Position(startLine, startCharacter + length - offset),
    )
}

private fun indexOfNonSpace(s: String): Int {
    for (i in s.indices) {
        if (s[i] != ' ') {
            return i
        }
    }
    return -1
}

private fun ensureNamespace(type: String, namespace: String): String {
    val pos = type.indexOf("::")
    return if (pos == -1) {
        namespace + type
    } else {
        type
    }
}

private fun determineReferenceRange(type: String, line: Int, start: Int, length: Int): Range {
    val pos = type.lastIndexOf("::")
    val typePos = if (pos == -1) 0 else pos + 2
    val defRange = Range(
        Position(line, start + typePos),
        Position(line, start + length - 1),
    )

    return defRange
}

data class ReferencedRange(val name: String, val range: Range)

private fun determineEntitiesInString(
    value: String,
    startLine: Int,
    startCharacter: Int,
    tokensBuilder: SemanticTokensBuilder,
    referencedTypes: MutableList<ReferencedRange>,
) {
    val foundArray = ENTITY_REGEXG.findAll(value).toList()
    foundArray.forEach { found ->
        val type = found.group("type") ?: return@forEach
        val typeRange = makeRange(startLine, startCharacter + 1, type.length)
        tokensBuilder.push(typeRange, "type", emptyList())
        referencedTypes.add(
            ReferencedRange(
                name = type,
                range = determineReferenceRange(type, startLine, startCharacter + 1, type.length + 1),
            ),
        )
    }
}

/*
 * Cedar policies
 */

data class EntityTypes(
    val principals: List<String>,
    val resources: List<String>,
    val actions: List<String>,
)

class PolicyRange(
    val id: String,
    val range: Range,
    val effectRange: Range,
    /** Lazily computed and cached by validate.ts (`fetchEntityTypes`). */
    @Volatile var entityTypes: EntityTypes?,
) {
    override fun toString() = "PolicyRange($id, $range, effect=$effectRange)"
}

class PolicyCacheItem(
    val version: Long,
    val policies: List<PolicyRange>,
    val tokens: SemanticTokens,
    val referencedTypes: List<ReferencedRange>,
    val actionIds: List<ReferencedRange>,
    val annotations: Set<String>,
)

private val policyCache = ConcurrentHashMap<String, PolicyCacheItem>()

private val ID_ANNOTATION = Regex("""@(id|cdkId)\("(?<id>(.+))"\)""")
private val ANNOTATION = Regex("""@(?<name>[_a-zA-Z][_a-zA-Z0-9]*)($|\(".*"\))""")

fun parseCedarPoliciesDoc(
    cedarDoc: TextDocument,
    visitPolicy: ((policyRange: PolicyRange, policyText: String) -> Unit)? = null,
): PolicyCacheItem {
    // policy text is not cached, so check for no visitPolicy callback
    if (visitPolicy == null) {
        val cachedItem = policyCache[cedarDoc.uri.toString()]
        if (cachedItem != null && cachedItem.version == cedarDoc.version) {
            // console.log("parseCedarPoliciesDoc (cached)");
            return cachedItem
        }
    }

    val policies = mutableListOf<PolicyRange>()
    val referencedTypes = mutableListOf<ReferencedRange>()
    val actionIds = mutableListOf<ReferencedRange>()
    val annotations = linkedSetOf("id")
    var count = 0
    var id: String? = null
    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)

    var tmpPolicy = StringBuilder()
    var effectRange: Range = DEFAULT_RANGE
    var startLine = 1
    for (i in 0 until cedarDoc.lineCount) {
        val textLine = cedarDoc.lineAt(i).text
        val trimmed = textLine.trim()
        if (trimmed.startsWith("@")) {
            if (id == null) {
                var found = trimmed.jsMatch(ID_ANNOTATION)
                if (found != null) {
                    id = found.group("id")
                } else {
                    found = trimmed.jsMatch(ANNOTATION)
                    val name = found?.group("name")
                    if (!name.isNullOrEmpty()) {
                        // collect annotation names for completion items
                        annotations.add(name)
                    }
                }
            }
        } else if (trimmed.startsWith("permit") || trimmed.startsWith("forbid")) {
            val startPos = maxOf(0, textLine.indexOf("permit"), textLine.indexOf("forbid"))
            effectRange = Range(Position(i, startPos), Position(i, startPos + 6))
        }

        if (tmpPolicy.isEmpty()) {
            startLine = i
        }
        if (!(tmpPolicy.isEmpty() && textLine.trim().isEmpty())) {
            tmpPolicy.append(textLine).append('\n')
        }

        val commentPos = textLine.indexOf("//")
        val linePreComment = textLine.substring(0, if (commentPos > -1) commentPos else textLine.length)

        // TODO: fix later, finds too many strings (like keywords), but these are
        // just candidate ranges resolved in findSchemaDefinition (definition.ts)
        val foundArray = ENTITY_REGEXG.findAll(linePreComment).toList()
        foundArray.forEach { found ->
            val type = found.group("type") ?: return@forEach
            if (type == "Action" || type.endsWith("::Action")) {
                val actionId = found.group("id")
                if (!actionId.isNullOrEmpty()) {
                    // `indexOf(...) || 0`: only 0 is falsy, so this is just indexOf
                    val startCharacter = linePreComment.indexOf("\"$actionId\"", found.index)
                    actionIds.add(
                        ReferencedRange(
                            name = "$type::\"$actionId\"",
                            range = makeRange(i, startCharacter + 1, actionId.length),
                        ),
                    )
                }
            } else {
                referencedTypes.add(
                    ReferencedRange(
                        name = type,
                        range = determineReferenceRange(type, i, found.index, type.length + 1),
                    ),
                )
            }
        }

        if (
            // end of policy or
            linePreComment.trim().endsWith(";") ||
            // end of file
            (i == cedarDoc.lineCount - 1 && !effectRange.isEqual(DEFAULT_RANGE))
        ) {
            val policyRange = PolicyRange(
                id = id ?: "policy$count",
                range = Range(Position(startLine, 0), Position(i, textLine.length)),
                effectRange = effectRange,
                entityTypes = null,
            )
            policies.add(policyRange)

            visitPolicy?.invoke(policyRange, tmpPolicy.toString())

            tmpPolicy = StringBuilder()
            count++
            id = null
            effectRange = DEFAULT_RANGE
        }
    }

    val cachedItem = PolicyCacheItem(
        version = cedarDoc.version,
        policies = policies,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
        actionIds = actionIds,
        annotations = annotations,
    )
    policyCache[cedarDoc.uri.toString()] = cachedItem

    return cachedItem
}

val cedarTokensProvider = DocumentSemanticTokensProvider { cedarDoc ->
    parseCedarPoliciesDoc(cedarDoc).tokens
}

/*
 * Cedar policy (JSON)
 */

class PolicyJsonCacheItem(
    val version: Long,
    val tokens: SemanticTokens,
    val referencedTypes: List<ReferencedRange>,
    val actionIds: List<ReferencedRange>,
)

private val policyJsonCache = ConcurrentHashMap<String, PolicyJsonCacheItem>()

fun parseCedarJsonPolicyDoc(cedarJsonDoc: TextDocument): PolicyJsonCacheItem {
    val cached = policyJsonCache[cedarJsonDoc.uri.toString()]
    if (cached != null && cached.version == cedarJsonDoc.version) {
        // console.log("parseCedarJsonPolicyDoc (cached)");
        return cached
    }

    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)

    val referencedTypes = mutableListOf<ReferencedRange>()
    val actionIds = mutableListOf<ReferencedRange>()
    var tmpActionType = ""
    var pathLenOffset = 0

    visit(cedarJsonDoc.getText(), object : JsonVisitor {
        override fun onObjectProperty(
            property: String,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            if (pathSupplier().isEmpty()) {
                if (property in listOf("templates", "staticPolicies", "templateLinks")) {
                    // multiple Cedar policies in JSON offset paths by 2
                    pathLenOffset = 2
                }
            }
            val path = pathSupplier()
            val jsonPathLen = path.size
            if (jsonPathLen - pathLenOffset == 0) {
                if (property in listOf("principal", "action", "resource")) {
                    tokensBuilder.push(range, "variable", listOf("readonly"))
                }
            } else if (jsonPathLen - pathLenOffset == 1 && path.at(jsonPathLen - 1) == "annotations") {
                tokensBuilder.push(range, "decorator", emptyList())
            } else if (property in listOf("&&", "||", "==", "!=", ">=", "<=", "<", ">", ".")) {
                tokensBuilder.push(range, "operator", emptyList())
            } else if (property in listOf("if", "then", "else") && path.at(jsonPathLen - 1) == "if-then-else") {
                tokensBuilder.push(range, "keyword", emptyList())
            } else if (property == "__entity" && path.at(jsonPathLen - 1) == "Value") {
                tokensBuilder.push(range, "macro", emptyList())
            }
        }

        override fun onLiteralValue(
            value: Any?,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            val path = pathSupplier()
            val jsonPathLen = path.size
            if (jsonPathLen - pathLenOffset == 1 && path.at(pathLenOffset) == "effect") {
                tokensBuilder.push(range, "keyword", emptyList())
            }

            if (
                jsonPathLen - pathLenOffset == 2 &&
                path.at(jsonPathLen - 1) == "slot" &&
                value in listOf("?principal", "?resource")
            ) {
                tokensBuilder.push(range, "variable", emptyList())
            }

            if (jsonPathLen - pathLenOffset == 2 && path.at(jsonPathLen - 1) == "op") {
                tokensBuilder.push(range, "operator", emptyList())
            }

            if (
                (jsonPathLen - pathLenOffset == 2 && path.at(jsonPathLen - 1) == "entity_type") ||
                (jsonPathLen > 2 && path.at(jsonPathLen - 1) == "type")
                // most things directly under "type" are a type
            ) {
                tokensBuilder.push(range, "type", emptyList())

                val s = jsString(value)
                if (s == "Action" || s.endsWith("::Action")) {
                    tmpActionType = s
                } else {
                    referencedTypes.add(
                        ReferencedRange(
                            name = s,
                            range = determineReferenceRange(s, startLine, startCharacter + 1, length - 1),
                        ),
                    )
                }
            }

            if (
                jsonPathLen - pathLenOffset == 3 &&
                path.at(pathLenOffset) == "conditions" &&
                path.at(jsonPathLen - 1) == "kind"
            ) {
                // most things directly under "kind" are a keyword
                tokensBuilder.push(range, "keyword", emptyList())
            } else if (
                jsonPathLen - pathLenOffset > 2 &&
                // type / id under top level 'action'
                (path.at(pathLenOffset) == "action" ||
                    // type / id directly under '__entity'
                    path.at(jsonPathLen - 2) == "__entity")
            ) {
                if (path.at(jsonPathLen - 1) == "id") {
                    actionIds.add(
                        ReferencedRange(
                            name = "$tmpActionType::\"${jsString(value)}\"",
                            range = makeRange(startLine, startCharacter, length, 1),
                        ),
                    )
                    tmpActionType = ""
                }
            } else if (jsonPathLen - pathLenOffset > 2 && path.at(jsonPathLen - 1) == "Var") {
                // most things directly under "Var" are a variable
                tokensBuilder.push(range, "variable", emptyList())
            }
        }
    })

    val cachedItem = PolicyJsonCacheItem(
        version = cedarJsonDoc.version,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
        actionIds = actionIds,
    )
    policyJsonCache[cedarJsonDoc.uri.toString()] = cachedItem

    return cachedItem
}

val cedarJsonTokensProvider = DocumentSemanticTokensProvider { cedarJsonDoc ->
    parseCedarJsonPolicyDoc(cedarJsonDoc).tokens
}

/*
 * Cedar entities
 */

class EntityRange(
    val uid: String,
    val range: Range,
    val uidKeyRange: Range,
    val uidTypeRange: Range?,
    val attrsKeyRange: Range?,
    val attrsRange: Range?,
    val attrsNameRanges: Map<String, Range>,
    val parentsKeyRange: Range?,
    val parentsRange: Range?,
    val tagsKeyRange: Range?,
    val tagsRange: Range?,
    val tagsNameRanges: Map<String, Range>,
) {
    override fun toString() = "EntityRange($uid, $range)"
}

class EntityCacheItem(
    val version: Long,
    val entities: List<EntityRange>,
    val tokens: SemanticTokens,
    val referencedTypes: List<ReferencedRange>,
)

private val entityCache = ConcurrentHashMap<String, EntityCacheItem>()

fun parseCedarEntitiesDoc(
    entitiesDoc: TextDocument,
    visitEntity: ((entityRange: EntityRange, entityText: String) -> Unit)? = null,
): EntityCacheItem {
    // entity text is not cached, so check for no visitEntity callback
    if (visitEntity == null) {
        val cachedItem = entityCache[entitiesDoc.uri.toString()]
        if (cachedItem != null && cachedItem.version == entitiesDoc.version) {
            // console.log("parseCedarEntitiesDoc (cached)");
            return cachedItem
        }
    }

    var UID = "uid"
    var TYPE = "type"
    var ID = "id"
    var ENTITY = "__entity"
    var ATTRS = "attrs"
    val PARENTS = "parents"
    val TAGS = "tags"

    // Amazon Verified Permissions entities format has a similar structure but different property names
    val filename = entitiesDoc.uri
        .toString()
        .substring(entitiesDoc.uri.toString().lastIndexOf('/') + 1)
    if (filename == "avpentities.json" || filename.endsWith(".avpentities.json")) {
        UID = "identifier"
        TYPE = "entityType"
        ID = "entityId"
        ENTITY = "entityIdentifier"
        ATTRS = "attributes"
    }

    val entities = mutableListOf<EntityRange>()
    val referencedTypes = mutableListOf<ReferencedRange>()

    var uidType = ""
    var uid = ""
    var uidId = ""
    var uidStart: Position? = null
    var uidEnd: Position?
    var uidKeyRange: Range? = null
    var uidTypeRange: Range? = null
    var attrsKeyRange: Range? = null
    var attrsRange: Range? = null
    var parentsKeyRange: Range? = null
    var parentsRange: Range? = null
    var tagsKeyRange: Range? = null
    var tagsRange: Range? = null
    var depth = 0
    var depth1Property = ""
    var attrsNameRanges = LinkedHashMap<String, Range>()
    var tagsNameRanges = LinkedHashMap<String, Range>()
    var parentsCount = 0
    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)

    visit(entitiesDoc.getText(), object : JsonVisitor {
        override fun onObjectBegin(
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ): Boolean {
            depth++
            if (pathSupplier().size == 1) {
                uidStart = Position(startLine, startCharacter)
            }
            return true
        }

        override fun onObjectEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {
            depth--
            if (depth == 0) {
                uidEnd = Position(startLine, startCharacter + length)
                val start = uidStart
                val end = uidEnd
                val keyRange = uidKeyRange
                if (start != null && end != null && keyRange != null) {
                    val entityRange = EntityRange(
                        uid = uid,
                        range = Range(start, end),
                        uidKeyRange = keyRange,
                        uidTypeRange = uidTypeRange,
                        attrsKeyRange = attrsKeyRange,
                        attrsRange = attrsRange,
                        attrsNameRanges = attrsNameRanges,
                        parentsKeyRange = parentsKeyRange,
                        parentsRange = parentsRange,
                        tagsKeyRange = tagsKeyRange,
                        tagsRange = tagsRange,
                        tagsNameRanges = tagsNameRanges,
                    )
                    entities.add(entityRange)
                }
                attrsKeyRange = null
                attrsRange = null
                attrsNameRanges = LinkedHashMap()
                parentsKeyRange = null
                parentsRange = null
                tagsKeyRange = null
                tagsRange = null
                tagsNameRanges = LinkedHashMap()
                parentsCount = 0
            } else if (depth == 1 && depth1Property == ATTRS && attrsKeyRange != null && attrsNameRanges.isNotEmpty()) {
                // only enable the range if there are attribute names
                attrsRange = Range(attrsKeyRange!!.start, Position(startLine, startCharacter + length))
            } else if (depth == 1 && depth1Property == TAGS && tagsKeyRange != null && tagsNameRanges.isNotEmpty()) {
                // only enable the range if there are tag names
                tagsRange = Range(tagsKeyRange!!.start, Position(startLine, startCharacter + length))
            } else if (depth == 1 && depth1Property == PARENTS && parentsKeyRange != null) {
                parentsCount++
            }
        }

        override fun onArrayEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {
            if (depth == 1 && depth1Property == PARENTS && parentsKeyRange != null && parentsCount > 0) {
                // only enable the range if there are objects inside parents array
                parentsRange = Range(parentsKeyRange!!.start, Position(startLine, startCharacter + length))
            }
        }

        override fun onObjectProperty(
            property: String,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            val innerRange = makeRange(startLine, startCharacter, length, 1)
            val path = pathSupplier()
            val jsonPathLen = path.size
            if (jsonPathLen == 1) {
                depth1Property = property
                if (property == UID) {
                    uidKeyRange = innerRange
                } else if (property == ATTRS) {
                    attrsKeyRange = innerRange
                } else if (property == PARENTS) {
                    parentsKeyRange = innerRange
                } else if (property == TAGS) {
                    tagsKeyRange = innerRange
                }
            } else if (jsonPathLen == 2 && path.at(1) == ATTRS) {
                // anything directly under "attrs" is an property
                tokensBuilder.push(range, "property", emptyList())
                attrsNameRanges[property] = innerRange
            } else if (jsonPathLen == 2 && path.at(1) == TAGS) {
                // anything directly under "tags" is an property
                tokensBuilder.push(range, "property", emptyList())
                tagsNameRanges[property] = innerRange
            } else if (property == "__expr") {
                // treat "__expr" as a deprecated macro
                tokensBuilder.push(range, "macro", listOf("deprecated"))
            } else if (property == "__entity" || property == "__extn") {
                // treat "__entity" and "__extn" as a macro
                tokensBuilder.push(range, "macro", emptyList())
            }
        }

        override fun onLiteralValue(
            value: Any?,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            val path = pathSupplier()
            val jsonPathLen = path.size
            if (path.at(1) == UID) {
                if (path.at(jsonPathLen - 1) == TYPE) {
                    uidType = jsString(value)
                    uid = "$uidType::\"$uidId\""
                    uidTypeRange = makeRange(startLine, startCharacter, length, 1)
                }
                if (path.at(jsonPathLen - 1) == ID) {
                    uidId = jsString(value)
                    uid = "$uidType::\"$uidId\""
                }
            }

            // entities as strings under UID or string array element under PARENTS
            if (
                (jsonPathLen == 2 && path.at(1) == UID) ||
                (jsonPathLen == 3 && path.at(1) == PARENTS)
            ) {
                if (path.at(1) == UID) {
                    uid = jsString(value)
                    uidTypeRange = makeRange(startLine, startCharacter, length)
                }
                // upstream calls String.matchAll, which only works on string values
                if (value is String) {
                    determineEntitiesInString(value, startLine, startCharacter, tokensBuilder, referencedTypes)
                }
            }

            if (
                jsonPathLen > 2 &&
                path.at(jsonPathLen - 1) == TYPE &&
                (path.at(1) == UID ||
                    path.at(1) == PARENTS ||
                    (path.at(1) == ATTRS && jsonPathLen > 3) ||
                    path.at(jsonPathLen - 2) == ENTITY)
            ) {
                // most things directly under "type" are a type
                tokensBuilder.push(range, "type", emptyList())
                val s = jsString(value)
                referencedTypes.add(
                    ReferencedRange(
                        name = s,
                        range = determineReferenceRange(s, startLine, startCharacter + 1, length - 1),
                    ),
                )
            } else if (
                jsonPathLen > 2 &&
                path.at(jsonPathLen - 1) == "fn" &&
                (path.at(jsonPathLen - 2) == "__extn" ||
                    value in listOf("ip", "decimal", "datetime", "duration"))
            ) {
                // things under "__extn" then "fn" are a function
                tokensBuilder.push(range, "function", emptyList())
            }
        }
    })

    val cachedItem = EntityCacheItem(
        version = entitiesDoc.version,
        entities = entities,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
    )
    entityCache[entitiesDoc.uri.toString()] = cachedItem

    return cachedItem
}

// analyze the Cedar entities JSON documents and return semantic tokens
val entitiesTokensProvider = DocumentSemanticTokensProvider { cedarEntitiesDoc ->
    parseCedarEntitiesDoc(cedarEntitiesDoc).tokens
}

/*
 * Cedar template links
 */

data class TemplateLinkRange(val id: String, val range: Range, val linkIdRange: Range)

class TemplateLinksCacheItem(
    val version: Long,
    val links: List<TemplateLinkRange>,
    val tokens: SemanticTokens,
    val referencedTypes: List<ReferencedRange>,
)

private val templateLinksCache = ConcurrentHashMap<String, TemplateLinksCacheItem>()

fun parseCedarTemplateLinksDoc(cedarTemplateLinksDoc: TextDocument): TemplateLinksCacheItem {
    val cached = templateLinksCache[cedarTemplateLinksDoc.uri.toString()]
    if (cached != null && cached.version == cedarTemplateLinksDoc.version) {
        // console.log("parseCedarTemplateLinksDoc (cached)");
        return cached
    }

    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)

    var linkId = ""
    var linkStart: Position? = null
    var linkEnd: Position?
    var linkIdRange: Range? = null
    var depth = 0
    val links = mutableListOf<TemplateLinkRange>()
    val referencedTypes = mutableListOf<ReferencedRange>()

    visit(cedarTemplateLinksDoc.getText(), object : JsonVisitor {
        override fun onObjectBegin(
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ): Boolean {
            depth++
            if (pathSupplier().size == 1) {
                linkStart = Position(startLine, startCharacter)
            }
            return true
        }

        override fun onObjectEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {
            depth--
            if (depth == 0) {
                linkEnd = Position(startLine, startCharacter + length)
                val start = linkStart
                val end = linkEnd
                val idRange = linkIdRange
                if (start != null && end != null && idRange != null) {
                    val templateLinkRange = TemplateLinkRange(
                        id = linkId,
                        range = Range(start, end),
                        linkIdRange = idRange,
                    )
                    links.add(templateLinkRange)
                }
            }
        }

        override fun onLiteralValue(
            value: Any?,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val path = pathSupplier()
            val jsonPathLen = path.size
            if (path.at(1) == "link_id") {
                linkId = jsString(value)
            } else if (path.at(1) == "args" && jsonPathLen == 3) {
                // upstream calls String.matchAll, which only works on string values
                if (value is String) {
                    determineEntitiesInString(value, startLine, startCharacter, tokensBuilder, referencedTypes)
                }
            }
        }

        override fun onObjectProperty(
            property: String,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            val jsonPathLen = pathSupplier().size
            if (jsonPathLen == 1) {
                if (property == "link_id") {
                    linkIdRange = makeRange(startLine, startCharacter, length, 1)
                }
            } else if (jsonPathLen == 2) {
                if (property in listOf("?principal", "?resource")) {
                    tokensBuilder.push(range, "variable", emptyList())
                }
            }
        }
    })

    val cachedItem = TemplateLinksCacheItem(
        version = cedarTemplateLinksDoc.version,
        links = links,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
    )
    templateLinksCache[cedarTemplateLinksDoc.uri.toString()] = cachedItem

    return cachedItem
}

// analyze the Cedar template links JSON documents and return semantic tokens
val templateLinksTokensProvider = DocumentSemanticTokensProvider { cedarTemplateLinksDoc ->
    parseCedarTemplateLinksDoc(cedarTemplateLinksDoc).tokens
}

/*
 * Cedar authorization requests (PARC)
 */
class AuthCacheItem(
    val version: Long,
    val tokens: SemanticTokens,
    val referencedTypes: List<ReferencedRange>,
    val actionIds: List<ReferencedRange>,
)

private val authCache = ConcurrentHashMap<String, AuthCacheItem>()

fun parseCedarAuthDoc(cedarAuthDoc: TextDocument): AuthCacheItem {
    val cached = authCache[cedarAuthDoc.uri.toString()]
    if (cached != null && cached.version == cedarAuthDoc.version) {
        // console.log("parseCedarAuthDoc (cached)");
        return cached
    }

    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)
    val referencedTypes = mutableListOf<ReferencedRange>()
    val actionIds = mutableListOf<ReferencedRange>()

    visit(cedarAuthDoc.getText(), object : JsonVisitor {
        override fun onLiteralValue(
            value: Any?,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val path = pathSupplier()
            val jsonPathLen = path.size
            val property = path.at(0)
            if (property in listOf("principal", "action", "resource") && jsonPathLen == 1) {
                // upstream calls String.matchAll, which only works on string values
                if (value !is String) return
                val foundArray = ENTITY_REGEXG.findAll(value).toList()
                foundArray.forEach { found ->
                    val type = found.group("type") ?: return@forEach
                    val typeRange = makeRange(startLine, startCharacter + 1, type.length)
                    tokensBuilder.push(typeRange, "type", emptyList())

                    if (property == "action") {
                        // JS `lastIndexOf(undefined)` searches for "undefined"
                        val actionId = found.group("id") ?: "undefined"
                        val pos = value.lastIndexOf(actionId)
                        if (pos > -1) {
                            actionIds.add(
                                ReferencedRange(
                                    name = value,
                                    range = makeRange(startLine, startCharacter + pos + 2, actionId.length),
                                ),
                            )
                        }
                    } else {
                        referencedTypes.add(
                            ReferencedRange(
                                name = type,
                                range = determineReferenceRange(type, startLine, startCharacter + 1, type.length + 1),
                            ),
                        )
                    }
                }
            }
        }

        override fun onObjectProperty(
            property: String,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            val jsonPathLen = pathSupplier().size
            if (jsonPathLen == 0) {
                if (property in listOf("principal", "action", "resource", "context")) {
                    tokensBuilder.push(range, "variable", emptyList())
                }
            }
        }
    })

    val cachedItem = AuthCacheItem(
        version = cedarAuthDoc.version,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
        actionIds = actionIds,
    )
    authCache[cedarAuthDoc.uri.toString()] = cachedItem

    return cachedItem
}

// analyze the Cedar request JSON documents and return semantic tokens
val authTokensProvider = DocumentSemanticTokensProvider { cedarAuthDoc ->
    parseCedarAuthDoc(cedarAuthDoc).tokens
}

// Cedar schema

val PRIMITIVE_TYPES = listOf(
    "String",
    "Long",
    "Bool",
    "Boolean",
    "Record",
    "Set",
    "Entity",
    "Extension",
    "EntityOrCommon",
)

private val EXTENSIONS = listOf("ipaddr", "decimal", "datetime", "duration")

class SchemaRange(
    /** 'commonTypes' | 'entityTypes' | 'actions' */
    val collection: String,
    val etype: String,
    var enums: List<String>? = null,
    val range: Range,
    val etypeRange: Range,
    val symbol: SymbolKind,
) {
    override fun toString() = "SchemaRange($collection, $etype, $range, $etypeRange, $symbol, enums=$enums)"
}

class SchemaCompletionData(
    val description: String,
    var children: SchemaCompletionRecord? = null,
) {
    override fun toString() = if (children == null) description else "$description$children"
}

typealias SchemaCompletionRecord = MutableMap<String, SchemaCompletionData>

class SchemaCacheItem(
    var version: Long,
    val definitionRanges: List<SchemaRange>,
    val tokens: SemanticTokens,
    val referencedTypes: List<ReferencedRange>,
    var entityTypes: List<String>,
    val actionIds: List<ReferencedRange>,
    var completions: Map<String, SchemaCompletionRecord>,
    var tags: List<String>,
)

private val schemaCache = ConcurrentHashMap<String, SchemaCacheItem>()

// upstream mutates the cached item after caching it (cedarschema); serialize to avoid torn reads
private val schemaLock = Any()

fun parseCedarSchemaDoc(
    schemaDoc: TextDocument,
    visitSchema: ((schemaRange: SchemaRange, schemaText: String) -> Unit)? = null,
): SchemaCacheItem = synchronized(schemaLock) {
    // schema text is not cached, so check for no visitSchema callback
    if (visitSchema == null) {
        val cachedItem = schemaCache[schemaDoc.uri.toString()]
        if (cachedItem != null && cachedItem.version == schemaDoc.version) {
            // console.log("parseCedarDocSchema (cached)");
            return cachedItem
        }
    }

    val cachedItem: SchemaCacheItem

    if (schemaDoc.languageId == "cedarschema") {
        cachedItem = parseCedarSchemaCedarDoc(schemaDoc, visitSchema)

        // get JSON
        val translateResult = Cedar.translateSchemaToJSON(schemaDoc.getText())
        if (translateResult.success && !translateResult.schema.isNullOrEmpty()) {
            // update cachedItem
            val tmpCachedItem = parseCedarSchemaJSONText(translateResult.schema)
            cachedItem.completions = tmpCachedItem.completions
            cachedItem.entityTypes = tmpCachedItem.entityTypes
            cachedItem.tags = tmpCachedItem.tags

            val enums = LinkedHashMap<String, List<String>>()
            tmpCachedItem.definitionRanges.forEach { definition ->
                val e = definition.enums
                if (e != null && e.isNotEmpty()) {
                    enums[definition.etype] = e
                }
            }
            if (enums.isNotEmpty()) {
                cachedItem.definitionRanges.forEach { definition ->
                    enums[definition.etype]?.let { definition.enums = it }
                }
            }
        }
    } else {
        cachedItem = parseCedarSchemaJSONText(schemaDoc.getText())
    }

    cachedItem.version = schemaDoc.version
    schemaCache[schemaDoc.uri.toString()] = cachedItem

    return cachedItem
}

private class TmpMemberOf(var id: String = "", var type: String = "", var range: Range? = null)

private class TmpAttribute(var key: String = "", var type: String = "", var range: Range? = null)

private fun parseCedarSchemaJSONText(schemaText: String): SchemaCacheItem {
    val definitionRanges = mutableListOf<SchemaRange>()
    val referencedTypes = mutableListOf<ReferencedRange>()
    val actionIds = mutableListOf<ReferencedRange>()
    val entityTypes = mutableListOf<String>()
    // JS object semantics: assigning undefined is equivalent to an absent key for lookups
    val completions = LinkedHashMap<String, SchemaCompletionRecord>()
    val tags = mutableListOf<String>()

    var namespace = ""
    var collection = "entityTypes"
    var etype = ""
    var enums: MutableList<String>? = null
    var etypeStart: Position? = null
    var etypeEnd: Position?
    var etypeRange: Range? = null
    var tmpMemberOf = TmpMemberOf()
    var tmpAttribute = TmpAttribute()

    // null models upstream's `undefined` (a shape that is a reference to an unknown common type)
    var tmpSchemaCompletionRecord: SchemaCompletionRecord? = LinkedHashMap()
    val tmpSchemaCompletionRecordStack = ArrayDeque<SchemaCompletionRecord?>()
    val tmpAttributeDepthStack = ArrayDeque<Int>()
    var depth = 0

    fun captureAttribute(type: String) {
        // upstream would throw a TypeError on an undefined record; skip instead
        val record = tmpSchemaCompletionRecord
        if (record != null) {
            record[tmpAttribute.key] = SchemaCompletionData(description = type)

            if (type == "Record") {
                val children: SchemaCompletionRecord = LinkedHashMap()
                record[tmpAttribute.key]!!.children = children
                tmpSchemaCompletionRecordStack.addLast(record)
                tmpSchemaCompletionRecord = children

                tmpAttributeDepthStack.addLast(depth)
            }
        }

        tmpAttribute = TmpAttribute()
    }

    var symbol = SymbolKind.Class
    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)

    visit(schemaText, object : JsonVisitor {
        override fun onObjectBegin(
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ): Boolean {
            depth++
            return true
        }

        override fun onObjectEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) {
            if (tmpAttributeDepthStack.isNotEmpty()) {
                if (tmpAttributeDepthStack.last() == depth) {
                    tmpAttributeDepthStack.removeLast()
                    tmpSchemaCompletionRecord = tmpSchemaCompletionRecordStack.removeLastOrNull()
                }
            }
            depth--
            if (depth == 3) {
                val record = tmpSchemaCompletionRecord
                if (record != null) completions[etype] = record else completions.remove(etype)
                tmpSchemaCompletionRecord = LinkedHashMap()
                etypeEnd = Position(startLine, startCharacter + length)
                val start = etypeStart
                val end = etypeEnd
                val eRange = etypeRange
                if (start != null && end != null && eRange != null) {
                    val schemaRange = SchemaRange(
                        collection = collection,
                        etype = etype,
                        enums = enums,
                        range = Range(start, end),
                        etypeRange = eRange,
                        symbol = symbol,
                    )
                    definitionRanges.add(schemaRange)
                    if (collection == "entityTypes") {
                        entityTypes.add(etype)
                    }
                }
            } else if (depth == 4) {
                val memberOfRange = tmpMemberOf.range
                if (tmpMemberOf.id.isNotEmpty() && memberOfRange != null) {
                    actionIds.add(
                        ReferencedRange(
                            name = if (tmpMemberOf.type.isNotEmpty()) {
                                "${tmpMemberOf.type}::\"${tmpMemberOf.id}\""
                            } else {
                                "${namespace}Action::\"${tmpMemberOf.id}\""
                            },
                            range = memberOfRange,
                        ),
                    )
                }
                tmpMemberOf = TmpMemberOf()
            }
        }

        override fun onObjectProperty(
            property: String,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val range = makeRange(startLine, startCharacter, length)
            val path = pathSupplier()
            val jsonPathLen = path.size
            if (jsonPathLen == 0) {
                namespace = if (property.isNotEmpty()) {
                    "$property::"
                } else {
                    ""
                }
                tokensBuilder.push(range, "namespace", listOf("declaration"))
            } else if (jsonPathLen == 2) {
                etypeStart = Position(startLine, startCharacter)
                etypeRange = makeRange(startLine, startCharacter, length, 1)
                enums = null

                if (path.at(1) == "commonTypes") {
                    tokensBuilder.push(range, "struct", listOf("declaration"))
                    collection = "commonTypes"
                    etype = namespace + property
                    symbol = SymbolKind.Struct
                } else if (path.at(1) == "entityTypes") {
                    tokensBuilder.push(range, "type", listOf("declaration"))
                    collection = "entityTypes"
                    etype = namespace + property
                    symbol = SymbolKind.Class
                } else if (path.at(1) == "actions") {
                    tokensBuilder.push(range, "type", listOf("declaration"))
                    collection = "actions"
                    etype = "${namespace}Action::\"$property\""
                    symbol = SymbolKind.Function
                } else if (path.at(jsonPathLen - 1) == "annotations") {
                    // anything directly under "annotations" is an annotations declaration
                    tokensBuilder.push(range, "decorator", emptyList())
                }
            } else if (jsonPathLen == 3 && path.at(1) == "entityTypes" && property == "tags") {
                tags.add(etype)
                tokensBuilder.push(range, "keyword", emptyList())
            } else if (jsonPathLen == 3 && path.at(1) == "entityTypes" && property == "enum") {
                tokensBuilder.push(range, "keyword", emptyList())
                enums = mutableListOf()
            } else if (jsonPathLen == 3 && path.at(1) == "actions" && property == "appliesTo") {
                tokensBuilder.push(range, "keyword", emptyList())
            } else if (path.at(jsonPathLen - 1) == "annotations") {
                // anything directly under "annotations" is an annotations declaration
                tokensBuilder.push(range, "decorator", emptyList())
            } else if (path.at(jsonPathLen - 1) == "attributes") {
                // anything directly under "attributes" is an property declaration
                tokensBuilder.push(range, "property", listOf("declaration"))

                tmpAttribute.key = property
            }
        }

        override fun onLiteralValue(
            value: Any?,
            offset: Int,
            length: Int,
            startLine: Int,
            startCharacter: Int,
            pathSupplier: () -> JSONPath,
        ) {
            val path = pathSupplier()
            val jsonPathLen = path.size
            val range = makeRange(startLine, startCharacter, length)
            // upstream assumes string values here; coerce like a template literal would
            val s = jsString(value)
            if (
                // anything directly under "memberOfTypes" under "entityTypes" is a type
                jsonPathLen == 5 &&
                path.at(1) == "entityTypes" &&
                path.at(3) == "memberOfTypes"
            ) {
                tokensBuilder.push(range, "type", emptyList())
                if (path.at(3) == "memberOfTypes") {
                    referencedTypes.add(
                        ReferencedRange(
                            name = ensureNamespace(s, namespace),
                            range = determineReferenceRange(s, startLine, startCharacter + 1, length - 1),
                        ),
                    )
                }
            } else if (
                // anything directly under "enum" under "entityTypes" is a enumMember
                jsonPathLen == 5 &&
                path.at(1) == "entityTypes" &&
                path.at(3) == "enum"
            ) {
                tokensBuilder.push(range, "enumMember", emptyList())
                enums?.add(s)
            } else if (
                // anything directly under "principalTypes" or "resourceTypes" under "actions" is a type
                jsonPathLen == 6 &&
                path.at(1) == "actions" &&
                (path.at(4) == "principalTypes" || path.at(4) == "resourceTypes")
            ) {
                tokensBuilder.push(range, "type", emptyList())
                referencedTypes.add(
                    ReferencedRange(
                        name = ensureNamespace(s, namespace),
                        range = determineReferenceRange(s, startLine, startCharacter + 1, length - 1),
                    ),
                )
            } else if (
                // "id" or "type" under "memberOf" under "actions" is a type
                jsonPathLen == 6 &&
                path.at(1) == "actions" &&
                path.at(3) == "memberOf" &&
                (path.at(5) == "id" || path.at(5) == "type")
            ) {
                tokensBuilder.push(range, "type", emptyList())

                // save off id, range, and (optional) type
                // actionIds is updated inside onObjectEnd
                if (path.at(5) == "type") {
                    tmpMemberOf.type = s
                } else if (path.at(5) == "id") {
                    tmpMemberOf.id = s
                    tmpMemberOf.range = makeRange(startLine, startCharacter, length, 1)
                }
            } else if (path.at(jsonPathLen - 1) == "type") {
                // anything directly under "type" not matching a primitive type (probably) a common type
                if (value !in PRIMITIVE_TYPES) {
                    tokensBuilder.push(range, "struct", emptyList())
                    referencedTypes.add(
                        ReferencedRange(
                            name = ensureNamespace(s, namespace),
                            range = determineReferenceRange(s, startLine, startCharacter + 1, length - 1),
                        ),
                    )
                }
                if (path.at(jsonPathLen - 2) == "element") {
                    if (value != "Entity" && value != "EntityOrCommon") {
                        // 'type' under 'element' indicates parent is a Set
                        captureAttribute("Set<$s>")
                    }
                } else if (path.at(jsonPathLen - 3) == "attributes") {
                    tmpAttribute.type = s
                    if (value !in listOf("Set", "Entity", "Extension", "EntityOrCommon")) {
                        captureAttribute(s)
                    }
                } else if (path.at(jsonPathLen - 2) == "shape" || path.at(jsonPathLen - 2) == "context") {
                    if (value != "Record") {
                        // the whole shape is a commonType, so just link to it
                        tmpSchemaCompletionRecord = completions[ensureNamespace(s, namespace)]
                    }
                }
            } else if (path.at(jsonPathLen - 1) == "name") {
                // anything directly under "name" is (probably) a type
                if (value in EXTENSIONS) {
                    tokensBuilder.push(range, "function", emptyList())
                } else if (value in PRIMITIVE_TYPES || s.startsWith("__cedar::")) {
                    // pass
                } else {
                    tokensBuilder.push(range, "type", emptyList())
                    referencedTypes.add(
                        ReferencedRange(
                            name = ensureNamespace(s, namespace),
                            range = determineReferenceRange(s, startLine, startCharacter + 1, length - 1),
                        ),
                    )
                }
                if (path.at(jsonPathLen - 2) == "element") {
                    // 'name' under 'element' indicates parent is a Set
                    if (value in EXTENSIONS || value in PRIMITIVE_TYPES || s.startsWith("__cedar::")) {
                        captureAttribute("Set<$s>")
                    } else {
                        captureAttribute("Set<${ensureNamespace(s, namespace)}>")
                    }
                } else if (path.at(jsonPathLen - 3) == "attributes") {
                    if (value in EXTENSIONS || value in PRIMITIVE_TYPES || s.startsWith("__cedar::")) {
                        captureAttribute(if (s.startsWith("__cedar::")) s.substring(9) else s)
                    } else {
                        captureAttribute(ensureNamespace(s, namespace))
                    }
                }
            }
        }
    })

    val cachedItem = SchemaCacheItem(
        version = 0,
        definitionRanges = definitionRanges,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
        entityTypes = entityTypes,
        actionIds = actionIds,
        completions = completions,
        tags = tags,
    )

    return cachedItem
}

private val NAMESPACE_DECL_REGEX = Regex("""^namespace\s+(([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*)\s*\{""")
private val TYPE_DECL_REGEX = Regex("""^type\s+([_a-zA-Z][_a-zA-Z0-9]*)\s*=""")
private val ENTITY_DECL_REGEX =
    Regex("""^entity\s+(([_a-zA-Z][_a-zA-Z0-9]*,\s*)*[_a-zA-Z][_a-zA-Z0-9]*)\s*( enum| in|=|\{|;|$)""")
private val ACTION_DECL_REGEX =
    Regex("""^action\s+((?:"[^"]*"|[_a-zA-Z][_a-zA-Z0-9]*)(?:\s*,\s*(?:"[^"]*"|[_a-zA-Z][_a-zA-Z0-9]*))*)\s*(?= in | appliesTo|;|$)""")
private val BRACKET_LIST_REGEX = Regex("""\[([_a-zA-Z0-9:, "]+)\]""")
private val IN_REGEX = Regex(""" in\s+(([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*)""")
private val ATTRIBUTE_DECL_REGEX =
    Regex("""^\s*(?:("[^"]+"|[_a-zA-Z][_a-zA-Z0-9]*))[?]?\s*:\s*(?:Set\s*<\s*)?(?<type>([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*)\s*(?:\s*>\s*)?""")

// DIVERGENCE (semport/DIVERGENCES.md#schema-annotation-values): upstream scans annotation values like
// `@doc("one in Two")` as schema syntax, so ` in `, `[...]`, `:` and `//` inside them produce tokens and
// references. Blank out the value (same length, so offsets are unchanged) before the line is scanned.
private val ANNOTATION_VALUE_REGEX = Regex("""@[_a-zA-Z][_a-zA-Z0-9]*\s*\(\s*"((?:[^"\\]|\\.)*)"""")

private fun maskAnnotationValues(textLine: String): String =
    ANNOTATION_VALUE_REGEX.replace(textLine) { m ->
        val value = m.groups[1]!!.range
        m.value.substring(0, value.first - m.range.first) +
            " ".repeat(value.last - value.first + 1) +
            m.value.substring(value.last + 1 - m.range.first)
    }

private fun parseCedarSchemaCedarDoc(
    schemaDoc: TextDocument,
    visitSchema: ((schemaRange: SchemaRange, schemaText: String) -> Unit)? = null,
): SchemaCacheItem {
    // schema text is not cached, so check for no visitSchema callback
    if (visitSchema == null) {
        val cachedItem = schemaCache[schemaDoc.uri.toString()]
        if (cachedItem != null && cachedItem.version == schemaDoc.version) {
            // console.log("parseCedarDocSchema (cached)");
            return cachedItem
        }
    }

    val definitionRanges = mutableListOf<SchemaRange>()
    val referencedTypes = mutableListOf<ReferencedRange>()
    val actionIds = mutableListOf<ReferencedRange>()
    //const completions: Record<string, SchemaCompletionRecord> = {};

    var symbol = SymbolKind.Class
    val tokensBuilder = SemanticTokensBuilder(semanticTokensLegend)

    var declarations = LinkedHashMap<String, Range?>()
    var namespace = ""
    var collection = "entityTypes"

    fun determineRange(textLine: String, i: Int, match: String, startPos: Int = 0, margin: Int = 0): Range? {
        var range: Range? = null
        val idx = textLine.indexOf(match, startPos)
        if (idx > -1) {
            range = Range(Position(i, idx + margin), Position(i, idx + match.length - margin))
        }
        return range
    }

    fun parseCedarSchemaEntityItem(itemIn: String, line: Int, offsetIn: Int) {
        val offset = offsetIn + indexOfNonSpace(itemIn)
        val item = itemIn.trim()
        val range = Range(line, offset, line, offset + item.length)
        tokensBuilder.push(range, "type", emptyList())
        referencedTypes.add(
            ReferencedRange(
                name = ensureNamespace(item, namespace),
                range = determineReferenceRange(item, line, offset, item.length + 1),
            ),
        )
    }

    fun parseCedarSchemaActionItem(item: String, line: Int, offset: Int) {
        val trimmed = item.trim()
        val quotePosition = item.indexOf('"')
        if (quotePosition > -1) {
            val foundArray = ENTITY_REGEXG.findAll(item).toList()
            foundArray.forEach { found ->
                val type = found.group("type") ?: return@forEach
                val typeRange = makeRange(line, offset + found.index, type.length)
                tokensBuilder.push(typeRange, "type", emptyList())
            }
            actionIds.add(
                ReferencedRange(
                    name = trimmed,
                    range = makeRange(line, offset + quotePosition, trimmed.length - quotePosition),
                ),
            )
        } else {
            val range = makeRange(line, offset, item.length)
            tokensBuilder.push(range, "string", emptyList())
            actionIds.add(
                ReferencedRange(
                    name = "${namespace}Action::\"$trimmed\"",
                    range = range,
                ),
            )
        }
    }

    var declarationStartLine = -1
    for (i in 0 until schemaDoc.lineCount) {
        val textLine = maskAnnotationValues(schemaDoc.lineAt(i).text)
        val commentPos = textLine.indexOf("//")
        var linePreComment = textLine
            .substring(0, if (commentPos > -1) commentPos else textLine.length)
            .trim()
        if (linePreComment.isNotEmpty()) {
            if (linePreComment.startsWith("namespace")) {
                val match = linePreComment.jsMatch(NAMESPACE_DECL_REGEX)
                if (match != null) {
                    namespace = match.groupValues[1] + "::"
                    linePreComment = linePreComment.substring(match.value.length).trim()
                }
            } else if (declarationStartLine == -1 && linePreComment == "}") {
                // assume closing } not inside declaration is end of namespace
                namespace = ""
            }

            // https://docs.cedarpolicy.com/schema/human-readable-schema.html#schema-commonTypes
            if (linePreComment.startsWith("type")) {
                declarationStartLine = i
                symbol = SymbolKind.Struct
                collection = "commonTypes"
                val match = linePreComment.jsMatch(TYPE_DECL_REGEX)
                if (match != null) {
                    val range = determineRange(textLine, i, match.groupValues[1], match.index)
                    if (range != null) {
                        tokensBuilder.push(range, "struct", listOf("declaration"))
                    }
                    declarations[namespace + match.groupValues[1]] = range
                }
            }
            // https://docs.cedarpolicy.com/schema/human-readable-schema.html#schema-entityTypes
            if (linePreComment.startsWith("entity")) {
                declarationStartLine = i
                symbol = SymbolKind.Class
                collection = "entityTypes"
                val match = linePreComment.jsMatch(ENTITY_DECL_REGEX)
                if (match != null) {
                    var startPos = match.index
                    val types = match.groupValues[1].split(',')
                    types.forEach { type ->
                        val range = determineRange(textLine, i, type.trim(), startPos)
                        if (range != null) {
                            tokensBuilder.push(range, "type", listOf("declaration"))
                        }
                        declarations[namespace + type.trim()] = range
                        startPos = startPos + type.length + 1
                    }
                }
            }
            // https://docs.cedarpolicy.com/schema/human-readable-schema.html#schema-actions
            if (linePreComment.startsWith("action")) {
                declarationStartLine = i
                symbol = SymbolKind.Function
                collection = "actions"
                val match = linePreComment.jsMatch(ACTION_DECL_REGEX)
                if (match != null) {
                    // `match.index || ...`: the anchored match index is always 0 (falsy)
                    var startPos = if (match.index != 0) match.index else textLine.indexOf("action") + 6
                    val ids = match.groupValues[1].split(',')
                    ids.forEach { rawId ->
                        var id = rawId.trim()
                        val isQuoted = id.startsWith("\"") && id.endsWith("\"")
                        val range: Range?
                        if (isQuoted) {
                            range = determineRange(textLine, i, id, startPos, 1)
                            id = id.jsSubstring(1, id.length - 1)
                        } else {
                            range = determineRange(textLine, i, id, startPos)
                        }
                        if (range != null) {
                            tokensBuilder.push(range, "string", emptyList())
                            declarations["${namespace}Action::\"$id\""] = range
                        }
                        startPos = startPos + id.length + (if (isQuoted) 3 else 1)
                    }
                }
            }
            val leftBracketIndex = linePreComment.indexOf('[')
            if (leftBracketIndex > -1) {
                val foundArray = BRACKET_LIST_REGEX.findAll(linePreComment).toList()
                val padding = textLine.indexOf('[') - linePreComment.indexOf('[')
                foundArray.forEach { found ->
                    var offset = padding + found.index + 1
                    val items = found.groupValues[1].split(',')
                    if (linePreComment.startsWith("action")) {
                        items.forEach { item ->
                            parseCedarSchemaActionItem(item, i, offset)
                            offset += item.length + 1
                        }
                    } else {
                        items.forEach { item ->
                            parseCedarSchemaEntityItem(item, i, offset)
                            offset += item.length + 1
                        }
                    }
                }
            } else {
                val inIndex = linePreComment.indexOf(" in ")
                if (inIndex > -1) {
                    val match = linePreComment.jsMatch(IN_REGEX)
                    if (match != null) {
                        val matchIndex = textLine.indexOf(match.groupValues[1], textLine.indexOf(" in "))
                        if (linePreComment.startsWith("action")) {
                            parseCedarSchemaActionItem(match.groupValues[1], i, matchIndex)
                        } else {
                            parseCedarSchemaEntityItem(match.groupValues[1], i, matchIndex)
                        }
                    }
                }
            }
            val colonIndex = textLine.indexOf(':')
            if (declarationStartLine != -1 && colonIndex > -1) {
                // inside a declaration
                val match = textLine.jsMatch(ATTRIBUTE_DECL_REGEX)
                val type = match?.group("type")
                if (!type.isNullOrEmpty()) {
                    if (!(type.startsWith("__cedar::") || type in listOf("Long", "String", "Bool", "Set"))) {
                        val idx = textLine.indexOf(type, colonIndex)
                        if (idx > -1) {
                            if (type !in EXTENSIONS) {
                                val range = makeRange(i, idx, type.length)
                                tokensBuilder.push(range, "type", emptyList())

                                referencedTypes.add(
                                    ReferencedRange(
                                        name = ensureNamespace(type, namespace),
                                        range = range,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (
                // end of type, entity, or action declaration
                linePreComment.trim().endsWith(";")
            ) {
                val range = Range(Position(declarationStartLine, 0), Position(i, textLine.length))
                declarations.forEach { (key, value) ->
                    val schemaRange = SchemaRange(
                        collection = collection,
                        etype = key,
                        range = range,
                        etypeRange = value ?: range,
                        symbol = symbol,
                    )
                    definitionRanges.add(schemaRange)
                }

                declarationStartLine = -1
                declarations = LinkedHashMap()
            }
        }
    }

    val cachedItem = SchemaCacheItem(
        version = schemaDoc.version,
        definitionRanges = definitionRanges,
        tokens = tokensBuilder.build(),
        referencedTypes = referencedTypes,
        entityTypes = emptyList(),
        actionIds = actionIds,
        completions = emptyMap(),
        tags = emptyList(),
    )
    schemaCache[schemaDoc.uri.toString()] = cachedItem

    return cachedItem
}

data class TraversePropertyChainResult(val lastType: String, val completion: SchemaCompletionRecord?)

fun traversePropertyChain(
    completions: Map<String, SchemaCompletionRecord>,
    properties: List<String>,
    entityType: String,
): TraversePropertyChainResult {
    var lastType = ""
    var completion: SchemaCompletionRecord? = completions[entityType]
    for (i in 1 until properties.size) {
        val data = completion?.get(properties[i])
        if (data != null) {
            lastType = data.description
            completion = if (data.children != null) {
                // Record attributes
                data.children
            } else {
                // common type or entity type
                completions[data.description]
            }
        } else {
            completion = null
            lastType = ""
        }
    }

    return TraversePropertyChainResult(lastType, completion)
}

val schemaTokensProvider = DocumentSemanticTokensProvider { cedarSchemaDoc ->
    parseCedarSchemaDoc(cedarSchemaDoc).tokens
}
