package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.testing.NavFsWorkspace
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.SymbolKind
import io.github.thefellow.cedar.vscode.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for the ports of documentsymbols.ts and definition.ts. */
class NavigationTest {
    private val testdata = File(System.getProperty("cedar.testdata"))
    private fun doc(path: String): TextDocument = NavFsWorkspace.open(File(testdata, path))!!
    private fun describe(symbols: List<io.github.thefellow.cedar.vscode.DocumentSymbol>) =
        symbols.joinToString("\n") { s ->
            "${s.name} ${s.kind} ${s.range} ${s.selectionRange}" +
                s.children.joinToString("") { c -> "\n  ${c.name} ${c.kind} ${c.range} ${c.selectionRange}" }
        }

    @Test
    fun policySymbols() {
        val symbols = CedarDocumentSymbolProvider().provideDocumentSymbols(doc("policyid/mixed.cedar"))
        println(describe(symbols))
        assertTrue(symbols.isNotEmpty())
        assertTrue(symbols.all { it.kind == SymbolKind.Function })
        // selection range is the effect keyword
        val text = doc("policyid/mixed.cedar")
        symbols.forEach { assertTrue(text.getText(it.selectionRange) in setOf("permit", "forbid")) }
    }

    @Test
    fun policyFolding() {
        val ranges = CedarFoldingRangeProvider().provideFoldingRanges(doc("RFC82/policies.cedar"))
        assertEquals(listOf(Triple(0, 13, "Region"), Triple(1, 13, "null")), ranges.map { Triple(it.start, it.end, it.kind.toString()) })
    }

    @Test
    fun entitiesSymbols() {
        val symbols = CedarEntitiesDocumentSymbolProvider().provideDocumentSymbols(doc("RFC82/cedarentities.json"))
        println(describe(symbols))
        assertEquals(1, symbols.size)
        assertEquals("User::\"expected\"", symbols[0].name)
        // upstream's parser records no range for an empty `parents: []`, so there is no parents child
        assertEquals(listOf("attrs", "tags"), symbols[0].children.map { it.name })
        assertEquals(listOf(SymbolKind.Object, SymbolKind.Object), symbols[0].children.map { it.kind })
        val withParents = io.github.thefellow.cedar.vscode.StringTextDocument(
            """
            [
              {
                "uid": { "type": "User", "id": "u1" },
                "parents": [
                  { "type": "Group", "id": "g1" }
                ]
              }
            ]
            """.trimIndent(),
            io.github.thefellow.cedar.vscode.Uri.file("/x/p.cedarentities.json"), "json",
        )
        val children = CedarEntitiesDocumentSymbolProvider().provideDocumentSymbols(withParents).single().children
        println(describe(CedarEntitiesDocumentSymbolProvider().provideDocumentSymbols(withParents)))
        assertEquals(listOf("parents" to SymbolKind.Array), children.map { it.name to it.kind })
    }

    @Test
    fun schemaSymbols() {
        for (path in listOf("RFC82/cedarschema", "RFC82/cedarschema.json")) {
            val symbols = CedarSchemaDocumentSymbolProvider().provideDocumentSymbols(doc(path))
            println("$path\n" + describe(symbols))
            assertTrue(path, symbols.map { it.name }.containsAll(listOf("User", "Document")))
        }
    }

    @Test
    fun invalidDocumentsYieldNoSymbolsRatherThanThrow() {
        val broken = io.github.thefellow.cedar.vscode.StringTextDocument(
            "[{\"uid\": ", io.github.thefellow.cedar.vscode.Uri.file("/x/broken.cedarentities.json"), "json",
        )
        CedarEntitiesDocumentSymbolProvider().provideDocumentSymbols(broken)
        CedarTemplateLinksDocumentSymbolProvider().provideDocumentSymbols(broken)
    }

    @Test
    fun policyDefinitionResolvesToSchema() {
        val workspace = NavFsWorkspace(testdata)
        val cedar = doc("RFC82/policies.cedar")
        // `User` in `principal is User`
        val line = cedar.lineAt(2).text
        val loc = CedarDefinitionProvider().provideDefinition(workspace, cedar, Position(2, line.indexOf("User") + 1))
        assertNotNull(loc)
        assertTrue(loc!!.uri.fsPath.endsWith("RFC82/cedarschema"))
        val schema = doc("RFC82/cedarschema")
        assertTrue(schema.getText(loc.range).startsWith("entity User"))

        // action id
        val actionLine = cedar.lineAt(3).text
        val actionLoc = CedarDefinitionProvider().provideDefinition(workspace, cedar, Position(3, actionLine.indexOf("writeDoc") + 1))
        assertNotNull(actionLoc)
        assertTrue(schema.getText(actionLoc!!.range).startsWith("action \"writeDoc\""))

        // not on a reference
        assertNull(CedarDefinitionProvider().provideDefinition(workspace, cedar, Position(0, 0)))
    }

    @Test
    fun entitiesDefinitionResolvesToSchema() {
        val workspace = NavFsWorkspace(testdata)
        val entities = doc("RFC82/cedarentities.json")
        val line = entities.lineAt(2).text
        val loc = CedarEntitiesDefinitionProvider().provideDefinition(workspace, entities, Position(2, line.indexOf("User") + 1))
        assertNotNull(loc)
        assertTrue(doc("RFC82/cedarschema").getText(loc!!.range).startsWith("entity User"))
    }

    @Test
    fun schemaDefinitionWithinSchema() {
        val workspace = NavFsWorkspace(testdata)
        val schema = doc("RFC82/cedarschema")
        // `owner: User`
        val lineNo = (0 until schema.lineCount).first { schema.lineAt(it).text.contains("owner: User") }
        val line = schema.lineAt(lineNo).text
        val loc = CedarSchemaDefinitionProvider().provideDefinition(workspace, schema, Position(lineNo, line.indexOf("User") + 1))
        assertNotNull(loc)
        assertTrue(schema.getText(loc!!.range).startsWith("entity User"))
    }

    @Test
    fun noSchemaMeansNoDefinition() {
        val workspace = NavFsWorkspace(testdata)
        val cedar = doc("notfound/policy.cedar")
        assertNull(CedarDefinitionProvider().provideDefinition(workspace, cedar, Position(1, 5)))
    }
}
