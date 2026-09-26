package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.testing.CompletionFsWorkspace
import io.github.thefellow.cedar.vscode.CompletionContext
import io.github.thefellow.cedar.vscode.CompletionItem
import io.github.thefellow.cedar.vscode.CompletionTriggerKind
import io.github.thefellow.cedar.vscode.MarkdownString
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.SnippetString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Port of upstream src/test/suite/completion.test.ts (splitPropertyChain Suite). */
class SplitPropertyChainTest {
    @Test fun `validate principal p1`() = assertEquals(listOf("principal", "p1"), splitPropertyChain("principal.p1"))
    @Test fun `validate principal p1 p2`() = assertEquals(listOf("principal", "p1", "p2"), splitPropertyChain("principal.p1.p2"))
    @Test fun `validate principal index p1`() = assertEquals(listOf("principal", "p1"), splitPropertyChain("principal[\"p1\"]"))
    @Test fun `validate principal index p1 then p2`() =
        assertEquals(listOf("principal", "p1", "p2"), splitPropertyChain("principal[\"p1\"].p2"))
    @Test fun `validate principal p1 dot`() = assertEquals(listOf("principal", "p1"), splitPropertyChain("principal.p1."))
    @Test fun `validate principal index p1 dot`() = assertEquals(listOf("principal", "p1"), splitPropertyChain("principal[\"p1\"]."))
    @Test fun `validate principal index p1 p2 dot`() =
        assertEquals(listOf("principal", "p1", "p2"), splitPropertyChain("principal[\"p1\"].p2."))
    @Test fun `validate principal some nested attribute indexes`() = assertEquals(
        listOf("principal", "some", "nested", "attribute"),
        splitPropertyChain("principal[\"some\"][\"nested\"][\"attribute\"]"),
    )
    @Test fun `validate principal some nested attribute mixed`() = assertEquals(
        listOf("principal", "some", "nested", "attribute"),
        splitPropertyChain("principal[\"some\"].nested[\"attribute\"]"),
    )
    @Test fun `validate principal has some nested attribute`() = assertEquals(
        listOf("principal", "some", "nested", "attribute"),
        splitPropertyChain("principal has some.nested.attribute"),
    )
    @Test fun `validate context a b c`() = assertEquals(listOf("context", "a", "b", "c"), splitPropertyChain("context.a.b.c"))
}

/** Port of upstream src/test/suite/completionjson.test.ts (snippetify Suite). */
class SnippetifyTest {
    private val schema: SchemaCacheItem by lazy {
        parseCedarSchemaDoc(CompletionFsWorkspace.open(File(CompletionFsWorkspace.testdata, "datatypes/cedarschema"))!!)
    }

    private fun snippitForEntityType(entityType: String): String {
        val completions = schema.completions
        val (attrs, tabstop) = snippetify(schema, completions[entityType], 2, listOf(entityType), 2)
        return "\n  \"uid\": { \"type\": \"$entityType\", \"id\": \"\$1\" },\n  \"attrs\": $attrs,\n  \"parents\": [\$$tabstop]\n"
    }

    @Test fun `snippetify User`() = assertEquals(
        """
  "uid": { "type": "NS1::User", "id": "${'$'}1" },
  "attrs": {
    "delegate": { "type": "NS1::User", "id": "${'$'}2" },
    "name": "${'$'}3"
  },
  "parents": [${'$'}4]
""",
        snippitForEntityType("NS1::User"),
    )

    @Test fun `snippetify Group`() = assertEquals(
        """
  "uid": { "type": "NS1::Group", "id": "${'$'}1" },
  "attrs": {
    "name": "${'$'}2",
    "owners": [${'$'}3]
  },
  "parents": [${'$'}4]
""",
        snippitForEntityType("NS1::Group"),
    )

    @Test fun `snippetify EP`() = assertEquals(
        """
  "uid": { "type": "NS1::EP", "id": "${'$'}1" },
  "attrs": {
    "b": ${'$'}{2|false,true|},
    "l": ${'$'}{3:0},
    "p": {
      "b": ${'$'}{4|false,true|},
      "l": ${'$'}{5:0},
      "s": "${'$'}6"
    },
    "s": "${'$'}7"
  },
  "parents": [${'$'}8]
""",
        snippitForEntityType("NS1::EP"),
    )

