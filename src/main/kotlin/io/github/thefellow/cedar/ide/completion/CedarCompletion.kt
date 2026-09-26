// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.completion

import io.github.thefellow.cedar.ide.validation.CedarValidationService
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.core.CedarCompletionItemProvider
import io.github.thefellow.cedar.core.CedarEntitiesJSONCompletionItemProvider
import io.github.thefellow.cedar.core.CedarSchemaCompletionItemProvider
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.adapters.toOffset
import io.github.thefellow.cedar.ide.adapters.toPosition
import io.github.thefellow.cedar.ide.lang.CedarLanguage
import io.github.thefellow.cedar.ide.lang.CedarSchemaLanguage
import io.github.thefellow.cedar.vscode.CompletionContext
import io.github.thefellow.cedar.vscode.CompletionItem
import io.github.thefellow.cedar.vscode.CompletionItemKind
import io.github.thefellow.cedar.vscode.CompletionTriggerKind
import io.github.thefellow.cedar.vscode.MarkdownString
import io.github.thefellow.cedar.vscode.SnippetString
import javax.swing.Icon

/** Upstream registers the Cedar provider with these trigger characters. */
internal const val CEDAR_TRIGGER_CHARACTERS = ".:@?"

/** Upstream `{**&#47;cedarentities.json,**&#47;*.cedarentities.json}` (the completion selector, without avpentities). */
internal fun isCedarEntitiesCompletionFile(name: String) =
    name == "cedarentities.json" || name.endsWith(".cedarentities.json")

/**
 * VS Code passes `CompletionTriggerKind.TriggerCharacter` when completion was opened by typing a trigger
 * character, and `Invoke` otherwise (explicit invocation and quick suggestions while typing).
 */
internal fun completionContext(parameters: CompletionParameters, triggerCharacters: String): CompletionContext {
    val offset = parameters.offset
    val text = parameters.editor.document.charsSequence
    val prev = if (offset > 0) text[offset - 1] else null
    return if (parameters.isAutoPopup && prev != null && prev in triggerCharacters) {
        CompletionContext(CompletionTriggerKind.TriggerCharacter, prev.toString())
    } else {
        CompletionContext(CompletionTriggerKind.Invoke)
    }
}

internal fun documentOf(parameters: CompletionParameters): IdeTextDocument? {
    val file = parameters.originalFile.viewProvider.virtualFile
    return IdeTextDocument(parameters.editor.document, file)
}

internal fun iconFor(kind: CompletionItemKind?): Icon? = when (kind) {
    CompletionItemKind.Function, CompletionItemKind.Method -> AllIcons.Nodes.Function
    CompletionItemKind.Variable -> AllIcons.Nodes.Variable
    CompletionItemKind.Class -> AllIcons.Nodes.Class
    CompletionItemKind.Struct -> AllIcons.Nodes.Record
    CompletionItemKind.Field -> AllIcons.Nodes.Field
    CompletionItemKind.Property -> AllIcons.Nodes.Property
    CompletionItemKind.Value, CompletionItemKind.Constant -> AllIcons.Nodes.Constant
    CompletionItemKind.EnumMember, CompletionItemKind.Enum -> AllIcons.Nodes.Enum
    CompletionItemKind.Snippet -> AllIcons.Nodes.Template
    CompletionItemKind.Keyword -> AllIcons.Nodes.Static
    else -> null
}

/**
 * Adds shim completion items to [result]: each item's replacement range becomes its prefix, snippet
 * insert texts become live templates, and VS Code's `sortText ?: label` ordering becomes priority.
 */
internal fun addItems(project: Project, document: Document, offset: Int, items: List<CompletionItem>, result: CompletionResultSet) {
    val sorted = items.withIndex().sortedWith(compareBy({ it.value.sortText ?: it.value.label }, { it.index }))
    val rank = HashMap<Int, Int>()
    sorted.forEachIndexed { r, iv -> rank[iv.index] = r }
    items.forEachIndexed { i, item ->
        val start = item.range?.start?.toOffset(document)?.coerceAtMost(offset) ?: offset
        val prefix = document.charsSequence.subSequence(start, offset).toString()
        val element = toLookupElement(project, item, document)
        val priority = (items.size - rank.getValue(i)).toDouble() + if (item.preselect) items.size else 0
        result.withPrefixMatcher(prefix).addElement(PrioritizedLookupElement.withPriority(element, priority))
    }
}

