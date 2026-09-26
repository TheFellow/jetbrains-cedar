// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.completion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.core.CedarSignatureHelpProvider
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.toPosition
import io.github.thefellow.cedar.vscode.MarkdownString
import io.github.thefellow.cedar.vscode.SignatureHelp

/** Upstream CedarSignatureHelpProvider (triggers `(` and `,`) as IntelliJ parameter info. */
class CedarParameterInfoHandler : ParameterInfoHandler<PsiElement, SignatureHelp> {

    /** The signature help at [offset] and the document offset of its call's `(`. */
    private fun signatureAt(file: PsiFile, editor: Editor, offset: Int): Pair<SignatureHelp, Int>? {
        val document = editor.document
        val doc = IdeTextDocument(document, file.viewProvider.virtualFile)
        val position = offset.toPosition(document)
        val (help, parenIndex) = CedarSignatureHelpProvider().provideSignatureHelpWithParen(doc, position) ?: return null
        return help to (document.getLineStartOffset(position.line) + parenIndex)
    }

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val (help, parenOffset) = signatureAt(context.file, context.editor, context.offset) ?: return null
        context.itemsToShow = arrayOf(help)
        return context.file.findElementAt(parenOffset) ?: context.file
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val (_, parenOffset) = signatureAt(context.file, context.editor, context.offset) ?: return null
        val element = context.file.findElementAt(parenOffset) ?: return null
        return if (element == context.parameterOwner) element else null
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(0)
    }

    override fun updateUI(p: SignatureHelp, context: ParameterInfoUIContext) {
        val sig = p.signatures.getOrNull(p.activeSignature) ?: return
        val label = sig.label
        val range = (sig.parameters.getOrNull(p.activeParameter)?.label as? List<*>)?.map { it as Int }
        val html = buildString {
            if (range != null) {
                append(StringUtil.escapeXmlEntities(label.substring(0, range[0])))
                append("<b>").append(StringUtil.escapeXmlEntities(label.substring(range[0], range[1]))).append("</b>")
                append(StringUtil.escapeXmlEntities(label.substring(range[1])))
            } else {
                append(StringUtil.escapeXmlEntities(label))
            }
            val doc = (sig.documentation as? MarkdownString)?.value ?: sig.documentation?.toString()
            if (!doc.isNullOrEmpty()) {
                val project = context.parameterOwner?.project
                val body = if (project != null) markdownToHtml(project, listOf(doc)) else StringUtil.escapeXmlEntities(doc)
                append("<br/>").append(body)
            }
        }
        context.setupRawUIComponentPresentation(html)
    }
}
