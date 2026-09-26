package io.github.thefellow.cedar.core

import com.google.gson.GsonBuilder
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.SemanticTokens
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.wasm.Cedar
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Differential-testing aid: when CEDAR_PARSER_DUMP is set, dumps every parser.ts entry point's output for each
 * input file (testdata/ plus CEDAR_PARSER_FIXTURES) as canonical JSON, to compare against upstream TypeScript run
 * under node with a stub `vscode` module. Skipped otherwise.
 */
class ParserDumpTest {
    private fun r(range: Range?) = range?.let { listOf(it.start.line, it.start.character, it.end.line, it.end.character) }
    private fun tokens(t: SemanticTokens) = t.tokens.map { r(it.range)!! + listOf(it.tokenType, it.tokenModifiers.joinToString(",")) }
    private fun refs(list: List<ReferencedRange>) = list.map { listOf(it.name, r(it.range)) }
    private fun completions(c: Map<String, SchemaCompletionRecord>): Map<String, Any?> =
        c.mapValues { (_, rec) -> rec.mapValues { (_, d) -> mapOf("description" to d.description, "children" to d.children?.let { completions(mapOf("x" to it))["x"] }) } }

    private fun languageId(f: File) = when {
        f.name.endsWith(".cedar") -> "cedar"
        f.name == "cedarschema" || f.name.endsWith(".cedarschema") -> "cedarschema"
        else -> "json"
    }

    @Test
    fun dump() {
        val out = System.getenv("CEDAR_PARSER_DUMP")
        assumeTrue(out != null)
        val roots = listOfNotNull(System.getProperty("cedar.testdata"), System.getenv("CEDAR_PARSER_FIXTURES")).map(::File)
        val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
        for (root in roots) for (f in root.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
            val rel = root.name + "/" + f.relativeTo(root).path
            val text = f.readText()
            val lang = languageId(f)
            val result = LinkedHashMap<String, Any?>()
            fun doc(kind: String) = StringTextDocument(text, Uri.file("/${kind}/$rel"), lang)
            val texts = mutableListOf<String>()
            val p = parseCedarPoliciesDoc(doc("p")) { _, t -> texts += t }
            result["policies"] = mapOf(
                "policies" to p.policies.map { mapOf("id" to it.id, "range" to r(it.range), "effectRange" to r(it.effectRange)) },
                "texts" to texts, "tokens" to tokens(p.tokens), "referencedTypes" to refs(p.referencedTypes),
                "actionIds" to refs(p.actionIds), "annotations" to p.annotations.toList(),
            )
            if (lang == "json") {
                val j = parseCedarJsonPolicyDoc(doc("j"))
                result["json"] = mapOf("tokens" to tokens(j.tokens), "referencedTypes" to refs(j.referencedTypes), "actionIds" to refs(j.actionIds))
                val e = parseCedarEntitiesDoc(doc("e"))
                result["entities"] = mapOf(
                    "entities" to e.entities.map {
                        mapOf(
                            "uid" to it.uid, "range" to r(it.range), "uidKeyRange" to r(it.uidKeyRange), "uidTypeRange" to r(it.uidTypeRange),
                            "attrsKeyRange" to r(it.attrsKeyRange), "attrsRange" to r(it.attrsRange), "attrsNameRanges" to it.attrsNameRanges.mapValues { (_, v) -> r(v) },
                            "parentsKeyRange" to r(it.parentsKeyRange), "parentsRange" to r(it.parentsRange),
                            "tagsKeyRange" to r(it.tagsKeyRange), "tagsRange" to r(it.tagsRange), "tagsNameRanges" to it.tagsNameRanges.mapValues { (_, v) -> r(v) },
                        )
                    },
                    "tokens" to tokens(e.tokens), "referencedTypes" to refs(e.referencedTypes),
                )
                val t = parseCedarTemplateLinksDoc(doc("t"))
                result["links"] = mapOf("links" to t.links.map { listOf(it.id, r(it.range), r(it.linkIdRange)) }, "tokens" to tokens(t.tokens), "referencedTypes" to refs(t.referencedTypes))
                val a = parseCedarAuthDoc(doc("a"))
                result["auth"] = mapOf("tokens" to tokens(a.tokens), "referencedTypes" to refs(a.referencedTypes), "actionIds" to refs(a.actionIds))
            }
            if (lang != "cedar") {
                if (lang == "cedarschema") {
                    val tr = Cedar.translateSchemaToJSON(text)
                    File(out, "translate/$rel.json").apply { parentFile.mkdirs() }.writeText(gson.toJson(mapOf("success" to tr.success, "schema" to tr.schema)))
                }
                val s = parseCedarSchemaDoc(doc("s"))
                result["schema"] = mapOf(
                    "definitionRanges" to s.definitionRanges.map {
                        mapOf("collection" to it.collection, "etype" to it.etype, "enums" to it.enums, "range" to r(it.range), "etypeRange" to r(it.etypeRange), "symbol" to it.symbol.name)
                    },
                    "tokens" to tokens(s.tokens), "referencedTypes" to refs(s.referencedTypes), "entityTypes" to s.entityTypes,
                    "actionIds" to refs(s.actionIds), "completions" to completions(s.completions), "tags" to s.tags,
                )
            }
            File(out, "kotlin/$rel.json").apply { parentFile.mkdirs() }.writeText(gson.toJson(result))
        }
    }
}
