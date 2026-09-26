package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsCoreTest {
    private val policies = """
        @id("first")
        permit (principal, action, resource);

        @id("second")
        forbid (principal == ?principal, action, resource);
    """.trimIndent()

    @Test
    fun quickPickItemsPreselectIntersectingPolicy() {
        val doc = StringTextDocument(policies, Uri.file("/tmp/x/policies.cedar"), "cedar")
        val items = getPolicyQuickPickItems(doc, Range(4, 0, 4, 3))
        assertEquals(listOf("first", "second"), items.map { it.label })
        assertEquals(listOf(false, true), items.map { it.picked })
        assertEquals("policies(second).cedar.json", items[1].detail)
    }

    @Test
    fun exportPolicyAndTemplate() {
        val doc = StringTextDocument(policies, Uri.file("/tmp/x/policies.cedar"), "cedar")
        val written = mutableMapOf<String, String>()
        val first = exportCedarDocPolicyById(doc, "first", "/out/first.json") { p, t -> written[p] = t }
        assertTrue(first.contains("\"effect\": \"permit\""))
        val second = exportCedarDocPolicyById(doc, "second", "/out/second.json") { p, t -> written[p] = t }
        assertTrue(second.contains("\"slot\": \"?principal\""))
        assertEquals(setOf("/out/first.json", "/out/second.json"), written.keys)
        assertEquals("", exportCedarDocPolicyById(doc, "missing", "/out/m.json") { _, _ -> error("no write") })
    }

    @Test
    fun jsonPreviewValue() {
        val cedar = StringTextDocument("permit(principal, action, resource);", Uri.file("/a.cedar"), "cedar")
        val v = cedarJsonDocumentValue(cedar)
        assertTrue(v, v.startsWith("{\n  \"templates\": {},\n  \"staticPolicies\": {"))
        val bad = StringTextDocument("permit(", Uri.file("/b.cedar"), "cedar")
        assertEquals("Invalid Cedar policies", cedarJsonDocumentValue(bad))
        val schema = StringTextDocument("entity User;", Uri.file("/s.cedarschema"), "cedarschema")
        val sv = cedarJsonDocumentValue(schema)
        assertTrue(sv, sv.contains("\"User\""))
        assertEquals("Invalid Cedar schema", cedarJsonDocumentValue(StringTextDocument("entity", Uri.file("/t.cedarschema"), "cedarschema")))
    }
}
