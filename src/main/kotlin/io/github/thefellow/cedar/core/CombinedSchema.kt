// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// DIVERGENCE (semport/DIVERGENCES.md#multiple-schema-files): upstream supports one `cedar.schemaFile`.
// The port also accepts a list of schema files and folders; when that resolves to more than one schema, every
// policy and entities file is validated against all of them, combined into one in-memory JSON schema.

package io.github.thefellow.cedar.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import io.github.thefellow.cedar.wasm.Cedar
import io.github.thefellow.cedar.wasm.ValidateMessage
import io.github.thefellow.cedar.wasm.ValidateSchemaResult

/**
 * Several schema files presented as one JSON schema document (`languageId` `json`). Each part is a fragment
 * (it may use types declared in another part), translated to JSON without resolving types. Like namespaces within
 * one Cedar schema, a namespace may only be declared once: a part declaring a namespace (or an unqualified
 * declaration) that an earlier part already declares gets an "already declared" error and adds nothing. Its
 * version changes whenever a part's does, so the uri + version caches stay correct. Diagnostics and go to
 * definition use [parts], the real files.
 */
class CombinedSchemaDocument internal constructor(
    val parts: List<TextDocument>,
    text: String,
    uri: Uri,
    version: Long,
    /** Namespaces and declarations a part repeats from an earlier part, by part uri. */
    internal val conflicts: Map<String, List<ValidateMessage>>,
) : StringTextDocument(text, uri, "json", version) {
    /** The combination validated as a whole (e.g. a type no part declares). */
    val combinedResult: ValidateSchemaResult by lazy { Cedar.validateSchemaJSON(getText()) }
}

/** The real schema documents behind [schemaDoc]: its parts when combined, else itself. */
fun schemaParts(schemaDoc: TextDocument): List<TextDocument> =
    (schemaDoc as? CombinedSchemaDocument)?.parts ?: listOf(schemaDoc)

/**
 * True when the configured schema setting goes beyond upstream's single `cedar.schemaFile`: more than one
 * entry, a folder, or an absolute path. Then the configured schemas are used for every document and
 * auto-detect is skipped.
 */
internal fun usesSchemaList(workspace: Workspace): Boolean {
    val entries = workspace.schemaFiles
    if (entries.size > 1) return true
    val entry = entries.singleOrNull() ?: return false
    if (isAbsolute(entry)) return true
    val uri = entryUri(workspace, entry) ?: return false
    return workspace.stat(uri) == FileType.Directory
}

private fun isAbsolute(entry: String) = entry.startsWith("/") || java.io.File(entry).isAbsolute

/** An entry: absolute, or relative to the first workspace folder (like upstream's `cedar.schemaFile`). */
private fun entryUri(workspace: Workspace, entry: String): Uri? =
    if (isAbsolute(entry)) Uri.file(entry) else workspace.workspaceFolders.firstOrNull()?.let { Uri.joinPath(it, entry) }

/** Each configured entry: a schema file, or a folder's schema files. */
internal fun configuredSchemaUris(workspace: Workspace): List<Uri> {
    val uris = LinkedHashSet<Uri>()
    for (entry in workspace.schemaFiles) {
        val uri = entryUri(workspace, entry) ?: continue
        if (workspace.stat(uri) == FileType.Directory) {
            val files = runCatching { findSchemaFilesInFolder(workspace, uri.fsPath) }.getOrElse { emptyList() }
            files.sorted().forEach { uris.add(Uri.joinPath(uri, it)) }
        } else {
            // a missing file is reported when it's opened, like upstream's cedar.schemaFile
            uris.add(uri)
        }
    }
    return uris.toList()
}

private val combinedLock = Any()
private val combinedCache = object : LinkedHashMap<String, CombinedSchemaDocument>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CombinedSchemaDocument>) = size > 8
}

internal fun clearCombinedSchemaCache() = synchronized(combinedLock) { combinedCache.clear() }

fun combineSchemaDocuments(parts: List<TextDocument>): CombinedSchemaDocument {
    val key = parts.joinToString("\n") { "${it.uri}@${it.version}" }
    synchronized(combinedLock) { combinedCache[key]?.let { return it } }

    val merged = JsonObject()
    val namespaceOwners = HashMap<String, TextDocument>()
    val declarationOwners = HashMap<String, TextDocument>()
    val conflicts = LinkedHashMap<String, MutableList<ValidateMessage>>()
    fun conflict(part: TextDocument, what: String, name: String, owner: TextDocument, offset: Int) {
        val message = "$what `$name` is already declared in ${owner.uri.fsPath.substringAfterLast('/')}"
        conflicts.getOrPut(part.uri.toString()) { mutableListOf() } += ValidateMessage(message, maxOf(offset, 0), if (offset < 0) 0 else name.length)
    }
    for (part in parts) {
        val json = if (part.languageId == "cedarschema") {
            Cedar.translateSchemaFragmentToJSON(part.getText()).takeIf { it.success }?.schema
        } else {
            part.getText()
        }
        // an invalid part contributes nothing here; its own validation reports the errors
        val obj = json?.let { runCatching { JsonParser.parseString(it) }.getOrNull() } as? JsonObject ?: continue
        for ((namespace, nsValue) in obj.entrySet()) {
            val source = nsValue as? JsonObject ?: continue
            if (namespace.isNotEmpty()) {
                val owner = namespaceOwners.putIfAbsent(namespace, part)
                if (owner != null) {
                    conflict(part, "namespace", namespace, owner, findNamespace(part, namespace))
                } else {
                    merged.add(namespace, source.deepCopy())
                }
                continue
            }
            // unqualified declarations (no namespace) may come from several parts, like in one Cedar schema
            val target = merged.getAsJsonObject("") ?: JsonObject().also { merged.add("", it) }
            for ((member, value) in source.entrySet()) {
                val what = DECLARATION_MAPS[member]
                if (what == null || value !is JsonObject) {
                    if (!target.has(member)) target.add(member, value.deepCopy())
                    continue
                }
                val declarations = target.getAsJsonObject(member) ?: JsonObject().also { target.add(member, it) }
                for ((name, declaration) in value.entrySet()) {
                    val owner = declarationOwners.putIfAbsent("$member $name", part)
                    if (owner != null) {
                        conflict(part, what, name, owner, findDeclaration(part, name))
                    } else {
                        declarations.add(name, declaration.deepCopy())
                    }
                }
            }
        }
    }

    val pathsKey = parts.joinToString("\n") { it.uri.toString() }
    val uri = Uri("cedar-combined", "/${Integer.toHexString(pathsKey.hashCode())}/combined.cedarschema.json")
    val version = parts.fold(17L) { acc, p -> acc * 31 + p.uri.toString().hashCode() * 1_000_003L + p.version }
    val doc = CombinedSchemaDocument(parts, merged.toString(), uri, version, conflicts)
    synchronized(combinedLock) { combinedCache[key] = doc }
    return doc
}

