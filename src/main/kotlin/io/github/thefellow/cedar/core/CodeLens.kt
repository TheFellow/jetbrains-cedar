// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/codelens.ts.
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.CodeLens
import io.github.thefellow.cedar.vscode.Command
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Workspace

// strings need to match commands in package.json (upstream commands.ts)

class ValidateWithSchemaCodeLensProvider {
    fun provideCodeLenses(workspace: Workspace, document: TextDocument): List<CodeLens> {
        val schemaDoc = getSchemaTextDocument(workspace, document)
        if (schemaDoc != null) {
            var schemaFileName = schemaDoc.uri.fsPath
            val folder = workspace.getWorkspaceFolder(document.uri)
            if (folder != null) {
                schemaFileName = schemaFileName.jsSubstring(folder.fsPath.length + 1)
            }

            val openCommand = Command(
                command = "vscode.open",
                title = "Validated using $schemaFileName",
                tooltip = "Open ${schemaDoc.uri.fsPath}",
                arguments = listOf(schemaDoc.uri),
            )

            val codeLens = CodeLens(Range(0, 0, 0, 0), openCommand)

            return listOf(codeLens)
        }

        return emptyList()
    }
}

class TranslateSchemaCodeLensProvider {
    @Suppress("UNUSED_PARAMETER")
    fun provideCodeLenses(document: TextDocument): List<CodeLens> {
        val translateCommand = Command(
            command = COMMAND_CEDAR_SCHEMATRANSLATE,
            title = "Translate Cedar schema",
        )

        val codeLens = CodeLens(Range(0, 0, 0, 0), translateCommand)

        return listOf(codeLens)
    }
}