    @Test fun `snippetify ES`() = assertEquals(
        """
  "uid": { "type": "NS1::ES", "id": "${'$'}1" },
  "attrs": {
    "bs": [${'$'}2],
    "ls": [${'$'}3],
    "ps": [${'$'}4],
    "s": {
      "bs": [${'$'}5],
      "ls": [${'$'}6],
      "ss": [${'$'}7]
    },
    "ss": [${'$'}8],
    "sss": [${'$'}9]
  },
  "parents": [${'$'}10]
""",
        snippitForEntityType("NS1::ES"),
    )

    @Test fun `snippetify EF`() = assertEquals(
        """
  "uid": { "type": "NS1::EF", "id": "${'$'}1" },
  "attrs": {
    "da": { "fn": "datetime", "arg": "${'$'}{2:2024-10-15T11:35:00Z}" },
    "de": { "fn": "decimal", "arg": "${'$'}{3:12345.6789}" },
    "du": { "fn": "duration", "arg": "${'$'}{4:1d2h3m4s5ms}" },
    "f": {
      "da": { "fn": "datetime", "arg": "${'$'}{5:2024-10-15T11:35:00Z}" },
      "de": { "fn": "decimal", "arg": "${'$'}{6:12345.6789}" },
      "du": { "fn": "duration", "arg": "${'$'}{7:1d2h3m4s5ms}" },
      "ip": { "fn": "ip", "arg": "${'$'}{8:127.0.0.1}" }
    },
    "ip": { "fn": "ip", "arg": "${'$'}{9:127.0.0.1}" }
  },
  "parents": [${'$'}10]
""",
        snippitForEntityType("NS1::EF"),
    )

    @Test fun `snippetify EB`() = assertEquals(
        """
  "uid": { "type": "NS1::EB", "id": "${'$'}1" },
  "attrs": {
    "b": {
      "f": {
        "da": { "fn": "datetime", "arg": "${'$'}{2:2024-10-15T11:35:00Z}" },
        "de": { "fn": "decimal", "arg": "${'$'}{3:12345.6789}" },
        "du": { "fn": "duration", "arg": "${'$'}{4:1d2h3m4s5ms}" },
        "ip": { "fn": "ip", "arg": "${'$'}{5:127.0.0.1}" }
      },
      "p": {
        "b": ${'$'}{6|false,true|},
        "l": ${'$'}{7:0},
        "s": "${'$'}8"
      }
    }
  },
  "parents": [${'$'}9]
""",
        snippitForEntityType("NS1::EB"),
    )
}

/** Behavioral tests of the completion, signature help and hover providers over testdata/datatypes. */
class CompletionProvidersTest {
    private val dir = File(CompletionFsWorkspace.testdata, "datatypes")
    private val workspace = CompletionFsWorkspace(dir)
    private val invoke = CompletionContext(CompletionTriggerKind.Invoke)
    private fun trigger(c: String) = CompletionContext(CompletionTriggerKind.TriggerCharacter, c)

    /** Document with `|` marking the caret; returns doc and caret position. */
    private fun at(text: String, name: String = "policies.cedar"): Pair<io.github.thefellow.cedar.vscode.TextDocument, Position> {
        val offset = text.indexOf('|')
        val doc = CompletionFsWorkspace.doc(File(dir, name), text.removeRange(offset, offset + 1))
        return doc to doc.positionAt(offset)
    }

    private fun labels(items: List<CompletionItem>?) = items!!.map { it.label }

    private fun complete(text: String, context: CompletionContext) = at(text).let { (doc, pos) ->
        CedarCompletionItemProvider(workspace).provideCompletionItems(doc, pos, context)
    }

    @Test fun `p at line start offers permit snippets`() {
        val items = complete("p|", invoke)!!
        assertEquals(listOf("permit", "permit"), labels(items))
        assertEquals(listOf("permit when", "permit"), items.map { it.labelDescription })
        assertEquals("permit (principal, action, resource)\nwhen { \${0:Expr} };", (items[0].insertText as SnippetString).value)
    }

    @Test fun `column zero offers nothing`() = assertNull(complete("|", invoke))

    @Test fun `space offers variables and extension constructors`() {
        assertEquals(
            listOf("principal", "action", "resource", "context", "ip", "decimal", "datetime", "duration"),
            labels(complete("permit (principal, action, resource) when { |", invoke)),
        )
    }

