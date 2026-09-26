// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/**
 * Highlighting keys. TextMate scopes (upstream grammars) and VS Code semantic token types (upstream
 * parser.ts) are both mapped onto these, with platform defaults as fallbacks so themes apply.
 */
object CedarColors {
    val COMMENT = createTextAttributesKey("CEDAR_COMMENT", D.LINE_COMMENT)
    val STRING = createTextAttributesKey("CEDAR_STRING", D.STRING)
    val STRING_ESCAPE = createTextAttributesKey("CEDAR_STRING_ESCAPE", D.VALID_STRING_ESCAPE)
    val STRING_INVALID_ESCAPE = createTextAttributesKey("CEDAR_STRING_INVALID_ESCAPE", D.INVALID_STRING_ESCAPE)
    val KEYWORD = createTextAttributesKey("CEDAR_KEYWORD", D.KEYWORD)
    val OPERATOR = createTextAttributesKey("CEDAR_OPERATOR", D.OPERATION_SIGN)
    val NUMBER = createTextAttributesKey("CEDAR_NUMBER", D.NUMBER)
    val BOOLEAN = createTextAttributesKey("CEDAR_BOOLEAN", D.KEYWORD)
    val VARIABLE = createTextAttributesKey("CEDAR_VARIABLE", D.KEYWORD)
    val SLOT = createTextAttributesKey("CEDAR_SLOT", D.PARAMETER)
    val ENTITY_TYPE = createTextAttributesKey("CEDAR_ENTITY_TYPE", D.CLASS_REFERENCE)
    val NAMESPACE = createTextAttributesKey("CEDAR_NAMESPACE", D.CLASS_NAME)
    val METHOD = createTextAttributesKey("CEDAR_METHOD", D.INSTANCE_METHOD)
    val FUNCTION = createTextAttributesKey("CEDAR_FUNCTION", D.STATIC_METHOD)
    val BUILTIN_TYPE = createTextAttributesKey("CEDAR_BUILTIN_TYPE", D.KEYWORD)
    val PROPERTY = createTextAttributesKey("CEDAR_PROPERTY", D.INSTANCE_FIELD)
    val ANNOTATION = createTextAttributesKey("CEDAR_ANNOTATION", D.METADATA)
    val DOT = createTextAttributesKey("CEDAR_DOT", D.DOT)
    val COMMA = createTextAttributesKey("CEDAR_COMMA", D.COMMA)
    val SEMICOLON = createTextAttributesKey("CEDAR_SEMICOLON", D.SEMICOLON)
    val PARENTHESES = createTextAttributesKey("CEDAR_PARENTHESES", D.PARENTHESES)
    val BRACES = createTextAttributesKey("CEDAR_BRACES", D.BRACES)
    val BRACKETS = createTextAttributesKey("CEDAR_BRACKETS", D.BRACKETS)

    // semantic tokens (upstream semanticTokensLegend), used in JSON files and Cedar schema
    val SEM_NAMESPACE = createTextAttributesKey("CEDAR_SEMANTIC_NAMESPACE", NAMESPACE)
    val SEM_TYPE = createTextAttributesKey("CEDAR_SEMANTIC_TYPE", ENTITY_TYPE)
    val SEM_TYPE_DECLARATION = createTextAttributesKey("CEDAR_SEMANTIC_TYPE_DECLARATION", D.CLASS_NAME)
    val SEM_STRUCT = createTextAttributesKey("CEDAR_SEMANTIC_STRUCT", D.CLASS_NAME)
    val SEM_PROPERTY = createTextAttributesKey("CEDAR_SEMANTIC_PROPERTY", PROPERTY)
    val SEM_MACRO = createTextAttributesKey("CEDAR_SEMANTIC_MACRO", D.METADATA)
    val SEM_FUNCTION = createTextAttributesKey("CEDAR_SEMANTIC_FUNCTION", D.FUNCTION_DECLARATION)
    val SEM_VARIABLE = createTextAttributesKey("CEDAR_SEMANTIC_VARIABLE", D.LOCAL_VARIABLE)
    val SEM_VARIABLE_READONLY = createTextAttributesKey("CEDAR_SEMANTIC_VARIABLE_READONLY", VARIABLE)
    val SEM_OPERATOR = createTextAttributesKey("CEDAR_SEMANTIC_OPERATOR", OPERATOR)
    val SEM_KEYWORD = createTextAttributesKey("CEDAR_SEMANTIC_KEYWORD", KEYWORD)
    val SEM_ENUM_MEMBER = createTextAttributesKey("CEDAR_SEMANTIC_ENUM_MEMBER", D.CONSTANT)
    val SEM_DECORATOR = createTextAttributesKey("CEDAR_SEMANTIC_DECORATOR", ANNOTATION)
    val SEM_STRING = createTextAttributesKey("CEDAR_SEMANTIC_STRING", STRING)

    /** Maps a TextMate scope list (outermost-first) to a key, most specific scope first. */
    fun forScopes(scopes: List<String>): TextAttributesKey? {
        for (scope in scopes.asReversed()) forScope(scope)?.let { return it }
        return null
    }

    private fun forScope(s: String): TextAttributesKey? = when {
        s.startsWith("comment") -> COMMENT
        s.startsWith("constant.character.escape") -> STRING_ESCAPE
        s.startsWith("invalid") -> STRING_INVALID_ESCAPE
        s.startsWith("variable.other.property") -> PROPERTY
        s.startsWith("string") -> STRING
        s.startsWith("entity.name.function.decorator") || s.startsWith("meta.decorator") -> ANNOTATION
        s.startsWith("keyword.control") || s.startsWith("keyword.operator.word") -> KEYWORD
        s.startsWith("keyword.operator") -> OPERATOR
        s.startsWith("constant.numeric") -> NUMBER
        s.startsWith("constant.language") -> BOOLEAN
        s.startsWith("variable.language") -> VARIABLE
        s.startsWith("variable.parameter") -> SLOT
        s.startsWith("entity.name.type") -> ENTITY_TYPE
        s.startsWith("entity.name.namespace") -> NAMESPACE
        s.startsWith("entity.name.function") -> METHOD
        s.startsWith("support.function") -> FUNCTION
        s.startsWith("support.type") -> BUILTIN_TYPE
        s.startsWith("punctuation.separator.namespace") || s.startsWith("punctuation.accessor") -> DOT
        s.startsWith("punctuation.separator.comma") -> COMMA
        s.startsWith("punctuation.terminator") -> SEMICOLON
        s.startsWith("punctuation.separator.key-value") -> OPERATOR
        s.startsWith("punctuation.definition.typeparameters") -> BRACKETS
        s.startsWith("punctuation.section.brackets") -> BRACES
        else -> null
    }

    /** Maps an upstream semantic token (type + modifiers from semanticTokensLegend) to a key. */
    fun forSemanticToken(type: String, modifiers: List<String>): TextAttributesKey? = when (type) {
        "namespace" -> SEM_NAMESPACE
        "type" -> if ("declaration" in modifiers) SEM_TYPE_DECLARATION else SEM_TYPE
        "struct" -> SEM_STRUCT
        "property" -> SEM_PROPERTY
        "macro" -> SEM_MACRO
        "function" -> SEM_FUNCTION
        "variable" -> if ("readonly" in modifiers) SEM_VARIABLE_READONLY else SEM_VARIABLE
        "operator" -> SEM_OPERATOR
        "keyword" -> SEM_KEYWORD
        "enumMember" -> SEM_ENUM_MEMBER
        "decorator" -> SEM_DECORATOR
        "string" -> SEM_STRING
        else -> null
    }
}
