package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.testing.FsWorkspace
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** semport/DIVERGENCES.md#multiple-schema-files: several configured schemas combine into one. */
class CombinedSchemaTest {
    private val collection = createDiagnosticCollection()
    private val revalidated = mutableListOf<Uri>()
    private val originalRevalidate = validationCache.revalidate
    private lateinit var root: File

    @Before
    fun setUp() {
        clearValidationCache()
        validationCache.revalidate = { _, uris, _ -> revalidated += uris }
        root = Files.createTempDirectory("cedar-multi").toFile()
        // two Cedar files, one using the other's types, plus a JSON schema for a third namespace
        write("schemas/users.cedarschema", "namespace App {\n  entity User in [Group];\n  entity Group;\n}\n")
        write(
            "schemas/docs.cedarschema",
            "namespace Docs {\n  entity Doc { owner: App::User };\n" +
                "  action view appliesTo { principal: [App::User], resource: [Doc] };\n}\n",
        )
        write(
            "billing/billing.cedarschema.json",
            """{"Billing": {"entityTypes": {"Invoice": {"shape": {"type": "Record", "attributes": {"amount": {"type": "Long"}}}}}, "actions": {}}}""",
        )
    }

    @After
    fun tearDown() {
        validationCache.revalidate = originalRevalidate
        clearValidationCache()
        root.deleteRecursively()
    }

    private fun write(path: String, text: String): File =
        File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun policy(text: String, path: String = "policies/p.cedar") =
        FsWorkspace.document(write(path, text).absolutePath)

    private fun workspace(vararg entries: String) =
        FsWorkspace(root = root, autodetectSchemaFile = true, schemaFiles = entries.toList())

    @Test
    fun `listed files and folders resolve to every schema`() {
        val uris = getSchemaUris(workspace("schemas", "billing/billing.cedarschema.json"))
        assertEquals(
            listOf("schemas/docs.cedarschema", "schemas/users.cedarschema", "billing/billing.cedarschema.json"),
            uris.map { it.fsPath.removePrefix(root.absolutePath + "/") },
        )
    }

    @Test
    fun `a policy validates against all schemas combined`() {
        val ws = workspace("schemas", "billing/billing.cedarschema.json")
        val ok = policy(
            "permit (principal in App::Group::\"g\", action == Docs::Action::\"view\", resource is Docs::Doc)\n" +
                "when { resource.owner == principal };",
        )
        assertTrue(validateCedarDoc(ws, ok, collection))
        assertEquals(emptyList<Any>(), collection.get(ok.uri))

        val bad = policy("permit (principal is App::Nope, action, resource);", "policies/bad.cedar")
        validateCedarDoc(ws, bad, collection)
        assertTrue(collection.get(bad.uri)!!.any { "App::Nope" in it.message })
        assertEquals(emptyList<String>(), ws.errors)
    }

    @Test
    fun `entity types from every schema are offered`() {
        val ws = workspace("schemas", "billing")
        val schemaDoc = getSchemaTextDocument(ws, policy("permit (principal, action, resource);"))
        assertTrue(schemaDoc is CombinedSchemaDocument)
        val types = parseCedarSchemaDoc(schemaDoc!!).definitionRanges.map { it.etype }
        assertTrue(types.toString(), types.containsAll(listOf("App::User", "App::Group", "Docs::Doc", "Billing::Invoice")))
    }

    @Test
    fun `go to definition lands in the file that declares the type`() {
        val ws = workspace("schemas", "billing")
        val text = "permit (principal is App::User, action, resource is Billing::Invoice);"
        val doc = policy(text)
        val user = CedarDefinitionProvider().provideDefinition(ws, doc, Position(0, text.indexOf("User") + 1))
        assertEquals(File(root, "schemas/users.cedarschema").absolutePath, user!!.uri.fsPath)
        val invoice = CedarDefinitionProvider().provideDefinition(ws, doc, Position(0, text.indexOf("Invoice") + 1))
        assertEquals(File(root, "billing/billing.cedarschema.json").absolutePath, invoice!!.uri.fsPath)
    }

    @Test
    fun `code lens lists every schema`() {
        val ws = workspace("schemas", "billing")
        val lens = ValidateWithSchemaCodeLensProvider().provideCodeLenses(ws, policy("permit (principal, action, resource);"))
        assertEquals(
            "Validated using schemas/docs.cedarschema, schemas/users.cedarschema, billing/billing.cedarschema.json",
            lens.single().command!!.title,
        )
        assertEquals(3, lens.single().command!!.arguments.size)
    }

    @Test
    fun `schema errors are reported on the file that has them`() {
        val broken = write("schemas/users.cedarschema", "namespace App {\n  entity User in [Group]\n}\n")
        val ws = workspace("schemas")
        val doc = policy("permit (principal, action, resource);")
        validateCedarDoc(ws, doc, collection)
        assertNotNull(collection.get(Uri.file(broken.absolutePath)))
        assertTrue(collection.get(Uri.file(broken.absolutePath))!!.isNotEmpty())
    }

    @Test
    fun `editing one schema re-validates policies that use the combination`() {
        val ws = workspace("schemas", "billing")
        val doc = policy("permit (principal, action, resource);")
        validateCedarDoc(ws, doc, collection)
        revalidated.clear()
        val edited = FsWorkspace.document(File(root, "billing/billing.cedarschema.json").absolutePath, version = 2)
        validateSchemaDoc(ws, edited, collection)
        assertEquals(listOf(doc.uri), revalidated)
        assertNull(validationCache.check(doc))
    }

