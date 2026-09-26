package io.github.thefellow.cedar.jsonc

import org.junit.Assert.assertEquals
import org.junit.Test

class JsoncTest {
    private fun events(text: String, options: ParseOptions = ParseOptions.DEFAULT): List<String> {
        val out = mutableListOf<String>()
        visit(text, object : JsonVisitor {
            override fun onObjectBegin(offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath): Boolean {
                out += "{ $offset:$length @$startLine:$startCharacter ${pathSupplier()}"; return true
            }
            override fun onObjectProperty(property: String, offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath) {
                out += "prop $property $offset:$length @$startLine:$startCharacter ${pathSupplier()}"
            }
            override fun onObjectEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) { out += "} $offset @$startLine:$startCharacter" }
            override fun onArrayBegin(offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath): Boolean {
                out += "[ ${pathSupplier()}"; return true
            }
            override fun onArrayEnd(offset: Int, length: Int, startLine: Int, startCharacter: Int) { out += "]" }
            override fun onLiteralValue(value: Any?, offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath) {
                out += "lit $value $offset:$length @$startLine:$startCharacter ${pathSupplier()}"
            }
            override fun onSeparator(character: String, offset: Int, length: Int, startLine: Int, startCharacter: Int) { out += "sep $character" }
            override fun onComment(offset: Int, length: Int, startLine: Int, startCharacter: Int) { out += "comment $offset:$length" }
            override fun onError(error: ParseErrorCode, offset: Int, length: Int, startLine: Int, startCharacter: Int) { out += "error $error $offset:$length" }
        }, options)
        return out
    }

    @Test
    fun pathsAndPositions() {
        val text = "{\n  \"a\": [1, \"x\", {\"b\": true}],\n  \"c\": null\n}"
        assertEquals(
            listOf(
                "{ 0:1 @0:0 []",
                "prop a 4:3 @1:2 []",
                "sep :",
                "[ [a]",
                "lit 1.0 10:1 @1:8 [a, 0]",
                "sep ,",
                "lit x 13:3 @1:11 [a, 1]",
                "sep ,",
                "{ 18:1 @1:16 [a, 2]",
                "prop b 19:3 @1:17 [a, 2]",
                "sep :",
                "lit true 24:4 @1:22 [a, 2, b]",
                "} 28 @1:26",
                "]",
                "sep ,",
                "prop c 34:3 @2:2 []",
                "sep :",
                "lit null 39:4 @2:7 [c]",
                "} 44 @3:0",
            ),
            events(text),
        )
    }

    @Test
    fun commentsAndCrLf() {
        assertEquals(
            listOf("comment 0:5", "{ 7:1 @1:0 []", "prop a 8:3 @1:1 []", "sep :", "lit -1.5 12:4 @1:5 [a]", "} 16 @1:9"),
            events("/*x*/\r\n{\"a\":-1.5}"),
        )
    }

    @Test
    fun errorRecovery() {
        assertEquals(
            listOf(
                "{ 0:1 @0:0 []",
                "prop a 1:3 @0:1 []",
                "error ColonExpected 5:1",
                "sep ,",
                "prop b 7:3 @0:7 []",
                "sep :",
                "lit 2.0 11:1 @0:11 [b]",
                "} 12 @0:12",
            ),
            events("{\"a\" 1,\"b\":2}"),
        )
        assertEquals(listOf("error ValueExpected 0:0"), events(""))
        assertEquals(listOf("[ []", "error CommaExpected 3:1", "lit 1.0 1:1 @0:1 [0]", "lit 2.0 3:1 @0:3 [1]", "]").sorted(), events("[1 2]").sorted())
    }

    @Test
    fun suppressedCallbacks() {
        val props = mutableListOf<String>()
        visit("""{"a":{"b":1},"c":2}""", object : JsonVisitor {
            override fun onObjectBegin(offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath) =
                pathSupplier().isEmpty()
            override fun onObjectProperty(property: String, offset: Int, length: Int, startLine: Int, startCharacter: Int, pathSupplier: () -> JSONPath) {
                props += property
            }
        })
        assertEquals(listOf("a", "c"), props)
    }
}
