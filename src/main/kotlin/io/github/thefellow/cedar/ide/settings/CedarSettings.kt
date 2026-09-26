// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.Topic

/** Upstream `contributes.configuration`: `cedar.schemaFile` and `cedar.autodetectSchemaFile`. */
class CedarSettingsState : BaseState() {
    var schemaFile by string("")
    var autodetectSchemaFile by property(true)
}

@Service(Service.Level.PROJECT)
@State(name = "CedarSettings", storages = [Storage("cedar.xml")])
class CedarSettings : SimplePersistentStateComponent<CedarSettingsState>(CedarSettingsState()) {
    /** Location of Cedar schema file used for policy validation (relative to the project root). */
    var schemaFile: String
        get() = state.schemaFile ?: ""
        set(value) { state.schemaFile = value }

    /** Auto detect Cedar schema file used for policy validation. */
    var autodetectSchemaFile: Boolean
        get() = state.autodetectSchemaFile
        set(value) { state.autodetectSchemaFile = value }

    companion object {
        fun getInstance(project: Project): CedarSettings = project.service()
    }
}

fun interface CedarSettingsListener {
    /** Upstream: `onDidChangeConfiguration` for `cedar.schemaFile`. */
    fun settingsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("Cedar settings", CedarSettingsListener::class.java)
    }
}

class CedarConfigurable(private val project: Project) : BoundConfigurable("Cedar") {
    override fun createPanel() = panel {
        val settings = CedarSettings.getInstance(project)
        row("Schema file:") {
            textField()
                .bindText(settings::schemaFile)
                .columns(COLUMNS_LARGE)
                .comment("Location of Cedar schema file used for policy validation (relative to the project root).")
        }
        row {
            checkBox("Auto detect Cedar schema file used for policy validation")
                .bindSelected(settings::autodetectSchemaFile)
        }
    }

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(CedarSettingsListener.TOPIC).settingsChanged()
    }
}
