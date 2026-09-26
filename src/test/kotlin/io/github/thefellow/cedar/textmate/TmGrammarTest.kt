// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.textmate

import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.CEDAR
import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.CEDARSCHEMA
import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.assertNotScope
import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.assertScope
import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.assertUnscoped
import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.policy
import io.github.thefellow.cedar.textmate.TmGrammarTestSupport.tokenize
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Port of upstream src/test/suite/tmgrammar.test.ts: tokenization tests for
 * syntaxes/cedar.tmLanguage.json and syntaxes/cedarschema.tmLanguage.json.
 * Each case names the finding it covers from the cross-implementation
 * comparison of the Prism, highlight.js and VS Code grammars.
 */

@Suppress("ClassName")
class TmGrammarTest {
// suite: Cedar policy TextMate Suite
  @Test
  fun `P1 methods require a receiver dot`() {
    assertScope(
      CEDAR,
      policy("context.s.contains(\"a\")"),
      "contains",
      "entity.name.function"
    )
    assertScope(
      CEDAR,
      policy("resource.getTag(\"w\")"),
      "getTag",
      "entity.name.function"
    )
    assertScope(
      CEDAR,
      policy("ip(\"1.1.1.1\").isInRange(ip(\"1.1.1.1/24\"))"),
      "isInRange",
      "entity.name.function"
    )
    // a bare call is an ExtFun call, not a method call
    assertUnscoped(CEDAR, policy("contains(\"a\")"), "contains")
    // the same word used as an attribute is not a method
    assertUnscoped(CEDAR, policy("context.contains == 1"), "contains")
  }

  @Test
  fun `P1 extension constructors`() {
    for (fn in listOf("ip", "decimal", "datetime", "duration")) {
      assertScope(
        CEDAR,
        policy("${fn}(\"x\")"),
        fn,
        "support.function"
      )
    }
  }

  @Test
  fun `P2 integer literals`() {
    assertScope(CEDAR, policy("context.a == 42"), "42", "constant.numeric")
    // leading zeros are a single INT per the grammar
    assertScope(CEDAR, policy("context.a == 007"), "007", "constant.numeric")
    // the sign is an operator, not part of the literal
    assertScope(CEDAR, policy("context.a == -42"), "-", "keyword.operator")
    assertScope(CEDAR, policy("context.a == -42"), "42", "constant.numeric")
    // Cedar has no digit separators
    assertUnscoped(CEDAR, policy("context.a == 1_000"), "1_000")
    // digits inside an identifier are not numbers
    assertUnscoped(CEDAR, policy("context.i18n == 1"), "i18n")
  }

  @Test
  fun `P3 unary ! is an operator`() {
    assertScope(CEDAR, policy("!true"), "!", "keyword.operator")
    assertScope(
      CEDAR,
      policy("!(principal in Group::\"g\")"),
      "!",
      "keyword.operator"
    )
    assertScope(CEDAR, policy("context.a != 1"), "!=", "keyword.operator")
  }

  @Test
  fun `P4 symbolic operators are scoped`() {
    val operators = listOf(
      "&&",
      "||",
      "==",
      "!=",
      ">=",
      "<=",
      ">",
      "<",
      "+",
      "-",
      "*",
    )
    for (op in operators) {
      assertScope(
        CEDAR,
        policy("context.a ${op} 1"),
        op,
        "keyword.operator"
      )
    }
  }

  @Test
  fun `P4 symbolic operators without surrounding whitespace`() {
    assertScope(CEDAR, policy("true&&false"), "&&", "keyword.operator")
    assertScope(CEDAR, policy("context.a==1"), "==", "keyword.operator")
    assertScope(CEDAR, policy("context.a+1>2"), "+", "keyword.operator")
    assertScope(CEDAR, policy("context.a+1>2"), ">", "keyword.operator")
  }

