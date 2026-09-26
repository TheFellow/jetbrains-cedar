// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// TEMPORARY — replaced by core/Validate.kt at merge.
// Faithful ports of the parts of upstream src/validate.ts that completion.ts, hover.ts and
// completionjson.ts need (fetchEntityTypes, narrowEntityTypes, determineEntityTypes, and a
// minimal validateSchemaDoc), so the completion port can compile independently.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.wasm.Cedar
import io.github.thefellow.cedar.wasm.ValidatePolicyResult
import java.util.concurrent.ConcurrentHashMap

private class EntityTypesCacheItemStub(val version: Long, val entityTypes: EntityTypes)

private val entityTypesCacheStub = ConcurrentHashMap<String, EntityTypesCacheItemStub>()

private fun fetchEntityTypesFromCache(schemaDoc: TextDocument): EntityTypes {
    val cachedItem = entityTypesCacheStub[schemaDoc.uri.toString()]
    return if (cachedItem != null && cachedItem.version == schemaDoc.version) {
        cachedItem.entityTypes
    } else {
        EntityTypes(principals = emptyList(), resources = emptyList(), actions = emptyList())
    }
}

// the look-behind is bounded for Java (upstream: `(?<!\s*(;|when|unless))`)
private val HEAD_REGEX = Regex("""(?:(permit|forbid)\s*\((.|\n)*?\))(?<!\s{0,64}(;|when|unless))""")

fun fetchEntityTypes(schemaDoc: TextDocument, cedarDoc: TextDocument, position: Position): EntityTypes {
    var head: String? = null
    for (policy in parseCedarPoliciesDoc(cedarDoc).policies) {
        if (policy.range.contains(position)) {
            val cached = policy.entityTypes
            if (cached == null) {
                val match = cedarDoc.getText(policy.range).jsMatch(HEAD_REGEX)
                if (match != null) {
                    head = match.value
                }
                val principalTypes = determineEntityTypes(schemaDoc, "principal", head)
                val resourceTypes = determineEntityTypes(schemaDoc, "resource", head)
                val actionIds = determineEntityTypes(schemaDoc, "action", head)
                val entityTypes = EntityTypes(
                    principals = principalTypes,
                    resources = resourceTypes,
                    actions = actionIds,
                )
                policy.entityTypes = entityTypes

                return entityTypes
            } else {
                return cached
            }
        }
    }

    return fetchEntityTypesFromCache(schemaDoc)
}

fun narrowEntityTypes(schemaDoc: TextDocument, scope: String, cedarDoc: TextDocument, position: Position): List<String> {
    val (principals, resources, actions) = fetchEntityTypes(schemaDoc, cedarDoc, position)
    val entities: List<String>
    if (scope == "principal") {
        entities = principals
    } else if (scope == "resource") {
        entities = resources
    } else if (listOf("context", "action").contains(scope)) {
        entities = actions
    } else {
        val pos = scope.lastIndexOf("::")
        entities = listOf(scope.jsSubstring(0, pos))
    }
    return entities
}

// TODO: find a real API to call for determineEntityTypes
// parsing errors from intentionally invalid policy is an ugly hack
private val UNEXPECTED_REGEX = Regex("""unexpected type: expected Bool but saw (?<suggestion>.+)""")
private val ATTRIBUTE_REGEX = Regex("""attribute `__vscode__` in context for (?<suggestion>.+) not found""")

fun determineEntityTypes(
    schemaDoc: TextDocument,
    scope: String, // 'principal' | 'resource' | 'action'
    head: String? = null,
): List<String> {
    val headOrDefault = head ?: "permit (principal, action, resource)"
    val types = mutableListOf<String>()
    val expr = if (scope == "action") "context.__vscode__" else scope
    val tmpPolicy = "$headOrDefault when { $expr };"
    val policyResult: ValidatePolicyResult = if (schemaDoc.languageId == "cedarschema") {
        Cedar.validatePolicySchemaCedar(schemaDoc.getText(), tmpPolicy)
    } else {
        Cedar.validatePolicySchemaJSON(schemaDoc.getText(), tmpPolicy)
    }
    val errors = policyResult.errors
    if (!policyResult.success && errors != null) {
        errors.forEach { e ->
            val found = if (scope == "action") e.message.jsMatch(ATTRIBUTE_REGEX) else e.message.jsMatch(UNEXPECTED_REGEX)
            val suggestion = found?.group("suggestion")
            if (!suggestion.isNullOrEmpty()) {
                if (!suggestion.startsWith("__cedar::internal::")) {
                    types.add(suggestion)
                }
            }
        }
    }
    return types.sorted()
}

/** Minimal validateSchemaDoc: validates and caches entity types; diagnostics are not reported here. */
@Suppress("UNUSED_PARAMETER")
fun validateSchemaDoc(schemaDoc: TextDocument, diagnosticCollection: DiagnosticCollection, userInitiated: Boolean = false): Boolean {
    val schema = schemaDoc.getText()
    val schemaResult = if (schemaDoc.languageId == "cedarschema") Cedar.validateSchemaCedar(schema) else Cedar.validateSchemaJSON(schema)
    if (schemaResult.success) {
        entityTypesCacheStub[schemaDoc.uri.toString()] = EntityTypesCacheItemStub(
            schemaDoc.version,
            EntityTypes(
                determineEntityTypes(schemaDoc, "principal"),
                determineEntityTypes(schemaDoc, "resource"),
                determineEntityTypes(schemaDoc, "action"),
            ),
        )
    }
    return schemaResult.success
}
