// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/quickfix.ts.
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.CodeAction
import io.github.thefellow.cedar.vscode.CodeActionContext
import io.github.thefellow.cedar.vscode.CodeActionKind
import io.github.thefellow.cedar.vscode.Command
import io.github.thefellow.cedar.vscode.Diagnostic
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.WorkspaceEdit

// strings need to match commands in package.json (upstream commands.ts)

class CedarQuickFix {
    companion object {
        val providedCodeActionKinds = listOf(CodeActionKind.QuickFix)
    }

    @Suppress("UNUSED_PARAMETER")
    fun provideCodeActions(
        document: TextDocument,
        range: Range,
        context: CodeActionContext,
    ): List<CodeAction> {
        // for each diagnostic entry that has the matching `code`, create a code action command
        val actions = mutableListOf<CodeAction>()

        context.diagnostics.forEach { diagnostic ->
            if (diagnostic.code == "unrecognized") {
                val action = createUnrecognizedQuickFixAction(document, diagnostic)
                if (action != null) {
                    actions.add(action)
                }
            }
        }

        return actions
    }

    private fun createUnrecognizedQuickFixAction(
        document: TextDocument,
        diagnostic: Diagnostic,
    ): CodeAction? {
        var fix: CodeAction? = null
        val found = diagnostic.message.jsMatch(UNRECOGNIZED_REGEX)
        val suggestion = found?.group("suggestion")
        if (found != null && !suggestion.isNullOrEmpty()) {
            fix = CodeAction("Replace with $suggestion", CodeActionKind.QuickFix)
            fix.edit = WorkspaceEdit().apply {
                replace(document.uri, diagnostic.range, suggestion)
            }
            fix.isPreferred = true
            fix.diagnostics = listOf(diagnostic)
            fix.command = Command("Validate Cedar policy", COMMAND_CEDAR_VALIDATE)
        }

        return fix
    }
}

private val CEDARSCHEMA_JSON = """{
  "": {
    "entityTypes": {},
    "actions": {}
  }
}"""

class CedarSchemaJSONQuickFix {
    companion object {
        val providedCodeActionKinds = listOf(CodeActionKind.QuickFix)
    }

    @Suppress("UNUSED_PARAMETER")
    fun provideCodeActions(
        document: TextDocument,
        range: Range,
        context: CodeActionContext,
    ): List<CodeAction> {
        // for each diagnostic entry that has the matching `code`, create a code action command
        val actions = mutableListOf<CodeAction>()

        context.diagnostics.forEach { diagnostic ->
            if (diagnostic.code == "undeclared") {
                val action = createUndeclaredCommonTypeQuickFixAction(document, diagnostic)
                if (action != null) {
                    actions.add(action)
                }
            } else if (diagnostic.code == "empty") {
                actions.add(
                    createSchemaCodeAction(
                        document,
                        diagnostic,
                        "Insert Cedar schema",
                        CEDARSCHEMA_JSON,
                    ),
                )
            }
        }

        return actions
    }

    private fun createUndeclaredCommonTypeQuickFixAction(
        document: TextDocument,
        diagnostic: Diagnostic,
    ): CodeAction? {
        var fix: CodeAction? = null

        if (diagnostic.message.startsWith("undeclared common type:")) {
            val type = diagnostic.message.jsSubstring(diagnostic.message.indexOf(": ") + 2)
            var suggestion: String? = null

            for (t in "String|Long|Boolean|Record|Set|Entity|Extension".split("|")) {
                if (type.lowercase() == t.lowercase()) {
                    suggestion = t
                    break
                }
            }

            if (suggestion != null) {
                fix = createSchemaCodeAction(
                    document,
                    diagnostic,
                    "Replace with $suggestion",
                    suggestion,
                )
            }
        }

        return fix
    }

    private fun createSchemaCodeAction(
        document: TextDocument,
        diagnostic: Diagnostic,
        title: String,
        suggestion: String,
    ): CodeAction {
        val fix = CodeAction(title, CodeActionKind.QuickFix)
        fix.edit = WorkspaceEdit().apply {
            replace(document.uri, diagnostic.range, suggestion)
        }
        fix.isPreferred = true
        fix.diagnostics = listOf(diagnostic)
        fix.command = Command("Validate Cedar schema", COMMAND_CEDAR_SCHEMAVALIDATE)

        return fix
    }
}
