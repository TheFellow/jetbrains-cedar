package io.github.thefellow.cedar.ide

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.thefellow.cedar.ide.lang.CedarLanguage
import io.github.thefellow.cedar.ide.lang.CedarSchemaLanguage

/** syntaxes/codeblock.json: ```cedar and ```cedarschema fences in Markdown get the Cedar languages injected. */
class MarkdownFenceTest : BasePlatformTestCase() {
    private fun injectedLanguageAt(text: String, marker: String): String? {
        val file = myFixture.configureByText("README.md", text)
        val element = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, text.indexOf(marker))
        return element?.containingFile?.language?.id
    }

    fun testCedarSchemaFence() {
        val md = "# Schema\n\n```cedarschema\nentity User in [Group];\n```\n"
        assertEquals(CedarSchemaLanguage.id, injectedLanguageAt(md, "User"))
    }

    fun testCedarFenceCaseInsensitiveWithAttributes() {
        val md = "```Cedar title=\"x\"\npermit (principal, action, resource);\n```\n"
        assertEquals(CedarLanguage.id, injectedLanguageAt(md, "principal"))
    }
}
