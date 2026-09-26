// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.ide

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.thefellow.cedar.wasm.CedarWasm

/** Parses and compiles the Cedar SDK wasm module (a few seconds, once per IDE session) off the EDT at startup. */
class CedarWarmUp : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            CedarWasm.warmUp()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger<CedarWarmUp>().warn("Cedar SDK failed to load", e)
        }
    }
}
