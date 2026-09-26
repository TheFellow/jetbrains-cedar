// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.lang

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider

/**
 * Upstream syntaxes/codeblock.json: highlight ```cedar and ```cedarschema fenced blocks in Markdown
 * (case-insensitive, optionally followed by attributes).
 */
class CedarCodeFenceLanguageProvider : CodeFenceLanguageProvider {
    override fun getLanguageByInfoString(infoString: String): Language? =
        when (infoString.trim().substringBefore(' ').lowercase()) {
            "cedar" -> CedarLanguage
            "cedarschema" -> CedarSchemaLanguage
            else -> null
        }

    override fun getCompletionVariantsForInfoString(parameters: CompletionParameters): List<LookupElement> = listOf(
        LookupElementBuilder.create("cedar").withIcon(CedarIcons.CEDAR),
        LookupElementBuilder.create("cedarschema").withIcon(CedarIcons.CEDAR_SCHEMA),
    )
}
