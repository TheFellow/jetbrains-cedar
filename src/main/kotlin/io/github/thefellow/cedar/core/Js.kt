// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Small helpers replicating JavaScript semantics that upstream code relies on.
package io.github.thefellow.cedar.core

/** JS `String(value)` / template-literal interpolation of a jsonc literal value. */
fun jsString(value: Any?): String = when (value) {
    null -> "null"
    is Double -> if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e21) value.toLong().toString() else value.toString()
    else -> value.toString()
}

/** JS `array[index]`: undefined (null) when out of range, including negative indices. */
fun <T> List<T>.at(index: Int): T? = if (index in indices) this[index] else null

/** JS `String.prototype.substring`: clamps and swaps its arguments instead of throwing. */
fun String.jsSubstring(start: Int, end: Int = length): String {
    val s = start.coerceIn(0, length)
    val e = end.coerceIn(0, length)
    return if (s <= e) substring(s, e) else substring(e, s)
}
