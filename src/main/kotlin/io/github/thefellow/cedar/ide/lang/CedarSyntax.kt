// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.thefellow.cedar.textmate.TmGrammar

/** Everything a TextMate-lexed language needs: grammar, token types, lexer factory, highlighter. */
abstract class TmLanguageSupport(val language: Language, grammarResource: String) {
    val grammar: TmGrammar by lazy { TmGrammar.load(grammarResource) }
    val tokenTypes = TmTokenTypes(language)
    val fileElementType = IFileElementType(language)

    fun createLexer(): Lexer = TmLexer(grammar, tokenTypes)

    val highlighter: SyntaxHighlighter = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer() = createLexer()
        override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
            val key = when (tokenType) {
                tokenTypes.comment -> CedarColors.COMMENT
                tokenTypes.lParen, tokenTypes.rParen -> CedarColors.PARENTHESES
                tokenTypes.lBrace, tokenTypes.rBrace -> CedarColors.BRACES
                tokenTypes.lBracket, tokenTypes.rBracket -> CedarColors.BRACKETS
                is TmElementType -> CedarColors.forScopes(tokenType.scopes)
                else -> null
            }
            return pack(key)
        }
    }
}

object CedarSupport : TmLanguageSupport(CedarLanguage, "/syntaxes/cedar.tmLanguage.json")
object CedarSchemaSupport : TmLanguageSupport(CedarSchemaLanguage, "/syntaxes/cedarschema.tmLanguage.json")

class CedarSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = CedarSupport.highlighter
}

class CedarSchemaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = CedarSchemaSupport.highlighter
}

/* PSI: a flat file of tokens. Upstream's language features are text/regex based, ported over the shim. */

class CedarPsiFile(viewProvider: FileViewProvider, private val fileType: LanguageFileType) :
    PsiFileBase(viewProvider, fileType.language) {
    override fun getFileType() = fileType
    override fun toString() = "${fileType.name} file"
}

abstract class TmParserDefinition(private val support: TmLanguageSupport, private val fileType: LanguageFileType) : ParserDefinition {
    override fun createLexer(project: Project?) = support.createLexer()
    override fun createParser(project: Project?) = PsiParser { root, builder: PsiBuilder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }
    override fun getFileNodeType() = support.fileElementType
    override fun getCommentTokens() = TokenSet.create(support.tokenTypes.comment)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = LeafPsiElement(node.elementType, node.text)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = CedarPsiFile(viewProvider, fileType)
}

class CedarParserDefinition : TmParserDefinition(CedarSupport, CedarFileType)
class CedarSchemaParserDefinition : TmParserDefinition(CedarSchemaSupport, CedarSchemaFileType)

/** language-configuration.json: line comment `//`. */
class CedarCommenter : Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** language-configuration.json: brackets `{}`, `[]`, `()`. */
abstract class TmBraceMatcher(support: TmLanguageSupport) : PairedBraceMatcher {
    private val pairs = support.tokenTypes.let {
        arrayOf(
            BracePair(it.lBrace, it.rBrace, true),
            BracePair(it.lBracket, it.rBracket, false),
            BracePair(it.lParen, it.rParen, false),
        )
    }
    override fun getPairs() = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}

class CedarBraceMatcher : TmBraceMatcher(CedarSupport)
class CedarSchemaBraceMatcher : TmBraceMatcher(CedarSchemaSupport)
