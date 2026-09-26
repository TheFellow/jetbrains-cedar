// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import io.github.thefellow.cedar.core.formatJsonText
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.wasm.Cedar
import io.github.thefellow.cedar.wasm.TranslateSchemaResult
import java.io.File

/** cedar.validate: `editorLangId == cedar` */
class ValidateAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isCedar
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val doc = ctx.document() ?: return
        ValidationBridge.validateCedar(ctx.project, doc)
    }
}

/** cedar.schemavalidate: `editorLangId == cedarschema || schema json` */
class SchemaValidateAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isCedarSchema || ctx.isSchemaJson
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val doc = ctx.document() ?: return
        ValidationBridge.validateSchema(ctx.project, doc)
    }
}

/** cedar.entitiesvalidate: entities json */
class EntitiesValidateAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isEntitiesJson
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val doc = ctx.document() ?: return
        ValidationBridge.validateEntities(ctx.project, doc)
    }
}

/** cedar.entitiesadd: entities json */
class EntitiesAddAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isEntitiesJson && ctx.editor != null
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        val editor = ctx?.editor ?: return
        ValidationBridge.addEntities(ctx.project, editor)
    }
}

/** cedar.clearproblems */
class ClearProblemsAction : CedarAction() {
    override val requiresFile = false
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ValidationBridge.clearProblems(e.project ?: return)
    }
}

/** cedar.schematranslate: `resourceScheme == file && (editorLangId == cedarschema || schema json)` */
class SchemaTranslateAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isFileScheme && (ctx.isCedarSchema || ctx.isSchemaJson)

    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val workspace = ctx.workspace
        if (!ctx.isFileScheme) {
            workspace.showErrorMessage("Cedar schema translate only supported for local files")
            return
        }
        val schemaDoc = ctx.document() ?: return

        if (!ValidationBridge.validateSchema(ctx.project, schemaDoc)) {
            workspace.showErrorMessage("Cedar schema translate requires a valid Cedar schema file")
            return
        }

        val schemaText = schemaDoc.getText()
        val translateResult: TranslateSchemaResult = withCedarProgress(ctx.project, "Translating Cedar schema") {
            if (schemaDoc.languageId == "cedarschema") {
                Cedar.translateSchemaToJSON(schemaText)
            } else {
                Cedar.translateSchemaFromJSON(schemaText)
            }
        }
        if (translateResult.success && translateResult.schema != null) {
            val uri = schemaDoc.uri
            val translateUri = uri.with(
                path = if (schemaDoc.languageId == "cedarschema") uri.path + ".json" else uri.path.substring(0, uri.path.length - 5),
            )
            saveTextAndFormat(ctx.project, translateUri, translateResult.schema)
        } else {
            workspace.showErrorMessage(translateResult.error ?: "Error translating Cedar Schema")
        }
    }
}

/**
 * Port of upstream fileutil.ts saveTextAndFormat: write [text] to [uri] (asking where to save when the file
 * doesn't exist), open it, reformat it with the IDE formatter, and save.
 */
fun saveTextAndFormat(project: Project, uri: Uri, text: String): Boolean {
    val workspace = IdeWorkspace.getInstance(project)
    var targetPath = uri.fsPath

    if (!File(targetPath).exists()) {
        // File doesn't exist, prompt user
        val target = File(targetPath)
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Save Translated Cedar Schema", ""),
            project,
        )
        val wrapper = saver.save(LocalFileSystem.getInstance().findFileByPath(target.parent), target.name) ?: return false
        targetPath = wrapper.file.path
    }

    // Format JSON text with user's indent settings if it's a JSON file
    var formattedText = text
    if (targetPath.endsWith(".json")) {
        formattedText = formatJsonText(workspace, text)
    }

    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath)
    val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
    val vf = if (document != null) {
        // File exists, use workspace edit
        WriteCommandAction.runWriteCommandAction(project, "Translate Cedar Schema", null, { document.setText(formattedText) })
        file
    } else {
        // New file, write directly
        writeLocalFile(targetPath, formattedText) ?: return false
    }

    openFile(project, vf)
    ApplicationManager.getApplication().invokeLater({
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@invokeLater
        val psi = PsiDocumentManager.getInstance(project).run { commitDocument(doc); getPsiFile(doc) }
        if (psi != null) {
            WriteCommandAction.runWriteCommandAction(project, "Format Translated Cedar Schema", null, {
                CodeStyleManager.getInstance(project).reformat(psi)
            })
        }
        WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
    }, project.disposed)
    return true
}
