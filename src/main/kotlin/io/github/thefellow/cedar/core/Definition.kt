// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/definition.ts. `vscode.Definition | null | undefined` becomes `Location?`.

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.Location
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Workspace

private fun findSchemaDefinition(
    schemaDoc: TextDocument,
    position: Position,
    referencedTypes: List<ReferencedRange>,
    actionIds: List<ReferencedRange> = emptyList(),
): Location? {
    // TODO: update from O(n^2) to something more efficient
    val schemaItem = parseCedarSchemaDoc(schemaDoc)
    val schemaDefinitionRanges = schemaItem.definitionRanges
    for (referencedType in referencedTypes) {
        if (referencedType.range.contains(position)) {
            for (schemaRange in schemaDefinitionRanges) {
                if (schemaRange.etype == referencedType.name) {
                    val loc = Location(schemaDoc.uri, schemaRange.range)
                    return loc
                }
            }
            // already matched position but didn't find definition, so return
            return null
        }
    }

    for (actionId in actionIds) {
        if (actionId.range.contains(position)) {
            for (schemaRange in schemaDefinitionRanges) {
                if (schemaRange.etype == actionId.name) {
                    val loc = Location(schemaDoc.uri, schemaRange.range)
                    return loc
                }
            }
            // already matched position but didn't find definition, so return
            return null
        }
    }
    return null
}

fun interface DefinitionProvider {
    fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location?
}

class CedarEntitiesDefinitionProvider : DefinitionProvider {
    override fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location? {
        val cedarEntitiesDoc = document
        val schemaDoc = getSchemaTextDocument(workspace, cedarEntitiesDoc)
        if (schemaDoc != null) {
            val referencedTypes =
                parseCedarEntitiesDoc(cedarEntitiesDoc).referencedTypes
            return findSchemaDefinition(schemaDoc, position, referencedTypes)
        }

        return null
    }
}

class CedarTemplateLinksDefinitionProvider : DefinitionProvider {
    override fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location? {
        val cedarTemplateLinksDoc = document
        val schemaDoc = getSchemaTextDocument(workspace, cedarTemplateLinksDoc)
        if (schemaDoc != null) {
            val referencedTypes = parseCedarTemplateLinksDoc(
                cedarTemplateLinksDoc,
            ).referencedTypes
            return findSchemaDefinition(schemaDoc, position, referencedTypes)
        }

        return null
    }
}

class CedarAuthDefinitionProvider : DefinitionProvider {
    override fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location? {
        val cedarAuthDoc = document
        val schemaDoc = getSchemaTextDocument(workspace, cedarAuthDoc)
        if (schemaDoc != null) {
            val authItem = parseCedarAuthDoc(cedarAuthDoc)
            val referencedTypes = authItem.referencedTypes
            val actionIds = authItem.actionIds
            return findSchemaDefinition(
                schemaDoc,
                position,
                referencedTypes,
                actionIds,
            )
        }

        return null
    }
}

class CedarJsonDefinitionProvider : DefinitionProvider {
    override fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location? {
        val cedarJsonDoc = document
        val schemaDoc = getSchemaTextDocument(workspace, cedarJsonDoc)
        if (schemaDoc != null) {
            val policyItem = parseCedarJsonPolicyDoc(cedarJsonDoc)
            val referencedTypes = policyItem.referencedTypes
            val actionIds = policyItem.actionIds
            return findSchemaDefinition(
                schemaDoc,
                position,
                referencedTypes,
                actionIds,
            )
        }

        return null
    }
}

class CedarSchemaDefinitionProvider : DefinitionProvider {
    override fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location? {
        val schemaDoc = document
        val schemaItem = parseCedarSchemaDoc(schemaDoc)
        val referencedTypes = schemaItem.referencedTypes
        val actionIds = schemaItem.actionIds
        return findSchemaDefinition(
            schemaDoc,
            position,
            referencedTypes,
            actionIds,
        )
    }
}

class CedarDefinitionProvider : DefinitionProvider {
    override fun provideDefinition(workspace: Workspace, document: TextDocument, position: Position): Location? {
        val cedarDoc = document
        val schemaDoc = getSchemaTextDocument(workspace, cedarDoc)
        if (schemaDoc != null) {
            val policyItem = parseCedarPoliciesDoc(cedarDoc)
            val referencedTypes = policyItem.referencedTypes
            val actionIds = policyItem.actionIds
            return findSchemaDefinition(
                schemaDoc,
                position,
                referencedTypes,
                actionIds,
            )
        }

        return null
    }
}
