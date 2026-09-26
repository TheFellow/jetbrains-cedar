// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.validation

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.lang.CedarFileType
import io.github.thefellow.cedar.ide.lang.CedarLanguage

/**
 * extension.ts `registerDocumentFormattingEditProvider('cedar', ...)`: formats the whole document with the
 * Cedar SDK formatter, and refuses when the policy doesn't pass syntax validation. Upstream has no range
 * formatter, so partial-range formatting requests (e.g. auto-indent) are left alone.
 * Cedar schema formatting is not implemented upstream (format.ts `formatCedarSchemaDoc` returns null).
 */
class CedarFormattingService : AbstractDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean = file.fileType == CedarFileType

    override fun formatDocument(
        document: Document,
        formattingRanges: List<TextRange>,
        formattingContext: FormattingContext,
        canChangeWhiteSpaceOnly: Boolean,
        quickFormat: Boolean,
    ) {
        val wholeDocument = formattingRanges.any { it.startOffset <= 0 && it.endOffset >= document.textLength }
        if (!wholeDocument) return
        val file = formattingContext.virtualFile ?: FileDocumentManager.getInstance().getFile(document) ?: return
        val service = CedarValidationService.getInstance(formattingContext.project)
        val cedarDoc = IdeTextDocument(document, file)

        // don't try and format if syntax doesn't validate
        if (!service.validateCedarDoc(cedarDoc)) {
            return
        }

        val formattedPolicy = service.formatCedarDoc(cedarDoc, formattingContext.containingFile)
        if (formattedPolicy != null && formattedPolicy != document.text) {
            // use replacement Range of full document
            document.replaceString(0, document.textLength, formattedPolicy)
        }
    }
}

/**
 * Upstream formats with `editor.tabSize` and `editor.wordWrapColumn`; their VS Code defaults (4 and 80)
 * are the Cedar code style defaults here (Settings | Editor | Code Style | Cedar).
 */
class CedarLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = CedarLanguage

    override fun getConfigurableDisplayName() = "Cedar"

    override fun getCodeSample(settingsType: SettingsType) = """
        @id("example")
        permit (
            principal == User::"alice",
            action in [Action::"view", Action::"edit"],
            resource in Album::"trip"
        )
        when { principal.department == "Engineering" && context.authenticated };
    """.trimIndent()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        commonSettings.RIGHT_MARGIN = 80
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.WRAPPING_AND_BRACES_SETTINGS) {
            consumer.showStandardOptions("RIGHT_MARGIN")
        }
    }

    override fun getIndentOptionsEditor() = IndentOptionsEditor()

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(CedarLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                        addWrappingAndBracesTab(settings)
                    }
                }
        }
}
