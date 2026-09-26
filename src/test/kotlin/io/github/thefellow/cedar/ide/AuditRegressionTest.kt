package io.github.thefellow.cedar.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.FormattingServiceUtil
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import io.github.thefellow.cedar.core.clearValidationCache
import io.github.thefellow.cedar.ide.actions.QuickPickChooser
import io.github.thefellow.cedar.ide.completion.quickPickCancelListener
import io.github.thefellow.cedar.ide.validation.CedarFormattingService
import io.github.thefellow.cedar.ide.validation.CedarValidationService
import io.github.thefellow.cedar.vscode.QuickPickItem
import java.lang.reflect.Proxy

/** Regressions for issues found in the parity/threading audit. */
class AuditRegressionTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        clearValidationCache()
    }

    private fun popup(): JBPopup =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JBPopup::class.java)) { _, _, _ -> null } as JBPopup

    /** Add Cedar Entity: an OK close must not also report a cancel (which inserted a second, empty entity). */
    fun testQuickPickOkCloseDoesNotReportCancel() {
        val picked = mutableListOf<QuickPickItem?>()
        val listener = quickPickCancelListener { picked += it }
        listener.onClosed(LightweightWindowEvent(popup(), true))
        assertEquals(emptyList<QuickPickItem?>(), picked)
        listener.onClosed(LightweightWindowEvent(popup(), false))
        assertEquals(listOf<QuickPickItem?>(null), picked)
    }

    /** Export Cedar Policy as JSON: items are labelled, not `QuickPickItem@hash`. */
    fun testPolicyPickerShowsLabels() {
        val chooser = QuickPickChooser()
        assertEquals("first  —  p(first).cedar.json", chooser.textOf(QuickPickItem("first", detail = "p(first).cedar.json")))
        assertEquals("second", chooser.textOf(QuickPickItem("second")))
    }

    private fun structureNames(): List<String> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            val root = model.root
            val first = root.children.map { it.presentation.presentableText!! }
            // the same (long-lived) root must reflect later edits
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "@id(\"added\")\nforbid (principal, action, resource);\n\n")
            }
            val second = root.children.map { it.presentation.presentableText!! }
            return first + listOf("|") + second
        } finally {
            Disposer.dispose(model)
        }
    }

    /** The structure view root recomputes symbols after edits instead of caching the first result. */
    fun testStructureViewFollowsEdits() {
        myFixture.configureByText("s.cedar", "@id(\"P0\")\npermit (principal, action, resource);\n")
        assertEquals(listOf("P0", "|", "added", "P0"), structureNames())
    }

    /** What the service's restartHighlighting does outside unit-test mode. */
    private fun restart() = DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file, "test")

    /** Clear Problems keeps the file's problems cleared until it is edited or saved, as upstream. */
    fun testClearProblemsStaysClearedUntilEditOrSave() {
        myFixture.configureByText("c.cedar", "permit (principal action resource);")
        assertEquals(1, myFixture.doHighlighting(HighlightSeverity.ERROR).size)

        CedarValidationService.getInstance(project).clearProblems()
        restart()
        assertEquals(0, myFixture.doHighlighting(HighlightSeverity.ERROR).size)

        myFixture.type(" ")
        assertEquals(1, myFixture.doHighlighting(HighlightSeverity.ERROR).size)

        CedarValidationService.getInstance(project).clearProblems()
        restart()
        assertEquals(0, myFixture.doHighlighting(HighlightSeverity.ERROR).size)
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        restart()
        assertEquals(1, myFixture.doHighlighting(HighlightSeverity.ERROR).size)
    }

    /** Reformat Code runs the Cedar SDK in an async formatting task, not inside the write action on the EDT. */
    fun testFormattingIsAsync() {
        myFixture.configureByText("f.cedar", "permit (\n  principal,\n    action,\n       resource\n);")
        val service = FormattingServiceUtil.findService(myFixture.file, true, true)
        assertInstanceOf(service, CedarFormattingService::class.java)
        assertInstanceOf(service, AsyncDocumentFormattingService::class.java)
    }
}
