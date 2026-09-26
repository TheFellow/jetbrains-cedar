package io.github.thefellow.cedar.testing

import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import java.io.File

/** A java.io-backed [Workspace] for completion/hover tests. */
class CompletionFsWorkspace(
    private val root: File,
    override val schemaFile: String = "",
    override val autodetectSchemaFile: Boolean = true,
) : Workspace {
    val errors = mutableListOf<String>()

    override val workspaceFolders: List<Uri> get() = listOf(Uri.file(root.absolutePath))

    override fun readDirectory(uri: Uri): List<Pair<String, FileType>> =
        File(uri.fsPath).listFiles()!!.map { it.name to if (it.isDirectory) FileType.Directory else FileType.File }

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
        val testdata = File(System.getProperty("cedar.testdata"))

        fun languageId(f: File) = when {
            f.name.endsWith(".cedar") -> "cedar"
            f.name == "cedarschema" || f.name.endsWith(".cedarschema") -> "cedarschema"
            else -> "json"
        }

        fun open(f: File): TextDocument? =
            if (!f.isFile) null else StringTextDocument(f.readText(), Uri.file(f.absolutePath), languageId(f), f.lastModified())

        /** A document with explicit text located at [path] (so schema auto-detection finds siblings). */
        fun doc(path: File, text: String) = StringTextDocument(text, Uri.file(path.absolutePath), languageId(path), System.nanoTime())
    }
}
