// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.navigation

import com.intellij.openapi.vfs.VirtualFile
import io.github.thefellow.cedar.core.CedarAuthDefinitionProvider
import io.github.thefellow.cedar.core.CedarDefinitionProvider
import io.github.thefellow.cedar.core.CedarDocumentSymbolProvider
import io.github.thefellow.cedar.core.CedarEntitiesDefinitionProvider
import io.github.thefellow.cedar.core.CedarEntitiesDocumentSymbolProvider
import io.github.thefellow.cedar.core.CedarJsonDefinitionProvider
import io.github.thefellow.cedar.core.CedarSchemaDefinitionProvider
import io.github.thefellow.cedar.core.CedarSchemaDocumentSymbolProvider
import io.github.thefellow.cedar.core.CedarTemplateLinksDefinitionProvider
import io.github.thefellow.cedar.core.CedarTemplateLinksDocumentSymbolProvider
import io.github.thefellow.cedar.core.DefinitionProvider
import io.github.thefellow.cedar.core.DocumentSemanticTokensProvider
import io.github.thefellow.cedar.core.DocumentSymbolProvider
import io.github.thefellow.cedar.core.authTokensProvider
import io.github.thefellow.cedar.core.cedarJsonTokensProvider
import io.github.thefellow.cedar.core.cedarTokensProvider
import io.github.thefellow.cedar.core.entitiesTokensProvider
import io.github.thefellow.cedar.core.isCedarAuthFile
import io.github.thefellow.cedar.core.isCedarEntitiesFile
import io.github.thefellow.cedar.core.isCedarJsonFile
import io.github.thefellow.cedar.core.isCedarSchemaJsonFile
import io.github.thefellow.cedar.core.isCedarTemplateLinksFile
import io.github.thefellow.cedar.core.schemaTokensProvider
import io.github.thefellow.cedar.core.templateLinksTokensProvider
import io.github.thefellow.cedar.ide.adapters.languageIdOf

/**
 * The document selectors upstream's extension.ts registers providers with (`{ language: 'cedar' }`,
 * `{ language: 'json', pattern: CEDAR_SCHEMA_GLOB }`, ...), resolved per file.
 */
object CedarProviders {
    private enum class Kind { CEDAR, SCHEMA, ENTITIES, TEMPLATE_LINKS, AUTH, CEDAR_JSON }

    private fun kindOf(file: VirtualFile): Kind? {
        val languageId = languageIdOf(file)
        val name = file.name
        return when {
            languageId == "cedar" -> Kind.CEDAR
            languageId == "cedarschema" -> Kind.SCHEMA
            languageId != "json" -> null
            isCedarSchemaJsonFile(name) -> Kind.SCHEMA
            isCedarEntitiesFile(name) -> Kind.ENTITIES
            isCedarTemplateLinksFile(name) -> Kind.TEMPLATE_LINKS
            isCedarAuthFile(name) -> Kind.AUTH
            isCedarJsonFile(name) -> Kind.CEDAR_JSON
            else -> null
        }
    }

    fun isCedarFile(file: VirtualFile) = kindOf(file) != null

    /** registerDocumentSymbolProvider: cedar, cedarschema / schema json, entities json, template links json. */
    fun symbolProvider(file: VirtualFile): DocumentSymbolProvider? = when (kindOf(file)) {
        Kind.CEDAR -> CedarDocumentSymbolProvider()
        Kind.SCHEMA -> CedarSchemaDocumentSymbolProvider()
        Kind.ENTITIES -> CedarEntitiesDocumentSymbolProvider()
        Kind.TEMPLATE_LINKS -> CedarTemplateLinksDocumentSymbolProvider()
        else -> null
    }

    /** registerDefinitionProvider for every selector. */
    fun definitionProvider(file: VirtualFile): DefinitionProvider? = when (kindOf(file)) {
        Kind.CEDAR -> CedarDefinitionProvider()
        Kind.SCHEMA -> CedarSchemaDefinitionProvider()
        Kind.ENTITIES -> CedarEntitiesDefinitionProvider()
        Kind.TEMPLATE_LINKS -> CedarTemplateLinksDefinitionProvider()
        Kind.AUTH -> CedarAuthDefinitionProvider()
        Kind.CEDAR_JSON -> CedarJsonDefinitionProvider()
        null -> null
    }

    /** registerDocumentSemanticTokensProvider for every selector. */
    fun tokensProvider(file: VirtualFile): DocumentSemanticTokensProvider? = when (kindOf(file)) {
        Kind.CEDAR -> cedarTokensProvider
        Kind.SCHEMA -> schemaTokensProvider
        Kind.ENTITIES -> entitiesTokensProvider
        Kind.TEMPLATE_LINKS -> templateLinksTokensProvider
        Kind.AUTH -> authTokensProvider
        Kind.CEDAR_JSON -> cedarJsonTokensProvider
        null -> null
    }
}