private fun toLookupElement(project: Project, item: CompletionItem, originalDocument: Document): LookupElement {
    val lookupString = item.filterText ?: item.label
    // additional edits are relative to the document before insertion (and always precede the insertion)
    val additionalEdits = item.additionalTextEdits.orEmpty().map { edit ->
        Triple(edit.range.start.toOffset(originalDocument), edit.range.end.toOffset(originalDocument), edit.newText)
    }
    var builder = LookupElementBuilder.create(item, lookupString)
        .withPresentableText(item.label)
        .withIcon(iconFor(item.kind))
        .withInsertHandler { context, _ -> insert(project, context, item, additionalEdits) }
    item.labelDetail?.let { builder = builder.withTailText(it, true) }
    (item.labelDescription ?: item.detail)?.let { builder = builder.withTypeText(it, true) }
    if (item.filterText != null && item.filterText != item.label) builder = builder.withLookupString(item.label)
    return builder
}

private fun insert(project: Project, context: InsertionContext, item: CompletionItem, additionalEdits: List<Triple<Int, Int, String>>) {
    val document = context.document
    var start = context.startOffset
    val insertText = item.insertText ?: item.label
    document.deleteString(start, context.tailOffset)
    // apply additional edits (in reverse order) that precede the insertion, shifting the insertion point
    for ((s, e, text) in additionalEdits.sortedByDescending { it.first }) {
        if (e <= start) {
            document.replaceString(s, e, text)
            start += text.length - (e - s)
        }
    }
    when (insertText) {
        is SnippetString -> {
            context.editor.caretModel.moveToOffset(start)
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
            TemplateManager.getInstance(project).startTemplate(context.editor, Snippets.toTemplate(project, insertText.value))
        }
        else -> {
            val text = insertText.toString()
            document.insertString(start, text)
            context.editor.caretModel.moveToOffset(start + text.length)
        }
    }
}

/** Upstream CedarCompletionItemProvider, registered for `cedar` with trigger characters `.`, `:`, `@`, `?`. */
class CedarCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.language != CedarLanguage) return
        val project = parameters.editor.project ?: return
        val document = documentOf(parameters) ?: return
        val position = parameters.offset.toPosition(parameters.editor.document)
        val context = completionContext(parameters, CEDAR_TRIGGER_CHARACTERS)
        val items = CedarCompletionItemProvider(CedarValidationService.getInstance(project).quietWorkspace)
            .provideCompletionItems(document, position, context) ?: return
        addItems(project, parameters.editor.document, parameters.offset, items, result)
        if (items.isNotEmpty()) result.stopHere()
    }
}

/** Upstream CedarSchemaCompletionItemProvider, registered for `cedarschema` (no trigger characters). */
class CedarSchemaCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.language != CedarSchemaLanguage) return
        val project = parameters.editor.project ?: return
        val document = documentOf(parameters) ?: return
        val position = parameters.offset.toPosition(parameters.editor.document)
        val context = completionContext(parameters, "")
        val items = CedarSchemaCompletionItemProvider().provideCompletionItems(document, position, context) ?: return
        addItems(project, parameters.editor.document, parameters.offset, items, result)
    }
}

/** Upstream CedarEntitiesJSONCompletionItemProvider, registered for cedarentities JSON files (trigger `{`). */
class CedarEntitiesJsonCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.viewProvider.virtualFile
        if (!isCedarEntitiesCompletionFile(file.name)) return
        val project = parameters.editor.project ?: return
        val document = documentOf(parameters) ?: return
        val position = parameters.offset.toPosition(parameters.editor.document)
        val items = CedarEntitiesJSONCompletionItemProvider(CedarValidationService.getInstance(project).quietWorkspace)
            .provideCompletionItems(document, position)
        addItems(project, parameters.editor.document, parameters.offset, items, result)
    }
}

/** Opens completion on upstream's trigger characters and signature help on `(` / `,`. */
class CedarTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language == CedarLanguage && charTyped in CEDAR_TRIGGER_CHARACTERS) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        if (charTyped == '{' && isCedarEntitiesCompletionFile(file.viewProvider.virtualFile.name)) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language == CedarLanguage && (c == '(' || c == ',')) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        }
        return Result.CONTINUE
    }
}

/** Renders shim hover/markdown contents (MarkdownString or markdown strings) as documentation HTML. */
internal fun markdownToHtml(project: Project, contents: List<Any>): String =
    contents.joinToString("<hr/>") { content ->
        val markdown = when (content) {
            is MarkdownString -> content.value
            else -> content.toString()
        }
        com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter.convert(project, markdown)
    }
