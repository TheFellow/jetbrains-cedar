// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/format.ts. Upstream reads `editor.tabSize` / `editor.wordWrapColumn` for the cedar
// language; the IDE passes the Cedar code style's indent size and right margin. The defaults are VS Code's
// effective defaults (tabSize 4, wordWrapColumn 80).
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.wasm.Cedar

fun formatCedarDoc(
    cedarDoc: TextDocument,
    tabSize: Int = 4,
    wordWrapColumn: Int = 80,
): String? {
    var formattedPolicy: String? = null
    val (_, skipFormatting) = scanLeadingComments(cedarDoc)

    if (!skipFormatting) {
        val formatResult = Cedar.formatPolicies(
            cedarDoc.getText(),
            wordWrapColumn,
            tabSize,
        )
        if (formatResult.success) {
            formattedPolicy = formatResult.policy
        }
    }

    return formattedPolicy
}

@Suppress("UNUSED_PARAMETER")
fun formatCedarSchemaDoc(schemaDoc: TextDocument): String? {
    // TODO: implement after https://github.com/cedar-policy/cedar/issues/682

    return null
}
