// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.wasm

import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The Cedar SDK (cedar-wasm/, a port of upstream vscode-cedar-wasm) running in the JVM via Chicory.
 *
 * This is the equivalent of upstream's `import * as cedar from 'vscode-cedar-wasm'`: see [Cedar] for
 * the typed functions. Calls are serialized; a trap discards the instance so the next call starts clean.
 */
object CedarWasm {
    private val gson = Gson()

    private val module: WasmModule by lazy {
        val bytes = CedarWasm::class.java.getResourceAsStream("/cedar/cedar.wasm")?.use { it.readBytes() }
            ?: error("cedar.wasm resource missing")
        Parser.parse(bytes)
    }

    private val machineFactory by lazy { MachineFactoryCompiler.compile(module) }

    private class Loaded(val instance: Instance, val alloc: ExportFunction, val free: ExportFunction, val call: ExportFunction)

    private var loaded: Loaded? = null

    private fun load(): Loaded {
        loaded?.let { return it }
        val wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        val instance = Instance.builder(module)
            .withImportValues(imports)
            .withMachineFactory(machineFactory)
            .withStart(false)
            .build()
        runCatching { instance.export("_initialize") }.getOrNull()?.apply()
        return Loaded(instance, instance.export("cedar_alloc"), instance.export("cedar_free"), instance.export("cedar_call"))
            .also { loaded = it }
    }

    /** Eagerly parse and compile the module (it takes a moment); safe to call from a background thread. */
    fun warmUp() {
        synchronized(this) { load() }
    }

    @Synchronized
    fun call(function: String, vararg args: Any): JsonElement {
        val request = JsonObject().apply {
            addProperty("fn", function)
            add("args", gson.toJsonTree(args))
        }
        val requestBytes = gson.toJson(request).toByteArray(Charsets.UTF_8)
        val wasm = load()
        try {
            val memory = wasm.instance.memory()
            val ptr = wasm.alloc.apply(requestBytes.size.toLong())[0].toInt()
            memory.write(ptr, requestBytes)
            val packed = wasm.call.apply(ptr.toLong(), requestBytes.size.toLong())[0]
            wasm.free.apply(ptr.toLong(), requestBytes.size.toLong())
            val outPtr = (packed ushr 32).toInt()
            val outLen = (packed and 0xffffffffL).toInt()
            val response = memory.readBytes(outPtr, outLen)
            wasm.free.apply(outPtr.toLong(), outLen.toLong())
            val json = gson.fromJson(String(response, Charsets.UTF_8), JsonObject::class.java)
            json.get("err")?.let { throw CedarWasmException(it.asString) }
            return json.get("ok")
        } catch (e: CedarWasmException) {
            throw e
        } catch (e: Exception) {
            loaded = null
            throw CedarWasmException("Cedar wasm call $function failed: ${e.message}", e)
        }
    }

    internal fun <T> call(type: Class<T>, function: String, vararg args: Any): T =
        gson.fromJson(call(function, *args), type)
}

class CedarWasmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
