// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.adapters

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.thefellow.cedar.ide.settings.CedarSettings
import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import java.io.File

/** Per-project implementation of the shim's `vscode.workspace` / `vscode.window` services. */
@Service(Service.Level.PROJECT)
class IdeWorkspace(private val project: Project) : Workspace {
    private val settings get() = CedarSettings.getInstance(project)

    override val schemaFile: String get() = settings.schemaFile
    override val autodetectSchemaFile: Boolean get() = settings.autodetectSchemaFile

    override val jsonIndentSize: Int
        get() = com.intellij.application.options.CodeStyle.getProjectOrDefaultSettings(project)
            .getIndentOptions(com.intellij.json.JsonFileType.INSTANCE).INDENT_SIZE

    override val workspaceFolders: List<Uri>
        get() = listOfNotNull(project.guessProjectDir()?.let { uriOf(it) })

    override fun getWorkspaceFolder(uri: Uri): Uri? {
        val file = virtualFileOf(uri) ?: return super.getWorkspaceFolder(uri)
        val root = ReadAction.computeBlocking<com.intellij.openapi.vfs.VirtualFile?, RuntimeException> {
            ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        }
        return root?.let { uriOf(it) } ?: super.getWorkspaceFolder(uri)
    }

    override fun readDirectory(uri: Uri): List<Pair<String, FileType>> {
        val dir = virtualFileOf(uri)
        if (dir != null && dir.isDirectory) {
            return dir.children.map { it.name to if (it.isDirectory) FileType.Directory else FileType.File }
        }
        return File(uri.fsPath).listFiles()?.map { it.name to if (it.isDirectory) FileType.Directory else FileType.File }
            ?: throw IllegalArgumentException("not a directory: $uri")
    }

    override fun stat(uri: Uri): FileType? {
        val file = virtualFileOf(uri) ?: LocalFileSystem.getInstance().findFileByPath(uri.path)
        return when {
            file == null -> null
            file.isDirectory -> FileType.Directory
            else -> FileType.File
        }
    }

    override fun openTextDocument(uri: Uri): TextDocument? {
        val file = virtualFileOf(uri) ?: return null
        return IdeTextDocument.of(file)
    }

    override fun showErrorMessage(message: String) = notify(message, NotificationType.ERROR)

    override fun showInformationMessage(message: String) = notify(message, NotificationType.INFORMATION)

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Cedar")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): IdeWorkspace = project.service()
    }
}
