// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// The commands registered in upstream src/extension.ts (package.json contributes.commands/menus).
// Visibility follows the upstream `when` clauses; ids match upstream command ids.

package io.github.thefellow.cedar.ide.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.github.thefellow.cedar.core.detectEntitiesDoc
import io.github.thefellow.cedar.core.getSchemaUri
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.adapters.languageIdOf
import io.github.thefellow.cedar.ide.adapters.virtualFileOf
import io.github.thefellow.cedar.wasm.Cedar
import java.awt.datatransfer.StringSelection

/** Context an upstream `when` clause can test. */
class CedarActionContext(val project: Project, val editor: Editor?, val file: VirtualFile) {
    val languageId: String = languageIdOf(file)
    val fileName: String = file.name
    val isFileScheme: Boolean = file.isInLocalFileSystem

    val isCedar get() = languageId == "cedar"
    val isCedarSchema get() = languageId == "cedarschema"

    /** `resourceFilename == cedarschema.json || resourceFilename =~ /\.cedarschema\.json$/` */
    val isSchemaJson get() = fileName == "cedarschema.json" || fileName.endsWith(".cedarschema.json")

    /** `resourceFilename == cedarentities.json || resourceFilename =~ /\.cedarentities\.json$/` */
    val isEntitiesJson get() = fileName == "cedarentities.json" || fileName.endsWith(".cedarentities.json")

    fun document(): IdeTextDocument? = editor?.let { IdeTextDocument.of(it.document) } ?: IdeTextDocument.of(file)

    val workspace: IdeWorkspace get() = IdeWorkspace.getInstance(project)

    companion object {
        fun of(e: AnActionEvent): CedarActionContext? {
            val project = e.project ?: return null
            val editor = e.getData(CommonDataKeys.EDITOR)
            val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
                ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
                ?: return null
            return CedarActionContext(project, editor, file)
        }
    }
}

abstract class CedarAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** The upstream `when` clause; null context (no file) disables file-bound commands. */
    protected open fun isApplicable(ctx: CedarActionContext): Boolean = true

    protected open val requiresFile: Boolean = true

    override fun update(e: AnActionEvent) {
        val ctx = CedarActionContext.of(e)
        e.presentation.isEnabledAndVisible = if (ctx == null) !requiresFile && e.project != null else isApplicable(ctx)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = CedarActionContext.of(e)
        if (ctx == null) {
            if (!requiresFile) perform(e, null)
            return
        }
        perform(e, ctx)
    }

    abstract fun perform(e: AnActionEvent, ctx: CedarActionContext?)
}

/** cedar.about */
class AboutAction : CedarAction() {
    override val requiresFile = false
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = true }

    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        val plugin = java.util.Properties().apply {
            AboutAction::class.java.getResourceAsStream("/cedar/plugin.properties")?.use { load(it) }
        }
        val app = ApplicationInfo.getInstance()
        val sdkVersion = e.project?.let { withCedarProgress(it, "Loading Cedar SDK") { Cedar.getCedarSDKVersion() } }
            ?: Cedar.getCedarSDKVersion()
        val extensionDetails =
            "${plugin.getProperty("id", "io.github.thefellow.cedar")}: ${plugin.getProperty("version", "?")}\n" +
                "Cedar SDK: $sdkVersion\n" +
                "${app.fullApplicationName}: ${app.fullVersion} (${app.build.asString()})\n" +
                "Java: ${System.getProperty("java.runtime.version")}\n"
        val result = Messages.showDialog(e.project, extensionDetails, "About Cedar", arrayOf("OK", "Copy"), 0, null)
        if (result == 1) {
            CopyPasteManager.getInstance().setContents(StringSelection(extensionDetails))
        }
    }
}

/** cedar.documentation */
class DocumentationAction : CedarAction() {
    override val requiresFile = false
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = true }
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) = BrowserUtil.browse("https://docs.cedarpolicy.com/")
}

/** cedar.activate: the plugin is always active in JetBrains IDEs; kept for command parity. */
class ActivateAction : CedarAction() {
    override val requiresFile = false
    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        val project = e.project ?: return
        IdeWorkspace.getInstance(project).showInformationMessage("Cedar extension activated")
    }
}

/** cedar.schemaopen: `editorLangId == cedar || entities json` */
class SchemaOpenAction : CedarAction() {
    override fun isApplicable(ctx: CedarActionContext) = ctx.isCedar || ctx.isEntitiesJson

    override fun perform(e: AnActionEvent, ctx: CedarActionContext?) {
        ctx ?: return
        val doc = ctx.document() ?: return
        val fileUri = getSchemaUri(ctx.workspace, doc)
        if (fileUri != null) {
            val schemaFile = virtualFileOf(fileUri)
            if (schemaFile != null) {
                OpenFileDescriptor(ctx.project, schemaFile).navigate(true)
            } else {
                ctx.workspace.showErrorMessage("Cannot open Cedar schema file: $fileUri")
            }
        } else {
            ctx.workspace.showErrorMessage("Cedar schema file not found or configured in settings.json")
        }
    }
}

/** Helper used by upstream `detectEntitiesDoc`-style checks from actions. */
internal fun CedarActionContext.isEntitiesDoc() = document()?.let { detectEntitiesDoc(it) } == true
