// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/fileutil.ts.
// handleDidRenameFiles / handleWillDeleteFiles live in ide (IntelliJ VFS listeners) and
// saveTextAndFormat in ide (it drives editors, dialogs and the formatter).

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import java.io.File

private const val CEDAR_SCHEMA_JSON_FILE = "cedarschema.json"
private const val CEDAR_SCHEMA_JSON_EXTENSION = ".cedarschema.json"
private const val CEDAR_SCHEMA_FILE = "cedarschema"
private const val CEDAR_SCHEMA_EXTENSION = ".cedarschema"
const val CEDAR_SCHEMA_GLOB = "{**/cedarschema.json,**/*.cedarschema.json}"

private const val CEDAR_ENTITIES_FILE = "cedarentities.json"
private const val CEDAR_ENTITIES_EXTENSION_JSON = ".cedarentities.json"
const val CEDAR_ENTITIES_GLOB =
    "{**/cedarentities.json,**/*.cedarentities.json,**/avpentities.json,**/*.avpentities.json}"

const val CEDAR_TEMPLATELINKS_GLOB =
    "{**/cedartemplatelinks.json,**/*.cedartemplatelinks.json,**/cedarlinks.json,**/*.cedarlinks.json}"
const val CEDAR_AUTH_GLOB = "{**/cedarauth.json,**/*.cedarauth.json,**/cedarparc.json,**/*.cedarparc.json}"
const val CEDAR_JSON_GLOB = "**/*.cedar.json"

/** The `{**&#47;name,**&#47;*.ext}` globs above, as file-name predicates (the JetBrains analog of a document selector). */
fun matchesGlob(glob: String, fileName: String): Boolean {
    val name = fileName.substringAfterLast('/').substringAfterLast(File.separatorChar)
    return glob.removePrefix("{").removeSuffix("}").split(',').any { pattern ->
        val p = pattern.removePrefix("**/")
        if (p.startsWith("*")) name.endsWith(p.substring(1)) else name == p
    }
}

fun isCedarSchemaJsonFile(fileName: String) = matchesGlob(CEDAR_SCHEMA_GLOB, fileName)
fun isCedarEntitiesFile(fileName: String) = matchesGlob(CEDAR_ENTITIES_GLOB, fileName)
fun isCedarTemplateLinksFile(fileName: String) = matchesGlob(CEDAR_TEMPLATELINKS_GLOB, fileName)
fun isCedarAuthFile(fileName: String) = matchesGlob(CEDAR_AUTH_GLOB, fileName)
fun isCedarJsonFile(fileName: String) = matchesGlob(CEDAR_JSON_GLOB, fileName)

private val sep: String get() = "/"

fun detectSchemaDoc(doc: TextDocument): Boolean {
    val result =
        doc.languageId == "cedarschema" ||
            doc.fileName.endsWith(sep + CEDAR_SCHEMA_JSON_FILE) ||
            doc.fileName.endsWith(CEDAR_SCHEMA_JSON_EXTENSION)

    return result
}

fun detectEntitiesDoc(doc: TextDocument): Boolean {
    val result =
        doc.fileName.endsWith(sep + CEDAR_ENTITIES_FILE) ||
            doc.fileName.endsWith(CEDAR_ENTITIES_EXTENSION_JSON)

    return result
}

private fun findSchemaFilesInFolder(workspace: Workspace, filepath: String): List<String> {
    val schemaFiles = LinkedHashSet<String>()
    val files = workspace.readDirectory(Uri.file(filepath))

    // first find Cedar schema files
    for (file in files) {
        if (
            (file.second == FileType.File && file.first == CEDAR_SCHEMA_FILE) ||
            file.first.endsWith(CEDAR_SCHEMA_EXTENSION)
        ) {
            schemaFiles.add(file.first)
        }
    }

    // then look for Cedar schema JSON files that aren't translated versions
    for (file in files) {
        if (
            (file.second == FileType.File && file.first == CEDAR_SCHEMA_JSON_FILE) ||
            file.first.endsWith(CEDAR_SCHEMA_JSON_EXTENSION)
        ) {
            if (!schemaFiles.contains(file.first.substring(0, file.first.length - 5))) {
                // .json
                schemaFiles.add(file.first)
            }
        }
    }

    return schemaFiles.toList()
}

private fun dirname(path: String) = path.substringBeforeLast('/', "").ifEmpty { "/" }

private fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name

fun getSchemaUri(workspace: Workspace, doc: TextDocument? = null): Uri? {
    var fileUri: Uri? = null
    val schemaFile = workspace.schemaFile
    if (schemaFile.isNotEmpty() && workspace.workspaceFolders.isNotEmpty()) {
        fileUri = Uri.joinPath(workspace.workspaceFolders[0], schemaFile)
    }

    val autodetect = workspace.autodetectSchemaFile
    if (doc != null && autodetect) {
        val cedarDocPath = dirname(doc.uri.fsPath)
        val files = runCatching { findSchemaFilesInFolder(workspace, cedarDocPath) }.getOrElse { emptyList() }
        if (files.size == 1) {
            fileUri = Uri.file(join(cedarDocPath, files[0]))
        } else {
            val folder = workspace.getWorkspaceFolder(doc.uri)
            if (folder != null) {
                val rootFiles = runCatching { findSchemaFilesInFolder(workspace, folder.fsPath) }.getOrElse { emptyList() }
                if (rootFiles.size == 1) {
                    fileUri = Uri.file(join(folder.fsPath, rootFiles[0]))
                }
            }
        }
    }

    return fileUri
}

fun getSchemaTextDocument(workspace: Workspace, doc: TextDocument? = null): TextDocument? {
    var schemaDoc: TextDocument? = null
    val fileUri = getSchemaUri(workspace, doc)
    if (fileUri != null) {
        schemaDoc = try {
            workspace.openTextDocument(fileUri) ?: throw IllegalStateException("not found")
        } catch (_: Exception) {
            workspace.showErrorMessage("Missing cedar.schemaFile: $fileUri")
            null
        }
    }

    return schemaDoc
}

/** `formatJsonText` from saveTextAndFormat: re-indent valid JSON with the user's JSON indent size. */
fun formatJsonText(workspace: Workspace, text: String): String = try {
    jsonStringify(com.google.gson.JsonParser.parseString(text).also { require(!it.isJsonNull || text.trim() == "null") }, workspace.jsonIndentSize)
} catch (_: Exception) {
    text // Return original if not valid JSON
}

/** `JSON.stringify(value, null, indent)` for a parsed JSON tree. */
fun jsonStringify(element: com.google.gson.JsonElement, indent: Int): String {
    val sb = StringBuilder()
    fun pad(level: Int) { repeat(level * indent) { sb.append(' ') } }
    fun write(e: com.google.gson.JsonElement, level: Int) {
        when {
            e.isJsonObject -> {
                val entries = e.asJsonObject.entrySet()
                if (entries.isEmpty()) { sb.append("{}"); return }
                sb.append("{")
                entries.forEachIndexed { i, (k, v) ->
                    if (indent > 0) { sb.append('\n'); pad(level + 1) }
                    sb.append(com.google.gson.JsonPrimitive(k).toString()).append(if (indent > 0) ": " else ":")
                    write(v, level + 1)
                    if (i < entries.size - 1) sb.append(',')
                }
                if (indent > 0) { sb.append('\n'); pad(level) }
                sb.append("}")
            }
            e.isJsonArray -> {
                val items = e.asJsonArray
                if (items.isEmpty) { sb.append("[]"); return }
                sb.append("[")
                items.forEachIndexed { i, v ->
                    if (indent > 0) { sb.append('\n'); pad(level + 1) }
                    write(v, level + 1)
                    if (i < items.size() - 1) sb.append(',')
                }
                if (indent > 0) { sb.append('\n'); pad(level) }
                sb.append("]")
            }
            e.isJsonNull -> sb.append("null")
            e.asJsonPrimitive.isNumber -> sb.append(jsNumber(e.asJsonPrimitive.asString))
            else -> sb.append(e.toString())
        }
    }
    write(element, 0)
    return sb.toString()
}

/** Formats a JSON number literal the way JavaScript's Number -> string conversion would. */
private fun jsNumber(literal: String): String {
    val d = literal.toDoubleOrNull() ?: return literal
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e21) return java.math.BigDecimal(d).toPlainString()
    var s = d.toString().lowercase()
    if (s.contains("e")) {
        val (m, exp) = s.split("e")
        val mant = m.removeSuffix(".0")
        s = mant + "e" + (if (exp.startsWith("-")) exp else "+$exp")
    }
    return s
}
