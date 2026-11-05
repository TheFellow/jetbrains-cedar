// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.navigation

import io.github.thefellow.cedar.ide.validation.CedarValidationService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.tree.TreeUtil
import io.github.thefellow.cedar.core.CedarFoldingRangeProvider
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.adapters.textRange
import io.github.thefellow.cedar.ide.adapters.toPosition
import io.github.thefellow.cedar.ide.adapters.virtualFileOf
import io.github.thefellow.cedar.ide.lang.CedarColors
import io.github.thefellow.cedar.ide.lang.CedarSchemaSupport
import io.github.thefellow.cedar.vscode.SemanticToken

/** Upstream CedarFoldingRangeProvider: each policy, and its body after annotations, folds. */
class CedarFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root.containingFile?.viewProvider?.virtualFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val doc = IdeTextDocument(document, file)
        val seen = HashSet<TextRange>()
        return CedarFoldingRangeProvider().provideFoldingRanges(doc).mapNotNull { range ->
            if (range.end <= range.start || range.end >= document.lineCount) return@mapNotNull null
            // keep the first line visible, like VS Code
            val textRange = TextRange(document.getLineEndOffset(range.start), document.getLineEndOffset(range.end))
            if (textRange.isEmpty || !seen.add(textRange)) return@mapNotNull null
            FoldingDescriptor(root.node, textRange, null, "...")
        }.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = "..."
    override fun isCollapsedByDefault(node: ASTNode) = false
}

// DIVERGENCE (semport/DIVERGENCES.md#schema-folding): upstream registers no folding provider for `cedarschema`,
// so VS Code falls back to indentation-based folding, which IntelliJ doesn't have. Fold each multi-line
// `{...}` / `[...]` block instead, using the lexer's bracket tokens (so brackets in strings and comments are ignored).
class CedarSchemaFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val types = CedarSchemaSupport.tokenTypes
        val closers = mapOf(types.rBrace to types.lBrace, types.rBracket to types.lBracket)
        val open = ArrayDeque<ASTNode>()
        val descriptors = mutableListOf<FoldingDescriptor>()
        var leaf = TreeUtil.findFirstLeaf(root.node)
        while (leaf != null) {
            when (val type = leaf.elementType) {
                types.lBrace, types.lBracket -> open.addLast(leaf)
                in closers -> {
                    // drop unmatched openers (incomplete code) down to the matching one
                    while (open.isNotEmpty() && open.last().elementType != closers[type]) open.removeLast()
                    val opener = open.removeLastOrNull()
                    if (opener != null) {
                        val range = TextRange(opener.startOffset, leaf.startOffset + leaf.textLength)
                        if (document.getLineNumber(range.startOffset) < document.getLineNumber(range.endOffset)) {
                            val placeholder = if (type == types.rBrace) "{...}" else "[...]"
                            descriptors += FoldingDescriptor(opener, range, null, placeholder)
                        }
                    }
                }
            }
            leaf = TreeUtil.nextLeaf(leaf)
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = if (node.elementType == CedarSchemaSupport.tokenTypes.lBracket) "[...]" else "{...}"
    override fun isCollapsedByDefault(node: ASTNode) = false
}

/** Upstream definition providers (extension.ts registerDefinitionProvider for every Cedar selector). */
class CedarGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val psiFile = sourceElement?.containingFile ?: return null
        val file = psiFile.originalFile.virtualFile ?: return null
        val provider = CedarProviders.definitionProvider(file) ?: return null
        val project = psiFile.project
        val doc = IdeTextDocument.of(file) ?: return null
        val location = provider.provideDefinition(CedarValidationService.getInstance(project).quietWorkspace, doc, offset.toPosition(doc.document))
            ?: return null
        val targetFile = virtualFileOf(location.uri) ?: return null
        val targetPsi = PsiManager.getInstance(project).findFile(targetFile) ?: return null
        val targetDoc = IdeTextDocument.of(targetFile) ?: return null
        val targetOffset = targetDoc.offsetAt(location.range.start)
        return arrayOf(targetPsi.findElementAt(targetOffset) ?: targetPsi)
    }
}

/** Upstream semantic tokens providers (parser.ts), applied as information-level text attributes. */
class CedarSemanticTokensAnnotator : ExternalAnnotator<IdeTextDocument, List<SemanticToken>>() {
    override fun collectInformation(file: PsiFile): IdeTextDocument? {
        val vf = file.originalFile.virtualFile ?: return null
        if (CedarProviders.tokensProvider(vf) == null) return null
        return IdeTextDocument.of(file)
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean) = collectInformation(file)

    override fun doAnnotate(collectedInfo: IdeTextDocument?): List<SemanticToken>? {
        val doc = collectedInfo ?: return null
        val provider = CedarProviders.tokensProvider(doc.file) ?: return null
        return runCatching { provider.provideDocumentSemanticTokens(doc).tokens }.getOrNull()
    }

    override fun apply(file: PsiFile, annotationResult: List<SemanticToken>?, holder: AnnotationHolder) {
        val tokens = annotationResult ?: return
        val document = file.viewProvider.document ?: return
        val doc = IdeTextDocument(document, file.viewProvider.virtualFile)
        for (token in tokens) {
            val key = CedarColors.forSemanticToken(token.tokenType, token.tokenModifiers) ?: continue
            val range = doc.textRange(token.range)
            if (range.isEmpty || range.endOffset > document.textLength) continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
    }
}
