package io.github.thefellow.cedar.ide.completion

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetsTest {
    @Test fun `plain text`() = assertEquals(listOf(SnippetPart.Text("abc")), Snippets.parse("abc"))

    @Test fun `tab stops and final`() = assertEquals(
        listOf(SnippetPart.Text("contains("), SnippetPart.TabStop(1), SnippetPart.Text(") "), SnippetPart.TabStop(0)),
        Snippets.parse("contains(\$1) \$0"),
    )

    @Test fun `braced tab stop`() = assertEquals(listOf(SnippetPart.TabStop(12)), Snippets.parse("\${12}"))

    @Test fun placeholder() {
        val parts = Snippets.parse("ip(\"\${1:127.0.0.1}\")\$0")
        assertEquals(SnippetPart.TabStop(1, listOf(SnippetPart.Text("127.0.0.1"))), parts[1])
        assertEquals("ip(\"127.0.0.1\")", Snippets.expand(parts))
    }

    @Test fun choice() {
        val parts = Snippets.parse("\"b\": \${2|false,true|},")
        assertEquals(SnippetPart.TabStop(2, choices = listOf("false", "true")), parts[1])
        assertEquals("\"b\": false,", Snippets.expand(parts))
    }

    @Test fun escapes() {
        assertEquals("a\$1}\\b", Snippets.expand("a\\\$1\\}\\\\b"))
        assertEquals("\${1:x}", Snippets.expand("\\\${1:x}"))
    }

    @Test fun `nested placeholder`() = assertEquals("a b c", Snippets.expand("\${1:a \${2:b} c}"))

    @Test fun `dollar that is not a tab stop stays text`() = assertEquals("\$x \${y}", Snippets.expand("\$x \${y}"))

    @Test fun `multiline snippet`() = assertEquals(
        "permit (\n    principal == Path::\"id\",\n    action == Action::\"id\",\n    resource == Path::\"id\"\n);",
        Snippets.expand(
            "permit (\n    principal == \${1:Path}::\"\${2:id}\",\n    action == Action::\"\${3:id}\",\n    resource == \${4:Path}::\"\${5:id}\"\n)\$0;",
        ),
    )
}
