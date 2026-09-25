// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.vscode

/* Language feature value types (completion, hover, symbols, code actions, ...). */

class SnippetString(var value: String = "") {
    private var tabstop = 1

    fun appendText(text: String) = apply { value += text.replace(Regex("""[$}\\]"""), """\\$0""") }
    fun appendTabstop(number: Int = tabstop++) = apply { value += "$$number" }
    fun appendPlaceholder(text: String, number: Int = tabstop++) =
        apply { value += "\${$number:${text.replace(Regex("""[$}\\]"""), """\\$0""")}}" }
    fun appendChoice(values: List<String>, number: Int = tabstop++) =
        apply { value += "\${$number|${values.joinToString(",")}|}" }

    override fun toString() = value
}

class MarkdownString(var value: String = "", var supportHtml: Boolean = false) {
    var isTrusted: Boolean = false

    fun appendText(text: String) = apply { value += text.replace(Regex("""[\\`*_{}\[\]()#+\-.!]"""), """\\$0""") }
    fun appendMarkdown(markdown: String) = apply { value += markdown }
    fun appendCodeblock(code: String, language: String = "") = apply { value += "\n```$language\n$code\n```\n" }

    override fun toString() = value
}

enum class CompletionItemKind {
    Text, Method, Function, Constructor, Field, Variable, Class, Interface, Module, Property, Unit, Value,
    Enum, Keyword, Snippet, Color, File, Reference, Folder, EnumMember, Constant, Struct, Event, Operator,
    TypeParameter, User, Issue,
}

enum class CompletionTriggerKind { Invoke, TriggerCharacter, TriggerForIncompleteCompletions }

class CompletionContext(val triggerKind: CompletionTriggerKind, val triggerCharacter: String? = null)

class Command(val title: String, val command: String, val arguments: List<Any?> = emptyList(), val tooltip: String? = null)

class TextEdit(val range: Range, val newText: String) {
    companion object {
        fun replace(range: Range, newText: String) = TextEdit(range, newText)
        fun insert(position: Position, newText: String) = TextEdit(Range(position, position), newText)
        fun delete(range: Range) = TextEdit(range, "")
    }
}

class CompletionItem(var label: String, var kind: CompletionItemKind? = null) {
    var detail: String? = null
    /** String or [MarkdownString]. */
    var documentation: Any? = null
    /** String or [SnippetString]; defaults to [label]. */
    var insertText: Any? = null
    var range: Range? = null
    var sortText: String? = null
    var filterText: String? = null
    var preselect: Boolean = false
    var command: Command? = null
    var additionalTextEdits: List<TextEdit>? = null
    var commitCharacters: List<String>? = null
    override fun toString() = "CompletionItem($label, $kind, insert=${insertText})"
}

class CompletionList(val items: List<CompletionItem>, val isIncomplete: Boolean = false)

class Hover(val contents: List<Any>, val range: Range? = null) {
    constructor(content: Any, range: Range? = null) : this(listOf(content), range)
}

enum class SymbolKind {
    File, Module, Namespace, Package, Class, Method, Property, Field, Constructor, Enum, Interface, Function,
    Variable, Constant, String, Number, Boolean, Array, Object, Key, Null, EnumMember, Struct, Event, Operator,
    TypeParameter,
}

class DocumentSymbol(
    var name: String,
    var detail: String,
    var kind: SymbolKind,
    var range: Range,
    var selectionRange: Range,
) {
    val children: MutableList<DocumentSymbol> = mutableListOf()
}

enum class FoldingRangeKind { Comment, Imports, Region }

class FoldingRange(val start: Int, val end: Int, val kind: FoldingRangeKind? = null)

class Location(val uri: Uri, val range: Range)

class CodeLens(val range: Range, var command: Command? = null)

class WorkspaceEdit {
    val edits: MutableMap<Uri, MutableList<TextEdit>> = LinkedHashMap()

    fun replace(uri: Uri, range: Range, newText: String) { edits.getOrPut(uri) { mutableListOf() } += TextEdit(range, newText) }
    fun insert(uri: Uri, position: Position, newText: String) = replace(uri, Range(position, position), newText)
    fun delete(uri: Uri, range: Range) = replace(uri, range, "")
    fun set(uri: Uri, textEdits: List<TextEdit>) { edits[uri] = textEdits.toMutableList() }
}

enum class CodeActionKind { QuickFix, Refactor, Source }

class CodeAction(val title: String, val kind: CodeActionKind? = null) {
    var diagnostics: List<Diagnostic>? = null
    var edit: WorkspaceEdit? = null
    var command: Command? = null
    var isPreferred: Boolean = false
}

class CodeActionContext(val diagnostics: List<Diagnostic>)

class ParameterInformation(val label: Any, val documentation: Any? = null)

class SignatureInformation(val label: String, val documentation: Any? = null) {
    val parameters: MutableList<ParameterInformation> = mutableListOf()
    var activeParameter: Int? = null
}

class SignatureHelp {
    val signatures: MutableList<SignatureInformation> = mutableListOf()
    var activeSignature: Int = 0
    var activeParameter: Int = 0
}

class QuickPickItem(val label: String, val description: String? = null, var picked: Boolean = false)

/* Semantic tokens: upstream builds VS Code semantic tokens; we keep the (range, type, modifiers) triples. */

class SemanticTokensLegend(val tokenTypes: List<String>, val tokenModifiers: List<String>)

data class SemanticToken(val range: Range, val tokenType: String, val tokenModifiers: List<String>)

class SemanticTokens(val tokens: List<SemanticToken>)

class SemanticTokensBuilder(val legend: SemanticTokensLegend? = null) {
    private val tokens = mutableListOf<SemanticToken>()
    fun push(range: Range, tokenType: String, tokenModifiers: List<String> = emptyList()) {
        tokens += SemanticToken(range, tokenType, tokenModifiers)
    }
    fun build() = SemanticTokens(tokens.toList())
}
