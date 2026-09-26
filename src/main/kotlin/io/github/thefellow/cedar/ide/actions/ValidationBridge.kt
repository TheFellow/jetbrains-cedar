// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.actions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import io.github.thefellow.cedar.vscode.TextDocument

/** Connects the commands to validation (ide.validation) and entities completion (ide.completion). */
object ValidationBridge {
    /** validateSchemaDoc(schemaDoc, diagnosticCollection, true) */
    fun validateSchema(project: Project, schemaDoc: TextDocument): Boolean = TODO("wired at merge")

    /** validateCedarDoc(doc, diagnosticCollection, true) */
    fun validateCedar(project: Project, cedarDoc: TextDocument): Unit = TODO("wired at merge")

    /** validateEntitiesDoc(doc, diagnosticCollection, true) */
    fun validateEntities(project: Project, entitiesDoc: TextDocument): Unit = TODO("wired at merge")

    /** diagnosticCollection.clear(); clearValidationCache() */
    fun clearProblems(project: Project): Unit = TODO("wired at merge")

    /** addEntitiesJSON(textEditor, diagnosticCollection) */
    fun addEntities(project: Project, editor: Editor): Unit = TODO("wired at merge")
}
