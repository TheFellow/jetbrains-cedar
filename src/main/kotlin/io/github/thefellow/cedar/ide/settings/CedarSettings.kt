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
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ListUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import com.intellij.util.messages.Topic

/** Upstream `contributes.configuration`: `cedar.schemaFile` and `cedar.autodetectSchemaFile`. */
class CedarSettingsState : BaseState() {
    /** Upstream's single setting; read for settings saved before [schemaFiles] existed. */
    var schemaFile by string("")

    /** DIVERGENCE (semport/DIVERGENCES.md#multiple-schema-files): schema files and folders. */
    var schemaFiles by list<String>()
    var autodetectSchemaFile by property(true)
}

@Service(Service.Level.PROJECT)
@State(name = "CedarSettings", storages = [Storage("cedar.xml")])
class CedarSettings : SimplePersistentStateComponent<CedarSettingsState>(CedarSettingsState()) {
    /**
     * DIVERGENCE (semport/DIVERGENCES.md#multiple-schema-files): Cedar schema files and folders used for policy
     * validation (relative to the project root). More than one schema (or a folder) combines them all.
     */
    var schemaFiles: List<String>
        get() = state.schemaFiles.ifEmpty { listOfNotNull(state.schemaFile?.takeIf { it.isNotEmpty() }) }
        set(value) {
            state.schemaFiles = value.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            state.schemaFile = ""
        }

    /** Upstream `cedar.schemaFile`: the single configured schema file, or empty. */
    var schemaFile: String
        get() = schemaFiles.singleOrNull() ?: ""
        set(value) { schemaFiles = listOf(value) }

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
        val schemaList = SchemaFilesPanel(project)
        row {
            cell(schemaList.component)
                .align(Align.FILL)
                .label("Schema files:", LabelPosition.TOP)
                .comment(
                    "Cedar schema files, or folders of schema files, used for policy validation (relative to the " +
                        "project root). With more than one schema, all of them are combined and used for every policy.",
                )
                .onReset { schemaList.items = settings.schemaFiles }
                .onIsModified { schemaList.items != settings.schemaFiles }
                .onApply { settings.schemaFiles = schemaList.items }
        }.resizableRow()
        row {
            checkBox("Auto detect Cedar schema file used for policy validation")
                .bindSelected(settings::autodetectSchemaFile)
                .comment("Used when no schema is configured, or a single schema file is.")
        }
    }

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(CedarSettingsListener.TOPIC).settingsChanged()
    }
}

/** An editable list of schema paths: add (file or folder chooser), edit, remove, reorder. */
private class SchemaFilesPanel(private val project: Project) {
    private val model = CollectionListModel<String>()
    private val list = JBList(model).apply {
        emptyText.text = "No schema configured"
        visibleRowCount = 5
    }

    var items: List<String>
        get() = model.items.toList()
        set(value) { model.replaceAll(value) }

    val component: JComponent = ToolbarDecorator.createDecorator(list)
        .setAddAction { add() }
        .setEditAction { edit() }
        .setRemoveAction { ListUtil.removeSelectedItems(list) }
        .createPanel()

    private fun add() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true)
            .withTitle("Add Cedar Schema Files or Folders")
        val base = project.guessProjectDir()
        FileChooser.chooseFiles(descriptor, project, base) { files ->
            for (file in files) {
                val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
                if (path !in model.items) model.add(path)
            }
        }
    }

    private fun edit() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val value = Messages.showInputDialog(
            project,
            "Schema file or folder (relative to the project root):",
            "Edit Cedar Schema Path",
            null,
            model.getElementAt(index),
            null,
        )?.trim() ?: return
        if (value.isNotEmpty()) model.setElementAt(value, index)
    }
}
