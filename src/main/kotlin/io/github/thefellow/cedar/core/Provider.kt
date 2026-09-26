// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/provider.ts (CedarJSONDocument.value): the read-only JSON view of a Cedar policy
// or Cedar schema document. The IDE part (virtual file, refresh on save, "Open <file>" code lens) is in
// ide/actions/JsonPreview.kt.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.wasm.Cedar

fun cedarJsonDocumentValue(document: TextDocument): String {
    var exportJson = ""
    if (document.languageId == "cedar") {
        val exportResult = Cedar.exportPolicies(document.getText())
        exportJson = if (exportResult.success && exportResult.json != null) {
            jsonStringify(com.google.gson.JsonParser.parseString(exportResult.json), 2)
        } else {
            "Invalid Cedar policies"
        }
    } else if (document.languageId == "cedarschema") {
        val translateResult = Cedar.translateSchemaToJSON(document.getText())
        exportJson = if (translateResult.success && translateResult.schema != null) {
            jsonStringify(com.google.gson.JsonParser.parseString(translateResult.schema), 2)
        } else {
            "Invalid Cedar schema"
        }
    }
    return exportJson
}
