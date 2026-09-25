// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.vscode

enum class DiagnosticSeverity { Error, Warning, Information, Hint }

class Diagnostic(
    var range: Range,
    var message: String,
    var severity: DiagnosticSeverity = DiagnosticSeverity.Error,
) {
    var code: String? = null
    var source: String? = null
    override fun toString() = "Diagnostic($severity $range '$message' code=$code)"
}

/** Port target for `vscode.DiagnosticCollection`, keyed by [Uri]. */
open class DiagnosticCollection(val name: String) {
    private val entries = LinkedHashMap<Uri, List<Diagnostic>>()

    /** Called after any change with the affected uris. */
    var onChange: (Collection<Uri>) -> Unit = {}

    @Synchronized
    fun set(uri: Uri, diagnostics: List<Diagnostic>?) {
        if (diagnostics == null) entries.remove(uri) else entries[uri] = diagnostics.toList()
        onChange(listOf(uri))
    }

    @Synchronized
    fun delete(uri: Uri) {
        entries.remove(uri)
        onChange(listOf(uri))
    }

    @Synchronized
    fun clear() {
        val uris = entries.keys.toList()
        entries.clear()
        onChange(uris)
    }

    @Synchronized
    fun get(uri: Uri): List<Diagnostic>? = entries[uri]

    @Synchronized
    fun has(uri: Uri) = entries.containsKey(uri)

    @Synchronized
    fun forEach(action: (Uri, List<Diagnostic>) -> Unit) = entries.toMap().forEach(action)
}
