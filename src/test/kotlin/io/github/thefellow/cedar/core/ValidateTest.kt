package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.testing.FsWorkspace
import io.github.thefellow.cedar.vscode.CodeActionContext
import io.github.thefellow.cedar.vscode.DiagnosticSeverity
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** validate.ts / diagnostics.ts / quickfix.ts / format.ts / codelens.ts end to end over testdata. */
class ValidateTest {
    private val workspace = FsWorkspace()
    private val collection = createDiagnosticCollection()
    private val revalidated = mutableListOf<Uri>()
    private val originalRevalidate = validationCache.revalidate

    @Before
    fun setUp() {
        clearValidationCache()
        validationCache.revalidate = { _, uris, _ -> revalidated += uris }
    }

    @After
    fun tearDown() {
        validationCache.revalidate = originalRevalidate
        clearValidationCache()
    }

    @Test
    fun `syntax error range`() {
        val doc = FsWorkspace.testdataDocument("notfound", "policy.cedar")
        assertFalse(validateCedarDoc(workspace, doc, collection))
        val diagnostics = collection.get(doc.uri)!!
        assertEquals(1, diagnostics.size)
        val d = diagnostics[0]
        assertTrue(d.message.startsWith("unexpected token `action`"))
        assertEquals(doc.getText().substring(18, 24), doc.getText(d.range))
        assertEquals(DiagnosticSeverity.Error, d.severity)
        assertEquals("Cedar", d.source)
    }

    @Test
    fun `unrecognized types with schema autodetected and quick fixes`() {
        val doc = FsWorkspace.testdataDocument("unrecognized", "policy.cedar")
        // syntax is valid; schema validation errors do not change the result
        assertTrue(validateCedarDoc(workspace, doc, collection))
        val diagnostics = collection.get(doc.uri)!!
        val unrecognized = diagnostics.filter { it.code == "unrecognized" }
        assertEquals(listOf("Tst", "Action::\"doTst\""), unrecognized.map { doc.getText(it.range) })
        val fixes = unrecognized.flatMap {
            CedarQuickFix().provideCodeActions(doc, it.range, CodeActionContext(listOf(it)))
        }
        assertEquals(listOf("Replace with Test", "Replace with Action::\"doTest\""), fixes.map { it.title })
        assertEquals("Test", fixes[0].edit!!.edits[doc.uri]!!.single().newText)
        assertEquals("cedar.validate", fixes[0].command!!.command)
    }

    @Test
    fun `results are cached by document version unless user initiated`() {
        val doc = FsWorkspace.testdataDocument("notfound", "policy.cedar")
        validateCedarDoc(workspace, doc, collection)
        collection.delete(doc.uri)
        validateCedarDoc(workspace, doc, collection)
        assertNull(collection.get(doc.uri))
        validateCedarDoc(workspace, doc, collection, userInitiated = true)
        assertEquals(1, collection.get(doc.uri)!!.size)
    }

    @Test
    fun `schema JSON undeclared entity type`() {
        val doc = FsWorkspace.testdataDocument("undeclared", "entitytype.cedarschema.json")
        assertFalse(validateSchemaDoc(workspace, doc, collection))
        val diagnostics = collection.get(doc.uri)!!
        val undeclared = diagnostics.filter { it.code == "undeclared" }
        assertTrue(undeclared.isNotEmpty())
        undeclared.forEach {
            assertEquals("undeclared entity type: Test", it.message)
            assertEquals("Test", doc.getText(it.range))
        }
        // plus the Cedar error itself, on the "entityTypes" key
        val main = diagnostics.single { it.code == null }
        assertTrue(main.message.contains("has not been declared as an entity type"))
        assertEquals("entityTypes", doc.getText(main.range))
    }

    @Test
    fun `schema Cedar undeclared common type quick fix`() {
        val dir = Files.createTempDirectory("cedar").toFile()
        val file = File(dir, "cedarschema.json")
        file.writeText(
            """{ "": { "entityTypes": { "User": { "shape": { "type": "Record", "attributes": { "a": { "type": "string" } } } } }, "actions": {} } }""",
        )
        val doc = FsWorkspace.document(file.absolutePath)
        assertFalse(validateSchemaDoc(workspace, doc, collection))
        val diagnostics = collection.get(doc.uri)!!
        val fixes = diagnostics.flatMap {
            CedarSchemaJSONQuickFix().provideCodeActions(doc, it.range, CodeActionContext(listOf(it)))
        }
        assertEquals(listOf("Replace with String"), fixes.map { it.title })
    }

    @Test
    fun `empty schema JSON offers insert quick fix`() {
        val doc = FsWorkspace.testdataDocument("blank", "cedarschema.json")
        assertFalse(validateSchemaDoc(workspace, doc, collection))
        val d = collection.get(doc.uri)!!.single()
        assertEquals("empty", d.code)
        val fix = CedarSchemaJSONQuickFix().provideCodeActions(doc, d.range, CodeActionContext(listOf(d))).single()
        assertEquals("Insert Cedar schema", fix.title)
        assertEquals("cedar.schemavalidate", fix.command!!.command)
    }

