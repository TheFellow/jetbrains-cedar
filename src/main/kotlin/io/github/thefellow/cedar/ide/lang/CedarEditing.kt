// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.lang

import com.intellij.codeInsight.editorActions.QuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

/** language-configuration.json: auto-closing / surrounding `"` pairs. */
class TmQuoteHandler : QuoteHandler {
    private fun isString(iterator: HighlighterIterator) =
        (iterator.tokenType as? TmElementType)?.scopes?.any { it.startsWith("string") } == true

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int) =
        isString(iterator) && iterator.end - iterator.start > 1 && offset == iterator.end - 1 &&
            iterator.document.charsSequence[offset] == '"'

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int) =
        isString(iterator) && offset == iterator.start && iterator.document.charsSequence[offset] == '"'

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int) = true

    override fun isInsideLiteral(iterator: HighlighterIterator) = isString(iterator)
}

class CedarColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Comment", CedarColors.COMMENT),
        AttributesDescriptor("String//Text", CedarColors.STRING),
        AttributesDescriptor("String//Escape sequence", CedarColors.STRING_ESCAPE),
        AttributesDescriptor("String//Invalid escape sequence", CedarColors.STRING_INVALID_ESCAPE),
        AttributesDescriptor("Keyword", CedarColors.KEYWORD),
        AttributesDescriptor("Operator", CedarColors.OPERATOR),
        AttributesDescriptor("Number", CedarColors.NUMBER),
        AttributesDescriptor("Boolean", CedarColors.BOOLEAN),
        AttributesDescriptor("Variable (principal, action, resource, context)", CedarColors.VARIABLE),
        AttributesDescriptor("Template slot", CedarColors.SLOT),
        AttributesDescriptor("Entity type", CedarColors.ENTITY_TYPE),
        AttributesDescriptor("Namespace", CedarColors.NAMESPACE),
        AttributesDescriptor("Method", CedarColors.METHOD),
        AttributesDescriptor("Extension function", CedarColors.FUNCTION),
        AttributesDescriptor("Built-in type", CedarColors.BUILTIN_TYPE),
        AttributesDescriptor("Attribute", CedarColors.PROPERTY),
        AttributesDescriptor("Annotation", CedarColors.ANNOTATION),
        AttributesDescriptor("Punctuation//Dot and ::", CedarColors.DOT),
        AttributesDescriptor("Punctuation//Comma", CedarColors.COMMA),
        AttributesDescriptor("Punctuation//Semicolon", CedarColors.SEMICOLON),
        AttributesDescriptor("Punctuation//Parentheses", CedarColors.PARENTHESES),
        AttributesDescriptor("Punctuation//Braces", CedarColors.BRACES),
        AttributesDescriptor("Punctuation//Brackets", CedarColors.BRACKETS),
        AttributesDescriptor("Semantic//Namespace", CedarColors.SEM_NAMESPACE),
        AttributesDescriptor("Semantic//Type", CedarColors.SEM_TYPE),
        AttributesDescriptor("Semantic//Type declaration", CedarColors.SEM_TYPE_DECLARATION),
        AttributesDescriptor("Semantic//Struct", CedarColors.SEM_STRUCT),
        AttributesDescriptor("Semantic//Property", CedarColors.SEM_PROPERTY),
        AttributesDescriptor("Semantic//Macro", CedarColors.SEM_MACRO),
        AttributesDescriptor("Semantic//Function", CedarColors.SEM_FUNCTION),
        AttributesDescriptor("Semantic//Variable", CedarColors.SEM_VARIABLE),
        AttributesDescriptor("Semantic//Read-only variable", CedarColors.SEM_VARIABLE_READONLY),
        AttributesDescriptor("Semantic//Operator", CedarColors.SEM_OPERATOR),
        AttributesDescriptor("Semantic//Keyword", CedarColors.SEM_KEYWORD),
        AttributesDescriptor("Semantic//Enum member", CedarColors.SEM_ENUM_MEMBER),
        AttributesDescriptor("Semantic//Decorator", CedarColors.SEM_DECORATOR),
        AttributesDescriptor("Semantic//String", CedarColors.SEM_STRING),
    )

    override fun getIcon() = CedarIcons.CEDAR
    override fun getHighlighter(): SyntaxHighlighter = CedarSupport.highlighter
    override fun getDemoText() = """
        // Cedar policy
        @id("view-photos")
        permit (
            principal in PhotoApp::UserGroup::"janeFriends",
            action in [PhotoApp::Action::"viewPhoto", PhotoApp::Action::"listPhotos"],
            resource is PhotoApp::Photo in ?resource
        )
        when { resource.tags.contains("holiday") && context.mfa == true }
        unless { ip(context.src).isLoopback() || context.age < 18 || !("a\tb" like "a*\q") };
    """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, com.intellij.openapi.editor.colors.TextAttributesKey>? = null
    override fun getAttributeDescriptors() = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName() = "Cedar"
}
