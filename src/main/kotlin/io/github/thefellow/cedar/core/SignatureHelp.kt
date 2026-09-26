// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/signaturehelp.ts.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.MarkdownString
import io.github.thefellow.cedar.vscode.ParameterInformation
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.SignatureHelp
import io.github.thefellow.cedar.vscode.SignatureInformation
import io.github.thefellow.cedar.vscode.TextDocument

private val WORD_BEFORE_PAREN_REGEX = Regex("""([_a-zA-Z][_a-zA-Z0-9]*)$""")

class CedarSignatureHelpProvider {
    /** Also returns the offset (within the line) of the call's '(' so IDEs can anchor the popup. */
    fun provideSignatureHelp(document: TextDocument, position: Position): SignatureHelp? =
        provideSignatureHelpWithParen(document, position)?.first

    fun provideSignatureHelpWithParen(document: TextDocument, position: Position): Pair<SignatureHelp, Int>? {
        val linePrefix = document
            .lineAt(position)
            .text.jsSubstring(0, position.character)

        // Scan backwards for the innermost unmatched '(' on the current line.
        // Track paren depth so nested calls like ip("1.2.3.4").isInRange(ip("..."))
        // resolve to the outermost active call rather than a completed inner one.
        var parenDepth = 0
        var openParenIndex = -1
        for (i in linePrefix.length - 1 downTo 0) {
            val ch = linePrefix[i]
            if (ch == ')') {
                parenDepth++
            } else if (ch == '(') {
                if (parenDepth == 0) {
                    openParenIndex = i
                    break
                }
                parenDepth--
            }
        }

        if (openParenIndex == -1) {
            return null
        }

        // Extract the identifier immediately before '('
        val beforeParen = linePrefix.jsSubstring(0, openParenIndex)
        val wordMatch = beforeParen.jsMatch(WORD_BEFORE_PAREN_REGEX) ?: return null

        val funcName = wordMatch.groupValues[1]
        val help = FUNCTION_HELP_DEFINITIONS[funcName] ?: return null

        val signatureLabel = help[0]
        val description = help[1]

        val sigInfo = SignatureInformation(
            signatureLabel,
            MarkdownString(description),
        )

        // Use [startOffset, endOffset] into signatureLabel to highlight the
        // parameter type when the user is inside the parentheses.
        val parenStart = signatureLabel.indexOf('(')
        val parenEnd = signatureLabel.indexOf(')')
        val paramsStr = signatureLabel.jsSubstring(parenStart + 1, parenEnd).trim()
        if (paramsStr.isNotEmpty()) {
            sigInfo.parameters.clear()
            sigInfo.parameters.add(
                ParameterInformation(listOf(parenStart + 1, parenEnd)),
            )
        }

        val sigHelp = SignatureHelp()
        sigHelp.signatures.clear()
        sigHelp.signatures.add(sigInfo)
        sigHelp.activeSignature = 0
        sigHelp.activeParameter = 0

        return sigHelp to openParenIndex
    }
}
