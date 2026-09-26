// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.adapters

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.thefellow.cedar.ide.lang.CedarFileType
import io.github.thefellow.cedar.ide.lang.CedarSchemaFileType
import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range
import io.github.thefellow.cedar.vscode.StringTextDocument
import io.github.thefellow.cedar.vscode.TextDocument
import io.github.thefellow.cedar.vscode.Uri

/** VS Code language id for a file: `cedar`, `cedarschema`, `json`, or the file type name. */
fun languageIdOf(file: VirtualFile): String = when {
    file.fileType == CedarFileType -> "cedar"
    file.fileType == CedarSchemaFileType -> "cedarschema"
    file.name.endsWith(".json") -> "json"
    else -> file.fileType.name.lowercase()
}

fun uriOf(file: VirtualFile): Uri =
    if (file.isInLocalFileSystem) Uri.file(file.path) else Uri(file.fileSystem.protocol, file.path)

fun virtualFileOf(uri: Uri): VirtualFile? =
    if (uri.scheme == "file") LocalFileSystem.getInstance().findFileByPath(uri.path) else null

/** A snapshot [TextDocument] of an IntelliJ [Document]; version is the modification stamp. */
class IdeTextDocument(
    val document: Document,
    val file: VirtualFile,
    text: CharSequence = document.immutableCharSequence,
    languageId: String = languageIdOf(file),
) : StringTextDocument(text.toString(), uriOf(file), languageId, document.modificationStamp) {
    companion object {
        fun of(file: VirtualFile): IdeTextDocument? = ReadAction.computeBlocking<IdeTextDocument?, RuntimeException> {
            if (!file.isValid) return@computeBlocking null
            FileDocumentManager.getInstance().getDocument(file)?.let { IdeTextDocument(it, file) }
        }

        fun of(psiFile: PsiFile): IdeTextDocument? {
            val file = psiFile.originalFile.virtualFile ?: psiFile.viewProvider.virtualFile
            return ReadAction.computeBlocking<IdeTextDocument?, RuntimeException> {
                val document = psiFile.viewProvider.document ?: return@computeBlocking null
                IdeTextDocument(document, file)
            }
        }

        fun of(document: Document): IdeTextDocument? {
            val file = FileDocumentManager.getInstance().getFile(document) ?: return null
            return IdeTextDocument(document, file)
        }
    }
}

/* Range conversions (VS Code line/character <-> IntelliJ offsets). */

fun Position.toOffset(document: Document): Int {
    if (document.lineCount == 0) return 0
    val line = line.coerceIn(0, document.lineCount - 1)
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    return (start + character).coerceIn(start, end)
}

fun Range.toTextRange(document: Document): TextRange = TextRange(start.toOffset(document), end.toOffset(document))

fun Int.toPosition(document: Document): Position {
    val offset = coerceIn(0, document.textLength)
    val line = document.getLineNumber(offset)
    return Position(line, offset - document.getLineStartOffset(line))
}

fun TextRange.toRange(document: Document): Range = Range(startOffset.toPosition(document), endOffset.toPosition(document))

fun TextDocument.textRange(range: Range): TextRange = TextRange(offsetAt(range.start), offsetAt(range.end))
