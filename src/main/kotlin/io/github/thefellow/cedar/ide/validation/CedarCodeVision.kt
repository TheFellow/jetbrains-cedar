// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.validation

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.core.ValidateWithSchemaCodeLensProvider
import io.github.thefellow.cedar.core.isCedarEntitiesFile
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.virtualFileOf
import io.github.thefellow.cedar.ide.lang.CedarFileType
import io.github.thefellow.cedar.vscode.Uri

/**
 * codelens.ts `ValidateWithSchemaCodeLensProvider` (registered for Cedar policies and entities JSON):
 * "Validated using <schema>" at the top of the file; clicking opens the schema.
 */
class CedarSchemaCodeVisionProvider : DaemonBoundCodeVisionProvider {
    override val id: String = ID
    override val name: String = "Cedar schema used for validation"
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        val virtualFile = file.virtualFile ?: return emptyList()
        if (virtualFile.fileType != CedarFileType && !isCedarEntitiesFile(virtualFile.name)) return emptyList()
        val doc = IdeTextDocument.of(file) ?: return emptyList()
        val workspace = CedarValidationService.getInstance(file.project).workspace
        val document = editor.document
        val lineEnd = if (document.lineCount > 0) document.getLineEndOffset(0) else 0
        return ValidateWithSchemaCodeLensProvider().provideCodeLenses(workspace, doc).mapNotNull { lens ->
            val command = lens.command ?: return@mapNotNull null
            val schemaUri = command.arguments.firstOrNull() as? Uri
            val entry = ClickableTextCodeVisionEntry(
                command.title,
                id,
                { _, clickedEditor ->
                    val project = clickedEditor.project ?: return@ClickableTextCodeVisionEntry
                    val schemaFile = schemaUri?.let { virtualFileOf(it) } ?: return@ClickableTextCodeVisionEntry
                    OpenFileDescriptor(project, schemaFile).navigate(true)
                },
                null,
                command.title,
                command.tooltip ?: "",
                emptyList(),
            )
            TextRange(0, lineEnd) to entry
        }
    }

    companion object {
        const val ID = "cedar.validatedWithSchema"
    }
}
