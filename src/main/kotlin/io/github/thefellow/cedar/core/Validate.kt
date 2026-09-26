// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/validate.ts. Upstream's async functions are synchronous here (callers run them
// off the UI thread); `workspace` replaces vscode.workspace / vscode.window.
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.Diagnostic
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import io.github.thefellow.cedar.wasm.Cedar
import io.github.thefellow.cedar.wasm.ValidatePolicyResult
import io.github.thefellow.cedar.wasm.ValidateSchemaResult

private class ValidationCacheItem(val version: Long, val valid: Boolean)

private class EntityTypesCacheItem(
    val version: Long,
    val principals: List<String>,
    val resources: List<String>,
    val actions: List<String>,
)

class ValidationCache {
    private var docsBySchema: MutableMap<String, MutableSet<String>> = HashMap()
    private var cache: MutableMap<String, ValidationCacheItem> = HashMap()
    private var entityTypes: MutableMap<String, EntityTypesCacheItem> = HashMap()

    /**
     * Called with the uris of documents that depend on a re-validated schema (after their cached results
     * were dropped). Upstream re-opens and re-validates each one asynchronously; by default this does the
     * same (after the schema's own result is stored), and the IDE replaces it to restart highlighting.
     */
    @Volatile
    var revalidate: ((workspace: Workspace, uris: List<Uri>, diagnosticCollection: DiagnosticCollection) -> Unit) =
        { workspace, uris, diagnosticCollection ->
            uris.forEach { uri ->
                workspace.openTextDocument(uri)?.let { validateTextDocument(workspace, it, diagnosticCollection) }
            }
        }

    @Synchronized
    fun check(doc: TextDocument): Boolean? {
        val cachedItem = cache[doc.uri.toString()]
        if (cachedItem != null && cachedItem.version == doc.version) {
            return cachedItem.valid
        }

        return null
    }

    @Synchronized
    fun store(doc: TextDocument, success: Boolean) {
        cache[doc.uri.toString()] = ValidationCacheItem(doc.version, success)
    }

    @Synchronized
    fun storeEntityTypes(
        schemaDoc: TextDocument,
        principals: List<String>,
        resources: List<String>,
        actions: List<String>,
    ) {
        entityTypes[schemaDoc.uri.toString()] = EntityTypesCacheItem(schemaDoc.version, principals, resources, actions)
    }

    @Synchronized
    private fun checkEntityTypes(schemaDoc: TextDocument): EntityTypesCacheItem? {
        val cachedItem = entityTypes[schemaDoc.uri.toString()]
        if (cachedItem != null && cachedItem.version == schemaDoc.version) {
            return cachedItem
        }

        return null
    }

    fun fetchEntityTypes(schemaDoc: TextDocument): EntityTypes {
        val cachedItem = checkEntityTypes(schemaDoc)
        return if (cachedItem != null) {
            EntityTypes(cachedItem.principals, cachedItem.resources, cachedItem.actions)
        } else {
            EntityTypes(emptyList(), emptyList(), emptyList())
        }
    }

    @Synchronized
    fun clear() {
        cache = HashMap()
        docsBySchema = HashMap()
        entityTypes = HashMap()
    }

    @Synchronized
    fun associateSchemaWithDoc(schemaDoc: TextDocument, doc: TextDocument) {
        val s = docsBySchema[schemaDoc.uri.toString()] ?: LinkedHashSet()
        s.add(doc.uri.toString())
        docsBySchema[schemaDoc.uri.toString()] = s
    }

    /** Drops cached results of documents validated with [schemaDoc]; returns their uris to re-validate. */
    @Synchronized
    fun revalidateSchema(schemaDoc: TextDocument): List<Uri> {
        val docs = docsBySchema[schemaDoc.uri.toString()] ?: return emptyList()
        return docs.map { docUri ->
            cache.remove(docUri)
            Uri.parse(docUri)
        }
    }
}

private val HEAD_REGEX = Regex("""(?:(permit|forbid)\s*\((.|\n)*?\))(?<!\s{0,64}(;|when|unless))""")

fun fetchEntityTypes(
    schemaDoc: TextDocument,
    cedarDoc: TextDocument,
    position: Position,
): EntityTypes {
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
                val entityTypes = EntityTypes(principalTypes, resourceTypes, actionIds)
                policy.entityTypes = entityTypes

                return entityTypes
            } else {
                return cached
            }
        }
    }

    return validationCache.fetchEntityTypes(schemaDoc)
}

val validationCache = ValidationCache()

fun clearValidationCache() {
    validationCache.clear()
}

