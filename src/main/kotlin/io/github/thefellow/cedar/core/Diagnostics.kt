// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/diagnostics.ts.
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.jsonc.JSONPath
import io.github.thefellow.cedar.jsonc.JsonVisitor
import io.github.thefellow.cedar.jsonc.visit
import io.github.thefellow.cedar.vscode.Diagnostic
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.DiagnosticSeverity
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.wasm.ValidateMessage

const val SOURCE_CEDAR = "Cedar"

val DEFAULT_RANGE = Range(Position(0, 0), Position(0, 0))

fun createDiagnosticCollection() = DiagnosticCollection(SOURCE_CEDAR)

private fun addDiagnosticsError(
    diagnostics: MutableList<Diagnostic>,
    range: Range,
    message: String,
    code: String? = null,
) {
    val diagnostic = Diagnostic(range, message, DiagnosticSeverity.Error)
    if (!code.isNullOrEmpty()) {
        diagnostic.code = code
    }
    diagnostic.source = SOURCE_CEDAR
    diagnostics.add(diagnostic)
}

/** JS `parseInt` of an optional regex group: null stands in for NaN. */
private fun parseIntOrNull(value: String?): Int? = value?.let { Regex("""^\s*[-+]?\d+""").find(it)?.value?.trim()?.toIntOrNull() }

private fun determineRangeFromPolicyMessage(
    vpm: ValidateMessage,
    policy: String,
    defaultErrorRange: Range,
    startLine: Int,
): Range {
    var range = defaultErrorRange
    var startCharacter = vpm.offset
    var endCharacter = vpm.offset + vpm.length

    // TODO: investigate if this still is a valid path
    val found = vpm.message.jsMatch(OFFSET_POLICY_REGEX)
    if (found != null) {
        // parseInt(...) yields NaN for a missing group; NaN comparisons below are false
        val start = parseIntOrNull(found.group("start"))
        startCharacter = start ?: Int.MIN_VALUE
        endCharacter = parseIntOrNull(found.group("end"))?.takeIf { it != 0 } ?: startCharacter
    }

    if (startCharacter > 0 && endCharacter > 0) {
        val lines = policy.split("\n")
        var lineStart = 0
        // not efficient, but Cedar policies are small
        for (i in lines.indices) {
            val lineEnd = lines[i].length
            if (
                lineStart + 1 <= startCharacter &&
                lineStart + 1 + lineEnd >= endCharacter
            ) {
                range = Range(
                    Position(startLine + i, startCharacter - lineStart),
                    Position(startLine + i, endCharacter - lineStart),
                )
                break
            }
            lineStart += lineEnd + 1
        }
    }
    return range
}

fun determineRangeFromOffset(
    document: TextDocument,
    offset: Int,
    length: Int,
): Range {
    var range = DEFAULT_RANGE
    val startCharacter = offset
    // "invalid token" is 0 length, make range at least 1 character
    val endCharacter = offset + maxOf(length, 1)
    var lineStart = 0
    // not efficient, but Cedar documents are small
    for (i in 0 until document.lineCount) {
        val lineEnd = document.lineAt(i).text.length
        if (
            lineStart + 1 <= startCharacter &&
            lineStart + 1 + lineEnd >= endCharacter
        ) {
            range = Range(
                Position(i, startCharacter - lineStart),
                Position(i, endCharacter - lineStart),
            )
            break
        }
        lineStart += lineEnd + 1
    }
    return range
}

private data class ErrorRange(val error: String, val range: Range)

private fun determineRangeFromError(
    vse: ValidateMessage,
    document: TextDocument,
): ErrorRange {
    var error = vse.message
    var range = DEFAULT_RANGE
    if (vse.offset > 0) {
        range = determineRangeFromOffset(document, vse.offset, vse.length)
    } else {
        val found = error.jsMatch(AT_LINE_SCHEMA_REGEX)
        if (found != null) {
            if (found.index != 0) {
                error = error.substring(0, found.index)
            }
            val line = parseIntOrNull(found.group("line"))
            val column = parseIntOrNull(found.group("column"))
            if (line != null && line != 0 && column != null && column != 0) {
                range = Range(
                    Position(line - 1, column - 1),
                    Position(line - 1, column - 1),
                )
            }
        } else if (
            error == "Entity type `Action` declared in `entityTypes` list."
        ) {
            val definitionRanges = parseCedarSchemaDoc(document).definitionRanges
            for (definitionRange in definitionRanges) {
                if (
                    definitionRange.etype == "Action" ||
                    definitionRange.etype.endsWith("::Action")
                ) {
                    range = definitionRange.etypeRange
                    break
                }
            }
        }
    }

    return ErrorRange(error, range)
}

