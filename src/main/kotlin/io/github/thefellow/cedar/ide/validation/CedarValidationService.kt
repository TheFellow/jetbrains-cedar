// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.validation

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.problems.WolfTheProblemSolver
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
import io.github.thefellow.cedar.ide.adapters.uriOf
import io.github.thefellow.cedar.ide.adapters.virtualFileOf
import io.github.thefellow.cedar.ide.lang.CedarFileType
import io.github.thefellow.cedar.ide.lang.CedarLanguage
import io.github.thefellow.cedar.vscode.Diagnostic
import io.github.thefellow.cedar.vscode.DiagnosticCollection
import io.github.thefellow.cedar.vscode.DiagnosticSeverity
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
class CedarValidationService(private val project: Project) : Disposable {
    /**
     * For user-initiated commands: messages are always shown, as upstream does.
     */
    val userWorkspace: Workspace = IdeWorkspace.getInstance(project)

    /**
     * For validation on the highlighting daemon. Upstream shows messages on open/save; the daemon runs on every
     * edit, so each distinct message is shown once until settings change or problems are cleared.
     */
    val workspace: Workspace = DedupingWorkspace(userWorkspace)

    /** For completion, hover and navigation, which upstream runs without surfacing schema lookup errors twice. */
    val quietWorkspace: Workspace = QuietWorkspace(userWorkspace)

    /** Documents whose problems were cleared (`cedar.clearproblems`), by uri, at their version when cleared. */
    private val cleared = ConcurrentHashMap<Uri, Long>()

    val diagnosticCollection: DiagnosticCollection = createDiagnosticCollection().also { collection ->
        collection.onChange = { uris -> onDiagnosticsChanged(uris) }
    }

    private val annotating = ThreadLocal<Uri?>()
    private val lastSignature = ConcurrentHashMap<Uri, List<String>>()

    init {
        installRevalidateHook()
        // upstream re-validates on save: a save ends the "cleared" state
        project.messageBus.connect(this).subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                if (cleared.remove(uriOf(file)) != null) restartHighlighting(project, uriOf(file))
            }
        })
    }

    override fun dispose() {}

    /** Forget which messages were already shown by the daemon. */
    fun resetMessages() = (workspace as DedupingWorkspace).reset()

    private fun onDiagnosticsChanged(uris: Collection<Uri>) {
        reportProblemFiles(uris)
        for (uri in uris) {
            val signature = diagnosticCollection.get(uri)?.map { "${it.severity}|${it.range}|${it.code}|${it.message}" }
            val previous = if (signature == null) lastSignature.remove(uri) else lastSignature.put(uri, signature)
            if (previous == signature) continue
            // the file being annotated renders its own result when its pass completes
            if (annotating.get() == uri) continue
            restartHighlighting(project, uri)
        }
    }

    /**
     * Upstream publishes diagnostics to the Problems view even for files that aren't open (e.g. an invalid schema
     * found while validating a policy). Mark such files as problem files (red in the Project view, listed under
     * Problems | Project Errors).
     */
    private fun reportProblemFiles(uris: Collection<Uri>) {
        if (project.isDisposed) return
        val updates = uris.mapNotNull { uri ->
            virtualFileOf(uri)?.let { it to (diagnosticCollection.get(uri)?.any { d -> d.severity == DiagnosticSeverity.Error } == true) }
        }
        if (updates.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            val wolf = WolfTheProblemSolver.getInstance(project)
            for ((file, hasErrors) in updates) {
                if (!file.isValid) continue
                if (hasErrors) wolf.reportProblemsFromExternalSource(file, this) else wolf.clearProblemsFromExternalSource(file, this)
            }
        }, project.disposed)
    }

    /** Runs upstream `validateTextDocument` for [doc] and returns the diagnostics to render for it. */
    fun annotate(doc: TextDocument): List<Diagnostic> {
        val clearedAt = cleared[doc.uri]
        if (clearedAt != null) {
            if (clearedAt == doc.version) return emptyList()
            cleared.remove(doc.uri) // edited since: validate again
        }
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

    fun validateCedarDoc(doc: TextDocument, userInitiated: Boolean = false): Boolean {
        if (userInitiated) cleared.remove(doc.uri)
        return coreValidateCedarDoc(workspaceFor(userInitiated), doc, diagnosticCollection, userInitiated)
    }

    private fun workspaceFor(userInitiated: Boolean) = if (userInitiated) userWorkspace else workspace

    /** `cedar.schemavalidate` */
    fun validateSchemaDoc(file: VirtualFile, userInitiated: Boolean = true): Boolean =
        IdeTextDocument.of(file)?.let { validateSchemaDoc(it, userInitiated) } ?: false

    fun validateSchemaDoc(doc: TextDocument, userInitiated: Boolean = false): Boolean {
        if (userInitiated) cleared.remove(doc.uri)
        return coreValidateSchemaDoc(workspaceFor(userInitiated), doc, diagnosticCollection, userInitiated)
    }

    /** `cedar.entitiesvalidate` */
    fun validateEntitiesDoc(file: VirtualFile, userInitiated: Boolean = true): Boolean =
        IdeTextDocument.of(file)?.let { validateEntitiesDoc(it, userInitiated) } ?: false

    fun validateEntitiesDoc(doc: TextDocument, userInitiated: Boolean = false): Boolean {
        if (userInitiated) cleared.remove(doc.uri)
        return coreValidateEntitiesDoc(workspaceFor(userInitiated), doc, diagnosticCollection, userInitiated)
    }

    /** `cedar.clearproblems` */
    fun clearProblems() {
        // upstream: problems stay cleared until the document is next validated (on open/save); the daemon would
        // re-validate immediately, so remember each open document's version and skip it until edited or saved
        for (file in FileEditorManager.getInstance(project).openFiles) {
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: continue
            cleared[uriOf(file)] = document.modificationStamp
        }
        diagnosticCollection.forEach { uri, _ ->
            val file = virtualFileOf(uri) ?: return@forEach
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@forEach
            cleared[uri] = document.modificationStamp
        }
        resetMessages()
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
            // test fixtures forbid daemon restarts while highlighting; restarts only refresh other open files
            if (ApplicationManager.getApplication().isUnitTestMode) return
            val file = virtualFileOf(uri) ?: return
            ApplicationManager.getApplication().invokeLater({
                val psiFile = ReadAction.computeBlocking<PsiFile?, RuntimeException> {
                    if (file.isValid) PsiManager.getInstance(project).findFile(file) else null
                } ?: return@invokeLater
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "Cedar diagnostics changed")
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

/** Shows each distinct error/info message once, until [reset]. */
private class DedupingWorkspace(private val delegate: Workspace) : Workspace by delegate {
    private val shown = ConcurrentHashMap.newKeySet<String>()

    fun reset() = shown.clear()

    override fun showErrorMessage(message: String) { if (shown.add(message)) delegate.showErrorMessage(message) }
    override fun showInformationMessage(message: String) { if (shown.add(message)) delegate.showInformationMessage(message) }
}

/** Never shows messages (lookups done for completion, hover and navigation). */
private class QuietWorkspace(delegate: Workspace) : Workspace by delegate {
    override fun showErrorMessage(message: String) {}
    override fun showInformationMessage(message: String) {}
}
