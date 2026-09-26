package io.github.thefellow.cedar.testing

import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import java.io.File

/** A filesystem-backed [Workspace] for IDE-independent tests of ported upstream modules. */
class FsWorkspace(
    root: File? = null,
    override val schemaFile: String = "",
    override val autodetectSchemaFile: Boolean = true,
) : Workspace {
    val errors = mutableListOf<String>()
    val infos = mutableListOf<String>()

    /** Documents opened with in-memory content (like unsaved editors), by path. */
    val overrides = mutableMapOf<String, String>()

    override val workspaceFolders: List<Uri> = listOfNotNull(root?.let { Uri.file(it.absolutePath) })

    override fun readDirectory(uri: Uri): List<Pair<String, FileType>> =
        File(uri.fsPath).listFiles()?.sortedBy { it.name }?.map { it.name to if (it.isDirectory) FileType.Directory else FileType.File }
            ?: throw IllegalArgumentException("not a directory: $uri")

    override fun stat(uri: Uri): FileType? {
        val f = File(uri.fsPath)
        return when {
            !f.exists() -> null
            f.isDirectory -> FileType.Directory
            else -> FileType.File
        }
    }

    override fun openTextDocument(uri: Uri): TextDocument? {
        val text = overrides[uri.fsPath] ?: File(uri.fsPath).takeIf { it.isFile }?.readText() ?: return null
        return document(uri.fsPath, text)
    }

    override fun showErrorMessage(message: String) { errors += message }
    override fun showInformationMessage(message: String) { infos += message }

    companion object {
        val testdata: File get() = File(System.getProperty("cedar.testdata") ?: "testdata")

        fun languageId(path: String): String {
            val name = path.substringAfterLast('/')
            return when {
                name.endsWith(".cedar") -> "cedar"
                name == "cedarschema" || name.endsWith(".cedarschema") -> "cedarschema"
                name.endsWith(".json") -> "json"
                else -> "plaintext"
            }
        }

        fun document(path: String, text: String = File(path).readText(), version: Long = 1): TextDocument =
            StringTextDocument(text, Uri.file(path), languageId(path), version)

        fun testdataDocument(dir: String, name: String): TextDocument =
            document(File(File(testdata, dir), name).absolutePath)
    }
}
