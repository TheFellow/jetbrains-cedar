package io.github.thefellow.cedar.testing

import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import java.io.File

/** A java.io-backed [Workspace] for navigation core tests. */
class NavFsWorkspace(
    root: File,
    override val schemaFile: String = "",
    override val autodetectSchemaFile: Boolean = true,
) : Workspace {
    override val workspaceFolders = listOf(Uri.file(root.absolutePath))
    val errors = mutableListOf<String>()

    override fun readDirectory(uri: Uri): List<Pair<String, FileType>> =
        File(uri.fsPath).listFiles()?.map { it.name to if (it.isDirectory) FileType.Directory else FileType.File }
            ?: throw IllegalArgumentException("not a directory: $uri")

    override fun stat(uri: Uri): FileType? = File(uri.fsPath).let {
        when {
            !it.exists() -> null
            it.isDirectory -> FileType.Directory
            else -> FileType.File
        }
    }

    override fun openTextDocument(uri: Uri): TextDocument? = open(File(uri.fsPath))

    override fun showErrorMessage(message: String) { errors += message }
    override fun showInformationMessage(message: String) {}

    companion object {
        fun languageId(f: File) = when {
            f.name.endsWith(".cedar") -> "cedar"
            f.name == "cedarschema" || f.name.endsWith(".cedarschema") -> "cedarschema"
            else -> "json"
        }

        fun open(f: File): TextDocument? =
            if (f.isFile) StringTextDocument(f.readText(), Uri.file(f.absolutePath), languageId(f)) else null
    }
}
