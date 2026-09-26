// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/regex.ts. JS `/g` regexes are plain Kotlin Regex used with `findAll`.
package io.github.thefellow.cedar.core

val IDENT_REGEX = Regex("""^[_a-zA-Z][_a-zA-Z0-9]*$""")
val PATH_REGEX = Regex("""^([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*$""")
val ENTITY_REGEXG =
    Regex("""(?<type>(?:[_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*)(?:::"(?<id>(?:[^"]*))")?""")
val PROPERTY_CHAIN_REGEX =
    Regex("""\b(?<!\.)(?<element>(([_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*::"(?<id>([^"]*))"|principal|resource|context))(((\.| has )[_a-zA-Z][_a-zA-Z0-9]*|\["([^"]*)"\]))*(?<trigger>.?)$""")

// parses out start / end characters from Cedar validator
val PARSE_ERROR_SCHEMA_REGEX =
    Regex("""(P|p)arse error in (?<type>(entity type|common type|namespace))( identifier)?: """)
val AT_LINE_SCHEMA_REGEX =
    Regex(""" at line (?<line>(\d)+)? column (?<column>(\d)+)?""")
val OFFSET_POLICY_REGEX =
    Regex(""" at offset (?<start>(\d)+)(-)?(?<end>(\d)+)?: """)
val UNRECOGNIZED_REGEX =
    Regex("""unrecognized (action|entity type) `(?<unrecognized>.+)`(\ndid you mean `(?<suggestion>.+)`\?)?""", RegexOption.MULTILINE)
// export const UNDECLARED_REGEX_OLD =
//   /(U|u)ndeclared (?<type>(entity type\(s\)|common type\(s\)|action\(s\))): {(?<undeclared>.+)}/;
val UNDECLARED_REGEX =
    Regex("""`(?<undeclared>.+)` has not been declared as (?<type>(an entity type|a common type|an action))""")
val UNDECLAREDS_REGEX =
    Regex("""neither `(?<undeclaredn>.+)` nor `(?<undeclared>.+)` refers to anything that has been declared as (?<type>an entity type)""")
val UNDECLARED_ACTION_REGEX =
    Regex("""undeclared (?<type>action)s?: (?<undeclared>.+)""")
// Cedar entities errors
val NOTDECLARED_TYPE_REGEX =
    Regex("""entity `(?<type>.+)::"(?<id>.+)"` has type `(.+)` which is not declared in the schema(. Did you mean `(?<suggestion>.+)`\?)?""")
val EXPECTED_ATTR_REGEX =
    Regex("""expected entity `(?<type>.+)::"(?<id>.+)"` to have attribute `(?<suggestion>.+)`, but it does not""")
val EXPECTED_ATTR2_REGEX =
    Regex("""in attribute `(?<attribute>.+)` on `(?<type>.+)::"(?<id>.+)"`, expected the record to have an attribute `(?<suggestion>.+)`, but it does not""")
val MISMATCH_ATTR_REGEX =
    Regex("""in attribute `(?<attribute>.+)` on `(?<type>.+)::"(?<id>.+)"`, type mismatch: value was expected to have type (\(entity of type )?(?<suggestion>.+)(\))?, but it actually has type (\(entity of type )?(?<unrecognized>.+)(\))?""")
val EXIST_ATTR_REGEX =
    Regex("""attribute `(?<attribute>.+)` on `(?<type>.+)::"(?<id>.+)"` should not exist according to the schema""")
val NOTALLOWED_PARENT_REGEX =
    Regex("""`(?<type>.+)::"(?<id>.+)"` is not allowed to have an ancestor of type `(?<undeclared>.+)` according to the schema""")
val UNKNOWN_ENTITY_REGEX =
    Regex("""in uid field of <unknown entity>, expected a literal entity reference, but got `"(?<unknown>.+)"`""")

/* JS regex helpers. */

/** JS `string.match(regex)` for a non-global regex: the first match or null. */
fun String.jsMatch(regex: Regex): MatchResult? = regex.find(this)

/** JS `match.groups.name`: the named group's value, or null when the group did not participate. */
fun MatchResult.group(name: String): String? =
    try {
        groups[name]?.value
    } catch (_: IllegalArgumentException) {
        null
    }

/** JS `match.index`. */
val MatchResult.index: Int get() = range.first
