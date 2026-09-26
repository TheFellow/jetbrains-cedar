// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.openapi.project.Project
import io.github.thefellow.cedar.ide.validation.CedarValidationService
import io.github.thefellow.cedar.vscode.TextDocument

/**
 * Runs [block] (Cedar SDK work) off the EDT: under a modal progress when called on the EDT, directly otherwise.
 * The wasm module is shared and its calls are serialized, so the EDT must never wait on it.
 */
fun <T> withCedarProgress(project: Project, title: String, block: () -> T): T =
    if (ApplicationManager.getApplication().isDispatchThread) {
        runWithModalProgressBlocking(project, title) { block() }
    } else {
        block()
    }

/** Connects the commands to validation (ide.validation) and entities completion (ide.completion). */
object ValidationBridge {
    /** validateSchemaDoc(schemaDoc, diagnosticCollection, true) */
    fun validateSchema(project: Project, schemaDoc: TextDocument): Boolean = withCedarProgress(project, "Validating Cedar schema") {
        CedarValidationService.getInstance(project).validateSchemaDoc(schemaDoc, userInitiated = true)
    }

    /** validateCedarDoc(doc, diagnosticCollection, true) */
    fun validateCedar(project: Project, cedarDoc: TextDocument) {
        withCedarProgress(project, "Validating Cedar policy") {
            CedarValidationService.getInstance(project).validateCedarDoc(cedarDoc, userInitiated = true)
        }
    }

    /** validateEntitiesDoc(doc, diagnosticCollection, true) */
    fun validateEntities(project: Project, entitiesDoc: TextDocument) {
        withCedarProgress(project, "Validating Cedar entities") {
            CedarValidationService.getInstance(project).validateEntitiesDoc(entitiesDoc, userInitiated = true)
        }
    }

    /** diagnosticCollection.clear(); clearValidationCache() */
    fun clearProblems(project: Project) = CedarValidationService.getInstance(project).clearProblems()

    /** addEntitiesJSON(textEditor, diagnosticCollection) */
    fun addEntities(project: Project, editor: Editor) {
        io.github.thefellow.cedar.ide.completion.addEntitiesJson(
            project, editor, CedarValidationService.getInstance(project).diagnosticCollection,
        )
    }
}
