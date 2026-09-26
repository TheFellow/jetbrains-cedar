package io.github.thefellow.cedar.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import io.github.thefellow.cedar.core.clearValidationCache
import io.github.thefellow.cedar.ide.validation.CedarValidationService
import io.github.thefellow.cedar.testing.FsWorkspace
import java.io.File

/** Validation annotator, quick fixes and formatter in the IDE. Files live on disk: upstream only validates `file:` documents. */
class ValidationIdeTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        clearValidationCache()
    }

    private fun testdata(dir: String, name: String) = File(File(FsWorkspace.testdata, dir), name).readText()

    fun testSyntaxErrorIsAnnotated() {
        myFixture.configureByText("a.cedar", "permit (principal action resource);")
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertEquals(1, errors.size)
        assertTrue(errors[0].description, errors[0].description.startsWith("unexpected token `action`"))
        assertEquals("action", errors[0].text)
    }

    fun testUnrecognizedQuickFix() {
        myFixture.addFileToProject("cedarschema.json", testdata("unrecognized", "cedarschema.json"))
        myFixture.configureByText("policy.cedar", testdata("unrecognized", "policy.cedar"))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertTrue(errors.joinToString { it.description }, errors.any { it.text == "Tst" })
        val fix = myFixture.getAllQuickFixes().single { it.text == "Replace with Test" }
        myFixture.launchAction(fix)
        assertTrue(myFixture.editor.document.text.startsWith("permit (principal == Test::\"offset\""))
        // diagnostics were published for the schema too (none: it is valid)
        val service = CedarValidationService.getInstance(project)
        assertTrue(service.diagnostics(myFixture.findFileInTempDir("cedarschema.json")).isEmpty())
    }

    fun testEmptySchemaJsonQuickFix() {
        myFixture.configureByText("cedarschema.json", "")
        myFixture.doHighlighting()
        val fix = myFixture.getAllQuickFixes().single { it.text == "Insert Cedar schema" }
        myFixture.launchAction(fix)
        assertTrue(myFixture.editor.document.text.contains("\"entityTypes\": {}"))
    }

    fun testFormatter() {
        myFixture.configureByText("f.cedar", "permit (\n  principal,\n    action,\n       resource\n);")
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals("permit (principal, action, resource);\n", myFixture.editor.document.text)
    }

    fun testFormatterUsesCodeStyleAndSkipsInvalidPolicies() {
        val policy = "forbid (principal, action, resource) \n    when { principal has tenant && resource has tenant && principal.tenant != resource.tenant};"
        myFixture.configureByText("g.cedar", policy)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(
            "forbid (principal, action, resource)\nwhen\n{\n    principal has tenant &&\n    resource has tenant &&\n    principal.tenant != resource.tenant\n};\n",
            myFixture.editor.document.text,
        )

        val invalid = "permit (principal action   resource);"
        myFixture.configureByText("h.cedar", invalid)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(invalid, myFixture.editor.document.text)
    }

    fun testEntitiesValidatedAgainstSchema() {
        myFixture.addFileToProject("cedarschema.json", testdata("entityattr", "cedarschema.json"))
        myFixture.configureByText("exist.cedarentities.json", testdata("entityattr", "exist.cedarentities.json"))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertTrue(errors.joinToString { it.description }, errors.any { it.description.contains("should not exist according to the schema") })
    }
}
