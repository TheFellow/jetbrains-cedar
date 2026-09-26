// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.textmate

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A small TextMate grammar interpreter, sufficient for upstream's `syntaxes/` TextMate grammars
 * (match/captures rules, begin/end rules, `#repository` and `$self` includes). The grammar files are
 * copied verbatim from upstream so grammar semports are file copies.
 *
 * Differences from vscode-textmate: Java regex instead of Oniguruma (unbounded quantifiers inside
 * look-behinds are bounded, see [toJavaRegex]); `while` rules and injections are not supported.
 */
class TmGrammar private constructor(val scopeName: String, private val root: JsonObject) {

    sealed class Rule {
        abstract val name: String?
    }

    class MatchRule(override val name: String?, val match: Regex, val captures: Map<Int, String>) : Rule()

    class BeginEndRule(
        override val name: String?,
        val contentName: String?,
        val begin: Regex,
        val beginCaptures: Map<Int, String>,
        val end: String,
        val endCaptures: Map<Int, String>,
        val patterns: () -> List<Rule>,
    ) : Rule() {
        /** End patterns may back-reference begin captures; compile lazily per distinct source. */
        private val compiled = java.util.concurrent.ConcurrentHashMap<String, Regex>()
        fun endRegex(beginMatch: MatchResult): Regex {
            val source = if (end.contains(BACKREF)) {
                end.replace(BACKREF) { m -> Regex.escape(beginMatch.groupValues.getOrElse(m.groupValues[1].toInt()) { "" }) }
            } else end
            return compiled.getOrPut(source) { Regex(toJavaRegex(source)) }
        }
    }

    private val repository = root.getAsJsonObject("repository") ?: JsonObject()
    private val resolved = HashMap<String, List<Rule>>()

    val patterns: List<Rule> by lazy { compilePatterns(root) }

    private fun compilePatterns(obj: JsonObject): List<Rule> {
        val arr = obj.getAsJsonArray("patterns") ?: return emptyList()
        return arr.flatMap { compileRule(it.asJsonObject) }
    }

    private fun resolveInclude(include: String): List<Rule> = when {
        include == "\$self" || include == "\$base" -> patterns
        include.startsWith("#") -> synchronized(resolved) {
            resolved[include] ?: run {
                resolved[include] = emptyList() // recursion guard
                val target = repository.getAsJsonObject(include.substring(1))
                val rules = if (target == null) emptyList() else compileRule(target)
                resolved[include] = rules
                rules
            }
        }
        else -> emptyList() // external grammars are not supported
    }

    private fun captures(obj: JsonObject?, key: String): Map<Int, String> {
        val caps = obj?.getAsJsonObject(key) ?: return emptyMap()
        return caps.entrySet().mapNotNull { (k, v) ->
            val name = v.asJsonObject.get("name")?.asString ?: return@mapNotNull null
            k.toIntOrNull()?.let { it to name }
        }.toMap()
    }

    private fun compileRule(obj: JsonObject): List<Rule> {
        obj.get("include")?.asString?.let { return resolveInclude(it) }
        val name = obj.get("name")?.asString
        obj.get("match")?.asString?.let { return listOf(MatchRule(name, Regex(toJavaRegex(it)), captures(obj, "captures"))) }
        obj.get("begin")?.asString?.let { begin ->
            val caps = captures(obj, "captures")
            return listOf(
                BeginEndRule(
                    name = name,
                    contentName = obj.get("contentName")?.asString,
                    begin = Regex(toJavaRegex(begin)),
                    beginCaptures = captures(obj, "beginCaptures").ifEmpty { caps },
                    end = obj.get("end")?.asString ?: "\$^",
                    endCaptures = captures(obj, "endCaptures").ifEmpty { caps },
                    patterns = { compilePatterns(obj) },
                ),
            )
        }
        // a container of patterns (e.g. a repository entry with only "patterns")
        return compilePatterns(obj)
    }

    companion object {
        private val BACKREF = Regex("""\\(\d)""")

        fun load(resource: String): TmGrammar {
            val text = TmGrammar::class.java.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("grammar $resource not found")
            return parse(text)
        }

        fun parse(text: String): TmGrammar {
            val root = JsonParser.parseString(text).asJsonObject
            return TmGrammar(root.get("scopeName").asString, root)
        }

        /**
         * Oniguruma accepts unbounded quantifiers in look-behind, Java does not: bound them.
         * Also maps the Oniguruma-only `\h` (hex digit) class.
         */
        fun toJavaRegex(source: String): String {
            val out = StringBuilder()
            var i = 0
            var inClass = false
            // depths (in group nesting) at which a look-behind started
            val groupStack = ArrayDeque<Boolean>()
            var lookBehindDepth = 0
            while (i < source.length) {
                val c = source[i]
                if (c == '\\' && i + 1 < source.length) {
                    val n = source[i + 1]
                    if (n == 'h') out.append("[0-9A-Fa-f]") else out.append(c).append(n)
                    i += 2
                    continue
                }
                if (inClass) {
                    if (c == ']') inClass = false
                    out.append(c); i++; continue
                }
                when (c) {
                    '[' -> { inClass = true; out.append(c) }
                    '(' -> {
                        val isLookBehind = source.startsWith("(?<=", i) || source.startsWith("(?<!", i)
                        groupStack.addLast(isLookBehind)
                        if (isLookBehind) lookBehindDepth++
                        out.append(c)
                    }
                    ')' -> {
                        if (groupStack.removeLastOrNull() == true) lookBehindDepth--
                        out.append(c)
                    }
                    '*' -> out.append(if (lookBehindDepth > 0) "{0,64}" else "*")
                    '+' -> out.append(if (lookBehindDepth > 0) "{1,64}" else "+")
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }
    }
}
