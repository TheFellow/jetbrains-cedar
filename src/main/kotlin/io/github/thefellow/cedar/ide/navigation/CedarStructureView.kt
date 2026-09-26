// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.navigation

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.vscode.DocumentSymbol
import io.github.thefellow.cedar.vscode.SymbolKind
import javax.swing.Icon

/**
 * Upstream document symbol providers (Outline view) as the Structure view. Registered for cedar,
 * cedarschema and (ordered first) JSON; ordinary JSON files are delegated to the next JSON factory.
 */
class CedarStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val file = psiFile.originalFile.virtualFile
        if (file != null && CedarProviders.symbolProvider(file) != null) {
            return object : TreeBasedStructureViewBuilder() {
                override fun createStructureViewModel(editor: Editor?): StructureViewModel = CedarStructureViewModel(psiFile, editor)
                override fun isRootNodeShown() = false
            }
        }
        return LanguageStructureViewBuilder.getInstance().allForLanguage(psiFile.language)
            .asSequence()
            .filter { it !is CedarStructureViewFactory }
            .firstNotNullOfOrNull { it.getStructureViewBuilder(psiFile) }
    }
}

class CedarStructureViewModel(psiFile: PsiFile, editor: Editor?) :
    StructureViewModelBase(psiFile, editor, CedarFileTreeElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement) = element is CedarSymbolTreeElement && element.symbol.children.isEmpty()

    override fun getCurrentEditorElement(): Any? {
        val editor = editor ?: return null
        val offset = editor.caretModel.offset
        fun find(elements: Array<TreeElement>): CedarSymbolTreeElement? {
            for (e in elements) {
                if (e is CedarSymbolTreeElement && e.contains(offset)) return find(e.children) ?: e
            }
            return null
        }
        return find(root.children)
    }
}

private class CedarFileTreeElement(private val psiFile: PsiFile) : StructureViewTreeElement {
    /** Symbols for a document snapshot; recomputed whenever the document changes (the root outlives edits). */
    private fun symbols(doc: IdeTextDocument): List<DocumentSymbol> {
        val file = psiFile.originalFile.virtualFile ?: return emptyList()
        val provider = CedarProviders.symbolProvider(file) ?: return emptyList()
        return provider.provideDocumentSymbols(doc)
    }

    override fun getValue() = psiFile
    override fun getPresentation(): ItemPresentation = psiFile.presentation ?: object : ItemPresentation {
        override fun getPresentableText() = psiFile.name
        override fun getIcon(unused: Boolean): Icon? = psiFile.getIcon(0)
    }
    override fun getChildren(): Array<TreeElement> {
        val doc = IdeTextDocument.of(psiFile) ?: return emptyArray()
        return symbols(doc).map { CedarSymbolTreeElement(psiFile, doc, it) }.toTypedArray()
    }
    override fun navigate(requestFocus: Boolean) = psiFile.navigate(requestFocus)
    override fun canNavigate() = psiFile.canNavigate()
    override fun canNavigateToSource() = psiFile.canNavigateToSource()
}

class CedarSymbolTreeElement(
    private val psiFile: PsiFile,
    private val doc: IdeTextDocument,
    val symbol: DocumentSymbol,
) : StructureViewTreeElement {
    private val startOffset = doc.offsetAt(symbol.range.start)
    private val endOffset = doc.offsetAt(symbol.range.end)
    private val selectionOffset = doc.offsetAt(symbol.selectionRange.start)

    fun contains(offset: Int) = offset in startOffset..endOffset

    override fun getValue() = this
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = symbol.name
        override fun getLocationString() = symbol.detail.ifEmpty { null }
        override fun getIcon(unused: Boolean): Icon = iconFor(symbol.kind)
    }
    override fun getChildren(): Array<TreeElement> =
        symbol.children.map { CedarSymbolTreeElement(psiFile, doc, it) }.toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(psiFile.project, doc.file, selectionOffset).navigate(requestFocus)
    }
    override fun canNavigate() = true
    override fun canNavigateToSource() = true

    override fun equals(other: Any?) = other is CedarSymbolTreeElement && other.symbol.name == symbol.name &&
        other.startOffset == startOffset && other.doc.file == doc.file
    override fun hashCode() = 31 * symbol.name.hashCode() + startOffset

    companion object {
        fun iconFor(kind: SymbolKind): Icon = when (kind) {
            SymbolKind.Function -> AllIcons.Nodes.Function
            SymbolKind.Class -> AllIcons.Nodes.Class
            SymbolKind.Struct -> AllIcons.Nodes.Record
            SymbolKind.Array -> AllIcons.Json.Array
            SymbolKind.Object -> AllIcons.Json.Object
            SymbolKind.Namespace, SymbolKind.Module -> AllIcons.Nodes.Package
            SymbolKind.Enum, SymbolKind.EnumMember -> AllIcons.Nodes.Enum
            SymbolKind.Property, SymbolKind.Field -> AllIcons.Nodes.Property
            else -> AllIcons.Nodes.Tag
        }
    }
}
