// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// IDE side of upstream src/provider.ts (CedarTextDocumentContentProvider, `cedar:` scheme) and the
// cedar.jsonpreview command: a read-only JSON view of a Cedar policy or schema file, refreshed when the
// source is saved, with an "Open <file>" link (upstream's CedarJSONDocumentCodeLensProvider).

package io.github.thefellow.cedar.ide.actions

import com.intellij.json.JsonFileType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import io.github.thefellow.cedar.core.cedarJsonDocumentValue
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.adapters.languageIdOf
import io.github.thefellow.cedar.ide.adapters.uriOf
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

object JsonPreviews {
    /** The source file a preview shows (upstream: the `cedar:` uri with `.json` trimmed). */
    val SOURCE: Key<VirtualFile> = Key.create("cedar.jsonpreview.source")

    private val previews = ConcurrentHashMap<String, LightVirtualFile>()

    fun open(project: Project, source: VirtualFile) {
        val preview = previews.compute(source.url) { _, existing ->
            existing ?: LightVirtualFile(source.name + ".json", JsonFileType.INSTANCE, "").apply {
                putUserData(SOURCE, source)
                isWritable = false
            }
        }!!
        refresh(source)
        FileEditorManager.getInstance(project).openFile(preview, true)
    }

    /** Upstream fires onDidChange for the virtual uri when the source document is saved. */
    fun refresh(source: VirtualFile) {
        val preview = previews[source.url] ?: return
        val doc = IdeTextDocument.of(source) ?: return
        val text = cedarJsonDocumentValue(doc)
        ApplicationManager.getApplication().invokeLater {
            val document = FileDocumentManager.getInstance().getDocument(preview) ?: return@invokeLater
            WriteAction.run<RuntimeException> {
                document.setReadOnly(false)
                document.setText(text)
                document.setReadOnly(true)
            }
        }
    }

    fun forget(preview: VirtualFile) {
        preview.getUserData(SOURCE)?.let { previews.remove(it.url) }
    }
}

/** cedar.jsonpreview: `resourceScheme == file && (editorLangId == cedarschema || editorLangId == cedar)` */
class JsonPreviewAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isFileScheme && (ctx.isCedarSchema || ctx.isCedar)

    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        if (ctx.languageId in listOf("cedar", "cedarschema")) {
            FileDocumentManager.getInstance().getDocument(ctx.file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
            JsonPreviews.open(ctx.project, ctx.file)
        }
    }
}

/** Refresh previews when a Cedar or Cedar schema document is saved. */
class JsonPreviewSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (languageIdOf(file) == "cedar" || languageIdOf(file) == "cedarschema") {
            ApplicationManager.getApplication().invokeLater { JsonPreviews.refresh(file) }
        }
    }
}

/** Upstream's CedarJSONDocumentCodeLensProvider: "Open <file>" on the JSON preview. */
class JsonPreviewNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val source = file.getUserData(JsonPreviews.SOURCE) ?: return null
        return Function { _ ->
            var fileName = source.path
            val workspace = IdeWorkspace.getInstance(project).getWorkspaceFolder(uriOf(source))
            if (workspace != null && fileName.startsWith(workspace.fsPath)) {
                fileName = fileName.substring(workspace.fsPath.length + 1)
            }
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "JSON view of ${source.name}"
                createActionLabel("Open $fileName") { openFile(project, source) }
                toolTipText = "Open ${source.path}"
            }
        }
    }
}