    @Test
    fun `a namespace declared in two files is an error on the second`() {
        val dup = write("billing/dup.cedarschema", "// again\nnamespace App {\n  entity Other;\n}\n")
        val ws = workspace("schemas", "billing")
        val doc = policy("permit (principal, action, resource);")
        validateCedarDoc(ws, doc, collection)
        val d = collection.get(Uri.file(dup.absolutePath))!!.single()
        assertEquals("namespace `App` is already declared in users.cedarschema", d.message)
        assertEquals(Range(1, 10, 1, 13), d.range)
        // the first declaration and the other files are fine
        for (name in listOf("schemas/users.cedarschema", "schemas/docs.cedarschema", "billing/billing.cedarschema.json")) {
            assertTrue(name, collection.get(Uri.file(File(root, name).absolutePath)).isNullOrEmpty())
        }
        assertEquals(emptyList<String>(), ws.errors)
    }

    @Test
    fun `a namespace repeated in a JSON file is an error there`() {
        val dup = write("billing/dup.cedarschema.json", """{"App": {"entityTypes": {}, "actions": {}}}""")
        val ws = workspace("schemas", "billing")
        validateCedarDoc(ws, policy("permit (principal, action, resource);"), collection)
        val d = collection.get(Uri.file(dup.absolutePath))!!.single()
        assertEquals("namespace `App` is already declared in users.cedarschema", d.message)
        assertEquals(Range(0, 2, 0, 5), d.range)
    }

    @Test
    fun `unqualified declarations may come from several files but not twice`() {
        write("top/a.cedarschema", "entity Thing;\n")
        val b = write("top/b.cedarschema", "entity Widget;\nentity Thing, Gadget;\n")
        val ws = workspace("top")
        val schemaDoc = getSchemaTextDocument(ws) as CombinedSchemaDocument
        validateSchemaDoc(ws, schemaDoc, collection)
        val d = collection.get(Uri.file(b.absolutePath))!!.single()
        assertEquals("entity type `Thing` is already declared in a.cedarschema", d.message)
        assertEquals(Range(1, 7, 1, 12), d.range)
        assertTrue(collection.get(Uri.file(File(root, "top/a.cedarschema").absolutePath)).isNullOrEmpty())

        b.writeText("entity Widget;\n")
        clearValidationCache() // the test workspace doesn't bump document versions
        val fixed = getSchemaTextDocument(ws)!!
        assertTrue(validateSchemaDoc(ws, fixed, collection, userInitiated = true))
        assertEquals(listOf("Thing", "Widget"), parseCedarSchemaDoc(fixed).definitionRanges.map { it.etype }.sorted())
    }

    @Test
    fun `a schema file using types from another is not an error on its own`() {
        val ws = workspace("schemas", "billing")
        val docs = FsWorkspace.document(File(root, "schemas/docs.cedarschema").absolutePath)
        assertTrue(validateSchemaDoc(ws, docs, collection))
        assertTrue(collection.get(docs.uri).isNullOrEmpty())
    }

    @Test
    fun `a type no schema declares is reported`() {
        write("billing/extra.cedarschema", "entity Thing in [Nowhere];\n")
        val ws = workspace("schemas", "billing")
        validateCedarDoc(ws, policy("permit (principal, action, resource);"), collection)
        val extra = FsWorkspace.document(File(root, "billing/extra.cedarschema").absolutePath)
        val diagnostics = collection.get(extra.uri)!!
        // upstream's undeclared-type handling places an error on the use (with its quick fix)
        val use = diagnostics.first { it.message == "undeclared entity type: Nowhere" }
        assertEquals("Nowhere", extra.getText(use.range))
        assertTrue(diagnostics.toString(), diagnostics.any { it.message.startsWith("Combined with the other configured schemas: ") })
    }

    @Test
    fun `missing entries are reported`() {
        val ws = workspace("schemas", "nope.cedarschema")
        getSchemaTextDocument(ws)
        assertEquals(listOf("Missing cedar.schemaFile: file://${root.absolutePath}/nope.cedarschema"), ws.errors)
    }

    @Test
    fun `a folder with one schema validates it whole`() {
        write("solo/cedarschema", "entity Thing in [Nowhere];\n")
        val ws = workspace("solo")
        val schemaDoc = getSchemaTextDocument(ws)!!
        assertFalse(schemaDoc is CombinedSchemaDocument)
        assertFalse(validateSchemaDoc(ws, schemaDoc, collection))
        assertTrue(collection.get(schemaDoc.uri)!!.any { it.message == "undeclared entity type: Nowhere" })
    }

    @Test
    fun `entries may be absolute paths`() {
        val ws = FsWorkspace(root = null, schemaFiles = listOf(File(root, "schemas").absolutePath))
        assertEquals(2, (getSchemaTextDocument(ws) as CombinedSchemaDocument).parts.size)
    }

    @Test
    fun `a single schema file keeps upstream behavior`() {
        val ws = FsWorkspace(root = root, schemaFile = "schemas/users.cedarschema", autodetectSchemaFile = false)
        val schemaDoc = getSchemaTextDocument(ws, policy("permit (principal, action, resource);"))
        assertFalse(schemaDoc is CombinedSchemaDocument)
        assertEquals(File(root, "schemas/users.cedarschema").absolutePath, schemaDoc!!.uri.fsPath)
    }
}
