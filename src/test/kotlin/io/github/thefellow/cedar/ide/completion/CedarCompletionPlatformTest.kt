package io.github.thefellow.cedar.ide.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import io.github.thefellow.cedar.vscode.SignatureHelp
import java.io.File

/** Completion, parameter info, documentation and "Add Cedar entity" in a real IDE fixture (local temp dir). */
class CedarCompletionPlatformTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = TempDirTestFixtureImpl()

    private val schema by lazy { File(System.getProperty("cedar.testdata"), "datatypes/cedarschema").readText() }

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.addFileToProject("cedarschema", schema)
    }

    private fun lookupStrings(): List<String> = myFixture.lookupElementStrings.orEmpty()

    fun testPermitSnippetExpandsAsTemplate() {
        myFixture.configureByText("policy.cedar", "p<caret>")
        val elements = myFixture.completeBasic()
        assertNotNull("no lookup: " + myFixture.editor.document.text, elements)
        val permitWhen = elements.first { e ->
            LookupElementPresentation().also { e.renderElement(it) }.typeText == "permit when"
        }
        myFixture.lookup.currentItem = permitWhen
        myFixture.finishLookup('\n')
        // the template's first stop is the `Expr` placeholder; finish it
        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
        assertNotNull("no template: " + myFixture.editor.document.text, state)
        WriteCommandAction.runWriteCommandAction(project) { state!!.gotoEnd(false) }
        assertEquals("permit (principal, action, resource)\nwhen { Expr };", myFixture.editor.document.text)
        assertEquals(myFixture.editor.document.text.indexOf("Expr") + 4, myFixture.caretOffset)
    }

    fun testMultiStopSnippetVisitsStopsInOrder() {
        myFixture.configureByText("policy.cedar", "p<caret>")
        val elements = myFixture.completeBasic()
        myFixture.lookup.currentItem = elements.first { e ->
            LookupElementPresentation().also { e.renderElement(it) }.typeText == "permit"
        }
        myFixture.finishLookup('\n')
        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)!!
        val document = myFixture.editor.document
        // first stop is ${1:Path} in the principal line
        assertEquals("Path", document.text.substring(myFixture.editor.selectionModel.selectionStart, myFixture.editor.selectionModel.selectionEnd))
        assertTrue(document.getLineNumber(myFixture.caretOffset) == 1)
        WriteCommandAction.runWriteCommandAction(project) { state.gotoEnd(false) }
        assertEquals(
            "permit (\n    principal == Path::\"id\",\n    action == Action::\"id\",\n    resource == Path::\"id\"\n);",
            document.text,
        )
        assertEquals(document.text.length - 1, myFixture.caretOffset)
    }

    fun testEntityTypesFromSchema() {
        myFixture.configureByText("policy.cedar", "permit (principal is <caret>")
        myFixture.completeBasic()
        assertTrue(lookupStrings().toString(), lookupStrings().containsAll(listOf("NS1::User", "NS1::Group")))
    }

    fun testAttributesAfterPeriodTrigger() {
        myFixture.configureByText("policy.cedar", "permit (principal is NS1::User, action, resource)\nwhen { principal.<caret> };")
        // invocation count 0 == auto-popup, i.e. opened by typing the '.' trigger character
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC, 0)
        assertEquals(listOf("delegate", "name"), lookupStrings().sorted())
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "name" }
        myFixture.finishLookup('\n')
        myFixture.checkResult("permit (principal is NS1::User, action, resource)\nwhen { principal.name<caret> };")
    }

    fun testTypedTriggerSchedulesPopup() {
        myFixture.configureByText("policy.cedar", "when { principal<caret> }")
        assertEquals(
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            CedarTypedHandler().checkAutoPopup('.', project, myFixture.editor, myFixture.file),
        )
        assertEquals(
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            CedarTypedHandler().checkAutoPopup('x', project, myFixture.editor, myFixture.file),
        )
    }

    fun testParameterInfo() {
        myFixture.configureByText("policy.cedar", "permit (principal, action, resource) when { ip(<caret> };")
        val handler = CedarParameterInfoHandler()
        val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val element = handler.findElementForParameterInfo(context)
        assertNotNull(element)
        val help = context.itemsToShow!!.single() as SignatureHelp
        val ui = MockParameterInfoUIContext<com.intellij.psi.PsiElement>(element)
        handler.updateUI(help, ui as ParameterInfoUIContext)
        assertTrue(ui.text, ui.text.contains("ip(<b>String</b>): ipaddr"))
    }

    fun testHoverDocumentation() {
        val file = myFixture.configureByText("policy.cedar", "permit (principal, action, resource) when { i<caret>p(\"1.2.3.4\") };")
        val targets = CedarDocumentationTargetProvider().documentationTargets(file, myFixture.caretOffset)
        val html = (targets.single() as CedarHoverDocumentationTarget).html()
        assertTrue(html, html.contains("ip(String): ipaddr"))
        assertTrue(html, html.contains("<code>ipaddr</code>"))

        val file2 = myFixture.configureByText(
            "policy.cedar",
            "permit (principal is NS1::User, action, resource)\nwhen { principal.na<caret>me == \"a\" };",
        )
        val html2 = (CedarDocumentationTargetProvider().documentationTargets(file2, myFixture.caretOffset).single() as CedarHoverDocumentationTarget).html()
        assertTrue(html2, html2.contains("name:&#32;String"))
    }

    fun testEntitiesJsonCompletion() {
        myFixture.configureByText("cedarentities.json", "[\n  {<caret>}\n]")
        myFixture.completeBasic()
        assertTrue(lookupStrings().toString(), lookupStrings().contains("NS1::Group"))
    }

    fun testAddEntity() {
        myFixture.configureByText("cedarentities.json", "[<caret>\n]")
        addEntitiesJson(project, myFixture.editor, pick = { items, _, onPicked -> onPicked(items.first { it.label == "NS1::Group" }) })
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.let { state ->
            WriteCommandAction.runWriteCommandAction(project) { state.gotoEnd(false) }
        }
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("\"uid\": { \"type\": \"NS1::Group\", \"id\": \"\" }"))
        assertTrue(text, text.contains("\"owners\": []"))
    }
}
