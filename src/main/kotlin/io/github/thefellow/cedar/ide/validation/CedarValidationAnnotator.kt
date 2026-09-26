// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.validation

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.core.CedarQuickFix
import io.github.thefellow.cedar.core.CedarSchemaJSONQuickFix
import io.github.thefellow.cedar.core.COMMAND_CEDAR_SCHEMAVALIDATE
import io.github.thefellow.cedar.core.COMMAND_CEDAR_VALIDATE
import io.github.thefellow.cedar.core.detectEntitiesDoc
import io.github.thefellow.cedar.core.detectSchemaDoc
import io.github.thefellow.cedar.core.isCedarSchemaJsonFile
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.toTextRange
import io.github.thefellow.cedar.vscode.CodeAction
import io.github.thefellow.cedar.vscode.CodeActionContext
import io.github.thefellow.cedar.vscode.Diagnostic
import io.github.thefellow.cedar.vscode.DiagnosticSeverity

/**
 * Upstream validates Cedar policies, schemas and entities on open/save/editor change and publishes a
 * DiagnosticCollection; here validation runs on the highlighting daemon (see [CedarValidationService])
 * and the collection's entries for the file are rendered as annotations, with upstream's quick fixes.
 */
class CedarValidationAnnotator :
    ExternalAnnotator<CedarValidationAnnotator.Info, CedarValidationAnnotator.Result>(), DumbAware {

    class Info(val project: Project, val doc: IdeTextDocument)

    class Result(val doc: IdeTextDocument, val diagnostics: List<Diagnostic>)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? =
        collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        val doc = IdeTextDocument.of(file) ?: return null
        val relevant = doc.languageId == "cedar" || detectSchemaDoc(doc) || detectEntitiesDoc(doc)
        return if (relevant) Info(file.project, doc) else null
    }

    override fun doAnnotate(collectedInfo: Info?): Result? {
        val info = collectedInfo ?: return null
        if (info.project.isDisposed) return null
        return Result(info.doc, CedarValidationService.getInstance(info.project).annotate(info.doc))
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        val result = annotationResult ?: return
        val document = file.viewProvider.document ?: return
        // stale result (document changed while validating): the next pass renders fresh diagnostics
        if (document.modificationStamp != result.doc.version) return
        val quickFixes = when {
            result.doc.languageId == "cedar" -> CedarQuickFix()::provideCodeActions
            result.doc.uri.scheme == "file" && isCedarSchemaJsonFile(result.doc.fileName) ->
                CedarSchemaJSONQuickFix()::provideCodeActions
            else -> null
        }
        for (diagnostic in result.diagnostics) {
            val (range, afterEndOfLine) = annotationRange(document, diagnostic)
            var builder = holder.newAnnotation(severity(diagnostic.severity), diagnostic.message)
                .range(range)
                .tooltip(tooltip(diagnostic))
            if (afterEndOfLine) builder = builder.afterEndOfLine()
            quickFixes?.invoke(result.doc, diagnostic.range, CodeActionContext(listOf(diagnostic)))
                ?.forEach { action -> builder = builder.withFix(CodeActionIntention(action)) }
            builder.create()
        }
    }

    private fun severity(severity: DiagnosticSeverity) = when (severity) {
        DiagnosticSeverity.Error -> HighlightSeverity.ERROR
        DiagnosticSeverity.Warning -> HighlightSeverity.WARNING
        DiagnosticSeverity.Information -> HighlightSeverity.WEAK_WARNING
        DiagnosticSeverity.Hint -> HighlightSeverity.INFORMATION
    }

    private fun tooltip(diagnostic: Diagnostic): String {
        val message = StringUtil.escapeXmlEntities(diagnostic.message).replace("\n", "<br>")
        return "<html>$message<br><span style='color:gray'>${diagnostic.source ?: "Cedar"}</span></html>"
    }

    /** VS Code renders empty ranges as a squiggle at the position; widen to one character when possible. */
    private fun annotationRange(document: Document, diagnostic: Diagnostic): Pair<TextRange, Boolean> {
        val range = diagnostic.range.toTextRange(document)
        if (!range.isEmpty) return range to false
        val offset = range.startOffset
        val text = document.immutableCharSequence
        if (offset < text.length && text[offset] != '\n') return TextRange(offset, offset + 1) to false
        // at the end of a line (or of the document)
        return TextRange(offset, offset) to true
    }
}

/** An upstream CodeAction (quick fix): applies its WorkspaceEdit, then runs its validate command. */
class CodeActionIntention(private val action: CodeAction) : IntentionAction, PriorityAction {
    override fun getText() = action.title
    override fun getFamilyName() = "Cedar"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = file != null
    override fun startInWriteAction() = false
    override fun getPriority() = if (action.isPreferred) PriorityAction.Priority.TOP else PriorityAction.Priority.NORMAL
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val psiFile = file ?: return
        val document = psiFile.viewProvider.document ?: return
        val edits = action.edit?.edits?.values?.flatten().orEmpty()
        WriteCommandAction.runWriteCommandAction(project, action.title, null, {
            edits.map { it.range.toTextRange(document) to it.newText }
                .sortedByDescending { it.first.startOffset }
                .forEach { (range, text) -> document.replaceString(range.startOffset, range.endOffset, text) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, psiFile)

        val command = action.command?.command ?: return
        val file0 = psiFile.virtualFile ?: return
        val service = CedarValidationService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            when (command) {
                COMMAND_CEDAR_VALIDATE -> service.validateCedarDoc(file0, userInitiated = true)
                COMMAND_CEDAR_SCHEMAVALIDATE -> service.validateSchemaDoc(file0, userInitiated = true)
            }
        }
    }
}