fun narrowEntityTypes(
    schemaDoc: TextDocument,
    scope: String,
    cedarDoc: TextDocument,
    position: Position,
): List<String> {
    val (principals, resources, actions) = fetchEntityTypes(schemaDoc, cedarDoc, position)
    var entities: List<String>
    if (scope == "principal") {
        entities = principals
    } else if (scope == "resource") {
        entities = resources
    } else if (scope in listOf("context", "action")) {
        entities = actions
    } else {
        val pos = scope.lastIndexOf("::")
        entities = listOf(scope.jsSubstring(0, pos))
    }
    return entities
}

fun validateTextDocument(
    workspace: Workspace,
    doc: TextDocument,
    diagnosticCollection: DiagnosticCollection,
) {
    if (doc.uri.scheme != "file") {
        // don't validate vscode-local-history or other non-file URIs
        return
    }
    if (doc.languageId == "cedar") {
        validateCedarDoc(workspace, doc, diagnosticCollection)
    } else if (detectSchemaDoc(doc)) {
        validateSchemaDoc(workspace, doc, diagnosticCollection)
    } else if (detectEntitiesDoc(doc)) {
        validateEntitiesDoc(workspace, doc, diagnosticCollection)
    }
}

fun validateCedarDoc(
    workspace: Workspace,
    cedarDoc: TextDocument,
    diagnosticCollection: DiagnosticCollection,
    userInitiated: Boolean = false,
): Boolean {
    if (!userInitiated) {
        val cachedItem = validationCache.check(cedarDoc)
        if (cachedItem != null) {
            // console.log(`validateCedarDoc (cached) ${cedarDoc.uri.toString()}`);
            return cachedItem
        }
    }

    val diagnostics = mutableListOf<Diagnostic>()
    reportFormatterOff(cedarDoc, diagnostics)
    val syntaxResult = Cedar.validateSyntax(cedarDoc.getText())
    val success = syntaxResult.success
    val syntaxErrors = syntaxResult.errors
    if (syntaxErrors != null) {
        addSyntaxDiagnosticErrors(diagnostics, syntaxErrors, cedarDoc)
    } else {
        val schemaDoc = getSchemaTextDocument(workspace, cedarDoc)
        if (schemaDoc != null) {
            if (validateSchemaDoc(workspace, schemaDoc, diagnosticCollection, userInitiated)) {
                validationCache.associateSchemaWithDoc(schemaDoc, cedarDoc)

                parseCedarPoliciesDoc(cedarDoc) { policyRange, policyText ->
                    val policyResult: ValidatePolicyResult = if (schemaDoc.languageId == "cedarschema") {
                        Cedar.validatePolicySchemaCedar(schemaDoc.getText(), policyText)
                    } else {
                        Cedar.validatePolicySchemaJSON(schemaDoc.getText(), policyText)
                    }
                    policyResult.warnings?.let { warnings ->
                        addPolicyResultMessages(
                            diagnostics,
                            warnings,
                            policyText,
                            policyRange.effectRange,
                            policyRange.range.start.line,
                            true,
                        )
                    }
                    val errors = policyResult.errors
                    if (!policyResult.success && errors != null) {
                        addPolicyResultMessages(
                            diagnostics,
                            errors,
                            policyText,
                            policyRange.effectRange,
                            policyRange.range.start.line,
                            false,
                        )
                    }
                }
            }
        }
    }
    diagnosticCollection.set(cedarDoc.uri, diagnostics)

    validationCache.store(cedarDoc, success)

    return success
}

fun validateSchemaDoc(
    workspace: Workspace,
    schemaDoc: TextDocument,
    diagnosticCollection: DiagnosticCollection,
    userInitiated: Boolean = false,
): Boolean {
    if (!userInitiated) {
        val cachedItem = validationCache.check(schemaDoc)
        if (cachedItem != null) {
            // console.log(`validateSchemaDoc (cached) ${schemaDoc.uri.toString()}`);
            return cachedItem
        }
    }
    // console.log(`validateSchemaDoc ${schemaDoc.uri.toString()}`);

    val schema = schemaDoc.getText()
    val schemaResult: ValidateSchemaResult = if (schemaDoc.languageId == "cedarschema") {
        Cedar.validateSchemaCedar(schema)
    } else {
        Cedar.validateSchemaJSON(schema)
    }
    val success = schemaResult.success
    var revalidateUris: List<Uri> = emptyList()
    val schemaErrors = schemaResult.errors
    if (!schemaResult.success && schemaErrors != null) {
        val schemaDiagnostics = mutableListOf<Diagnostic>()
        val vse = schemaErrors.map { e -> e.copy() }
        addSyntaxDiagnosticErrors(schemaDiagnostics, vse, schemaDoc)
        diagnosticCollection.set(schemaDoc.uri, schemaDiagnostics)
    } else {
        // reset any errors for the schema from a previous validateSchema
        diagnosticCollection.delete(schemaDoc.uri)

        schemaResult.warnings?.let { warnings ->
            val schemaDiagnostics = mutableListOf<Diagnostic>()
            warnings.forEach { w ->
                val range = determineRangeFromOffset(schemaDoc, w.offset, w.length)
                addValidationDiagnosticWarning(schemaDiagnostics, w.message, range)
            }
            diagnosticCollection.set(schemaDoc.uri, schemaDiagnostics)
        }

        // determine applicable principal and resource types
        val principalTypes = determineEntityTypes(schemaDoc, "principal")
        val resourceTypes = determineEntityTypes(schemaDoc, "resource")
        val actionIds = determineEntityTypes(schemaDoc, "action")
        validationCache.storeEntityTypes(
            schemaDoc,
            principalTypes,
            resourceTypes,
            actionIds,
        )

        // revalidate any Cedar files using this schema
        revalidateUris = validationCache.revalidateSchema(schemaDoc)
    }

    validationCache.store(schemaDoc, success)

    // upstream re-validates asynchronously (after the store above); do the same ordering here
    if (revalidateUris.isNotEmpty()) {
        validationCache.revalidate(workspace, revalidateUris, diagnosticCollection)
    }

    return success
}

