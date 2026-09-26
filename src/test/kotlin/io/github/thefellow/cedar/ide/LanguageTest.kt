package io.github.thefellow.cedar.ide

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.thefellow.cedar.ide.lang.CedarColors
import io.github.thefellow.cedar.ide.lang.CedarFileType
import io.github.thefellow.cedar.ide.lang.CedarSchemaFileType

class LanguageTest : BasePlatformTestCase() {
    fun testFileTypes() {
        assertEquals(CedarFileType, myFixture.configureByText("a.cedar", "permit(principal, action, resource);").fileType)
        assertEquals(CedarSchemaFileType, myFixture.configureByText("a.cedarschema", "entity User;").fileType)
        assertEquals(CedarSchemaFileType, myFixture.configureByText("cedarschema", "entity User;").fileType)
    }

    fun testHighlighting() {
        myFixture.configureByText("a.cedar", "permit(principal, action, resource) when { context.a == 42 }; // c")
        val highlighter = (myFixture.editor as EditorEx).highlighter
        fun keyAt(text: String): String? {
            val it = highlighter.createIterator(myFixture.editor.document.text.indexOf(text))
            return it.textAttributesKeys.firstOrNull()?.externalName
        }
        assertEquals(CedarColors.KEYWORD.externalName, keyAt("permit"))
        assertEquals(CedarColors.VARIABLE.externalName, keyAt("principal"))
        assertEquals(CedarColors.NUMBER.externalName, keyAt("42"))
        assertEquals(CedarColors.COMMENT.externalName, keyAt("// c"))
    }

    fun testCommentAndBraces() {
        myFixture.configureByText("a.cedar", "<caret>permit(principal, action, resource);")
        myFixture.performEditorAction("CommentByLineComment")
        myFixture.checkResult("//permit(principal, action, resource);")
    }
}
