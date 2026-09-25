package io.github.thefellow.cedar

import io.github.thefellow.cedar.wasm.Cedar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CedarWasmTest {
    @Test
    fun version() {
        val t0 = System.nanoTime()
        assertEquals("4.13.0", Cedar.getCedarSDKVersion())
        println("wasm load+first call: ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    @Test
    fun syntax() {
        val ok = Cedar.validateSyntax("permit(principal, action, resource);")
        assertTrue(ok.success)
        assertEquals(1, ok.policies)
        val bad = Cedar.validateSyntax("permit(2pac, action, resource)")
        assertFalse(bad.success)
        assertTrue(bad.errors!!.isNotEmpty())
    }

    @Test
    fun format() {
        val policy = """permit(principal, action == Action::"view", resource in Albums::"gangsta rap") when {principal.is_gangsta == true};"""
        val expected = "permit (\n    principal,\n    action == Action::\"view\",\n    resource in Albums::\"gangsta rap\"\n)\nwhen { principal.is_gangsta == true };\n"
        assertEquals(expected, Cedar.formatPolicies(policy, 80, 4).policy)
    }
}