// TODO: find a real API to call for determineEntityTypes
// parsing errors from intentionally invalid policy is an ugly hack
private val UNEXPECTED_REGEX =
    Regex("""unexpected type: expected Bool but saw (?<suggestion>.+)""")
private val ATTRIBUTE_REGEX =
    Regex("""attribute `__vscode__` in context for (?<suggestion>.+) not found""")

fun determineEntityTypes(
    schemaDoc: TextDocument,
    /** 'principal' | 'resource' | 'action' */
    scope: String,
    head: String? = null,
): List<String> {
    val policyHead = head ?: "permit (principal, action, resource)"
    val types = mutableListOf<String>()
    val expr = if (scope == "action") "context.__vscode__" else scope
    val tmpPolicy = "$policyHead when { $expr };"
    val policyResult: ValidatePolicyResult = if (schemaDoc.languageId == "cedarschema") {
        Cedar.validatePolicySchemaCedar(schemaDoc.getText(), tmpPolicy)
    } else {
        Cedar.validatePolicySchemaJSON(schemaDoc.getText(), tmpPolicy)
    }
    val errors = policyResult.errors
    if (!policyResult.success && errors != null) {
        errors.forEach { e ->
            val found = if (scope == "action") {
                e.message.jsMatch(ATTRIBUTE_REGEX)
            } else {
                e.message.jsMatch(UNEXPECTED_REGEX)
            }

            val suggestion = found?.group("suggestion")
            if (found != null && !suggestion.isNullOrEmpty()) {
                if (!suggestion.startsWith("__cedar::internal::")) {
                    types.add(suggestion)
                }
            }
        }
    }
    // JS Array.prototype.sort(): UTF-16 code unit order
    return types.sortedWith { a, b -> a.compareTo(b) }
}

fun validateEntitiesDoc(
    workspace: Workspace,
    entitiesDoc: TextDocument,
    diagnosticCollection: DiagnosticCollection,
    userInitiated: Boolean = false,
): Boolean {
    if (!userInitiated) {
        val cachedItem = validationCache.check(entitiesDoc)
        if (cachedItem != null) {
            // console.log(`validateEntitiesDoc (cached) ${entitiesDoc.uri.toString()}`);
            return cachedItem
        }
    }
    // console.log(`validateEntitiesDoc ${entitiesDoc.uri.toString()}`);

    var success = false
    val entitiesDiagnostics = mutableListOf<Diagnostic>()

    val entities = entitiesDoc.getText()
    val schemaDoc = getSchemaTextDocument(workspace, entitiesDoc)
    if (schemaDoc != null) {
        if (validateSchemaDoc(workspace, schemaDoc, diagnosticCollection, userInitiated)) {
            validationCache.associateSchemaWithDoc(schemaDoc, entitiesDoc)

            val entitiesResult = if (schemaDoc.languageId == "cedarschema") {
                Cedar.validateEntitiesSchemaCedar(schemaDoc.getText(), entities)
            } else {
                Cedar.validateEntitiesSchemaJSON(schemaDoc.getText(), entities)
            }
            success = entitiesResult.success
            val errors = entitiesResult.errors
            if (!entitiesResult.success && errors != null) {
                addSyntaxDiagnosticErrors(
                    entitiesDiagnostics,
                    errors,
                    entitiesDoc,
                )
            }
        }
    } else {
        if (userInitiated) {
            workspace.showErrorMessage(
                "Cedar schema file not found or configured in settings.json",
            )
        }
    }

    diagnosticCollection.set(entitiesDoc.uri, entitiesDiagnostics)

    validationCache.store(entitiesDoc, success)

    return success
}
