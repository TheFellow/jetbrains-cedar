// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.validation

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.thefellow.cedar.core.clearValidationCache
import io.github.thefellow.cedar.core.getSchemaTextDocument
import io.github.thefellow.cedar.ide.settings.CedarSettingsListener
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.FileType
import io.github.thefellow.cedar.vscode.Uri

/*
 * Ports of upstream fileutil.ts handleDidRenameFiles / handleWillDeleteFiles over IntelliJ VFS events:
 * keep the diagnostic collection keyed by the files' current locations.
 */

fun handleDidRenameFiles(
    files: List<Triple<Uri, Uri, FileType>>,
    diagnosticCollection: DiagnosticCollection,
) {
    files.forEach { (oldUri, newUri, type) ->
        if (type == FileType.Directory) {
            diagnosticCollection.forEach { uri, _ ->
                if (uri.path.startsWith(oldUri.path)) {
                    val movedUri = Uri.file(uri.path.replaceFirst(oldUri.path, newUri.path))
                    diagnosticCollection.set(movedUri, diagnosticCollection.get(uri))
                    diagnosticCollection.delete(uri)
                }
            }
        } else if (diagnosticCollection.has(oldUri)) {
            diagnosticCollection.set(newUri, diagnosticCollection.get(oldUri))
            diagnosticCollection.delete(oldUri)
        }
    }
}

fun handleWillDeleteFiles(
    files: List<Pair<Uri, FileType>>,
    diagnosticCollection: DiagnosticCollection,
) {
    files.forEach { (file, type) ->
        if (type == FileType.Directory) {
            diagnosticCollection.forEach { uri, _ ->
                if (uri.fsPath.startsWith(file.fsPath)) {
                    diagnosticCollection.delete(uri)
                }
            }
        } else if (diagnosticCollection.has(file)) {
            diagnosticCollection.delete(file)
        }
    }
}

class CedarFileListener(private val project: Project) : BulkFileListener {
    private val collection get() = CedarValidationService.getInstance(project).diagnosticCollection

    private fun type(file: VirtualFile) = if (file.isDirectory) FileType.Directory else FileType.File

    override fun before(events: List<VFileEvent>) {
        val deleted = events.filterIsInstance<VFileDeleteEvent>()
            .filter { it.file.isInLocalFileSystem }
            .map { Uri.file(it.file.path) to type(it.file) }
        if (deleted.isNotEmpty() && !project.isDisposed) handleWillDeleteFiles(deleted, collection)
    }

    override fun after(events: List<VFileEvent>) {
        val renamed = events.mapNotNull { event ->
            when {
                event is VFileMoveEvent && event.file.isInLocalFileSystem ->
                    Triple(Uri.file(event.oldPath), Uri.file(event.newPath), type(event.file))
                event is VFilePropertyChangeEvent && event.isRename && event.file.isInLocalFileSystem ->
                    Triple(Uri.file(event.oldPath), Uri.file(event.newPath), type(event.file))
                else -> null
            }
        }
        if (renamed.isNotEmpty() && !project.isDisposed) handleDidRenameFiles(renamed, collection)
    }
}

/**
 * extension.ts `onDidChangeConfiguration` for `cedar.schemaFile`: `getSchemaTextDocument()` reports a
 * missing configured schema. Additionally re-validates open files, since the schema they use may differ.
 */
class CedarSettingsChangeListener(private val project: Project) : CedarSettingsListener {
    override fun settingsChanged() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val service = CedarValidationService.getInstance(project)
            service.resetMessages()
            // no need to await to invoke vscode.window.showErrorMessage
            getSchemaTextDocument(service.userWorkspace)
            clearValidationCache()
            DaemonCodeAnalyzer.getInstance(project).restart("Cedar settings changed")
        }
    }
}