  @Test
  fun `P5 annotations, including the valueless form`() {
    // meta.decorator alone is not coloured by any bundled VS Code theme, so the
    // name also carries entity.name.function.decorator. That is what lets the
    // TextMate grammar stand on its own without a semantic token provider,
    // which matters inside markdown ```cedar blocks where none runs.
    for (scope in listOf("meta.decorator", "entity.name.function.decorator")) {
      assertScope(CEDAR, "@id(\"x\")\n${policy("true")}", "@id", scope)
      assertScope(
        CEDAR,
        "@doNotOptimize\n${policy("true")}",
        "@doNotOptimize",
        scope
      )
      // ANYIDENT, so a reserved word is a legal annotation name
      assertScope(CEDAR, "@is(\"x\")\n${policy("true")}", "@is", scope)
      assertScope(
        CEDAR,
        "@__cedar(\"x\")\n${policy("true")}",
        "@__cedar",
        scope
      )
    }
    // a '@' inside a string is not an annotation
    assertScope(
      CEDAR,
      policy("context.a == \"@notAnAnnotation\""),
      "@notAnAnnotation",
      "string.quoted"
    )
    assertNotScope(
      CEDAR,
      policy("context.a == \"@notAnAnnotation\""),
      "@notAnAnnotation",
      "meta.decorator"
    )
  }

  @Test
  fun `P6 an unqualified type after is`() {
    assertScope(
      CEDAR,
      "permit (principal is User, action, resource);",
      "User",
      "entity.name.type"
    )
    assertScope(
      CEDAR,
      "permit (principal is A::User, action, resource);",
      "A::User",
      "entity.name.type"
    )
    assertScope(CEDAR, policy("resource is Photo"), "Photo", "entity.name.type")
  }

  @Test
  fun `P7 entity literals`() {
    assertScope(
      CEDAR,
      policy("principal == User::\"alice\""),
      "User",
      "entity.name.type"
    )
    assertScope(
      CEDAR,
      policy("principal == A::B::User::\"a\""),
      "A::B::User",
      "entity.name.type"
    )
  }

  @Test
  fun `P8 word operators and control flow are scoped apart`() {
    assertScope(CEDAR, policy("if true then 1 else 2"), "if", "keyword.control")
    assertScope(
      CEDAR,
      policy("if true then 1 else 2"),
      "then",
      "keyword.control"
    )
    assertScope(
      CEDAR,
      policy("if true then 1 else 2"),
      "else",
      "keyword.control"
    )
    assertScope(CEDAR, policy("principal in A::\"b\""), "in", "keyword.operator")
    assertScope(CEDAR, policy("principal has x"), "has", "keyword.operator")
    assertScope(
      CEDAR,
      policy("resource.n like \"a*\""),
      "like",
      "keyword.operator"
    )
    for (kw in listOf("permit", "when")) {
      assertScope(
        CEDAR,
        "permit (principal, action, resource)\nwhen { true };",
        kw,
        "keyword.control"
      )
    }
    assertScope(
      CEDAR,
      "forbid (principal, action, resource)\nunless { true };",
      "forbid",
      "keyword.control"
    )
    assertScope(
      CEDAR,
      "forbid (principal, action, resource)\nunless { true };",
      "unless",
      "keyword.control"
    )
  }

  @Test
  fun `P9 variables, template slots and booleans`() {
    for (v in listOf("principal", "action", "resource")) {
      assertScope(
        CEDAR,
        "permit (principal, action, resource);",
        v,
        "variable.language"
      )
    }
    assertScope(CEDAR, policy("context.a == 1"), "context", "variable.language")
    assertScope(
      CEDAR,
      "permit (principal in ?principal, action, resource);",
      "?principal",
      "variable.parameter"
    )
    assertScope(
      CEDAR,
      "permit (principal, action, resource in ?resource);",
      "?resource",
      "variable.parameter"
    )
    assertScope(CEDAR, policy("true"), "true", "constant.language.boolean")
    assertScope(CEDAR, policy("false"), "false", "constant.language.boolean")
  }

