package io.github.thefellow.cedar.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * generate.ts differential aid (see semport/differential/run.sh): when CEDAR_PARSER_DUMP is set, writes
 * generateDiagram output for every JSON schema under testdata/ and CEDAR_PARSER_FIXTURES.
 */
class GenerateDumpTest {
    private fun schemas(): List<Pair<String, File>> =
        listOfNotNull(System.getProperty("cedar.testdata"), System.getenv("CEDAR_PARSER_FIXTURES")).map(::File).flatMap { root ->
            root.walkTopDown().filter { it.isFile && isCedarSchemaJsonFile(it.name) }.sortedBy { it.path }
                .map { root.name + "/" + it.relativeTo(root).path to it }.toList()
        }

    @Test
    fun smoke() {
        val schema = File(System.getProperty("cedar.testdata"), "RFC82/cedarschema.json")
        val json = JsonParser.parseString(schema.readText()) as JsonObject
        val puml = generateDiagram("cedarschema", json, SchemaExportType.PlantUML)
        val mmd = generateDiagram("cedarschema", json, SchemaExportType.Mermaid)
        assertTrue(puml.startsWith("@startuml cedarschema\n") && puml.endsWith("@enduml\n"))
        assertTrue(mmd.startsWith("---\ntitle: Cedar Schema") && mmd.contains("classDiagram"))
    }

    @Test
    fun dump() {
        val out = System.getenv("CEDAR_PARSER_DUMP")
        assumeTrue(out != null)
        for ((rel, f) in schemas()) {
            val json = runCatching { JsonParser.parseString(f.readText()) as JsonObject }.getOrNull() ?: continue
            for (type in SchemaExportType.entries) {
                val text = runCatching { generateDiagram("diagram", json, type) }.getOrElse { "THREW" }
                File(out, "generate-kotlin/$rel.${type.value}").apply { parentFile.mkdirs() }.writeText(text)
            }
        }
    }
}