    @Test
    fun `schema shadow warnings`() {
        val doc = FsWorkspace.testdataDocument("shadow", "Demo.cedarschema")
        assertTrue(validateSchemaDoc(workspace, doc, collection))
        val warnings = collection.get(doc.uri)!!
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.severity == DiagnosticSeverity.Warning })
        assertNotEquals(DEFAULT_RANGE, warnings[0].range)
    }

    @Test
    fun `entities errors are placed on the entity`() {
        val doc = FsWorkspace.testdataDocument("entityattr", "exist.cedarentities.json")
        assertFalse(validateEntitiesDoc(workspace, doc, collection))
        val d = collection.get(doc.uri)!!.single()
        assertEquals("attribute `tst` on `Test::\"exist\"` should not exist according to the schema", d.message)
        assertEquals("tst", doc.getText(d.range).trim('"'))
    }

    @Test
    fun `entities without schema report only when user initiated`() {
        val dir = Files.createTempDirectory("cedar").toFile()
        val doc = FsWorkspace.document(File(dir, "cedarentities.json").absolutePath, "[]")
        assertFalse(validateEntitiesDoc(workspace, doc, collection))
        assertTrue(workspace.errors.isEmpty())
        validateEntitiesDoc(workspace, doc, collection, userInitiated = true)
        assertEquals(listOf("Cedar schema file not found or configured in settings.json"), workspace.errors)
    }

    @Test
    fun `schema re-validation re-validates dependent documents`() {
        val policy = FsWorkspace.testdataDocument("unrecognized", "policy.cedar")
        validateCedarDoc(workspace, policy, collection)
        val schemaPath = File(File(FsWorkspace.testdata, "unrecognized"), "cedarschema.json").absolutePath
        val schema = FsWorkspace.document(schemaPath, version = 2)
        validateSchemaDoc(workspace, schema, collection)
        assertEquals(listOf(policy.uri), revalidated)
        // the dependent result was dropped
        assertNull(validationCache.check(policy))
    }

    @Test
    fun `missing configured schema file`() {
        val dir = Files.createTempDirectory("cedar").toFile()
        val ws = FsWorkspace(root = dir, schemaFile = "nope.cedarschema", autodetectSchemaFile = false)
        val doc = FsWorkspace.document(File(dir, "a.cedar").absolutePath, "permit(principal, action, resource);")
        assertTrue(validateCedarDoc(ws, doc, collection))
        assertEquals(1, ws.errors.size)
        assertTrue(ws.errors[0].startsWith("Missing cedar.schemaFile: "))
    }

    @Test
    fun `narrowEntityTypes uses the policy head`() {
        val schema = FsWorkspace.testdataDocument("narrow", "cedarschema.json")
        val text = "permit (principal in NS::E::\"id\", action == NS::Action::\"a1\", resource)\nwhen { true };"
        val doc = FsWorkspace.document("/tmp/narrow.cedar", text)
        val pos = io.github.thefellow.cedar.vscode.Position(1, 3)
        assertEquals(listOf("NS::E1"), narrowEntityTypes(schema, "principal", doc, pos))
        assertEquals(listOf("NS::R1"), narrowEntityTypes(schema, "resource", doc, pos))
        assertEquals(listOf("NS::Action::\"a1\""), narrowEntityTypes(schema, "context", doc, pos))
        assertEquals(listOf("NS::E"), narrowEntityTypes(schema, "NS::E::\"id\"", doc, pos))
    }

    @Test
    fun `format and formatter off`() {
        val doc = FsWorkspace.document("/tmp/f.cedar", "permit (\n  principal,\n    action,\n       resource\n);")
        assertEquals("permit (principal, action, resource);\n", formatCedarDoc(doc, 4, 80))
        val off = FsWorkspace.document("/tmp/g.cedar", "// @formatter:off\npermit (\n principal, action, resource);")
        assertNull(formatCedarDoc(off))
        assertNull(formatCedarSchemaDoc(off))
        val diagnostics = mutableListOf<io.github.thefellow.cedar.vscode.Diagnostic>()
        assertTrue(reportFormatterOff(off, diagnostics))
        assertEquals("Cedar formatting disabled by @formatter:off", diagnostics.single().message)
        assertEquals(Range(0, 3, 0, 17), diagnostics.single().range)
        assertEquals(DiagnosticSeverity.Information, diagnostics.single().severity)
    }

    @Test
    fun `code lens names the schema relative to the workspace folder`() {
        val root = File(FsWorkspace.testdata, "unrecognized")
        val ws = FsWorkspace(root = FsWorkspace.testdata)
        val doc = FsWorkspace.document(File(root, "policy.cedar").absolutePath)
        val lens = ValidateWithSchemaCodeLensProvider().provideCodeLenses(ws, doc).single()
        assertEquals("Validated using unrecognized/cedarschema.json", lens.command!!.title)
        assertEquals(Range(0, 0, 0, 0), lens.range)
    }
}