  @Test
  fun `P11 a string does not run past the end of a line`() {
    val source =
      "permit (principal, action, resource)\n" +
      "when { context.a == \"unterminated };\n" +
      "permit (principal, action, resource);"
    // the policy on the following line is still tokenized as Cedar
    assertScope(CEDAR, source, "permit", "keyword.control")
    val strings = tokenize(CEDAR, source).filter { t ->
      t.scopes.any { s -> s.startsWith("string.quoted") }
    }
    assertEquals(
      "the unterminated string should not swallow the next line",
      true,
      strings.all { t -> !t.text.contains("permit") },
    )
  }

  @Test
  fun `P11 escape sequences`() {
    assertScope(
      CEDAR,
      policy("context.a == \"a\\nb\""),
      "\\n",
      "constant.character.escape"
    )
    assertScope(
      CEDAR,
      policy("context.a == \"a\\x41b\""),
      "\\x41",
      "constant.character.escape"
    )
    assertScope(
      CEDAR,
      policy("context.a == \"a\\u{1F600}b\""),
      "\\u{1F600}",
      "constant.character.escape"
    )
    // PAT allows \* as an extra escape
    assertScope(
      CEDAR,
      policy("resource.n like \"a\\*b\""),
      "\\*",
      "constant.character.escape"
    )
    // an escape Cedar does not define is flagged rather than shown as valid
    assertScope(
      CEDAR,
      policy("context.a == \"a\\qb\""),
      "\\q",
      "invalid.illegal"
    )
  }

  @Test
  fun `P13 punctuation`() {
    assertScope(
      CEDAR,
      policy("principal == A::User::\"u\""),
      "::",
      "punctuation.separator.namespace"
    )
    assertScope(
      CEDAR,
      policy("context.a == 1"),
      ".",
      "punctuation.accessor"
    )
    assertScope(
      CEDAR,
      "permit (principal, action, resource);",
      ",",
      "punctuation.separator.comma"
    )
    assertScope(
      CEDAR,
      "permit (principal, action, resource);",
      ";",
      "punctuation.terminator"
    )
    assertScope(
      CEDAR,
      "permit (principal, action, resource);",
      "(",
      "punctuation.section.brackets"
    )
    // the receiver dot and the opening paren of a call are scoped too, rather
    // than being silently consumed by the method and function rules
    assertScope(
      CEDAR,
      policy("context.s.contains(\"a\")"),
      ".",
      "punctuation.accessor"
    )
    assertScope(
      CEDAR,
      policy("context.s.contains(\"a\")"),
      "(",
      "punctuation.section.brackets"
    )
    assertScope(
      CEDAR,
      policy("ip(\"1.1.1.1\")"),
      "(",
      "punctuation.section.brackets"
    )
  }

