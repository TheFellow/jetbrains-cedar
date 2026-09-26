// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.completion

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.psi.PsiDocumentManager
import io.github.thefellow.cedar.core.addEntitiesJSON
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.adapters.toOffset
import io.github.thefellow.cedar.ide.adapters.toPosition
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.QuickPickItem

/** `vscode.window.showQuickPick` as a popup chooser; [onPicked] gets null when the popup is cancelled. */
fun showQuickPickPopup(editor: Editor, items: List<QuickPickItem>, title: String, onPicked: (QuickPickItem?) -> Unit) {
    var chosen = false
    JBPopupFactory.getInstance()
        .createPopupChooserBuilder(items)
        .setTitle(title)
        .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.label })
        .setNamerForFiltering { it.label }
        .setItemChosenCallback { chosen = true; onPicked(it) }
        .addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (!chosen) onPicked(null)
            }
        })
        .createPopup()
        .showInBestPositionFor(editor)
}

/**
 * The "Add Cedar entity" command (upstream `addEntitiesJSON`): asks for an entity type from the schema and
 * inserts a new entity snippet after the entity following the caret. [diagnosticCollection] receives
 * schema validation results (upstream passes the extension's collection). [pick] defaults to a popup.
 */
fun addEntitiesJson(
    project: Project,
    editor: Editor,
    diagnosticCollection: DiagnosticCollection = DiagnosticCollection("Cedar"),
    pick: (items: List<QuickPickItem>, title: String, onPicked: (QuickPickItem?) -> Unit) -> Unit =
        { items, title, onPicked -> showQuickPickPopup(editor, items, title, onPicked) },
): Boolean {
    val document = editor.document
    val file = FileDocumentManager.getInstance().getFile(document) ?: return false
    val doc = IdeTextDocument(document, file)
    val cursor = editor.caretModel.offset.toPosition(document)
    return addEntitiesJSON(
        IdeWorkspace.getInstance(project),
        doc,
        cursor,
        diagnosticCollection,
        pick,
    ) { snippet, position ->
        WriteCommandAction.runWriteCommandAction(project, "Add Cedar Entity", null, {
            editor.caretModel.moveToOffset(position.toOffset(document))
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
            TemplateManager.getInstance(project).startTemplate(editor, Snippets.toTemplate(project, snippet.value))
        })
    }
}