    @Test fun `function items carry help detail`() {
        val ip = complete("permit (principal, action, resource) when { |", invoke)!!.first { it.label == "ip" }
        assertEquals("(String): ipaddr", ip.labelDetail)
    }

    @Test fun `principal is offers entity types only`() {
        val items = complete("permit (principal is |", invoke)!!
        assertTrue(labels(items).containsAll(listOf("NS1::User", "NS1::Group")))
        assertTrue(items.all { it.insertText == null })
    }

    @Test fun `principal == offers entity snippets`() {
        val items = complete("permit (principal == |", invoke)!!
        val user = items.first { it.label == "NS1::User" }
        assertEquals("NS1::User::\"\$1\"", (user.insertText as SnippetString).value)
    }

    @Test fun `action == offers actions`() {
        val items = complete("permit (principal, action == |", invoke)!!
        assertTrue(labels(items).toString(), labels(items).any { it.contains("renameGroup") })
    }

    @Test fun `period after principal offers attributes narrowed by head`() {
        val items = complete("permit (principal is NS1::User, action, resource)\nwhen { principal.| };", trigger("."))!!
        assertEquals(listOf("delegate", "name"), labels(items))
        assertEquals(": String", items.first { it.label == "name" }.labelDetail)
        assertEquals("NS1::User", items.first().labelDescription)
    }

    @Test fun `period after an entity-typed attribute offers that entity's attributes`() {
        val items = complete("permit (principal is NS1::User, action, resource)\nwhen { principal.delegate.| };", trigger("."))!!
        assertEquals(listOf("delegate", "name"), labels(items))
    }

    @Test fun `period after a set offers set functions`() {
        val items = complete("permit (principal, action, resource is NS1::Group)\nwhen { resource.owners.| };", trigger("."))!!
        assertEquals(listOf("contains", "containsAll", "containsAny", "isEmpty"), labels(items))
    }

    @Test fun `period after ip literal offers ip functions`() {
        assertEquals(
            listOf("isIpv4", "isIpv6", "isLoopback", "isMulticast", "isInRange"),
            labels(complete("permit (principal, action, resource) when { ip(\"1.2.3.4\").| };", trigger("."))),
        )
    }

    @Test fun `colon trigger offers entity types for a namespace`() {
        val items = complete("permit (principal == NS1::|", trigger(":"))!!
        assertTrue(labels(items).contains("NS1::User"))
    }

    @Test fun `at trigger offers annotations`() {
        val items = complete("@doc(\"x\")\npermit (principal, action, resource);\n@|", trigger("@"))!!
        assertEquals(listOf("@id", "@doc"), labels(items))
        assertEquals("id(\"\$1\")\$0", (items[0].insertText as SnippetString).value)
        assertEquals(listOf("@formatter:off"), labels(complete("// @|", trigger("@"))))
    }

    @Test fun `question mark trigger offers template slots`() {
        val items = complete("permit (principal == ?|", trigger("?"))!!
        assertEquals(listOf("?principal"), labels(items))
        assertEquals("principal", (items[0].insertText as SnippetString).value)
    }

    @Test fun `schema snippets`() {
        val (doc, pos) = at("namespace NS {\n  e|\n}", "x.cedarschema")
        val items = CedarSchemaCompletionItemProvider().provideCompletionItems(doc, pos, invoke)!!
        assertEquals(listOf("entity", "entity"), labels(items))
        assertEquals("entity \${1:E} {  \n  \$0  \n};", (items[0].insertText as SnippetString).value)
        val (doc2, pos2) = at("n|", "x.cedarschema")
        assertEquals(listOf("namespace"), labels(CedarSchemaCompletionItemProvider().provideCompletionItems(doc2, pos2, invoke)))
    }

