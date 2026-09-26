package io.github.thefellow.cedar.ide

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import io.github.thefellow.cedar.ide.lang.CedarColors
import io.github.thefellow.cedar.ide.navigation.CedarGotoDeclarationHandler
import io.github.thefellow.cedar.ide.navigation.CedarStructureViewModel
import java.io.File

/** Structure view, folding, go to declaration and semantic highlighting on real (local) files. */
class NavigationPlatformTest : BasePlatformTestCase() {
    // local files, so schema auto-detection (a directory listing) works like on disk
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val testdata = File(System.getProperty("cedar.testdata"))

    private fun copy(dir: String): VirtualFile = myFixture.copyDirectoryToProject(dir, dir)

    override fun getTestDataPath(): String = testdata.path

    private fun structure(): List<String> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            assertTrue(model is CedarStructureViewModel)
            fun walk(e: com.intellij.ide.util.treeView.smartTree.TreeElement, depth: Int): List<String> =
                listOf("  ".repeat(depth) + e.presentation.presentableText) + e.children.flatMap { walk(it, depth + 1) }
            return model.root.children.flatMap { walk(it, 0) }
        } finally {
            model.dispose()
        }
    }

    fun testCedarStructureView() {
        copy("policyid")
        myFixture.configureFromTempProjectFile("policyid/mixed.cedar")
        assertEquals(listOf("P0", "policy1"), structure())
    }

    fun testSchemaStructureViews() {
        copy("RFC82")
        myFixture.configureFromTempProjectFile("RFC82/cedarschema")
        assertEquals(listOf("User", "Document", "Action::\"writeDoc\""), structure())
        myFixture.configureFromTempProjectFile("RFC82/cedarschema.json")
        assertEquals(listOf("Document", "User", "Action::\"writeDoc\""), structure())
        myFixture.configureFromTempProjectFile("RFC82/cedarentities.json")
        assertEquals(listOf("User::\"expected\"", "  attrs", "  tags"), structure())
    }

    fun testPlainJsonKeepsJsonStructureView() {
        myFixture.configureByText("plain.json", """{ "a": { "b": 1 } }""")
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file)
        assertNotNull(builder)
        val model = (builder as TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor)
        try {
            assertFalse(model is CedarStructureViewModel)
            assertTrue(model.javaClass.name, model.javaClass.name.contains("Json"))
        } finally {
            model.dispose()
        }
    }

    fun testFolding() {
        copy("RFC82")
        myFixture.configureFromTempProjectFile("RFC82/policies.cedar")
        val doc = myFixture.editor.document
        val descriptors = com.intellij.lang.folding.LanguageFolding.buildFoldingDescriptors(
            com.intellij.lang.folding.LanguageFolding.INSTANCE.forLanguage(myFixture.file.language), myFixture.file, doc, false,
        )
        assertEquals(2, descriptors.size)
        com.intellij.testFramework.EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val regions = myFixture.editor.foldingModel.allFoldRegions
        assertEquals(
            listOf(0 to 13, 1 to 13),
            regions.map { doc.getLineNumber(it.startOffset) to doc.getLineNumber(it.endOffset) }.sortedBy { it.first },
        )
    }

    private fun gotoTarget(textOnLine: String, word: String): Pair<VirtualFile, Int>? {
        val text = myFixture.editor.document.text
        val offset = text.indexOf(textOnLine).let { it + textOnLine.indexOf(word) + 1 }
        assertTrue(GotoDeclarationHandler.EP_NAME.extensionList.any { it is CedarGotoDeclarationHandler })
        val targets = CedarGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)
            ?: return null
        val target = targets.single()
        return target.containingFile.virtualFile to target.textOffset
    }

    fun testGotoDeclarationFromPolicy() {
        copy("RFC82")
        myFixture.configureFromTempProjectFile("RFC82/policies.cedar")
        val (file, offset) = gotoTarget("principal is User", "User")!!
        assertEquals("cedarschema", file.name)
        val schemaText = String(file.contentsToByteArray())
        assertTrue(schemaText.substring(offset).startsWith("entity User"))
        val (_, actionOffset) = gotoTarget("Action::\"writeDoc\"", "writeDoc")!!
        assertTrue(schemaText.substring(actionOffset).startsWith("action \"writeDoc\""))
    }

    fun testGotoDeclarationFromEntitiesAndSchema() {
        copy("RFC82")
        myFixture.configureFromTempProjectFile("RFC82/cedarentities.json")
        val (file, offset) = gotoTarget("\"type\": \"User\"", "User")!!
        assertTrue(String(file.contentsToByteArray()).substring(offset).startsWith("entity User"))

        myFixture.configureFromTempProjectFile("RFC82/cedarschema")
        val (_, inSchema) = gotoTarget("owner: User", "User")!!
        assertTrue(myFixture.editor.document.text.substring(inSchema).startsWith("entity User"))
    }

    fun testSemanticHighlighting() {
        copy("RFC82")
        myFixture.configureFromTempProjectFile("RFC82/cedarentities.json")
        val keys = myFixture.doHighlighting().mapNotNull { it.forcedTextAttributesKey?.externalName }
        assertTrue(keys.toString(), keys.any { it.startsWith("CEDAR_SEMANTIC") })

        myFixture.configureFromTempProjectFile("RFC82/cedarschema")
        val infos = myFixture.doHighlighting().filter { it.forcedTextAttributesKey?.externalName?.startsWith("CEDAR_SEMANTIC") == true }
        val text = myFixture.editor.document.text
        val typed = infos.map { text.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey }
        assertTrue(typed.toString(), typed.any { it.first == "User" && it.second in setOf(CedarColors.SEM_TYPE, CedarColors.SEM_TYPE_DECLARATION) })
    }

    fun testOrdinaryJsonHasNoSemanticTokens() {
        myFixture.configureByText("plain.json", """{ "entityTypes": { "User": {} } }""")
        val keys = myFixture.doHighlighting().mapNotNull { it.forcedTextAttributesKey?.externalName }
        assertFalse(keys.any { it.startsWith("CEDAR_SEMANTIC") })
    }
}