private fun addUndeclaredDiagnosticErrors(
    diagnostics: MutableList<Diagnostic>,
    document: TextDocument,
    undeclaredName: String,
    undeclaredType: String?,
): Range {
    var parentRange = DEFAULT_RANGE
    val undeclaredNames = listOf(undeclaredName)

    var namespace = ""

    if (document.languageId == "cedarschema") {
        if (undeclaredType in listOf("entityTypes", "commonTypes")) {
            val referencedTypes = parseCedarSchemaDoc(document).referencedTypes
            undeclaredNames.forEach { t ->
                for (referencedType in referencedTypes) {
                    if (referencedType.name == t) {
                        addDiagnosticsError(
                            diagnostics,
                            referencedType.range,
                            "undeclared ${undeclaredType!!.replace("Types", " type")}: $t",
                            "undeclared",
                        )
                    }
                }
            }
        }

        val lastLine = document.lineAt(document.lineCount - 1)
        return Range(lastLine.range.end, lastLine.range.end)
    }

    visit(
        document.getText(),
        object : JsonVisitor {
            override fun onObjectProperty(
                property: String,
                offset: Int,
                length: Int,
                startLine: Int,
                startCharacter: Int,
                pathSupplier: () -> JSONPath,
            ) {
                val len = pathSupplier().size
                if (len == 0 && property.isNotEmpty()) {
                    namespace = "$property::"
                } else if (len == 1 && property == undeclaredType) {
                    parentRange = Range(
                        Position(startLine, startCharacter + 1),
                        Position(startLine, startCharacter + length - 1),
                    )
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
                val range = Range(
                    Position(startLine, startCharacter + 1),
                    Position(startLine, startCharacter + length - 1),
                )
                if (
                    (jsonPathLen == 5 &&
                        path.at(1) == "entityTypes" &&
                        path.at(3) == "memberOfTypes") ||
                    (jsonPathLen == 6 &&
                        path.at(1) == "actions" &&
                        (path.at(4) == "principalTypes" ||
                            path.at(4) == "resourceTypes")) ||
                    path.at(jsonPathLen - 1) == "name"
                ) {
                    if (undeclaredType == "entityTypes") {
                        undeclaredNames.forEach { t ->
                            if (t == value) {
                                addDiagnosticsError(
                                    diagnostics,
                                    range,
                                    "undeclared entity type: ${jsString(value)}",
                                    "undeclared",
                                )
                            }
                        }
                    }
                } else if (
                    undeclaredType == "actions" &&
                    jsonPathLen == 6 &&
                    path.at(1) == "actions" &&
                    path.at(3) == "memberOf" &&
                    path.at(5) == "id"
                ) {
                    undeclaredNames.forEach { t ->
                        val currentType = "${namespace}Action::\"${jsString(value)}\""
                        if (t == currentType) {
                            addDiagnosticsError(
                                diagnostics,
                                range,
                                "undeclared action: ${jsString(value)}",
                                "undeclared",
                            )
                        }
                    }
                } else if (
                    undeclaredType == "commonTypes" &&
                    path.at(jsonPathLen - 1) == "type"
                ) {
                    undeclaredNames.forEach { t ->
                        if (t == value) {
                            addDiagnosticsError(
                                diagnostics,
                                range,
                                "undeclared common type: ${jsString(value)}",
                                "undeclared",
                            )
                        }
                    }
                }
            }
        },
    )

    return parentRange
}

private fun rangeFromParseError(
    document: TextDocument,
    undeclaredType: String,
): Range {
    var parentRange = DEFAULT_RANGE

    visit(
        document.getText(),
        object : JsonVisitor {
            override fun onObjectProperty(
                property: String,
                offset: Int,
                length: Int,
                startLine: Int,
                startCharacter: Int,
                pathSupplier: () -> JSONPath,
            ) {
                val len = pathSupplier().size
                if (
                    (len == 0 && undeclaredType == "namespace") ||
                    (len == 1 &&
                        property == "entityTypes" &&
                        undeclaredType == "entity type") ||
                    (len == 1 &&
                        property == "commonTypes" &&
                        undeclaredType == "common type")
                ) {
                    parentRange = Range(
                        Position(startLine, startCharacter + 1),
                        Position(startLine, startCharacter + length - 1),
                    )
                }
            }
        },
    )

    return parentRange
}

private fun handleEntitiesDiagnosticError(
    diagnostics: MutableList<Diagnostic>,
    document: TextDocument,
    error: String,
): Boolean {
    var uid = ""
    var attribute = ""
    var uidTypeError = false
    var parentsError = false
    var found = error.jsMatch(MISMATCH_ATTR_REGEX)
    if (found != null) {
        uid = "${found.group("type")}::\"${found.group("id")}\""
        attribute = found.group("attribute") ?: "undefined"
    } else {
        found = error.jsMatch(EXIST_ATTR_REGEX)
        if (found != null) {
            uid = "${found.group("type")}::\"${found.group("id")}\""
            attribute = found.group("attribute") ?: "undefined"
        } else {
            found = error.jsMatch(EXPECTED_ATTR_REGEX)
            if (found != null) {
                uid = "${found.group("type")}::\"${found.group("id")}\""
            } else {
                found = error.jsMatch(EXPECTED_ATTR2_REGEX)
                if (found != null) {
                    uid = "${found.group("type")}::\"${found.group("id")}\""
                    attribute = found.group("attribute") ?: "undefined"
                } else {
                    found = error.jsMatch(NOTDECLARED_TYPE_REGEX)
                    if (found != null) {
                        uid = "${found.group("type")}::\"${found.group("id")}\""
                        uidTypeError = true
                    } else {
                        found = error.jsMatch(UNKNOWN_ENTITY_REGEX)
                        if (found != null) {
                            uid = found.group("unknown")!!.replace("\\\"", "\"")
                            uidTypeError = true
                        } else {
                            found = error.jsMatch(NOTALLOWED_PARENT_REGEX)
                            if (found != null) {
                                uid = "${found.group("type")}::\"${found.group("id")}\""
                                parentsError = true
                            }
                        }
                    }
                }
            }
        }
    }

    if (uid.isNotEmpty()) {
        val entityRanges = parseCedarEntitiesDoc(document).entities
        entityRanges.forEach { entityRange ->
            if (entityRange.uid == uid) {
                val attributeRange = if (entityRange.attrsNameRanges.containsKey(attribute)) {
                    entityRange.attrsNameRanges[attribute]
                } else {
                    null
                }
                var diagnosticRange =
                    attributeRange ?: entityRange.attrsKeyRange ?: entityRange.uidKeyRange
                if (uidTypeError) {
                    diagnosticRange = entityRange.uidTypeRange ?: entityRange.uidKeyRange
                } else if (parentsError) {
                    diagnosticRange = entityRange.parentsRange ?: entityRange.uidKeyRange
                }
                addDiagnosticsError(diagnostics, diagnosticRange, error)
            }
        }
        return true
    }

    return false
}

fun addSyntaxDiagnosticErrors(
    diagnostics: MutableList<Diagnostic>,
    errors: List<ValidateMessage>,
    document: TextDocument,
) {
    // create an error for each of the syntax validator errors
    errors.forEach { vse ->
        var e = vse.message
        if (
            e.startsWith("entity does not conform to the schema: ") ||
            e.startsWith("error during entity deserialization: ")
        ) {
            e = e.jsSubstring(e.indexOf(": ") + 2)

            if (handleEntitiesDiagnosticError(diagnostics, document, e)) {
                return@forEach
            }
        } else if (e.startsWith("JSON Schema file could not be parsed: ")) {
            e = e.jsSubstring(e.indexOf(": ") + 2)
        }

        var found = e.jsMatch(UNDECLARED_REGEX)
        if (found == null) {
            found = e.jsMatch(UNDECLAREDS_REGEX)
        }
        if (found == null) {
            found = e.jsMatch(UNDECLARED_ACTION_REGEX)
        }
        val undeclared = found?.group("undeclared")
        if (found != null && !undeclared.isNullOrEmpty()) {
            val undeclaredName = undeclared
            val mappings = mapOf(
                "action" to "actions",
                "an action" to "actions",
                "an entity type" to "entityTypes",
                "a common type" to "commonTypes",
            )
            val undeclaredType = mappings[found.group("type")]
            // let range = determineRangeFromOffset(document, vse.offset, vse.length);
            val endOfDocRange = addUndeclaredDiagnosticErrors(
                diagnostics,
                document,
                undeclaredName,
                undeclaredType,
            )
            addDiagnosticsError(diagnostics, endOfDocRange, e)
            return@forEach
        }

        // defend against future parse errors including the range
        if (vse.offset == 0 && vse.length == 0) {
            found = e.jsMatch(PARSE_ERROR_SCHEMA_REGEX)
            val type = found?.group("type")
            if (found != null && !type.isNullOrEmpty()) {
                val range = rangeFromParseError(document, type)
                addDiagnosticsError(diagnostics, range, e)
                return@forEach
            }
        }

        val (error, range) = determineRangeFromError(
            vse.copy(message = e),
            document,
        )
        if (
            error == "EOF while parsing a value" &&
            document.getText().trim() == ""
        ) {
            addDiagnosticsError(diagnostics, range, error, "empty")
        } else {
            addDiagnosticsError(diagnostics, range, error)
        }
    }
}

fun addValidationDiagnosticInfo(
    diagnostics: MutableList<Diagnostic>,
    info: String,
) {
    val diagnostic = Diagnostic(DEFAULT_RANGE, info, DiagnosticSeverity.Information)
    diagnostic.source = SOURCE_CEDAR
    diagnostics.add(diagnostic)
}

fun addValidationDiagnosticWarning(
    diagnostics: MutableList<Diagnostic>,
    message: String,
    range: Range = DEFAULT_RANGE,
) {
    val diagnostic = Diagnostic(range, message, DiagnosticSeverity.Warning)
    diagnostic.source = SOURCE_CEDAR
    diagnostics.add(diagnostic)
}

fun addPolicyResultMessages(
    diagnostics: MutableList<Diagnostic>,
    messages: List<ValidateMessage>,
    policy: String,
    effectRange: Range,
    startLine: Int,
    areWarnings: Boolean,
) {
    // create an error for each of the errors
    messages.forEach { vpm ->
        var e = vpm.message
        var diagnosticCode: String? = null
        var range = determineRangeFromPolicyMessage(
            vpm,
            policy,
            effectRange,
            startLine,
        )
        if (
            e.startsWith("validation error on policy `policy0`") ||
            e.startsWith("validation error on `policy `policy0`")
        ) {
            // validation error on `policy `policy0``: unable to find an applicable action given the policy head constraints
            // validation error on `policy `policy0` at offset 267-285`: attribute `a` for entity type NS::e not found, did you mean `b`?
            e = e.jsSubstring(e.indexOf(": ") + 2)
        } else if (e.startsWith("for policy `policy0`, ")) {
            e = e.jsSubstring(e.indexOf(", ") + 2)
        }

        val found = e.jsMatch(UNRECOGNIZED_REGEX)
        val unrecognized = found?.group("unrecognized")
        if (found != null && !unrecognized.isNullOrEmpty()) {
            val lines = policy.split("\n")
            // not efficient, but Cedar policies are small
            for (i in lines.indices) {
                // unrecognized Actions end in "
                val suffix = if (unrecognized.endsWith("\"")) "" else "::"
                val startCharacter = lines[i].indexOf(unrecognized + suffix)
                if (startCharacter > -1) {
                    val endCharacter = startCharacter + unrecognized.length
                    range = Range(
                        Position(startLine + i, startCharacter),
                        Position(startLine + i, endCharacter),
                    )

                    diagnosticCode = "unrecognized"
                    break
                }
            }
        }

        val diagnostic = Diagnostic(
            range,
            e,
            if (areWarnings) DiagnosticSeverity.Warning else DiagnosticSeverity.Error,
        )
        diagnostic.source = SOURCE_CEDAR
        if (diagnosticCode != null) {
            diagnostic.code = diagnosticCode
        }
        diagnostics.add(diagnostic)
    }
}

fun reportFormatterOff(
    document: TextDocument,
    diagnostics: MutableList<Diagnostic>,
): Boolean {
    val (_, skipFormatting, formatterDirectiveRange) = scanLeadingComments(document)

    if (skipFormatting && formatterDirectiveRange != null) {
        val diagnostic = Diagnostic(
            formatterDirectiveRange,
            "Cedar formatting disabled by @formatter:off",
            DiagnosticSeverity.Information,
        )
        diagnostic.source = SOURCE_CEDAR
        diagnostics.add(diagnostic)
    }

    return skipFormatting
}

data class LeadingComments(
    val firstNonCommentLine: Int,
    val skipFormatting: Boolean,
    val formatterDirectiveRange: Range?,
)

fun scanLeadingComments(document: TextDocument): LeadingComments {
    var skipFormatting = false
    var firstNonCommentLine = 0
    var formatterDirectiveRange: Range? = null
    for (i in 0 until document.lineCount) {
        val textLine = document.lineAt(i).text
        if (!textLine.startsWith("//")) {
            firstNonCommentLine = i
            break
        } else {
            // look for JetBrains style directive to disable formatting
            val off = "@formatter:off"
            val idx = textLine.indexOf(off)
            if (idx > 0) {
                formatterDirectiveRange = Range(
                    Position(i, idx),
                    Position(i, idx + off.length),
                )
                skipFormatting = true
            }
        }
    }

    return LeadingComments(firstNonCommentLine, skipFormatting, formatterDirectiveRange)
}
