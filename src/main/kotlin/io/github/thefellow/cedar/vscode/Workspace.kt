// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.vscode

enum class FileType { Unknown, File, Directory, SymbolicLink }

/**
 * The `vscode.workspace` / `vscode.window` services upstream reaches for. The IntelliJ implementation is
 * per project (`ide.IdeWorkspace`); tests use a filesystem-backed one.
 */
interface Workspace {
    /** `cedar.schemaFile` setting (relative to the first workspace folder), or empty. */
    val schemaFile: String

    /** `cedar.autodetectSchemaFile` setting. */
    val autodetectSchemaFile: Boolean

    /** Indent size used for JSON (`editor.tabSize` for json). */
    val jsonIndentSize: Int get() = 2

    val workspaceFolders: List<Uri>

    fun getWorkspaceFolder(uri: Uri): Uri? = workspaceFolders.firstOrNull { uri.path == it.path || uri.path.startsWith(it.path.trimEnd('/') + "/") }

    fun readDirectory(uri: Uri): List<Pair<String, FileType>>

    fun stat(uri: Uri): FileType?

    /** Opens (reads) a document; throws or returns null when it doesn't exist. */
    fun openTextDocument(uri: Uri): TextDocument?

    fun showErrorMessage(message: String)
    fun showInformationMessage(message: String)
}
