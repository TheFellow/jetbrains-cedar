// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.validation

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.thefellow.cedar.core.createDiagnosticCollection
import io.github.thefellow.cedar.core.clearValidationCache
import io.github.thefellow.cedar.core.validationCache
import io.github.thefellow.cedar.ide.adapters.IdeTextDocument
import io.github.thefellow.cedar.ide.adapters.IdeWorkspace
import io.github.thefellow.cedar.ide.adapters.virtualFileOf
import io.github.thefellow.cedar.ide.lang.CedarFileType
import io.github.thefellow.cedar.ide.lang.CedarLanguage
import io.github.thefellow.cedar.vscode.Diagnostic
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri
import io.github.thefellow.cedar.vscode.Workspace
import java.util.concurrent.ConcurrentHashMap
import io.github.thefellow.cedar.core.formatCedarDoc as coreFormatCedarDoc
import io.github.thefellow.cedar.core.validateCedarDoc as coreValidateCedarDoc
import io.github.thefellow.cedar.core.validateEntitiesDoc as coreValidateEntitiesDoc
import io.github.thefellow.cedar.core.validateSchemaDoc as coreValidateSchemaDoc
import io.github.thefellow.cedar.core.validateTextDocument as coreValidateTextDocument

/**
 * Owns upstream's `diagnosticCollection` (extension.ts `createDiagnosticCollection()`) for a project and
 * runs upstream's validate.ts against it. The [CedarValidationAnnotator] renders the collection; changes to
 * other files' diagnostics (a schema validated while validating a policy, a schema re-validation that drops
 * dependent results) restart highlighting of those files.
 */
@Service(Service.Level.PROJECT)
class CedarValidationService(private val project: Project) {
    /** VS Code collapses repeated identical notifications; the daemon re-runs often, so do the same. */
    val workspace: Workspace = DedupingWorkspace(IdeWorkspace.getInstance(project))

    val diagnosticCollection: DiagnosticCollection = createDiagnosticCollection().also { collection ->
        collection.onChange = { uris -> onDiagnosticsChanged(uris) }
    }

    private val annotating = ThreadLocal<Uri?>()
    private val lastSignature = ConcurrentHashMap<Uri, List<String>>()

    init {
        installRevalidateHook()
    }

    private fun onDiagnosticsChanged(uris: Collection<Uri>) {
        for (uri in uris) {
            val signature = diagnosticCollection.get(uri)?.map { "${it.severity}|${it.range}|${it.code}|${it.message}" }
            val previous = if (signature == null) lastSignature.remove(uri) else lastSignature.put(uri, signature)
            if (previous == signature) continue
            // the file being annotated renders its own result when its pass completes
            if (annotating.get() == uri) continue
            restartHighlighting(project, uri)
        }
    }

    /** Runs upstream `validateTextDocument` for [doc] and returns the diagnostics to render for it. */
    fun annotate(doc: TextDocument): List<Diagnostic> {
        annotating.set(doc.uri)
        try {
            coreValidateTextDocument(workspace, doc, diagnosticCollection)
        } finally {
            annotating.remove()
        }
        return diagnosticCollection.get(doc.uri).orEmpty()
    }

    fun diagnostics(file: VirtualFile): List<Diagnostic> =
        IdeTextDocument.of(file)?.let { diagnosticCollection.get(it.uri) }.orEmpty()

    fun validateTextDocument(file: VirtualFile) {
        IdeTextDocument.of(file)?.let { coreValidateTextDocument(workspace, it, diagnosticCollection) }
    }

    /** `cedar.validate` */
    fun validateCedarDoc(file: VirtualFile, userInitiated: Boolean = true): Boolean =
        IdeTextDocument.of(file)?.let { validateCedarDoc(it, userInitiated) } ?: false

    fun validateCedarDoc(doc: TextDocument, userInitiated: Boolean = false): Boolean =
        coreValidateCedarDoc(workspace, doc, diagnosticCollection, userInitiated)

    /** `cedar.schemavalidate` */
    fun validateSchemaDoc(file: VirtualFile, userInitiated: Boolean = true): Boolean =
        IdeTextDocument.of(file)?.let { validateSchemaDoc(it, userInitiated) } ?: false

    fun validateSchemaDoc(doc: TextDocument, userInitiated: Boolean = false): Boolean =
        coreValidateSchemaDoc(workspace, doc, diagnosticCollection, userInitiated)

    /** `cedar.entitiesvalidate` */
    fun validateEntitiesDoc(file: VirtualFile, userInitiated: Boolean = true): Boolean =
        IdeTextDocument.of(file)?.let { validateEntitiesDoc(it, userInitiated) } ?: false

    fun validateEntitiesDoc(doc: TextDocument, userInitiated: Boolean = false): Boolean =
        coreValidateEntitiesDoc(workspace, doc, diagnosticCollection, userInitiated)

    /** `cedar.clearproblems` */
    fun clearProblems() {
        diagnosticCollection.clear()
        clearValidationCache()
    }

    /** format.ts `formatCedarDoc`, with the Cedar code style's indent size and right margin. */
    fun formatCedarDoc(doc: TextDocument, psiFile: PsiFile? = null): String? {
        val settings = if (psiFile != null) CodeStyle.getSettings(psiFile) else CodeStyle.getSettings(project)
        val tabSize = settings.getIndentOptions(CedarFileType).INDENT_SIZE
        val wordWrapColumn = settings.getRightMargin(CedarLanguage)
        return coreFormatCedarDoc(doc, tabSize, wordWrapColumn)
    }

    companion object {
        fun getInstance(project: Project): CedarValidationService = project.service()

        /** Restarts highlighting of [uri]'s file in [project] if it is open. */
        fun restartHighlighting(project: Project, uri: Uri) {
            val file = virtualFileOf(uri) ?: return
            ApplicationManager.getApplication().invokeLater({
                val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
                    if (file.isValid) PsiManager.getInstance(project).findFile(file) else null
                } ?: return@invokeLater
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            }, project.disposed)
        }

        @Volatile
        private var hookInstalled = false

        /**
         * validate.ts `revalidateSchema` re-validates dependent documents; here they are re-validated by
         * restarting their highlighting (their cached results were already dropped).
         */
        private fun installRevalidateHook() {
            if (hookInstalled) return
            hookInstalled = true
            validationCache.revalidate = { _, uris, _ ->
                for (project in ProjectManager.getInstance().openProjects) {
                    if (project.isDisposed) continue
                    uris.forEach { restartHighlighting(project, it) }
                }
            }
        }
    }
}

/** Suppresses an identical error/info message shown again within a short window. */
private class DedupingWorkspace(private val delegate: Workspace) : Workspace by delegate {
    private val shown = ConcurrentHashMap<String, Long>()

    private fun once(message: String, show: () -> Unit) {
        val now = System.currentTimeMillis()
        val last = shown[message]
        if (last != null && now - last < WINDOW_MS) return
        shown[message] = now
        show()
    }

    override fun showErrorMessage(message: String) = once(message) { delegate.showErrorMessage(message) }
    override fun showInformationMessage(message: String) = once(message) { delegate.showInformationMessage(message) }

    companion object {
        const val WINDOW_MS = 30_000L
    }
}
