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

    /** The editor highlighter re-lexes incrementally; after edits it must agree with a fresh lex of the text. */
    fun testIncrementalHighlightingMatchesFullLex() {
        myFixture.configureByText(
            "a.cedar",
            """
            permit(
                principal,
                action in [
                    Acme::Action::"list",<caret>
                    Acme::Action::"view"
                ],
                resource
            );
            """.trimIndent(),
        )
        myFixture.type("\n        Acme::Action::\"edit\",")
        myFixture.type("\n        Acme::Action::\"de")
        myFixture.type("lete\",")

        val editor = myFixture.editor as EditorEx
        fun tokens(highlighter: com.intellij.openapi.editor.highlighter.EditorHighlighter): List<String> {
            val out = mutableListOf<String>()
            val it = highlighter.createIterator(0)
            while (!it.atEnd()) {
                out += "${it.start}-${it.end} ${it.tokenType}"
                it.advance()
            }
            return out
        }
        val fresh = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, myFixture.file.virtualFile)
        fresh.setText(editor.document.immutableCharSequence)
        assertEquals(tokens(fresh).joinToString("\n"), tokens(editor.highlighter).joinToString("\n"))
    }

    fun testCommentAndBraces() {
        myFixture.configureByText("a.cedar", "<caret>permit(principal, action, resource);")
        myFixture.performEditorAction("CommentByLineComment")
        myFixture.checkResult("//permit(principal, action, resource);")
    }
}
