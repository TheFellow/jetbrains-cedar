// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.wasm

/*
 * Typed facade mirroring upstream's `vscode-cedar-wasm` TypeScript API (see cedar-wasm/src).
 * Function and field names match upstream so ported call sites read the same.
 */

data class ValidateMessage(val message: String, val offset: Int, val length: Int)

data class ValidateSyntaxResult(
    val success: Boolean,
    val policies: Int?,
    val templates: Int?,
    val errors: List<ValidateMessage>?,
)

data class ValidatePolicyResult(
    val success: Boolean,
    val warnings: List<ValidateMessage>?,
    val errors: List<ValidateMessage>?,
)

data class ValidateSchemaResult(
    val success: Boolean,
    val warnings: List<ValidateMessage>?,
    val errors: List<ValidateMessage>?,
)

data class ValidateEntitiesResult(val success: Boolean, val errors: List<ValidateMessage>?)

data class FormatPoliciesResult(val success: Boolean, val policy: String?, val error: String?)

data class ExportPolicyResult(val success: Boolean, val json: String?)

data class TranslateSchemaResult(val success: Boolean, val schema: String?, val error: String?)

object Cedar {
    fun getCedarSDKVersion(): String = CedarWasm.call("getCedarSDKVersion").asString

    fun validateSyntax(policies: String) =
        CedarWasm.call(ValidateSyntaxResult::class.java, "validateSyntax", policies)

    fun validatePolicySchemaJSON(schema: String, policies: String) =
        CedarWasm.call(ValidatePolicyResult::class.java, "validatePolicySchemaJSON", schema, policies)

    fun validatePolicySchemaCedar(schema: String, policies: String) =
        CedarWasm.call(ValidatePolicyResult::class.java, "validatePolicySchemaCedar", schema, policies)

    fun validateSchemaJSON(schema: String) =
        CedarWasm.call(ValidateSchemaResult::class.java, "validateSchemaJSON", schema)

    fun validateSchemaCedar(schema: String) =
        CedarWasm.call(ValidateSchemaResult::class.java, "validateSchemaCedar", schema)

    fun validateEntitiesSchemaJSON(schema: String, entities: String) =
        CedarWasm.call(ValidateEntitiesResult::class.java, "validateEntitiesSchemaJSON", schema, entities)

    fun validateEntitiesSchemaCedar(schema: String, entities: String) =
        CedarWasm.call(ValidateEntitiesResult::class.java, "validateEntitiesSchemaCedar", schema, entities)

    fun formatPolicies(policies: String, lineWidth: Int, indentWidth: Int) =
        CedarWasm.call(FormatPoliciesResult::class.java, "formatPolicies", policies, lineWidth, indentWidth)

    fun exportPolicy(policy: String) = CedarWasm.call(ExportPolicyResult::class.java, "exportPolicy", policy)

    fun exportPolicies(policies: String) = CedarWasm.call(ExportPolicyResult::class.java, "exportPolicies", policies)

    fun exportPolicyTemplate(template: String) =
        CedarWasm.call(ExportPolicyResult::class.java, "exportPolicyTemplate", template)

    fun translateSchemaFromJSON(json: String) =
        CedarWasm.call(TranslateSchemaResult::class.java, "translateSchemaFromJSON", json)

    fun translateSchemaToJSON(cedar: String) =
        CedarWasm.call(TranslateSchemaResult::class.java, "translateSchemaToJSON", cedar)
}
