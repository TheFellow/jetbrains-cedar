// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/documentsymbols.ts.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.DocumentSymbol
import io.github.thefellow.cedar.vscode.FoldingRange
import io.github.thefellow.cedar.vscode.FoldingRangeKind
import io.github.thefellow.cedar.vscode.SymbolKind
import io.github.thefellow.cedar.vscode.TextDocument

fun interface DocumentSymbolProvider {
    fun provideDocumentSymbols(document: TextDocument): List<DocumentSymbol>
}

class CedarDocumentSymbolProvider : DocumentSymbolProvider {
    override fun provideDocumentSymbols(document: TextDocument): List<DocumentSymbol> {
        return try {
            val symbols = mutableListOf<DocumentSymbol>()
            val policyRanges = parseCedarPoliciesDoc(document).policies
            policyRanges.forEach { policyRange ->
                symbols.add(
                    DocumentSymbol(
                        policyRange.id,
                        "",
                        SymbolKind.Function,
                        policyRange.range,
                        policyRange.effectRange,
                    ),
                )
            }

            symbols
        } catch (error: Exception) {
            emptyList()
        }
    }
}

class CedarFoldingRangeProvider {
    fun provideFoldingRanges(document: TextDocument): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()
        val policyRanges = parseCedarPoliciesDoc(document).policies
        policyRanges.forEach { policyRange ->
            ranges.add(
                FoldingRange(
                    policyRange.range.start.line,
                    policyRange.range.end.line,
                    FoldingRangeKind.Region,
                ),
            )

            if (policyRange.effectRange.start.line > policyRange.range.start.line) {
                ranges.add(
                    FoldingRange(
                        policyRange.effectRange.start.line,
                        policyRange.range.end.line,
                    ),
                )
            }
        }

        return ranges
    }
}

class CedarEntitiesDocumentSymbolProvider : DocumentSymbolProvider {
    override fun provideDocumentSymbols(document: TextDocument): List<DocumentSymbol> {
        return try {
            val symbols = mutableListOf<DocumentSymbol>()

            val entityRanges = parseCedarEntitiesDoc(document).entities
            entityRanges.forEach { entityRange ->
                val symbol = DocumentSymbol(
                    entityRange.uid,
                    "",
                    SymbolKind.Object,
                    entityRange.range,
                    entityRange.uidKeyRange,
                )
                if (entityRange.attrsRange != null && entityRange.attrsKeyRange != null) {
                    symbol.children.add(
                        DocumentSymbol(
                            "attrs",
                            "",
                            SymbolKind.Object,
                            entityRange.attrsRange,
                            entityRange.attrsKeyRange,
                        ),
                    )
                }
                if (entityRange.parentsRange != null && entityRange.parentsKeyRange != null) {
                    symbol.children.add(
                        DocumentSymbol(
                            "parents",
                            "",
                            SymbolKind.Array,
                            entityRange.parentsRange,
                            entityRange.parentsKeyRange,
                        ),
                    )
                }
                if (entityRange.tagsRange != null && entityRange.tagsKeyRange != null) {
                    symbol.children.add(
                        DocumentSymbol(
                            "tags",
                            "",
                            SymbolKind.Object,
                            entityRange.tagsRange,
                            entityRange.tagsKeyRange,
                        ),
                    )
                }
                symbols.add(symbol)
            }

            symbols
        } catch (error: Exception) {
            emptyList()
        }
    }
}

class CedarTemplateLinksDocumentSymbolProvider : DocumentSymbolProvider {
    override fun provideDocumentSymbols(document: TextDocument): List<DocumentSymbol> {
        val cedarTemplateLinksDoc = document
        return try {
            val symbols = mutableListOf<DocumentSymbol>()

            val templateLinkRanges = parseCedarTemplateLinksDoc(
                cedarTemplateLinksDoc,
            ).links
            templateLinkRanges.forEach { templateLinkRange ->
                symbols.add(
                    DocumentSymbol(
                        templateLinkRange.id,
                        "",
                        SymbolKind.Object,
                        templateLinkRange.range,
                        templateLinkRange.linkIdRange,
                    ),
                )
            }

            symbols
        } catch (error: Exception) {
            emptyList()
        }
    }
}

class CedarSchemaDocumentSymbolProvider : DocumentSymbolProvider {
    override fun provideDocumentSymbols(document: TextDocument): List<DocumentSymbol> {
        val cedarSchemaDoc = document
        return try {
            val symbols = mutableListOf<DocumentSymbol>()

            val definitionRanges =
                parseCedarSchemaDoc(cedarSchemaDoc).definitionRanges
            definitionRanges.forEach { entityRange ->
                symbols.add(
                    DocumentSymbol(
                        entityRange.etype,
                        "",
                        entityRange.symbol,
                        entityRange.range,
                        entityRange.etypeRange,
                    ),
                )
            }

            symbols
        } catch (error: Exception) {
            emptyList()
        }
    }
}
