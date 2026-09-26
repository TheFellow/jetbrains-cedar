// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.completion

import io.github.thefellow.cedar.ide.validation.CedarValidationService
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.core.CedarEntitiesJSONHoverProvider
import io.github.thefellow.cedar.core.CedarHoverProvider
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.lang.CedarIcons
import io.github.thefellow.cedar.ide.lang.CedarLanguage
import io.github.thefellow.cedar.vscode.Hover

/** A computed upstream hover, shown as documentation (mouse hover and Quick Documentation). */
class CedarHoverDocumentationTarget(private val project: Project, private val title: String, val hover: Hover) :
    DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(title).icon(CedarIcons.CEDAR).presentation()

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(html())

    fun html(): String = markdownToHtml(project, hover.contents)
}

/**
 * Upstream CedarHoverProvider (for `cedar`) and CedarEntitiesJSONHoverProvider (for Cedar entities JSON
 * files, `{**&#47;cedarentities.json,**&#47;*.cedarentities.json,**&#47;avpentities.json,**&#47;*.avpentities.json}`).
 */
class CedarDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val name = file.viewProvider.virtualFile.name
        val isEntities = io.github.thefellow.cedar.core.isCedarEntitiesFile(name)
        if (file.language != CedarLanguage && !isEntities) return emptyList()
        val document = IdeTextDocument.of(file) ?: return emptyList()
        val position = document.positionAt(offset)
        val hover = when {
            file.language == CedarLanguage ->
                CedarHoverProvider(CedarValidationService.getInstance(file.project).quietWorkspace).provideHover(document, position)
            isEntities ->
                CedarEntitiesJSONHoverProvider().provideHover(document, position)
            else -> null
        } ?: return emptyList()
        val title = hover.range?.let { document.getText(it) }
            ?: document.getWordRangeAtPosition(position)?.let { document.getText(it) }
            ?: "Cedar"
        return listOf(CedarHoverDocumentationTarget(file.project, title, hover))
    }
}