/** The declaration maps of a JSON schema namespace, and what Cedar calls their members. */
private val DECLARATION_MAPS = mapOf("entityTypes" to "entity type", "actions" to "action", "commonTypes" to "common type")

/** Offset of [namespace]'s name where [part] declares it, or -1. */
private fun findNamespace(part: TextDocument, namespace: String): Int {
    val pattern = if (part.languageId == "cedarschema") {
        Regex("""\bnamespace\s+(${Regex.escape(namespace)})\s*\{""")
    } else {
        Regex(""""(${Regex.escape(namespace)})"\s*:""")
    }
    return pattern.find(part.getText())?.groups?.get(1)?.range?.first ?: -1
}

/** Offset of an unqualified declaration's [name] in [part], or -1 (best effort: the first declaration naming it). */
private fun findDeclaration(part: TextDocument, name: String): Int {
    val pattern = if (part.languageId == "cedarschema") {
        Regex("""\b(?:entity|type|action)\s[^;{=]*?(?<![\w"])"?(${Regex.escape(name)})"?(?![\w"])""")
    } else {
        Regex(""""(${Regex.escape(name)})"\s*:""")
    }
    return pattern.find(part.getText())?.groups?.get(1)?.range?.first ?: -1
}

/**
 * validateSchemaDoc's schema result for one configured part of a multi-file schema, or null for any other
 * schema (validated as a whole, like upstream). The part is checked as a fragment (syntax, offsets in the part);
 * when that passes, a namespace or declaration it repeats from an earlier part is reported where it declares it;
 * otherwise errors from the combination as a whole (e.g. a type no part declares) are reported on every part, at
 * the start of the file since they have no position in it.
 */
internal fun schemaPartResult(workspace: Workspace, schemaDoc: TextDocument): ValidateSchemaResult? {
    if (schemaDoc is CombinedSchemaDocument || !usesSchemaList(workspace)) return null
    val configured = configuredSchemaUris(workspace)
    // a list that resolves to one schema is that whole schema: validate it like upstream
    if (configured.size < 2 || configured.none { it.toString() == schemaDoc.uri.toString() }) return null

    val fragmentResult = if (schemaDoc.languageId == "cedarschema") {
        Cedar.validateSchemaFragmentCedar(schemaDoc.getText())
    } else {
        Cedar.validateSchemaFragmentJSON(schemaDoc.getText())
    }
    if (!fragmentResult.success) return fragmentResult

    val combined = getSchemaTextDocument(workspace) as? CombinedSchemaDocument ?: return fragmentResult
    val conflicts = combined.conflicts[schemaDoc.uri.toString()].orEmpty()
    val errors = if (conflicts.isNotEmpty()) {
        conflicts
    } else if (combined.conflicts.isEmpty() && !combined.combinedResult.success) {
        combined.combinedResult.errors.orEmpty().map { e ->
            ValidateMessage("Combined with the other configured schemas: ${e.message}", 0, 0)
        }
    } else {
        emptyList()
    }
    return if (errors.isEmpty()) fragmentResult else ValidateSchemaResult(false, null, errors)
}

/** validateSchemaDoc for a combined schema: each part (diagnostics on the real files), then the combination. */
internal fun validateCombinedSchemaDoc(
    workspace: Workspace,
    schemaDoc: CombinedSchemaDocument,
    diagnosticCollection: io.github.thefellow.cedar.vscode.DiagnosticCollection,
    userInitiated: Boolean,
): Boolean {
    if (!userInitiated) {
        validationCache.check(schemaDoc)?.let { return it }
    }

    var success = true
    for (part in schemaDoc.parts) {
        success = validateSchemaDoc(workspace, part, diagnosticCollection, userInitiated) && success
    }
    success = success && schemaDoc.conflicts.isEmpty() && schemaDoc.combinedResult.success
    if (success) {
        validationCache.storeEntityTypes(
            schemaDoc,
            determineEntityTypes(schemaDoc, "principal"),
            determineEntityTypes(schemaDoc, "resource"),
            determineEntityTypes(schemaDoc, "action"),
        )
    }
    validationCache.store(schemaDoc, success)
    return success
}
