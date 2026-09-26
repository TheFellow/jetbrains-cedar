// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/generate.ts. The schema (src/cedarschema.d.ts CedarSchemaDefinition) is walked as a
// parsed JSON tree, like upstream's untyped JSON.parse result; `undefined` interpolates as "undefined".

package io.github.thefellow.cedar.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

enum class SchemaExportType(val value: String) {
    PlantUML("PlantUML"),
    Mermaid("Mermaid"),
}

val schemaExportTypeExtension: Map<SchemaExportType, String> = mapOf(
    SchemaExportType.PlantUML to ".puml",
    SchemaExportType.Mermaid to ".mmd",
)

private fun JsonElement?.obj(key: String): JsonObject? =
    (this as? JsonObject)?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement?.prop(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** `${value}` for a possibly-undefined JSON value, as JavaScript would interpolate it. */
private fun JsonElement?.js(): String = when {
    this == null -> "undefined"
    isJsonNull -> "null"
    isJsonPrimitive -> asJsonPrimitive.let { if (it.isString) it.asString else it.toString() }
    isJsonArray -> asJsonArray.joinToString(",") { it.js() }
    else -> "[object Object]"
}

/** A value compared as a string (`x as string` in upstream), or null when undefined. */
private fun JsonElement?.str(): String? = this?.takeUnless { it.isJsonNull }?.js()

private fun buildProperties(attributes: JsonObject, diagramType: SchemaExportType, prefix: String = ""): String {
    var properties = ""

    attributes.keySet().forEach { k ->
        var kname = k
        val attribute = attributes.get(k)
        val required = attribute.prop("required")
        if (required != null && required.isJsonPrimitive && required.asJsonPrimitive.isBoolean && !required.asBoolean) {
            kname += "?"
        }
        var attributeType = attribute.prop("type").js()
        if (attributeType in listOf("Entity", "EntityOrCommon")) {
            attributeType = attribute.prop("name").js()
        } else if (attributeType == "Set") {
            val shapeType = attribute.prop("element").prop("type").js()
            if (shapeType in listOf("Entity", "EntityOrCommon")) {
                attributeType = if (diagramType == SchemaExportType.Mermaid) {
                    "Set~${attribute.prop("element").prop("name").js()}~"
                } else {
                    "Set<${attribute.prop("element").prop("name").js()}>"
                }
            } else {
                attributeType = if (diagramType == SchemaExportType.Mermaid) {
                    "Set~$shapeType~"
                } else {
                    "Set<$shapeType>"
                }
            }
        }

        val nested = attribute.obj("attributes")
        if (attributeType == "Record" && nested != null) {
            properties += buildProperties(nested, diagramType, k)
        } else {
            properties += if (prefix.isNotEmpty()) {
                "\n    $attributeType $prefix[\"$kname\"]"
            } else {
                "\n    $attributeType $kname"
            }
        }
    }

    return properties
}

fun generateDiagram(diagramFilename: String, cedarschema: JsonObject, diagramType: SchemaExportType): String {
    var dsl = ""

    cedarschema.keySet().forEach { namespace ->
        val ns = cedarschema.get(namespace)
        var title = "Cedar Schema"
        if (namespace.isNotEmpty()) {
            title += " - $namespace"
        }
        var commonTypesDSL = ""

        var commonTypes: List<String> = emptyList()
        val commonTypesObj = ns.obj("commonTypes")
        if (commonTypesObj != null) {
            commonTypes = commonTypesObj.keySet().toList()
            commonTypes.forEach { name ->
                val e = commonTypesObj.get(name)
                var properties = "\n"
                val attributes = e.obj("attributes")
                if (attributes != null) {
                    properties = buildProperties(attributes, diagramType).replace("    ", "  ")
                }
                commonTypesDSL += if (diagramType == SchemaExportType.Mermaid) {
                    "class $name {\n  <<commonType>>$properties\n}\n\n"
                } else {
                    "struct $name <<commonType>> {$properties\n}\n\n"
                }
            }
        }

        var entityTypesDSL = ""
        val entityTypesDSLTmp = LinkedHashMap<String, String>()
        var membersDSL = ""
        var parentsDSL = ""
        val entityTypesObj = ns.obj("entityTypes") ?: JsonObject()
        val entityTypes = entityTypesObj.keySet().toList()
        entityTypes.forEach { name ->
            val e = entityTypesObj.get(name)
            var properties = ""
            val attributes = e.obj("shape").obj("attributes")
            if (attributes != null) {
                properties = buildProperties(attributes, diagramType)
            }
            entityTypesDSLTmp[name] =
                if (diagramType == SchemaExportType.Mermaid) {
                    "  class $name {\n    <<Entity>>$properties"
                } else {
                    "  class $name <<Entity>> {$properties"
                }

            val memberOfTypes = e.prop("memberOfTypes")
            if (memberOfTypes != null && memberOfTypes.isJsonArray) {
                memberOfTypes.asJsonArray.forEach { m ->
                    membersDSL += if (diagramType == SchemaExportType.Mermaid) {
                        "$name ..> ${m.js()} : memberOf\n"
                    } else {
                        "$name - ${m.js()} : > memberOf\n"
                    }
                }
            }
            val shapeType = e.prop("shape").prop("type").str()
            if (shapeType != null && commonTypes.contains(shapeType)) {
                parentsDSL += if (diagramType == SchemaExportType.Mermaid) {
                    "$name ..> $shapeType : shape\n"
                } else {
                    "$name -U- $shapeType : > shape\n"
                }
            }
        }

        val actionsTmp = LinkedHashMap<String, String>()

        var actions = ""
        val actionsObj = ns.obj("actions") ?: JsonObject()
        actionsObj.keySet().forEach { name ->
            val a = actionsObj.get(name)
            var tmp =
                if (diagramType == SchemaExportType.Mermaid) {
                    "  class $name {\n    <<Action>>"
                } else {
                    "  class $name <<Action>> {"
                }

            val appliesTo = a.obj("appliesTo")
            val contextAttributes = appliesTo.obj("context").obj("attributes")
            if (contextAttributes != null) {
                val properties = buildProperties(contextAttributes, diagramType)
                if (diagramType == SchemaExportType.PlantUML) {
                    tmp += "\n    --context--"
                }
                tmp += properties + "\n"
            }
            tmp += if (tmp[tmp.length - 1] == '{') {
                "}\n"
            } else {
                "  }\n"
            }
            //tmp += diagramType === ExportType.Mermaid ? `\n  }\n` : `}\n`;
            val contextType = appliesTo.prop("context").prop("type").str()
            if (contextType != null && commonTypes.contains(contextType)) {
                parentsDSL += if (diagramType == SchemaExportType.Mermaid) {
                    "$name ..> $contextType : context\n"
                } else {
                    "$name -U- $contextType : > context\n"
                }
            }
            actions += tmp + "\n"

            val memberOf = a.prop("memberOf")
            if (memberOf != null && memberOf.isJsonArray) {
                memberOf.asJsonArray.forEach { m ->
                    membersDSL += if (diagramType == SchemaExportType.Mermaid) {
                        "$name ..> ${m.prop("id").js()} : memberOf\n"
                    } else {
                        "$name - ${m.prop("id").js()} : > memberOf\n"
                    }
                }
            }

            val principalTypes = appliesTo.prop("principalTypes")
            val resourceTypes = appliesTo.prop("resourceTypes")
            if (appliesTo != null && principalTypes != null && principalTypes.isJsonArray && resourceTypes != null) {
                principalTypes.asJsonArray.forEach { p ->
                    val pName = p.js()
                    (resourceTypes as? com.google.gson.JsonArray)?.forEach { r ->
                        if (actionsTmp[pName] == null) {
                            actionsTmp[pName] = ""
                        }
                        actionsTmp[pName] = actionsTmp[pName] + "\n    $name(${r.js()})"
                    }
                }
            }
        }

        entityTypes.forEach { name ->
            entityTypesDSL += entityTypesDSLTmp[name]
            if (!actionsTmp[name].isNullOrEmpty()) {
                entityTypesDSL += actionsTmp[name]
            }
            if (entityTypesDSL[entityTypesDSL.length - 1] != '{') {
                entityTypesDSL += "\n  "
            }
            entityTypesDSL += "}\n\n"
        }

        dsl += if (diagramType == SchemaExportType.Mermaid) {
            formatMermaid(diagramFilename, title, commonTypesDSL, entityTypesDSL, actions, membersDSL, parentsDSL)
        } else {
            formatPuml(diagramFilename, title, commonTypesDSL, entityTypesDSL, actions, membersDSL, parentsDSL)
        }
    }
    return dsl
}

private fun formatPuml(
    diagramFilename: String,
    title: String,
    commonTypes: String,
    entityTypes: String,
    actions: String,
    members: String,
    parents: String,
): String {
    val puml = "@startuml $diagramFilename\n" +
        "\n" +
        "title $title\n" +
        "\n" +
        "$commonTypes\n" +
        "package entityTypes <<Rectangle>> {\n" +
        "\n" +
        "$entityTypes}\n" +
        "\n" +
        "package actions <<Rectangle>> {\n" +
        "  \n" +
        "$actions}\n" +
        "\n" +
        "$members\n" +
        "$parents\n" +
        "@enduml\n"

    return puml
}

private fun formatMermaid(
    diagramFilename: String,
    title: String,
    commonTypes: String,
    entityTypes: String,
    actions: String,
    members: String,
    parents: String,
): String {
    val mmd = "---\n" +
        "title: $title\n" +
        "---\n" +
        "classDiagram\n" +
        "\n" +
        "$commonTypes\n" +
        "namespace entityTypes {\n" +
        "\n" +
        "$entityTypes}\n" +
        "\n" +
        "namespace actions {\n" +
        "  \n" +
        "$actions}\n" +
        "\n" +
        "$members\n" +
        parents

    return mmd
}
