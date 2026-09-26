package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.SymbolKind
import io.github.thefellow.cedar.vscode.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParserTest {
    private val testdata = File(System.getProperty("cedar.testdata"))

    private fun file(rel: String, languageId: String): StringTextDocument {
        val f = File(testdata, rel)
        return StringTextDocument(f.readText(), Uri.file(f.path), languageId)
    }

    private fun text(text: String, name: String, languageId: String, version: Long = 1) =
        StringTextDocument(text, Uri.file("/mem/$name"), languageId, version)

    @Test
    fun policiesIdsRangesAndTexts() {
        val texts = mutableListOf<String>()
        val doc = file("policyid/mixed.cedar", "cedar")
        val result = parseCedarPoliciesDoc(doc) { _, t -> texts += t }
        assertEquals(listOf("P0", "policy1"), result.policies.map { it.id })
        assertEquals(Range(0, 0, 1, 37), result.policies[0].range)
        assertEquals(Range(1, 0, 1, 6), result.policies[0].effectRange)
        assertEquals(Range(3, 0, 3, 37), result.policies[1].range)
        assertEquals("permit (principal, action, resource);\n", texts[1])
    }

    @Test
    fun policiesAnnotationsReferencesAndCache() {
        val src = """
            @id("first")
            @doc("x")
            permit (principal == User::"alice", action == Action::"view", resource in NS::Album::"a") // User::"not"
            when { context.x == NS::Action::"edit" };
            @custom
            forbid (principal, action, resource)
        """.trimIndent()
        val doc = text(src, "a.cedar", "cedar")
        val result = parseCedarPoliciesDoc(doc)
        assertEquals(listOf("first", "policy1"), result.policies.map { it.id })
        // unterminated last policy ends at end of file
        assertEquals(Range(4, 0, 5, 36), result.policies[1].range)
        assertEquals(setOf("id", "custom"), result.annotations)
        assertEquals(listOf("Action::\"view\"", "NS::Action::\"edit\""), result.actionIds.map { it.name })
        assertEquals(Range(2, 55, 2, 59), result.actionIds[0].range)
        assertTrue(result.referencedTypes.any { it.name == "NS::Album" && it.range == Range(2, 78, 2, 83) })
        assertTrue(result.referencedTypes.none { it.name == "User" && it.range.start.character > 90 })
        assertSame(result, parseCedarPoliciesDoc(doc))
        assertTrue(result !== parseCedarPoliciesDoc(text(src, "a.cedar", "cedar", version = 2)))
    }

    @Test
    fun entities() {
        val result = parseCedarEntitiesDoc(file("RFC82/cedarentities.json", "json"))
        assertEquals(1, result.entities.size)
        val e = result.entities[0]
        assertEquals("User::\"expected\"", e.uid)
        assertEquals(Range(2, 5, 2, 8), e.uidKeyRange)
        assertEquals(setOf("jobLevel"), e.attrsNameRanges.keys)
        assertNull(e.parentsRange)
        assertEquals(setOf("test"), e.tagsNameRanges.keys)
        assertTrue(result.tokens.tokens.any { it.tokenType == "property" })
        assertEquals(listOf("User"), result.referencedTypes.map { it.name })
    }

    @Test
    fun avpEntities() {
        val src = """[{ "identifier": { "entityType": "NS::User", "entityId": "alice" }, "attributes": { "n": { "string": "A" } }, "parents": [] }]"""
        val e = parseCedarEntitiesDoc(text(src, "x.avpentities.json", "json")).entities.single()
        assertEquals("NS::User::\"alice\"", e.uid)
        assertEquals(setOf("n"), e.attrsNameRanges.keys)
    }

    @Test
    fun templateLinksAndAuth() {
        val links = parseCedarTemplateLinksDoc(
            text("""[{ "template_id": "t", "link_id": "l0", "args": { "?principal": "User::\"a\"" } }]""", "x.cedartemplatelinks.json", "json"),
        )
        assertEquals(listOf("l0"), links.links.map { it.id })
        assertEquals(listOf("variable", "type"), links.tokens.tokens.map { it.tokenType })
        val auth = parseCedarAuthDoc(text("""{"principal": "User::\"a\"", "action": "NS::Action::\"view\"", "context": {}}""", "x.cedarauth.json", "json"))
        assertEquals(listOf("User"), auth.referencedTypes.map { it.name })
        assertEquals(listOf("NS::Action::\"view\""), auth.actionIds.map { it.name })
    }

    @Test
    fun jsonPolicy() {
        val src = """{"effect": "permit", "principal": {"op": "==", "entity": {"type": "User", "id": "a"}}, "action": {"op": "==", "entity": {"type": "Action", "id": "view"}}, "resource": {"op": "All"}, "conditions": []}"""
        val result = parseCedarJsonPolicyDoc(text(src, "p.cedar.json", "json"))
        assertEquals(listOf("User"), result.referencedTypes.map { it.name })
        assertEquals(listOf("Action::\"view\""), result.actionIds.map { it.name })
    }

    @Test
    fun schemaJson() {
        val result = parseCedarSchemaDoc(file("entityattr/cedarschema.json", "json"))
        assertTrue(result.definitionRanges.isNotEmpty())
        assertTrue(result.entityTypes.isNotEmpty())
        assertTrue(result.tokens.tokens.any { it.tokenType == "namespace" && it.tokenModifiers == listOf("declaration") })
    }

    @Test
    fun schemaCedarWithCompletionsFromTranslation() {
        val result = parseCedarSchemaDoc(file("datatypes/cedarschema", "cedarschema"))
        assertEquals(listOf("NS1::EB", "NS1::EF", "NS1::EP", "NS1::ES", "NS1::Group", "NS1::User"), result.entityTypes)
        val user = result.completions["NS1::User"]!!
        assertEquals("NS1::User", user["delegate"]!!.description)
        assertEquals("String", user["name"]!!.description)
        val primitives = result.definitionRanges.single { it.etype == "NS1::Primitives" }
        assertEquals(SymbolKind.Struct, primitives.symbol)
        val rename = result.definitionRanges.single { it.etype == "NS1::Action::\"renameGroup\"" }
        assertEquals(SymbolKind.Function, rename.symbol)
        assertEquals("NS1::Primitives", traversePropertyChain(result.completions, listOf("principal", "p"), "NS1::EP").lastType)
        assertEquals("Bool", traversePropertyChain(result.completions, listOf("principal", "p", "b"), "NS1::EP").lastType)
    }

    @Test
    fun schemaCedarEnums() {
        val src = "entity Color enum [\"red\", \"green\"];\nentity User;\n"
        val result = parseCedarSchemaDoc(text(src, "e.cedarschema", "cedarschema"))
        assertEquals(listOf("red", "green"), result.definitionRanges.single { it.etype == "Color" }.enums)
    }
}