    @Test fun `signature help`() {
        val (doc, pos) = at("permit (principal, action, resource) when { ip(\"1.2.3.4\").isInRange(ip(|")
        val help = CedarSignatureHelpProvider().provideSignatureHelp(doc, pos)!!
        assertEquals("ip(String): ipaddr", help.signatures[0].label)
        assertEquals(listOf(3, 9), help.signatures[0].parameters[0].label)
        val (doc2, pos2) = at("when { ip(\"1.2.3.4\").isInRange(ip(\"1.1.1.1/24\"), |")
        assertEquals("isInRange(ipaddr): Boolean", CedarSignatureHelpProvider().provideSignatureHelp(doc2, pos2)!!.signatures[0].label)
        val (doc3, pos3) = at("when { foo(|")
        assertNull(CedarSignatureHelpProvider().provideSignatureHelp(doc3, pos3))
        val (doc4, pos4) = at("when { context.x.isEmpty(|")
        assertTrue(CedarSignatureHelpProvider().provideSignatureHelp(doc4, pos4)!!.signatures[0].parameters.isNotEmpty())
        val (doc5, pos5) = at("when { ip(\"1\").isIpv4(|")
        assertTrue(CedarSignatureHelpProvider().provideSignatureHelp(doc5, pos5)!!.signatures[0].parameters.isEmpty())
    }

    @Test fun `hover on variable shows narrowed types`() {
        val (doc, pos) = at("permit (principal is NS1::User, action, resource)\nwhen { prin|cipal.name == \"a\" };")
        val hover = CedarHoverProvider(workspace).provideHover(doc, pos)!!
        assertEquals("\n```cedar\nNS1::User\n```\n", (hover.contents.single() as MarkdownString).value)
    }

    @Test fun `hover on attribute shows its type`() {
        val (doc, pos) = at("permit (principal is NS1::User, action, resource)\nwhen { principal.na|me == \"a\" };")
        val hover = CedarHoverProvider(workspace).provideHover(doc, pos)!!
        assertEquals("\n```cedar\n(NS1::User) name: String\n```\n", (hover.contents.single() as MarkdownString).value)
    }

    @Test fun `hover on function shows help`() {
        val (doc, pos) = at("permit (principal, action, resource) when { i|p(\"1.2.3.4\") };")
        val hover = CedarHoverProvider(workspace).provideHover(doc, pos)!!
        assertEquals(FUNCTION_HELP_DEFINITIONS["ip"], hover.contents)
    }

    @Test fun `entities json hover`() {
        val (doc, pos) = at("[{\"attrs\": {\"a\": {\"fn\": \"i|p\", \"arg\": \"1.1.1.1\"}}}]", "x.cedarentities.json")
        assertEquals(FUNCTION_HELP_DEFINITIONS["ip"], CedarEntitiesJSONHoverProvider().provideHover(doc, pos)!!.contents)
    }

    @Test fun `entities json completion`() {
        val (doc, pos) = at("[\n  {|}\n]", "cedarentities.json")
        val items = CedarEntitiesJSONCompletionItemProvider(workspace).provideCompletionItems(doc, pos)
        assertTrue(labels(items).containsAll(listOf("NS1::User", "NS1::Group")))
        val user = items.first { it.label == "NS1::User" }
        assertEquals("\"uid\": { \"type\": \"NS1::User\", ...", user.labelDescription)
        assertTrue((user.insertText as SnippetString).value.contains("\"delegate\": { \"type\": \"NS1::User\", \"id\": \"\$2\" }"))
        // not at top level of an entity object
        val (doc2, pos2) = at("[\n  {\"attrs\": {|}}\n]", "cedarentities.json")
        assertTrue(CedarEntitiesJSONCompletionItemProvider(workspace).provideCompletionItems(doc2, pos2).isEmpty())
    }

    @Test fun `addEntitiesJSON inserts after the entity following the cursor`() {
        val (doc, pos) = at("[\n  |{\n    \"uid\": { \"type\": \"NS1::User\", \"id\": \"a\" }\n  }\n]", "cedarentities.json")
        var inserted: Pair<SnippetString, Position>? = null
        addEntitiesJSON(
            workspace, doc, pos, io.github.thefellow.cedar.vscode.DiagnosticCollection("t"),
            { items, title, onPicked ->
                assertEquals("Add Cedar entity", title)
                onPicked(items.first { it.label == "NS1::Group" })
            },
        ) { s, p -> inserted = s to p }
        assertNotNull(inserted)
        assertEquals(Position(3, 3), inserted!!.second)
        assertEquals(
            ",\n{\n  \"uid\": { \"type\": \"NS1::Group\", \"id\": \"\$1\" },\n  \"attrs\": {\n    \"name\": \"\$2\",\n    \"owners\": [\$3]\n  },\n  \"parents\": [\$4]\n}",
            inserted!!.first.value,
        )
    }
}
