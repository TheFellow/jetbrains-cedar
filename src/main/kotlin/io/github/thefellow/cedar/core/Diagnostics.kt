// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

// Port of upstream src/diagnostics.ts. Only DEFAULT_RANGE so far (needed by Parser.kt); the rest of
// diagnostics.ts will be ported into this file.
package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.vscode.Position
import io.github.thefellow.cedar.vscode.Range

val DEFAULT_RANGE = Range(Position(0, 0), Position(0, 0))
