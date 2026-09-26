// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/help.ts.

package io.github.thefellow.cedar.core

val FUNCTION_HELP_DEFINITIONS: Map<String, List<String>> = linkedMapOf(
  "contains" to listOf(
    "contains(any): Boolean",
    "Function that evaluates to `true` if the operand is a member of the receiver on the left side of the function. The receiver must be of type `Set`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-contains",
  ),
  "containsAll" to listOf(
    "containsAll(Set): Boolean",
    "Function that evaluates to `true` if every member of the operand set is a member of the receiver set. Both the receiver and the operand must be of type `Set`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-containsAll",
  ),
  "containsAny" to listOf(
    "containsAny(Set): Boolean",
    "Function that evaluates to `true` if any one or more members of the operand set is a member of the receiver set. Both the receiver and the operand must be of type `Set`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-containsAny",
  ),
  "isEmpty" to listOf(
    "isEmpty(Set): Boolean",
    "Function that evaluates to `true` if the set is empty. The receiver must be of type `Set`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-isEmpty",
  ),
  "hasTag" to listOf(
    "hasTag(Expr): Boolean",
    "Method that evaluates to `true` if the entity on the left has a value defined for the tag name specified on the right." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#operator-hasTag",
  ),
  "getTag" to listOf(
    "getTag(Expr): DataType",
    "Method that gets the value of a given tag. The tag name (`Expr`) may be any (string-typed) expression, and does not have to be a string literal." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#operator-getTag",
  ),
  // IPAddr extension functions
  "ip" to listOf(
    "ip(String): ipaddr",
    "Function that parses the `String` and attempts to convert it to type `ipaddr`. If the string doesn't represent a valid IP address or range, then it generates an error." +
      " Supports both IPv4 and IPv6. Ranges are indicated with CIDR notation (e.g. /24)." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-ip",
  ),
  "isIpv4" to listOf(
    "isIpv4(): Boolean",
    "Evaluates to `true` if the receiver is an IPv4 address. This function takes no operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-isIpv4",
  ),
  "isIpv6" to listOf(
    "isIpv6(): Boolean",
    "Function that evaluates to `true` if the receiver is an IPv6 address. This function takes no operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-isIpv6.title",
  ),
  "isLoopback" to listOf(
    "isLoopback(): Boolean",
    "Function that evaluates to `true` if the receiver is a valid loopback address for its IP version type. This function takes no operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-isLoopback.title",
  ),
  "isMulticast" to listOf(
    "isMulticast(): Boolean",
    "Function that evaluates to `true` if the receiver is a multicast address for its IP version type. This function takes no operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-isMulticast.title",
  ),
  "isInRange" to listOf(
    "isInRange(ipaddr): Boolean",
    "Function that evaluates to `true` if the receiver is an IP address or a range of addresses that fall completely within the range specified by the operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-isInRange.title",
  ),
  // Decimal extension functions
  "decimal" to listOf(
    "decimal(String): decimal",
    "Function that parses the `String` and tries to convert it to type `decimal`. If the string doesn't represent a valid `decimal` value, it generates an error." +
      "\n\nTo be interpreted successfully as a `decimal` value, the string must contain a decimal separator (.) and at least one digit before and at least one digit after the separator. There can be no more than 4 digits after the separator. The value must be within the valid range of the `decimal` type, from `-922337203685477.5808` to `922337203685477.5807`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-decimal",
  ),
  "lessThan" to listOf(
    "lessThan(decimal): Boolean",
    "Function that compares two `decimal` operands and evaluates to `true` if the left operand is numerically less than the right operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-lessThan",
  ),
  "lessThanOrEqual" to listOf(
    "lessThanOrEqual(decimal): Boolean",
    "Function that compares two `decimal` operands and evaluates to `true` if the left operand is numerically less than or equal to the right operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-lessThanOrEqual",
  ),
  "greaterThan" to listOf(
    "greaterThan(decimal): Boolean",
    "Function that compares two `decimal` operands and evaluates to `true` if the left operand is numerically greater than the right operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-greaterThan",
  ),
  "greaterThanOrEqual" to listOf(
    "greaterThanOrEqual(decimal): Boolean",
    "Function that compares two `decimal` operands and evaluates to `true` if the left operand is numerically greater than or equal to the right operand." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-greaterThanOrEqual",
  ),
  // Datetime extension functions
  "datetime" to listOf(
    "datetime(String): datetime",
    "Function that constructs a `datetime` value from a string in one of the forms:" +
      "\n* `\"YYYY-MM-DD\"` (date only)" +
      "\n* `\"YYYY-MM-DDThh:mm:ssZ\"` (UTC)" +
      "\n* `\"YYYY-MM-DDThh:mm:ss.SSSZ`\" (UTC with millisecond precision)" +
      "\n* `\"YYYY-MM-DDThh:mm:ss(+/-)hhmm\"` (With timezone offset in hours and minutes)" +
      "\n* `\"YYYY-MM-DDThh:mm:ss.SSS(+/-)hhmm\"` (With timezone offset in hours and minutes and millisecond precision)" +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-datatypes.html#datatype-datetime",
  ),
  "offset" to listOf(
    "offset(duration): datetime",
    "Function returns a new `datetime`, offset by `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-offset.title",
  ),
  "durationSince" to listOf(
    "durationSince(datetime): duration",
    "Function returns the difference as a `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-durationSince.title",
  ),
  "toDate" to listOf(
    "toDate(): datetime",
    "Function returns a new `datetime`, truncating to the day, such that printing the `datetime` would have 00:00:00 as the time." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toDate.title",
  ),
  "toTime" to listOf(
    "toTime(): duration",
    "Function returns a new `duration`, removing the days, such that only milliseconds since `.toDate()` are left." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toTime.title",
  ),
  "duration" to listOf(
    "duration(String): duration",
    "Function that constructs a duration value from a duration string. " +
      "The string is a concatenated sequence of quantity - unit pairs. For example, `\"1d2h3m4s5ms\"` is a valid duration string. " +
      "The quantity part is a positive integer. The unit is one of the following:" +
      "\n * `d`: days" +
      "\n * `h`: hours" +
      "\n * `m`: minutes" +
      "\n * `s`: seconds" +
      "\n * `ms`: milliseconds" +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-datatypes.html#datatype-duration",
  ),
  "toMilliseconds" to listOf(
    "toMilliseconds(): Long",
    "Function describing the number of milliseconds in this `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toMilliseconds.title",
  ),
  "toSeconds" to listOf(
    "toSeconds(): Long",
    "Function describing the number of seconds in this `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toSeconds.title",
  ),
  "toMinutes" to listOf(
    "toMinutes(): Long",
    "Function describing the number of minutes in this `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toMinutes.title",
  ),
  "toHours" to listOf(
    "toHours(): Long",
    "Function describing the number of hours in this `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toHours.title",
  ),
  "toDays" to listOf(
    "toDays(): Long",
    "Function describing the number of days in this `duration`." +
      "\n\nhttps://docs.cedarpolicy.com/policies/syntax-operators.html#function-toDays.title",
  ),
)
