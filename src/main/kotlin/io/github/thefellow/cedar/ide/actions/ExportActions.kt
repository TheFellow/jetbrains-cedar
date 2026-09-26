// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.actions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.ElementsChooser
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.thefellow.cedar.core.SchemaExportType
import io.github.thefellow.cedar.core.exportCedarDocPolicyById
import io.github.thefellow.cedar.core.generateDiagram
import io.github.thefellow.cedar.core.getPolicyQuickPickItems
import io.github.thefellow.cedar.core.jsSubstring
import io.github.thefellow.cedar.core.schemaExportTypeExtension
import io.github.thefellow.cedar.ide.adapters.toRange
import io.github.thefellow.cedar.vscode.QuickPickItem
import io.github.thefellow.cedar.vscode.Range
import java.io.File
import java.io.IOException
import javax.swing.JComponent

/** Writes (creating or overwriting) a local file through the VFS; returns it. */
internal fun writeLocalFile(path: String, text: String): VirtualFile? = WriteAction.computeAndWait<VirtualFile?, IOException> {
    val file = File(path)
    val parent = VfsUtil.createDirectoryIfMissing(file.parent) ?: return@computeAndWait null
    val vf = parent.findChild(file.name) ?: parent.createChildData(CedarActionContext::class.java, file.name)
    VfsUtil.saveText(vf, text)
    vf
}

internal fun openFile(project: Project, file: VirtualFile) = OpenFileDescriptor(project, file).navigate(true)

/** cedar.export: `resourceScheme == file && editorLangId == cedar` */
class ExportPolicyAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isFileScheme && ctx.isCedar

    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val cedarDoc = ctx.document() ?: return
        val selection: Range = ctx.editor?.let {
            com.intellij.openapi.util.TextRange(it.selectionModel.selectionStart, it.selectionModel.selectionEnd)
                .toRange(it.document)
        } ?: Range(0, 0, 0, 0)
        val items = getPolicyQuickPickItems(cedarDoc, selection)
        val results = PolicyPickDialog(ctx.project, items).showAndGetSelection() ?: return
        results.forEach { result ->
            val exportFilename = cedarDoc.uri.fsPath.replace(Regex("""\.cedar$"""), "(${result.label}).cedar.json")
            var written: VirtualFile? = null
            val exportJson = withCedarProgress(ctx.project, "Exporting Cedar policy") {
                exportCedarDocPolicyById(cedarDoc, result.label, exportFilename) { path, text ->
                    written = writeLocalFile(path, text)
                }
            }

            if (exportJson.isEmpty()) {
                ctx.workspace.showErrorMessage("Unable to export Cedar policy: ${result.label}")
            } else if (results.size == 1) {
                written?.let { openFile(ctx.project, it) }
            }
        }
    }
}

/** Checkbox list of quick pick items, labelled like VS Code's quick pick (label, then detail). */
internal class QuickPickChooser : ElementsChooser<QuickPickItem>(true) {
    override fun getItemText(value: QuickPickItem) = value.detail?.let { "${value.label}  —  $it" } ?: value.label

    fun textOf(value: QuickPickItem): String = getItemText(value)
}

/** The multi-select quick pick ("Export Cedar policy as JSON", canPickMany). */
private class PolicyPickDialog(project: Project, private val items: List<QuickPickItem>) : DialogWrapper(project) {
    private val chooser = QuickPickChooser().apply {
        items.forEach { addElement(it, it.picked) }
    }

    init {
        title = "Export Cedar policy as JSON"
        init()
    }

    override fun createCenterPanel(): JComponent = chooser

    fun showAndGetSelection(): List<QuickPickItem>? =
        if (showAndGet()) chooser.markedElements.takeIf { it.isNotEmpty() } else null
}

/** cedar.schemaexport: `resourceScheme == file && schema json` */
class SchemaExportAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isFileScheme && ctx.isSchemaJson

    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val schemaDoc = ctx.document() ?: return
        if (!ValidationBridge.validateSchema(ctx.project, schemaDoc)) {
            ctx.workspace.showErrorMessage("Cedar schema export requires a valid Cedar schema file")
            return
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(SchemaExportType.entries.toList())
            .setTitle("Export Cedar schema (experimental)")
            .setRenderer(com.intellij.ui.dsl.listCellRenderer.textListCellRenderer<SchemaExportType> { "${it.value}  —  Class Diagram" })
            .setItemChosenCallback { type -> export(ctx, schemaDoc.getText(), type) }
            .createPopup()
            .let { popup -> ctx.editor?.let { popup.showInBestPositionFor(it) } ?: popup.showCenteredInCurrentWindow(ctx.project) }
    }

    private fun export(ctx: CedarActionContext, schemaText: String, type: SchemaExportType) {
        val path = ctx.file.path + schemaExportTypeExtension.getValue(type)
        val target = File(path)
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export Cedar Schema", "", schemaExportTypeExtension.getValue(type).removePrefix(".")),
            ctx.project,
        )
        val wrapper = saver.save(LocalFileSystem.getInstance().findFileByPath(target.parent), target.name) ?: return
        val cedarschema = JsonParser.parseString(schemaText) as? JsonObject ?: return
        var diagramName = ctx.file.path.substring(ctx.file.path.lastIndexOf('/') + 1)
        diagramName = diagramName.jsSubstring(0, diagramName.indexOf('.'))

        val dsl = generateDiagram(diagramName, cedarschema, type)
        writeLocalFile(wrapper.file.path, dsl)?.let { openFile(ctx.project, it) }
    }
}
