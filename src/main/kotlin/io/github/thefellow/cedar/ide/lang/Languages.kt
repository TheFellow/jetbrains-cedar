// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Language ids match the VS Code language ids (`cedar`, `cedarschema`), also used for markdown fences. */
object CedarLanguage : Language("cedar") {
    private fun readResolve(): Any = CedarLanguage
    override fun getDisplayName() = "Cedar"
}

object CedarSchemaLanguage : Language("cedarschema") {
    private fun readResolve(): Any = CedarSchemaLanguage
    override fun getDisplayName() = "Cedar Schema"
}

object CedarIcons {
    @JvmField val CEDAR: Icon = IconLoader.getIcon("/icons/Cedar_16.svg", CedarIcons::class.java)
    @JvmField val CEDAR_SCHEMA: Icon = IconLoader.getIcon("/icons/CedarSchema_16.svg", CedarIcons::class.java)
}

object CedarFileType : LanguageFileType(CedarLanguage) {
    override fun getName() = "Cedar"
    override fun getDescription() = "Cedar policy"
    override fun getDefaultExtension() = "cedar"
    override fun getIcon(): Icon = CedarIcons.CEDAR
}

object CedarSchemaFileType : LanguageFileType(CedarSchemaLanguage) {
    override fun getName() = "Cedar Schema"
    override fun getDescription() = "Cedar schema"
    override fun getDefaultExtension() = "cedarschema"
    override fun getIcon(): Icon = CedarIcons.CEDAR_SCHEMA
}