  @Test
  fun `comments`() {
    assertScope(
      CEDAR,
      "// hello\npermit (principal, action, resource);",
      "// hello",
      "comment.line"
    )
    // a comment directly after a path separator
    assertScope(
      CEDAR,
      "${policy("A::B::User::\"u\" == principal")}//y",
      "//y",
      "comment.line"
    )
    // a url inside a string is not a comment
    assertScope(
      CEDAR,
      policy("context.a == \"https://x.com\""),
      "https://x.com",
      "string.quoted"
    )
    // '//' inside a string is not a comment
    assertScope(
      CEDAR,
      policy("context.a == \"// not a comment\""),
      "// not a comment",
      "string.quoted"
    )
  }

  // suite: Cedar schema TextMate Suite
  @Test
  fun `S1 tags does not require a preceding brace`() {
    assertScope(
      CEDARSCHEMA,
      "entity E { a: String } tags String;",
      "tags",
      "keyword"
    )
    assertScope(CEDARSCHEMA, "entity A tags String;", "tags", "keyword")
    assertScope(
      CEDARSCHEMA,
      "entity B in [P] tags Set<String>;",
      "tags",
      "keyword"
    )
    assertScope(
      CEDARSCHEMA,
      "entity C in P tags Set<Set<String>>;",
      "tags",
      "keyword"
    )
    assertScope(
      CEDARSCHEMA,
      "entity R tags { note: String };",
      "tags",
      "keyword"
    )
  }

  @Test
  fun `S1 tags used as a name is not the keyword`() {
    assertNotScope(CEDARSCHEMA, "entity D { tags: Bool };", "tags", "keyword")
    assertNotScope(CEDARSCHEMA, "entity D { tags?: Bool };", "tags", "keyword")
    assertNotScope(CEDARSCHEMA, "entity tags;", "tags", "keyword")
    assertNotScope(CEDARSCHEMA, "entity E, tags;", "tags", "keyword")
    assertNotScope(CEDARSCHEMA, "type tags = Long;", "tags", "keyword")
    assertNotScope(CEDARSCHEMA, "type X = { a: tags };", "tags", "keyword")
    assertNotScope(CEDARSCHEMA, "entity tags in Parent;", "tags", "keyword")
    assertNotScope(
      CEDARSCHEMA,
      "action tags appliesTo { context: {} };",
      "tags",
      "keyword"
    )
  }

  @Test
  fun `S2 enum`() {
    assertScope(
      CEDARSCHEMA,
      "entity Color enum [\"Red\", \"Blue\"];",
      "enum",
      "keyword"
    )
    assertNotScope(CEDARSCHEMA, "entity T { enum: Color };", "enum", "keyword")
  }

  @Test
  fun `S3 in does not require a bracketed list`() {
    assertScope(CEDARSCHEMA, "entity Photo in Album;", "in", "keyword")
    assertScope(CEDARSCHEMA, "entity Photo in [Album];", "in", "keyword")
    assertScope(CEDARSCHEMA, "action view in Action::\"read\";", "in", "keyword")
  }

  @Test
  fun `S4 declarations are not anchored to the start of a line`() {
    assertScope(CEDARSCHEMA, "entity User;", "entity", "keyword.control")
    assertScope(CEDARSCHEMA, "  entity User;", "entity", "keyword.control")
    assertScope(
      CEDARSCHEMA,
      "namespace N { entity User; }",
      "entity",
      "keyword.control"
    )
    assertScope(
      CEDARSCHEMA,
      "namespace N { action x; }",
      "action",
      "keyword.control"
    )
    assertScope(
      CEDARSCHEMA,
      "namespace N { type T = Long; }",
      "type",
      "keyword.control"
    )
  }

  @Test
  fun `S5 a namespace name is a Path`() {
    assertScope(
      CEDARSCHEMA,
      "namespace Foo {\n entity E;\n}",
      "Foo",
      "entity.name.namespace"
    )
    assertScope(
      CEDARSCHEMA,
      "namespace Foo::Bar::Baz {\n entity E;\n}",
      "Foo::Bar::Baz",
      "entity.name.namespace"
    )
  }

  @Test
  fun `S6 schema annotations`() {
    // as in the policy grammar, the name carries a themed scope as well as the
    // meta wrapper, so no semantic token provider is required
    assertScope(
      CEDARSCHEMA,
      "@doc(\"ns\")\nnamespace N { entity E; }",
      "@doc",
      "entity.name.function.decorator"
    )
    assertScope(
      CEDARSCHEMA,
      "@doc(\"ns\")\nnamespace N { entity E; }",
      "@doc",
      "meta.decorator"
    )
    assertScope(
      CEDARSCHEMA,
      "entity E { @doc(\"a\") \"id\": Long };",
      "@doc",
      "meta.decorator"
    )
    assertScope(CEDARSCHEMA, "@doc(\"t\")\ntype T = Long;", "@doc", "meta.decorator")
    assertScope(CEDARSCHEMA, "@internal\nentity E;", "@internal", "meta.decorator")
  }

  @Test
  fun `S7 built-in type names`() {
    assertScope(CEDARSCHEMA, "entity E { l: Long };", "Long", "support.type")
    assertScope(CEDARSCHEMA, "entity E { b: Bool };", "Bool", "support.type")
    assertScope(
      CEDARSCHEMA,
      "entity E { s: Set<String> };",
      "Set",
      "support.type"
    )
    assertScope(
      CEDARSCHEMA,
      "entity E { s: Set<String> };",
      "String",
      "support.type"
    )
    assertScope(CEDARSCHEMA, "entity E { ip: ipaddr };", "ipaddr", "support.type")
    assertScope(CEDARSCHEMA, "type Alias = Long;", "Long", "support.type")
    assertScope(CEDARSCHEMA, "entity A tags String;", "String", "support.type")
  }

  @Test
  fun `S7 extension types, which no longer get a semantic token`() {
    // parseCedarSchemaCedarDoc used to push a 'function' semantic token for
    // these; the grammar is now the only thing scoping them, so assert each one.
    for (ext in listOf("ipaddr", "decimal", "datetime", "duration")) {
      assertScope(
        CEDARSCHEMA,
        "entity E { a: ${ext} };",
        ext,
        "support.type"
      )
      assertScope(
        CEDARSCHEMA,
        "type T = Set<${ext}>;",
        ext,
        "support.type"
      )
    }
  }

  @Test
  fun `S7 a declared name that collides with a reserved type name`() {
    assertNotScope(
      CEDARSCHEMA,
      "entity String { a: Long };",
      "String",
      "support.type"
    )
    assertNotScope(
      CEDARSCHEMA,
      "type ipaddr = { a: Long };",
      "ipaddr",
      "support.type"
    )
    // a common type reference is not a built-in type
    assertNotScope(
      CEDARSCHEMA,
      "entity E { p: Primitives };",
      "Primitives",
      "support.type"
    )
  }

  @Test
  fun `S8 assignment and Set brackets`() {
    assertScope(
      CEDARSCHEMA,
      "type T = { a: Long };",
      "=",
      "keyword.operator.assignment"
    )
    assertScope(CEDARSCHEMA, "entity E { s: Set<String> };", "<", "punctuation")
    assertScope(CEDARSCHEMA, "entity E { s: Set<String> };", ">", "punctuation")
  }

  @Test
  fun `S11 appliesTo requires a following brace`() {
    assertScope(
      CEDARSCHEMA,
      "action a appliesTo { principal: [U] };",
      "appliesTo",
      "keyword"
    )
    assertNotScope(
      CEDARSCHEMA,
      "entity E { appliesTo: Bool };",
      "appliesTo",
      "keyword"
    )
  }

  @Test
  fun `S12 quoted attribute names are properties`() {
    assertScope(
      CEDARSCHEMA,
      "type T = { \"id\": Long };",
      "\"id\"",
      "variable.other.property"
    )
    assertScope(
      CEDARSCHEMA,
      "type T = { \"owner info\": String };",
      "\"owner info\"",
      "variable.other.property"
    )
    assertScope(
      CEDARSCHEMA,
      "entity E { a?: Long };",
      "a",
      "variable.other.property"
    )
    // an ordinary string is still a string, even with a colon in it
    assertScope(
      CEDARSCHEMA,
      "@doc(\"id: the task id\")\nentity E;",
      "id: the task id",
      "string.quoted"
    )
    assertScope(CEDARSCHEMA, "action \"x\";", "x", "string.quoted")
  }

  @Test
  fun `S13 a comment after a path separator`() {
    assertScope(CEDARSCHEMA, "entity E { a: N::T };//c", "//c", "comment.line")
  }

  @Test
  fun `S14 namespaced entity types`() {
    assertScope(
      CEDARSCHEMA,
      "action a appliesTo { principal: [N2::E] };",
      "N2::E",
      "entity.name.type"
    )
    assertScope(
      CEDARSCHEMA,
      "entity E in [Foo::Bar::Baz];",
      "Foo::Bar::Baz",
      "entity.name.type"
    )
    assertScope(
      CEDARSCHEMA,
      "entity E { g: Set<__cedar::String> };",
      "__cedar::String",
      "entity.name.type"
    )
  }

  @Test
  fun `a string does not run past the end of a line`() {
    val source = "entity E { a: \"unterminated };\nentity F;";
    assertScope(CEDARSCHEMA, source, "entity", "keyword.control")
    val strings = tokenize(CEDARSCHEMA, source).filter { t ->
      t.scopes.any { s -> s.startsWith("string.quoted") }
    }
    assertEquals(
      true,
      strings.all { t -> !t.text.contains("entity F") },
    )
  }
}
