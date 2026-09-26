// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/policy.ts. Writing the export file is left to the caller (IDE VFS write).

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.QuickPickItem
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.wasm.Cedar
import io.github.thefellow.cedar.wasm.ExportPolicyResult

fun getPolicyQuickPickItems(cedarDoc: TextDocument, selection: Range): List<QuickPickItem> {
    val items = mutableListOf<QuickPickItem>()
    val policyRanges = parseCedarPoliciesDoc(cedarDoc).policies
    policyRanges.forEach { policyRange ->
        val item = QuickPickItem(
            label = policyRange.id,
            detail = cedarDoc.uri.path
                .substring(cedarDoc.uri.path.lastIndexOf('/') + 1)
                .replace(Regex("""\.cedar$"""), "(${policyRange.id}).cedar.json"),
        )
        if (policyRange.range.intersection(selection) != null) {
            // if selection range intersects with policy, pre-select in pick list
            item.picked = true
        }
        items.add(item)
    }
    return items
}

/**
 * Returns the pretty-printed JSON for the policy with [policyId], or "" when it can't be exported.
 * Upstream also writes it to `exportFilename`; the IDE caller does that with [writeFile].
 */
fun exportCedarDocPolicyById(
    cedarDoc: TextDocument,
    policyId: String,
    exportFilename: String,
    writeFile: (path: String, text: String) -> Unit,
): String {
    var exportJson = ""
    val policyRanges = parseCedarPoliciesDoc(cedarDoc).policies
    for (policyRange in policyRanges) {
        if (policyRange.id == policyId) {
            val rawPolicy = cedarDoc.getText(policyRange.range)
            val isTemplate = rawPolicy.contains("?principal") || rawPolicy.contains("?resource")
            val exportResult: ExportPolicyResult = if (isTemplate) {
                Cedar.exportPolicyTemplate(rawPolicy)
            } else {
                Cedar.exportPolicy(rawPolicy)
            }
            val success = exportResult.success
            if (success && exportResult.json != null) {
                exportJson = jsonStringify(com.google.gson.JsonParser.parseString(exportResult.json), 2)

                writeFile(exportFilename, exportJson)
            }

            // upstream's `return` inside forEach only ends this iteration; later policies with the same id
            // are visited too (and overwrite exportJson)
        }
    }

    return exportJson
}
